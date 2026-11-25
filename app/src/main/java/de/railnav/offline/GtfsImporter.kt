package de.railnav.offline

import de.railnav.data.TransitousProvider
import de.railnav.model.LatLon
import de.railnav.model.Product
import de.railnav.model.distanceKm
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.zip.ZipFile
import kotlin.math.floor

/** Growable primitive int list. */
class IntList(cap: Int = 1024) {
    var a = IntArray(cap); var size = 0
    fun add(v: Int) { if (size == a.size) a = a.copyOf(a.size * 2); a[size++] = v }
    operator fun get(i: Int) = a[i]
    fun toArray() = a.copyOf(size)
}

/**
 * Converts one or more GTFS zip files into a [Timetable].
 * Platform-level stops are merged into stations (parent_station, then same name within 1 km).
 */
class GtfsImporter(private val progress: (String) -> Unit) {
    private val strings = ArrayList<String>()
    private val stringIdx = HashMap<String, Int>()
    private fun str(s: String): Int = stringIdx.getOrPut(s) { strings.add(s); strings.size - 1 }

    // stations
    private val stName = IntList(); private val stLat = ArrayList<Float>(); private val stLon = ArrayList<Float>()
    private val byName = HashMap<String, MutableList<Int>>()

    // trips
    private val tripLine = IntList(); private val tripHead = IntList(); private val tripProd = ArrayList<Byte>()
    private val tripService = IntList(); private val tripStart = IntList()
    private val seqStation = IntList(1 shl 20); private val seqArr = IntList(1 shl 20)
    private val seqDep = IntList(1 shl 20); private val seqPlat = IntList(1 shl 20)

    // services: added/removed date sets per service, resolved at the end
    private class Svc(var start: Int = Int.MAX_VALUE, var end: Int = Int.MIN_VALUE, var mask: Int = 0,
                      val added: MutableSet<Int> = HashSet(), val removed: MutableSet<Int> = HashSet())
    private val services = ArrayList<Svc>()
    private val serviceIdx = HashMap<String, Int>()
    private var timezone = "Europe/Berlin"
    private val feedNames = ArrayList<String>()

    private val dateFmt = DateTimeFormatter.BASIC_ISO_DATE

    private fun station(name: String, lat: Double, lon: Double): Int {
        val list = byName.getOrPut(name) { mutableListOf() }
        for (s in list) {
            if (distanceKm(LatLon(lat, lon), LatLon(stLat[s].toDouble(), stLon[s].toDouble())) < 1.0) return s
        }
        val id = stName.size
        stName.add(str(name)); stLat.add(lat.toFloat()); stLon.add(lon.toFloat())
        list += id
        return id
    }

    fun addFeed(zipFile: File, label: String) {
        feedNames += label
        val prefix = "${feedNames.size}:"
        ZipFile(zipFile).use { zip ->
            fun rows(name: String, block: (Csv) -> Unit) {
                val e = zip.getEntry(name) ?: return
                BufferedReader(InputStreamReader(zip.getInputStream(e), Charsets.UTF_8), 1 shl 16).use { r ->
                    var header = r.readLine() ?: return
                    if (header.startsWith("﻿")) header = header.substring(1)
                    val csv = Csv(splitCsv(header))
                    while (true) {
                        val line = r.readLine() ?: break
                        if (line.isBlank()) continue
                        csv.values = splitCsv(line)
                        block(csv)
                    }
                }
            }

            rows("agency.txt") { c -> c["agency_timezone"]?.let { if (feedNames.size == 1) timezone = it } }

            progress("$label: станции…")
            data class RawStop(val id: String, val name: String, val lat: Double, val lon: Double, val parent: String?, val platform: String?)
            val raw = HashMap<String, RawStop>()
            rows("stops.txt") { c ->
                val id = c["stop_id"] ?: return@rows
                raw[id] = RawStop(
                    id, c["stop_name"] ?: id,
                    c["stop_lat"]?.toDoubleOrNull() ?: 0.0, c["stop_lon"]?.toDoubleOrNull() ?: 0.0,
                    c["parent_station"]?.ifBlank { null }, c["platform_code"]?.ifBlank { null }
                )
            }
            val stopStation = HashMap<String, Int>(raw.size * 2)
            val stopPlatform = HashMap<String, Int>()
            fun rootOf(s: RawStop): RawStop {
                var root = s; var guard = 0
                while (root.parent != null && raw[root.parent] != null && guard++ < 4) root = raw[root.parent]!!
                return root
            }
            // Parent names are often odd ("Dresden Hauptbahnhof", "Aachen, Hbf"); platforms carry the
            // familiar name ("Dresden Hbf"). Use the most common child name per parent.
            val children = HashMap<String, MutableList<String>>()
            for (s in raw.values) {
                val r = rootOf(s)
                if (r.id != s.id) children.getOrPut(r.id) { mutableListOf() } += s.name
            }
            val rootStation = HashMap<String, Int>()
            for (s in raw.values) {
                val root = rootOf(s)
                stopStation[s.id] = rootStation.getOrPut(root.id) {
                    val token = root.name.split(' ', ',', '(', '-', '/')
                        .firstOrNull { it.length >= 3 && it.any(Char::isLetter) && it.uppercase() !in setOf("S+U", "BF", "HBF") }.orEmpty()
                    val names = children[root.id].orEmpty()
                        .filter { !it.contains("FlixTrain") && !it.contains("Central Station") }
                        .filter { token.isNotEmpty() && it.contains(token, ignoreCase = true) }
                    val name = names.groupingBy { it }.eachCount().entries
                        .sortedWith(compareBy({ -it.value }, { it.key.length })).firstOrNull()?.key
                        ?: root.name.replace(", ", " ")
                    station(name, root.lat, root.lon)
                }
                s.platform?.let { stopPlatform[s.id] = str(it) }
            }

            progress("$label: календарь…")
            rows("calendar.txt") { c ->
                val sid = serviceIdx.getOrPut(prefix + (c["service_id"] ?: "")) { services.add(Svc()); services.size - 1 }
                val s = services[sid]
                s.start = LocalDate.parse(c["start_date"], dateFmt).toEpochDay().toInt()
                s.end = LocalDate.parse(c["end_date"], dateFmt).toEpochDay().toInt()
                val days = listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")
                s.mask = days.withIndex().sumOf { (i, d) -> if (c[d] == "1") 1 shl i else 0 }
            }
            rows("calendar_dates.txt") { c ->
                val sid = serviceIdx.getOrPut(prefix + (c["service_id"] ?: "")) { services.add(Svc()); services.size - 1 }
                val d = LocalDate.parse(c["date"], dateFmt).toEpochDay().toInt()
                if (c["exception_type"] == "1") services[sid].added += d else services[sid].removed += d
            }

            progress("$label: линии…")
            val routeLine = HashMap<String, Pair<Int, Product>>()
            rows("routes.txt") { c ->
                val name = c["route_short_name"]?.ifBlank { null } ?: c["route_long_name"] ?: "?"
                val type = c["route_type"]?.toIntOrNull() ?: 2
                routeLine[c["route_id"] ?: return@rows] = str(name) to productOf(type, name)
            }

            progress("$label: рейсы…")
            data class RawTrip(val line: Int, val head: Int, val prod: Product, val service: Int)
            val trips = HashMap<String, RawTrip>()
            rows("trips.txt") { c ->
                val r = routeLine[c["route_id"]] ?: return@rows
                val sid = serviceIdx[prefix + (c["service_id"] ?: "")] ?: return@rows
                val short = c["trip_short_name"]?.ifBlank { null }
                val lineName = strings[r.first].let { ln ->
                    if (short != null && !ln.contains(short) && r.second.isRail && ln.length <= 4 && !ln.any { it.isDigit() }) "$ln $short" else ln
                }
                trips[c["trip_id"] ?: return@rows] = RawTrip(str(lineName), str(c["trip_headsign"] ?: ""), r.second, sid)
            }

            progress("$label: расписание (это самая долгая часть)…")
            var curTrip: String? = null
            var curInfo: RawTrip? = null
            val bufSeq = IntList(64); val bufSt = IntList(64); val bufA = IntList(64); val bufD = IntList(64); val bufP = IntList(64)
            var rowsRead = 0
            fun flush() {
                val info = curInfo
                if (info != null && bufSt.size >= 2) {
                    // sort by stop_sequence (usually already sorted)
                    val order = (0 until bufSt.size).sortedBy { bufSeq[it] }
                    tripLine.add(info.line); tripHead.add(info.head); tripProd.add(info.prod.ordinal.toByte())
                    tripService.add(info.service); tripStart.add(seqStation.size)
                    var lastA = 0; var lastD = 0
                    for (k in order) {
                        val a = if (bufA[k] >= 0) bufA[k] else lastD
                        val d = if (bufD[k] >= 0) bufD[k] else a
                        seqStation.add(bufSt[k]); seqArr.add(a); seqDep.add(d); seqPlat.add(bufP[k])
                        lastA = a; lastD = d
                    }
                }
                bufSeq.size = 0; bufSt.size = 0; bufA.size = 0; bufD.size = 0; bufP.size = 0
            }
            rows("stop_times.txt") { c ->
                val tid = c["trip_id"] ?: return@rows
                if (tid != curTrip) { flush(); curTrip = tid; curInfo = trips[tid] }
                if (curInfo == null) return@rows
                val sid = c["stop_id"] ?: return@rows
                val st = stopStation[sid] ?: return@rows
                bufSeq.add(c["stop_sequence"]?.toIntOrNull() ?: bufSeq.size)
                bufSt.add(st)
                bufA.add(parseTime(c["arrival_time"]))
                bufD.add(parseTime(c["departure_time"]))
                bufP.add(stopPlatform[sid] ?: -1)
                if (++rowsRead % 500_000 == 0) progress("$label: расписание… ${rowsRead / 1000} тыс. строк")
            }
            flush()
        }
    }

    fun build(): Timetable {
        progress("Сборка индекса…")
        val nTrips = tripLine.size
        tripStart.add(seqStation.size)
        val tStart = tripStart.toArray()
        val sSt = seqStation.toArray(); val sDep = seqDep.toArray()

        // Connections sorted by departure: key = dep<<32 | (connection serial)
        var nConn = 0
        for (t in 0 until nTrips) nConn += tStart[t + 1] - tStart[t] - 1
        val keys = LongArray(nConn)
        val cTripTmp = IntArray(nConn); val cPosTmp = IntArray(nConn)
        var k = 0
        for (t in 0 until nTrips) {
            for (p in 0 until tStart[t + 1] - tStart[t] - 1) {
                keys[k] = (sDep[tStart[t] + p].toLong() shl 32) or k.toLong()
                cTripTmp[k] = t; cPosTmp[k] = p; k++
            }
        }
        keys.sort()
        val connTrip = IntArray(nConn); val connPos = IntArray(nConn); val connDep = IntArray(nConn)
        for (i in 0 until nConn) {
            val idx = (keys[i] and 0xffffffffL).toInt()
            connTrip[i] = cTripTmp[idx]; connPos[i] = cPosTmp[idx]; connDep[i] = (keys[i] ushr 32).toInt()
        }

        // Services → day bitsets
        progress("Календарь…")
        var minDay = Int.MAX_VALUE; var maxDay = Int.MIN_VALUE
        for (s in services) {
            if (s.mask != 0) { minDay = minOf(minDay, s.start); maxDay = maxOf(maxDay, s.end) }
            s.added.forEach { minDay = minOf(minDay, it); maxDay = maxOf(maxDay, it) }
        }
        if (minDay == Int.MAX_VALUE) { minDay = LocalDate.now().toEpochDay().toInt(); maxDay = minDay }
        val numDays = (maxDay - minDay + 1).coerceIn(1, 800)
        val words = (numDays + 63) / 64
        val bits = LongArray(services.size * words)
        for ((si, s) in services.withIndex()) {
            for (d in 0 until numDays) {
                val day = minDay + d
                val dow = LocalDate.ofEpochDay(day.toLong()).dayOfWeek.value - 1
                var on = s.mask != 0 && day in s.start..s.end && (s.mask shr dow) and 1 == 1
                if (day in s.added) on = true
                if (day in s.removed) on = false
                if (on) bits[si * words + (d ushr 6)] = bits[si * words + (d ushr 6)] or (1L shl (d and 63))
            }
        }

        progress("Пешие переходы…")
        val nSt = stName.size
        val lat = FloatArray(nSt) { stLat[it] }; val lon = FloatArray(nSt) { stLon[it] }
        val grid = HashMap<Long, MutableList<Int>>()
        fun cell(la: Float, lo: Float) = (floor(la / 0.005).toLong() shl 32) xor (floor(lo / 0.008).toLong() and 0xffffffffL)
        for (s in 0 until nSt) grid.getOrPut(cell(lat[s], lon[s])) { mutableListOf() } += s
        val fpStart = IntArray(nSt + 1); val fpTo = IntList(); val fpSec = IntList()
        for (s in 0 until nSt) {
            fpStart[s] = fpTo.size
            val cy = floor(lat[s] / 0.005).toLong(); val cx = floor(lon[s] / 0.008).toLong()
            for (dy in -1L..1L) for (dx in -1L..1L) {
                val list = grid[((cy + dy) shl 32) xor ((cx + dx) and 0xffffffffL)] ?: continue
                for (o in list) {
                    if (o == s) continue
                    val d = distanceKm(LatLon(lat[s].toDouble(), lon[s].toDouble()), LatLon(lat[o].toDouble(), lon[o].toDouble()))
                    if (d <= 0.45) { fpTo.add(o); fpSec.add((60 + d * 1000 * 1.3 / 1.2).toInt()) }
                }
            }
        }
        fpStart[nSt] = fpTo.size

        return Timetable(
            strings = strings.toTypedArray(), stationName = stName.toArray(), stationLat = lat, stationLon = lon,
            fpStart = fpStart, fpTo = fpTo.toArray(), fpSec = fpSec.toArray(),
            tripLine = tripLine.toArray(), tripHeadsign = tripHead.toArray(),
            tripProduct = ByteArray(nTrips) { tripProd[it] }, tripService = tripService.toArray(), tripStart = tStart,
            seqStation = sSt, seqArr = seqArr.toArray(), seqDep = sDep, seqPlatform = seqPlat.toArray(),
            connTrip = connTrip, connPos = connPos, connDep = connDep,
            baseEpochDay = minDay, numDays = numDays, serviceWords = words, serviceBits = bits,
            timezone = timezone,
            feedInfo = "${feedNames.joinToString(" + ")}: ${nSt} станций, ${nTrips} рейсов, " +
                "${LocalDate.ofEpochDay(minDay.toLong())} – ${LocalDate.ofEpochDay((minDay + numDays - 1).toLong())}",
        )
    }

    class Csv(private val header: List<String>) {
        private val idx = header.withIndex().associate { it.value.trim() to it.index }
        var values: List<String> = emptyList()
        operator fun get(col: String): String? = idx[col]?.let { values.getOrNull(it) }
    }

    companion object {
        fun splitCsv(line: String): List<String> {
            if (line.indexOf('"') < 0) return line.split(',')
            val out = ArrayList<String>(16)
            val sb = StringBuilder()
            var q = false; var i = 0
            while (i < line.length) {
                val ch = line[i]
                when {
                    q && ch == '"' && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                    ch == '"' -> q = !q
                    ch == ',' && !q -> { out += sb.toString(); sb.setLength(0) }
                    else -> sb.append(ch)
                }
                i++
            }
            out += sb.toString()
            return out
        }

        fun parseTime(s: String?): Int {
            if (s.isNullOrBlank()) return -1
            val p = s.trim().split(':')
            if (p.size < 2) return -1
            return (p[0].toIntOrNull() ?: return -1) * 3600 + (p[1].toIntOrNull() ?: 0) * 60 + (p.getOrNull(2)?.toIntOrNull() ?: 0)
        }

        fun productOf(routeType: Int, line: String): Product {
            val byType = when (routeType) {
                101 -> Product.HIGHSPEED
                102, 103 -> Product.LONG_DISTANCE
                105 -> Product.NIGHT
                106, 107, 108 -> Product.REGIONAL
                109 -> Product.SUBURBAN
                1, 400, 401, 402 -> Product.SUBWAY
                0, 5, 7, 900, 1400 -> Product.TRAM
                3, 700, 702, 704, 715 -> Product.BUS
                200, 201, 202 -> Product.COACH
                4, 1000, 1200 -> Product.FERRY
                else -> null
            }
            val byName = TransitousProvider.guessFromLine(line)
            return when {
                routeType == 2 || routeType == 100 -> byName ?: Product.REGIONAL
                byType == Product.REGIONAL && byName != null -> byName
                byType != null -> byType
                else -> byName ?: Product.OTHER
            }
        }
    }
}
