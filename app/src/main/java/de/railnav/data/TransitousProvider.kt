package de.railnav.data

import de.railnav.model.Journey
import de.railnav.model.LatLon
import de.railnav.model.Leg
import de.railnav.model.Place
import de.railnav.model.Product
import de.railnav.model.StopTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.time.Instant
import java.util.Locale

/** Transitous: free, community-run MOTIS 2 instance covering most of Europe. */
class TransitousProvider(private val base: String = "https://api.transitous.org") : Provider {
    override val id = "transitous"
    override val title = "Transitous (MOTIS, вся Европа)"
    override val supportsRealtime = true

    private suspend fun get(path: String, params: List<Pair<String, String>>): JsonElement =
        withContext(Dispatchers.IO) {
            val url = (base + path).toHttpUrl().newBuilder().apply {
                params.forEach { (k, v) -> addQueryParameter(k, v) }
            }.build()
            val req = Request.Builder().url(url).header("Accept", "application/json").build()
            try {
                Net.client.newCall(req).execute().use { r ->
                    val body = r.body?.string().orEmpty()
                    if (!r.isSuccessful) throw ProviderException("Transitous: HTTP ${r.code} ${body.take(200)}")
                    Net.json.parseToJsonElement(body)
                }
            } catch (e: java.io.IOException) {
                throw ProviderException("Нет связи с Transitous: ${e.message}", e)
            }
        }

    override suspend fun searchPlaces(query: String): List<Place> {
        val lang = "de" // local names for German regions/cities
        val arr = get("/api/v1/geocode", listOf("text" to query, "language" to lang)).jsonArray
        val places = ArrayList<Place>()
        val cityFromStops = LinkedHashMap<String, Place>()
        for (e in arr) {
            val o = e.jsonObject
            val type = o.str("type")
            val name = o.str("name") ?: continue
            val lat = o.dbl("lat") ?: continue
            val lon = o.dbl("lon") ?: continue
            val country = o.str("country")
            val areas = o["areas"]?.jsonArray?.mapNotNull { a ->
                val ao = a.jsonObject; val n = ao.str("name") ?: return@mapNotNull null
                (ao.dbl("adminLevel")?.toInt() ?: 0) to n
            }.orEmpty()
            val state = areas.firstOrNull { it.first == 4 }?.second
            val district = areas.firstOrNull { it.first == 6 }?.second
            val town = areas.firstOrNull { it.first == 8 }?.second ?: district
            val cc = if (country != null && country != "DE") " ($country)" else ""
            when (type) {
                "STOP" -> {
                    places += Place(id = o.str("id"), name = name, lat = lat, lon = lon, provider = id,
                        region = (listOfNotNull(town?.takeIf { !name.startsWith(it) }, state).joinToString(", ") + cc).ifEmpty { null })
                    if (town != null) cityFromStops.putIfAbsent(town + cc,
                        Place(name = town + cc, lat = lat, lon = lon, isCity = true, provider = id, region = state))
                }
                "PLACE" -> places += Place(
                    name = name + cc, lat = lat, lon = lon, isCity = true, provider = id,
                    region = listOfNotNull(district?.takeIf { it != name }, state).distinct().joinToString(", ").ifEmpty { null },
                )
                else -> {} // addresses are not useful for a rail planner
            }
        }
        // Cities derived from station areas ("Brandenburg an der Havel"), unless already offered as a PLACE nearby.
        val token = query.trim().split(' ', ',', '-').firstOrNull { it.length >= 3 }?.lowercase()
        for (c in cityFromStops.values) {
            if (token != null && !c.name.lowercase().contains(token)) continue
            val dup = places.any { it.isCity && (it.name == c.name || de.railnav.model.distanceKm(it.pos, c.pos) < 4.0) }
            if (!dup) places += c
        }
        // German cities first, then German stations, then everything abroad.
        fun foreign(p: Place) = Regex("\\([A-Z]{2}\\)$").containsMatchIn(p.name) || p.region?.endsWith(")") == true
        return places.withIndex().sortedWith(compareBy({ if (foreign(it.value)) 2 else if (it.value.isCity) 0 else 1 }, { it.index }))
            .map { it.value }
    }

    private fun placeParam(p: Place): String =
        if (!p.isCity && p.id != null && p.provider == id) p.id
        else String.format(Locale.US, "%.6f,%.6f", p.lat, p.lon)

    override suspend fun journeys(q: JourneyQuery): JourneyPage {
        val modes = q.products.flatMap { productModes(it) }.distinct().ifEmpty { listOf("TRANSIT") }
        val params = mutableListOf(
            "fromPlace" to placeParam(q.from),
            "toPlace" to placeParam(q.to),
            "time" to Instant.ofEpochMilli(q.time).toString(),
            "arriveBy" to q.arriveBy.toString(),
            "transitModes" to modes.joinToString(","),
            "numItineraries" to q.count.toString(),
            "minTransferTime" to q.minTransferMin.toString(),
            "detailedTransfers" to "false",
            "joinInterlinedLegs" to "true",
            "maxPreTransitTime" to if (q.from.isCity) "2400" else "900",
            "maxPostTransitTime" to if (q.to.isCity) "2400" else "900",
        )
        q.maxTransfers?.let { params += "maxTransfers" to it.toString() }
        q.pageCursor?.let { params += "pageCursor" to it }
        val root = get("/api/v6/plan", params).jsonObject
        val its = root["itineraries"]?.jsonArray.orEmpty()
        val journeys = its.mapNotNull { parseItinerary(it.jsonObject) }
        return JourneyPage(journeys, root.str("previousPageCursor"), root.str("nextPageCursor"))
    }

    override suspend fun board(place: Place, time: Long, arrivals: Boolean, products: Set<Product>): List<de.railnav.model.BoardEntry> {
        val modes = products.flatMap { productModes(it) }.distinct()
        val params = mutableListOf(
            "time" to Instant.ofEpochMilli(time).toString(),
            "arriveBy" to arrivals.toString(),
            "n" to "40",
        )
        if (modes.isNotEmpty()) params += "mode" to modes.joinToString(",")
        if (!place.isCity && place.id != null && place.provider == id) params += "stopId" to place.id
        else { params += "center" to String.format(Locale.US, "%.6f,%.6f", place.lat, place.lon); params += "radius" to if (place.isCity) "800" else "300" }
        val root = get("/api/v6/stoptimes", params).jsonObject
        return root["stopTimes"]?.jsonArray.orEmpty().mapNotNull { e ->
            val o = e.jsonObject
            val stop = parseStop(o["place"]?.jsonObject) ?: return@mapNotNull null
            val line = o.str("displayName") ?: o.str("routeShortName") ?: o.str("tripShortName") ?: "?"
            val mode = o.str("mode") ?: "OTHER"
            de.railnav.model.BoardEntry(
                product = Product.refine(modeToProduct(mode, line), line),
                line = line,
                direction = if (arrivals) (o["tripFrom"] as? JsonObject)?.str("name") ?: "" else o.str("headsign") ?: "",
                stop = stop.copy(cancelled = stop.cancelled || o.bool("cancelled") == true || o.bool("tripCancelled") == true),
                tripId = o.str("tripId"), operator = o.str("agencyName"),
            )
        }.filter { it.product != Product.WALK }
            .sortedBy { it.time(arrivals) ?: 0L }
    }

    override suspend fun trip(tripId: String): Leg? {
        val it = get("/api/v6/trip", listOf("tripId" to tripId)).jsonObject
        return parseItinerary(it)?.legs?.firstOrNull { !it.isWalk }
    }

    override suspend fun refreshLeg(leg: Leg): Leg? {
        val tripId = leg.tripId ?: return null
        val it = get("/api/v6/trip", listOf("tripId" to tripId)).jsonObject
        val full = parseItinerary(it) ?: return null
        val tl = full.legs.firstOrNull { !it.isWalk } ?: return null
        // Cut the full trip to the part the user rides on.
        val all = listOf(tl.from) + tl.stops + listOf(tl.to)
        val i0 = all.indexOfFirst { sameStop(it, leg.from) }
        val i1 = all.indexOfLast { sameStop(it, leg.to) }
        if (i0 < 0 || i1 <= i0) return null
        return leg.copy(
            from = all[i0], to = all[i1],
            stops = all.subList(i0 + 1, i1),
            realtime = tl.realtime,
        )
    }

    private fun sameStop(a: StopTime, b: StopTime) =
        a.name == b.name || (a.stopId != null && a.stopId == b.stopId)

    private fun parseItinerary(o: JsonObject): Journey? {
        val legs = o["legs"]?.jsonArray?.mapNotNull { parseLeg(it.jsonObject) } ?: return null
        if (legs.isEmpty()) return null
        val idStr = o.str("id") ?: legs.joinToString("|") { "${it.line}${it.dep}" }
        return Journey(id = "t:" + idStr.hashCode().toUInt().toString(16) + legs.first().dep, legs = legs, provider = id)
    }

    private fun parseStop(o: JsonObject?): StopTime? {
        o ?: return null
        return StopTime(
            name = o.str("name") ?: "?",
            stopId = o.str("parentId") ?: o.str("stopId"),
            lat = o.dbl("lat") ?: 0.0,
            lon = o.dbl("lon") ?: 0.0,
            arrPlanned = o.time("scheduledArrival"),
            arrRt = o.time("arrival").takeIf { o.str("arrivalTimestampReason") != "NO_RT_DATA" },
            depPlanned = o.time("scheduledDeparture"),
            depRt = o.time("departure").takeIf { o.str("departureTimestampReason") != "NO_RT_DATA" },
            platformPlanned = o.str("scheduledTrack")?.cleanTrack(),
            platformRt = o.str("track")?.cleanTrack(),
            cancelled = o.bool("cancelled") == true,
        )
    }

    private fun parseLeg(o: JsonObject): Leg? {
        val mode = o.str("mode") ?: return null
        val lineName = o.str("displayName") ?: o.str("routeShortName")
        val product = Product.refine(modeToProduct(mode, lineName), lineName)
        var from = parseStop(o["from"]?.jsonObject) ?: return null
        var to = parseStop(o["to"]?.jsonObject) ?: return null
        // Walk legs have no stop times; use leg start/end.
        if (from.depPlanned == null) from = from.copy(depPlanned = o.time("scheduledStartTime"), depRt = o.time("startTime"))
        if (to.arrPlanned == null) to = to.copy(arrPlanned = o.time("scheduledEndTime"), arrRt = o.time("endTime"))
        val geom = o["legGeometry"]?.jsonObject
        val shape = geom?.let { g ->
            val pts = g.str("points") ?: return@let emptyList()
            decodePolyline(pts, g.int("precision") ?: 6)
        }.orEmpty()
        val stops = o["intermediateStops"]?.jsonArray?.mapNotNull { parseStop(it.jsonObject) }.orEmpty()
        val notes = o["alerts"]?.jsonArray?.mapNotNull { a ->
            val ao = a.jsonObject
            listOfNotNull(ao.str("headerText"), ao.str("descriptionText")).joinToString(": ").ifBlank { null }
        }.orEmpty()
        val line = if (product == Product.WALK) "Пешком"
        else o.str("displayName") ?: o.str("routeShortName") ?: o.str("tripShortName") ?: mode
        return Leg(
            product = product,
            line = line,
            direction = o.str("headsign"),
            tripId = o.str("tripId"),
            from = from, to = to, stops = stops, shape = shape,
            walkMeters = o.dbl("distance")?.toInt(),
            notes = notes,
            color = o.str("routeColor"),
            operator = o.str("agencyName"),
            realtime = o.bool("realTime") == true,
        )
    }

    companion object {
        fun productModes(p: Product): List<String> = when (p) {
            Product.HIGHSPEED -> listOf("HIGHSPEED_RAIL")
            Product.LONG_DISTANCE -> listOf("LONG_DISTANCE")
            Product.NIGHT -> listOf("NIGHT_RAIL")
            Product.REGIONAL, Product.REGIONAL_EXPRESS -> listOf("REGIONAL_RAIL")
            Product.SUBURBAN -> listOf("SUBURBAN")
            Product.SUBWAY -> listOf("SUBWAY")
            Product.TRAM -> listOf("TRAM", "FUNICULAR")
            Product.BUS -> listOf("BUS")
            Product.COACH -> listOf("COACH")
            Product.FERRY -> listOf("FERRY")
            else -> emptyList()
        }

        fun modeToProduct(mode: String, line: String?): Product = when (mode) {
            "WALK", "BIKE", "CAR", "RENTAL" -> Product.WALK
            "HIGHSPEED_RAIL" -> Product.HIGHSPEED
            "LONG_DISTANCE" -> Product.LONG_DISTANCE
            "NIGHT_RAIL" -> Product.NIGHT
            "REGIONAL_RAIL", "REGIONAL_FAST_RAIL" -> Product.REGIONAL
            "SUBURBAN" -> Product.SUBURBAN
            "SUBWAY", "METRO" -> Product.SUBWAY
            "TRAM", "FUNICULAR", "CABLE_CAR", "AERIAL_LIFT" -> Product.TRAM
            "BUS" -> Product.BUS
            "COACH" -> Product.COACH
            "FERRY" -> Product.FERRY
            "RAIL" -> guessFromLine(line) ?: Product.REGIONAL
            else -> Product.OTHER
        }

        fun guessFromLine(line: String?): Product? {
            val l = line?.trim()?.uppercase() ?: return null
            return when {
                l.startsWith("ICE") || l.startsWith("TGV") || l.startsWith("RJ") || l.startsWith("ECE") || l.startsWith("FR ") -> Product.HIGHSPEED
                l.startsWith("NJ") || l.startsWith("EN") || l.startsWith("ES ") -> Product.NIGHT
                l.startsWith("IC") || l.startsWith("EC") || l.startsWith("FLX") || l.startsWith("IR ") || l.startsWith("D ") -> Product.LONG_DISTANCE
                l.startsWith("RE") || l.startsWith("IRE") || l.startsWith("MEX") || l.startsWith("FEX") -> Product.REGIONAL_EXPRESS
                l.startsWith("RB") || l.startsWith("R ") || l.startsWith("BRB") || l.startsWith("ERB") -> Product.REGIONAL
                Regex("^S ?\\d").containsMatchIn(l) -> Product.SUBURBAN
                Regex("^U ?\\d").containsMatchIn(l) -> Product.SUBWAY
                else -> null
            }
        }
    }
}

internal fun String.cleanTrack(): String = removePrefix("Gl. ").removePrefix("Gleis ").removePrefix("Gl.").trim()

internal fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
internal fun JsonObject.dbl(k: String): Double? = (this[k] as? JsonPrimitive)?.doubleOrNull
internal fun JsonObject.int(k: String): Int? = (this[k] as? JsonPrimitive)?.intOrNull
internal fun JsonObject.bool(k: String): Boolean? = (this[k] as? JsonPrimitive)?.booleanOrNull
internal fun JsonObject.time(k: String): Long? = str(k)?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
internal fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()

/** Google encoded polyline with configurable precision. */
fun decodePolyline(s: String, precision: Int): List<LatLon> {
    val factor = Math.pow(10.0, precision.toDouble())
    val out = ArrayList<LatLon>()
    var i = 0; var lat = 0L; var lon = 0L
    while (i < s.length) {
        for (k in 0..1) {
            var shift = 0; var result = 0L
            while (true) {
                val b = s[i++].code - 63
                result = result or ((b and 0x1f).toLong() shl shift)
                shift += 5
                if (b < 0x20) break
            }
            val d = if (result and 1L != 0L) (result shr 1).inv() else result shr 1
            if (k == 0) lat += d else lon += d
        }
        out += LatLon(lat / factor, lon / factor)
    }
    return out
}
