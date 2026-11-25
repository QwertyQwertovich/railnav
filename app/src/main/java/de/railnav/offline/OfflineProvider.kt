package de.railnav.offline

import android.content.Context
import de.railnav.data.JourneyPage
import de.railnav.data.JourneyQuery
import de.railnav.data.Net
import de.railnav.data.Provider
import de.railnav.data.ProviderException
import de.railnav.model.Journey
import de.railnav.model.LatLon
import de.railnav.model.Leg
import de.railnav.model.Place
import de.railnav.model.Product
import de.railnav.model.StopTime
import de.railnav.model.distanceKm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Downloads GTFS feeds, converts them and routes on the device without network. */
class OfflineProvider(private val ctx: Context) : Provider {
    override val id = "offline"
    override val title = "Офлайн (GTFS на устройстве)"
    override val supportsRealtime = false

    private val dir get() = File(ctx.filesDir, "offline").apply { mkdirs() }
    private val dataFile get() = File(dir, "timetable.bin")
    private val mutex = Mutex()
    @Volatile private var tt: Timetable? = null

    fun hasData() = dataFile.exists()
    fun info(): String? = tt?.feedInfo ?: if (hasData()) "Данные загружены (${dataFile.length() / 1_000_000} МБ)" else null

    private suspend fun timetable(): Timetable = mutex.withLock {
        tt ?: withContext(Dispatchers.IO) {
            if (!dataFile.exists()) throw ProviderException("Офлайн-данные не загружены. Скачайте их в настройках.")
            Timetable.load(dataFile).also { tt = it }
        }
    }

    /** Download + import. [feeds] are URLs of GTFS zips. */
    suspend fun importFeeds(feeds: List<String>, progress: (String, Float?) -> Unit) = withContext(Dispatchers.IO) {
        val importer = GtfsImporter { progress(it, null) }
        for ((n, url) in feeds.withIndex()) {
            val zip = File(dir, "feed$n.zip")
            progress("Скачивание ${n + 1}/${feeds.size}: ${url.substringAfter("//").take(60)}…", 0f)
            val req = Request.Builder().url(url).build()
            Net.client.newBuilder().readTimeout(5, java.util.concurrent.TimeUnit.MINUTES).build()
                .newCall(req).execute().use { r ->
                    if (!r.isSuccessful) throw ProviderException("Ошибка скачивания $url: HTTP ${r.code}")
                    val total = r.body!!.contentLength()
                    r.body!!.byteStream().use { input ->
                        zip.outputStream().use { out ->
                            val buf = ByteArray(1 shl 16); var done = 0L; var last = 0L
                            while (true) {
                                val k = input.read(buf); if (k < 0) break
                                out.write(buf, 0, k); done += k
                                if (done - last > 2_000_000) {
                                    last = done
                                    progress("Скачивание ${n + 1}/${feeds.size}… ${done / 1_000_000} / ${if (total > 0) total / 1_000_000 else "?"} МБ",
                                        if (total > 0) done.toFloat() / total else null)
                                }
                            }
                        }
                    }
                }
            importer.addFeed(zip, url.substringBeforeLast("/").substringAfterLast("/"))
            zip.delete()
        }
        val t = importer.build()
        progress("Сохранение…", null)
        val tmp = File(dir, "timetable.tmp")
        t.save(tmp)
        tmp.renameTo(dataFile.also { it.delete() })
        mutex.withLock { tt = t }
        File(dir, "meta.txt").writeText((listOf(System.currentTimeMillis().toString()) + feeds).joinToString(System.lineSeparator()))
        progress("Готово: ${t.feedInfo}", 1f)
    }

    fun deleteData() { dataFile.delete(); File(dir, "meta.txt").delete(); tt = null }

    /** When the data was imported (epoch millis) or null. */
    fun importedAt(): Long? = runCatching { File(dir, "meta.txt").readLines().first().toLong() }.getOrNull()
    fun sizeMb(): Long = if (dataFile.exists()) dataFile.length() / 1_000_000 else 0

    /** Last service day covered by the data (epoch day) — needs loaded timetable. */
    suspend fun validUntil(): java.time.LocalDate? = runCatching {
        val t = timetable(); java.time.LocalDate.ofEpochDay((t.baseEpochDay + t.numDays - 1).toLong())
    }.getOrNull()

    private fun norm(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}"), "").replace("ß", "ss")

    override suspend fun searchPlaces(query: String): List<Place> = withContext(Dispatchers.Default) {
        val t = timetable()
        val q = norm(query.trim())
        if (q.isEmpty()) return@withContext emptyList()
        val hits = ArrayList<Pair<Int, Int>>()
        for (s in 0 until t.nStations) {
            val n = norm(t.name(s))
            val score = when {
                n == q -> 0
                n.startsWith(q) -> 1 + n.length / 10
                n.contains(q) -> 5 + n.length / 10
                else -> continue
            }
            hits += s to score
        }
        hits.sortedBy { it.second }.take(15).map { (s, _) -> stationPlace(t, s) }
    }

    private fun stationPlace(t: Timetable, s: Int) =
        Place(id = s.toString(), name = t.name(s), lat = t.stationLat[s].toDouble(), lon = t.stationLon[s].toDouble(), provider = id)

    /** Station set for a place: city → all stations in radius; foreign station → nearest by name/coords. */
    private fun stationsFor(t: Timetable, p: Place): IntArray {
        if (!p.isCity && p.provider == id && p.id != null) return intArrayOf(p.id.toInt())
        val r = if (p.isCity) p.radiusKm.coerceAtLeast(2.0) else 1.5
        val within = (0 until t.nStations).filter {
            distanceKm(p.pos, LatLon(t.stationLat[it].toDouble(), t.stationLon[it].toDouble())) <= r
        }
        if (p.isCity) return within.toIntArray()
        val byName = within.firstOrNull { norm(t.name(it)) == norm(p.name) }
        val nearest = byName ?: within.minByOrNull {
            distanceKm(p.pos, LatLon(t.stationLat[it].toDouble(), t.stationLon[it].toDouble()))
        }
        return nearest?.let { intArrayOf(it) } ?: IntArray(0)
    }

    override suspend fun board(place: Place, time: Long, arrivals: Boolean, products: Set<Product>): List<de.railnav.model.BoardEntry> =
        withContext(Dispatchers.Default) {
            val t = timetable()
            val sts = stationsFor(t, place.let { if (it.isCity) it.copy(radiusKm = 0.6) else it }).toHashSet()
            if (sts.isEmpty()) throw ProviderException("В офлайн-данных нет станции «${place.name}»")
            val zone = ZoneId.of(t.timezone)
            val prods = if (Product.REGIONAL in products || Product.REGIONAL_EXPRESS in products)
                products + Product.REGIONAL + Product.REGIONAL_EXPRESS else products
            val mask = prods.fold(0) { m, p -> m or (1 shl p.ordinal) }
            val date = java.time.Instant.ofEpochMilli(time).atZone(zone).toLocalDate()
            val midnight = date.atStartOfDay(zone).toInstant().toEpochMilli()
            val start = ((time - midnight) / 1000).toInt()
            val end = start + 4 * 3600
            val day0 = (date.toEpochDay() - t.baseEpochDay).toInt()
            val out = ArrayList<de.railnav.model.BoardEntry>()
            for ((p, off) in listOf(-86400, 0).withIndex()) {
                var c = t.lowerBound(start - off - if (arrivals) 3 * 3600 else 0)
                while (c < t.nConn && t.connDep[c] + off <= end) {
                    val trip = t.connTrip[c]
                    val base = t.tripStart[trip] + t.connPos[c]
                    val k = if (arrivals) base + 1 else base
                    val st = t.seqStation[k]
                    val tm = (if (arrivals) t.seqArr[k] else t.seqDep[k]) + off
                    if (st in sts && tm in start..end && productAllowed(t, trip, mask) &&
                        t.serviceActive(t.tripService[trip], day0 + p - 1)) {
                        val ms = midnight + tm * 1000L
                        val first = t.tripStart[trip]; val last = t.tripStart[trip + 1] - 1
                        val line = t.strings[t.tripLine[trip]]
                        out += de.railnav.model.BoardEntry(
                            product = Product.refine(Product.entries[t.tripProduct[trip].toInt()], line),
                            line = line,
                            direction = if (arrivals) t.name(t.seqStation[first])
                            else t.strings[t.tripHeadsign[trip]].ifBlank { t.name(t.seqStation[last]) },
                            stop = StopTime(t.name(st), st.toString(), t.stationLat[st].toDouble(), t.stationLon[st].toDouble(),
                                arrPlanned = if (arrivals) ms else null, depPlanned = if (arrivals) null else ms,
                                platformPlanned = t.seqPlatform[k].takeIf { it >= 0 }?.let { t.strings[it] }),
                            tripId = "off:$trip:${midnight + off * 1000L}",
                        )
                    }
                    c++
                }
            }
            out.sortedBy { it.time(arrivals) }.take(60)
        }

    override suspend fun trip(tripId: String): Leg? = withContext(Dispatchers.Default) {
        val p = tripId.split(':')
        if (p.size < 3 || p[0] != "off") return@withContext null
        val t = timetable()
        val trip = p[1].toIntOrNull()?.takeIf { it in 0 until t.nTrips } ?: return@withContext null
        CsaRouter(t, 0, 0, id).fullTrip(trip, p[2].toLong())
    }

    private fun productAllowed(t: Timetable, trip: Int, mask: Int) = mask and (1 shl t.tripProduct[trip].toInt()) != 0

    override suspend fun journeys(q: JourneyQuery): JourneyPage = withContext(Dispatchers.Default) {
        val t = timetable()
        val src = stationsFor(t, q.from); val dst = stationsFor(t, q.to)
        if (src.isEmpty()) throw ProviderException("В офлайн-данных нет станции «${q.from.name}»")
        if (dst.isEmpty()) throw ProviderException("В офлайн-данных нет станции «${q.to.name}»")
        val zone = ZoneId.of(t.timezone)
        val prods = if (Product.REGIONAL in q.products || Product.REGIONAL_EXPRESS in q.products)
            q.products + Product.REGIONAL + Product.REGIONAL_EXPRESS else q.products // exact RE/RB filter happens in the planner
        val mask = prods.fold(0) { m, p -> m or (1 shl p.ordinal) }
        val router = CsaRouter(t, mask, q.minTransferMin * 60, id)
        val startT = if (q.pageCursor != null) q.pageCursor.toLong() else if (q.arriveBy) q.time - 4 * 3600_000L else q.time
        val out = ArrayList<Journey>()
        var cursor = startT
        val want = if (q.arriveBy) q.count + 6 else q.count + 3
        repeat(want) {
            val j = router.route(src, dst, cursor, zone) ?: return@repeat
            if (out.none { it.signature == j.signature }) out += j
            cursor = j.dep + 60_000
        }
        // drop dominated journeys (another one leaves later and arrives no later)
        val pareto = out.filter { j -> out.none { k -> k !== j && k.dep >= j.dep && k.arr <= j.arr && (k.dep > j.dep || k.arr < j.arr) } }
        val result = if (q.arriveBy) pareto.filter { it.arr <= q.time }.takeLast(q.count) else pareto.take(q.count)
        JourneyPage(
            result,
            earlierCursor = (result.firstOrNull()?.dep ?: q.time).minus(2 * 3600_000L).toString(),
            laterCursor = (result.lastOrNull()?.dep?.plus(60_000) ?: (q.time + 3600_000)).toString(),
        )
    }

    companion object {
        val DEFAULT_FEEDS = listOf(
            "https://download.gtfs.de/germany/fv_free/latest.zip",
            "https://download.gtfs.de/germany/rv_free/latest.zip",
        )
        @Suppress("unused") fun today(): LocalDate = LocalDate.now()
    }
}
