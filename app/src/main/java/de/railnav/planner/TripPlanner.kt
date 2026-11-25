package de.railnav.planner

import de.railnav.data.JourneyPage
import de.railnav.data.JourneyQuery
import de.railnav.data.Provider
import de.railnav.data.ProviderException
import de.railnav.model.Journey
import de.railnav.model.Place
import de.railnav.model.PlanRequest
import de.railnav.model.Product
import de.railnav.model.ReturnMode
import de.railnav.model.Segment
import de.railnav.model.TripPlan
import de.railnav.model.Waypoint
import de.railnav.model.distanceKm
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

class TripPlanner(
    private val provider: Provider,
    private val defaultCityRadiusKm: Double,
    /** Route cities via their main stations (Hbf / most important) instead of any stop in the radius. */
    private val useMainStations: Boolean = true,
) {

    private val cache = HashMap<String, JourneyPage>()
    private val mainCache get() = MainStationCache.map

    private fun withRadius(p: Place) = if (p.isCity && p.radiusKm <= 0.0) p.copy(radiusKm = defaultCityRadiusKm) else p

    /** City -> its main stations (cached), or the city itself when the provider can't tell. */
    private suspend fun expand(p: Place, products: Set<Product>): List<Place> {
        if (!p.isCity || !useMainStations) return listOf(p)
        val key = "${provider.id}|${p.name}|${p.lat}|${products.any { it == Product.HIGHSPEED || it == Product.LONG_DISTANCE }}"
        mainCache[key]?.let { return it }
        val mains = runCatching { provider.mainStations(p, p.radiusKm, products) }.getOrDefault(emptyList())
        return (mains.ifEmpty { listOf(p) }).also { mainCache[key] = it }
    }

    /** Simple A->B search (cities via main stations, exact product filtering). */
    suspend fun search(q: JourneyQuery): JourneyPage {
        val from = withRadius(provider.resolve(q.from)); val to = withRadius(provider.resolve(q.to))
        val key = listOf(provider.id, from.id ?: from.pos, to.id ?: to.pos, q.time / 60000, q.arriveBy, q.products.sorted(),
            q.pageCursor, q.minTransferMin, q.count, useMainStations).joinToString()
        cache[key]?.let { return it }
        // RE vs RB is filtered client-side: ask for more results when only one of them is wanted.
        val partial = (Product.REGIONAL in q.products) != (Product.REGIONAL_EXPRESS in q.products)
        val froms = expand(from, q.products); val tos = expand(to, q.products)
        val combos = froms.flatMap { f -> tos.map { t -> f to t } }.take(4)
        if (combos.size == 1) {
            val (f, t) = combos[0]
            val page = provider.journeys(q.copy(from = f, to = t, count = if (partial) q.count + 4 else q.count))
            val trimmed = page.journeys.map { trimForCity(it, f, t) }
                .filter { it.legs.isNotEmpty() && it.matches(q.products) }
                .distinctBy { it.signature }
            return page.copy(journeys = trimmed).also { cache[key] = it }
        }
        // Several main stations (e.g. Jena Paradies / West / Goeschwitz): query each pair and merge.
        val all = ArrayList<Journey>()
        for ((f, t) in combos) {
            runCatching { provider.journeys(q.copy(from = f, to = t, pageCursor = null, count = q.count)) }
                .getOrNull()?.journeys?.let { all += it }
        }
        val merged = all.filter { it.legs.isNotEmpty() && it.matches(q.products) }
            .distinctBy { it.signature }
            // drop dominated options: another one leaves no earlier and arrives no later
            .let { js -> js.filter { j -> js.none { k -> k !== j && k.dep >= j.dep && k.arr <= j.arr && (k.dep > j.dep || k.arr < j.arr) } } }
            .sortedBy { if (q.arriveBy) -it.arr else it.dep }
            .take(q.count + 2)
            .sortedBy { it.dep }
        return JourneyPage(merged).also { cache[key] = it }
    }

    /**
     * Builds multi-stop / round trips. Returns up to [variants] alternative plans that differ in
     * the choice for the first segment; following segments are chained (earliest arrival after
     * the minimum stay, or according to the return mode for the way back).
     */
    suspend fun plan(req: PlanRequest, variants: Int = 3): List<TripPlan> {
        val stops = orderedStops(req)
        if (stops.size < 2) throw ProviderException("Нужно минимум две точки")
        val first = segmentOptions(req, stops[0].place, stops[1].place, req.time, arriveBy = false)
        if (first.isEmpty()) throw ProviderException(noRoute(stops[0].place, stops[1].place, req.time))
        val plans = ArrayList<TripPlan>()
        var lastError: String? = null
        for (start in first.take(variants)) {
            val segs = ArrayList<Segment>()
            segs += Segment(stops[0].place, stops[1].place, start, first.filter { it !== start }.take(6), req.time)
            try {
                for (i in 1 until stops.size - 1) segs += nextSegment(req, stops, i, segs.last().journey.arr)
            } catch (e: ProviderException) { lastError = e.message; continue }
            var plan = TripPlan(UUID.randomUUID().toString(), title(stops), segs, req)
            plan = runCatching { distribute(req, stops, plan) }.getOrDefault(plan)
            plans += plan
        }
        if (plans.isEmpty()) throw ProviderException(lastError ?: "Не удалось связать все участки поездки")
        return plans.distinctBy { p -> p.segments.joinToString { it.journey.signature } }
    }

    /**
     * When the way home is fixed in time (latest / not before), the greedy chain piles all spare time
     * into the last city. Spread it over the cities by their priority instead: city k gets
     * spare * weight_k / sum(weights) on top of its minimum stay (capped by its maximum). Each segment still
     * takes the earliest-arriving connection after that time, so no pointless detours.
     */
    private suspend fun distribute(req: PlanRequest, stops: List<Waypoint>, base: TripPlan): TripPlan {
        if (!(req.roundTrip || req.circular) || req.returnMode == ReturnMode.EARLIEST) return base
        val segs = base.segments
        val n = segs.size
        if (n < 3) return base // only one city: all spare time belongs to it anyway
        val cities = (1 until n).map { stops[it] }
        val lastArr = segs[n - 2].journey.arr
        val spare = segs[n - 1].journey.dep - (lastArr + cities.last().minStayMin * 60_000L)
        if (spare < 30 * 60_000L) return base
        val total = cities.sumOf { it.weight }
        for (factor in listOf(1.0, 0.8, 0.6, 0.4)) {
            val out = ArrayList<Segment>(); out += segs[0]
            var arr = segs[0].journey.arr
            var failed = false
            for (k in 1 until n - 1) {
                val wp = stops[k]
                var extra = (spare * factor * wp.weight / total).toLong()
                wp.maxStayMin?.let { mx -> extra = minOf(extra, maxOf(0L, (mx - wp.minStayMin) * 60_000L)) }
                val t = arr + wp.minStayMin * 60_000L + extra
                val opts = segmentOptions(req, wp.place, stops[k + 1].place, t, arriveBy = false)
                val c = opts.firstOrNull()
                if (c == null) { failed = true; break }
                out += Segment(wp.place, stops[k + 1].place, c, opts.drop(1).take(6), t)
                arr = c.arr
            }
            if (failed) continue
            val back = runCatching { nextSegment(req, stops, n - 1, arr) }.getOrNull() ?: continue
            // accept only if the way home is still (about) the late one
            if (back.journey.dep >= segs[n - 1].journey.dep - 3600_000L) {
                out += back
                return base.copy(segments = out)
            }
        }
        return base
    }

    private fun title(stops: List<Waypoint>) = buildString {
        append(stops.first().place.name)
        stops.drop(1).forEach { append(" → ").append(it.place.name) }
    }

    private fun noRoute(a: Place, b: Place, t: Long) =
        "Не найден участок ${a.name} → ${b.name} после ${hm(t)}. Проверьте точки (город или станцию) и выбранный транспорт."

    private fun hm(t: Long) = Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()).let { "%02d:%02d".format(it.hour, it.minute) }

    /** Segment from stops[i] to stops[i+1], given the arrival time at stops[i]. */
    private suspend fun nextSegment(req: PlanRequest, stops: List<Waypoint>, i: Int, arr: Long): Segment {
        val wp = stops[i]
        val from = wp.place; val to = stops[i + 1].place
        val earliest = arr + wp.minStayMin * 60_000L
        val isReturn = (req.roundTrip || req.circular) && i == stops.size - 2
        if (isReturn && req.returnMode == ReturnMode.LATEST) {
            val deadline = req.returnTime ?: endOfDay(arr)
            val opts = segmentOptions(req, from, to, deadline, arriveBy = true)
                .filter { it.dep >= earliest && it.arr <= deadline }
                .sortedByDescending { it.dep }
            if (opts.isNotEmpty()) return Segment(from, to, opts.first(), opts.drop(1).take(6), earliest)
            // nothing arrives by the deadline after the stay → fall back to the first train after the stay
        }
        val t = if (isReturn && req.returnMode == ReturnMode.NOT_BEFORE && req.returnTime != null) maxOf(earliest, req.returnTime) else earliest
        val opts = segmentOptions(req, from, to, t, arriveBy = false)
        val chosen = opts.firstOrNull() ?: throw ProviderException(noRoute(from, to, t))
        val exceeded = wp.maxStayMin != null && (chosen.dep - arr) / 60000 > wp.maxStayMin
        return Segment(from, to, chosen, opts.drop(1).take(6), t, exceeded)
    }

    /** End of the local day of [t] (23:59), or 02:00 next day when arriving after 22:00. */
    private fun endOfDay(t: Long): Long {
        val z = Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault())
        val eod = z.toLocalDate().atTime(23, 59).atZone(z.zone)
        return (if (z.hour >= 22) eod.plusHours(2) else eod).toInstant().toEpochMilli()
    }

    /** Re-chains everything after segment [index] after the user picked another journey for it. */
    suspend fun rechainFrom(plan: TripPlan, index: Int, chosen: Journey): TripPlan {
        val req = plan.request ?: return plan
        val stops = orderedStopsFromPlan(plan)
        val segs = plan.segments.toMutableList()
        val old = segs[index]
        segs[index] = old.copy(journey = chosen, alternatives = (old.alternatives + old.journey).filter { it !== chosen }.sortedBy { it.dep })
        for (i in index + 1 until segs.size) {
            segs[i] = try { nextSegment(req, stops, i, segs[i - 1].journey.arr) } catch (e: ProviderException) { break }
        }
        return plan.copy(segments = segs)
    }

    private fun orderedStopsFromPlan(plan: TripPlan): List<Waypoint> {
        val req = plan.request!!
        val byPlace = (req.vias + Waypoint(req.destination, req.minStayAtDestinationMin)).associateBy { it.place }
        return listOf(Waypoint(plan.segments.first().from)) + plan.segments.map { byPlace[it.to] ?: Waypoint(it.to) }
    }

    private suspend fun segmentOptions(req: PlanRequest, from: Place, to: Place, t: Long, arriveBy: Boolean): List<Journey> {
        val page = search(JourneyQuery(from, to, t, arriveBy, req.products, req.minTransferMin, req.maxTransfers, count = 6))
        if (arriveBy) return page.journeys
        // earliest arrival first, then later departure
        return page.journeys.filter { it.dep >= t - 60_000 }.sortedWith(compareBy({ it.arr }, { -it.dep }))
    }

    /** Waypoint order: given order, or the shortest (great-circle) tour if the order is free. */
    fun orderedStops(req: PlanRequest): List<Waypoint> {
        val origin = Waypoint(req.origin)
        val dest = Waypoint(req.destination, req.minStayAtDestinationMin, priority = req.destinationPriority)
        var vias = req.vias
        if (!req.strictOrder && vias.size > 1) {
            vias = bestOrder(req.origin, vias, req.destination, req.roundTrip)
        }
        val list = listOf(origin) + vias + dest
        return if (req.roundTrip) list + Waypoint(req.origin) else list
    }

    private fun bestOrder(o: Place, vias: List<Waypoint>, d: Place, round: Boolean): List<Waypoint> {
        if (vias.size > 8) return vias
        var best = vias; var bestLen = Double.MAX_VALUE
        fun len(order: List<Waypoint>): Double {
            var prev = o; var s = 0.0
            for (w in order) { s += distanceKm(prev.pos, w.place.pos); prev = w.place }
            s += distanceKm(prev.pos, d.pos)
            if (round && d != o) s += distanceKm(d.pos, o.pos)
            return s
        }
        fun perm(prefix: List<Waypoint>, rest: List<Waypoint>) {
            if (rest.isEmpty()) { val l = len(prefix); if (l < bestLen) { bestLen = l; best = prefix }; return }
            for (i in rest.indices) perm(prefix + rest[i], rest.filterIndexed { k, _ -> k != i })
        }
        perm(emptyList(), vias)
        return best
    }

    companion object {
        /**
         * For city places the router starts/ends at the city centre (with walking). Cut the journey so it
         * starts at the last station inside the origin city and ends at the first station inside the
         * destination city: "arrive at any station in the city".
         */
        fun trimForCity(j: Journey, from: Place, to: Place): Journey {
            var legs = j.legs
            if (to.isCity) {
                val r = to.radiusKm
                val i = legs.indexOfFirst { !it.isWalk && distanceKm(it.to.pos, to.pos) <= r }
                if (i >= 0) legs = legs.subList(0, i + 1)
            }
            if (from.isCity) {
                val r = from.radiusKm
                var start = 0
                for (k in legs.indices) {
                    val l = legs[k]
                    if (!l.isWalk && distanceKm(l.from.pos, from.pos) <= r) start = k
                    if (!l.isWalk && distanceKm(l.to.pos, from.pos) > r) break
                }
                legs = legs.subList(start, legs.size)
            }
            if (from.isCity || to.isCity) {
                legs = legs.dropWhile { it.isWalk }.dropLastWhile { it.isWalk }
            }
            return if (legs.size == j.legs.size) j else j.copy(legs = legs.ifEmpty { j.legs })
        }
    }
}

/** Main stations per city, shared between planner instances for the app session. */
object MainStationCache { val map = HashMap<String, List<Place>>() }
