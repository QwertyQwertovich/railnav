package de.railnav.offline

import de.railnav.model.Journey
import de.railnav.model.LatLon
import de.railnav.model.Leg
import de.railnav.model.Product
import de.railnav.model.StopTime
import de.railnav.model.distanceKm
import java.time.Instant
import java.time.ZoneId

/** Connection Scan Algorithm (earliest arrival) over three consecutive service days. */
class CsaRouter(val t: Timetable, val productMask: Int, val minTransferSec: Int, val providerId: String = "offline") {
    private val INF = Int.MAX_VALUE / 2

    fun route(src: IntArray, dst: IntArray, startMillis: Long, zone: ZoneId): Journey? {
        val startZ = Instant.ofEpochMilli(startMillis).atZone(zone)
        val date = startZ.toLocalDate()
        val midnight = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val start = ((startMillis - midnight) / 1000).toInt()
        val day0 = (date.toEpochDay() - t.baseEpochDay).toInt()

        val n = t.nStations
        val earliest = IntArray(n) { INF }
        val byTrain = BooleanArray(n)
        val enter = IntArray(n) { -1 }; val exit = IntArray(n) { -1 }; val foot = IntArray(n) { -1 }
        val isDst = BooleanArray(n).also { a -> dst.forEach { a[it] = true } }
        val tripEnter = Array(3) { IntArray(t.nTrips) { -1 } }
        var best = INF

        fun reach(st: Int, time: Int, train: Boolean, e: Int, x: Int, f: Int) {
            earliest[st] = time; byTrain[st] = train; enter[st] = e; exit[st] = x; foot[st] = f
            if (isDst[st] && time < best) best = time
        }
        for (s in src) reach(s, start, false, -1, -1, -1)
        for (s in src) for (k in t.fpStart[s] until t.fpStart[s + 1]) {
            val nb = t.fpTo[k]; val tt2 = start + t.fpSec[k]
            if (tt2 < earliest[nb]) reach(nb, tt2, false, -1, -1, s)
        }

        // three passes: previous day (+24h shifted), today, next day
        val offs = intArrayOf(-86400, 0, 86400)
        val idx = IntArray(3) { p -> t.lowerBound(start - offs[p]) }
        val limit = start + 30 * 3600
        while (true) {
            var p = -1; var bestT = Int.MAX_VALUE
            for (q in 0..2) if (idx[q] < t.nConn) {
                val tq = t.connDep[idx[q]] + offs[q]
                if (tq < bestT) { bestT = tq; p = q }
            }
            if (p < 0 || bestT >= best || bestT > limit) break
            val c = idx[p]++
            val trip = t.connTrip[c]
            if (productMask and (1 shl t.tripProduct[trip].toInt()) == 0) continue
            if (!t.serviceActive(t.tripService[trip], day0 + p - 1)) continue
            val base = t.tripStart[trip] + t.connPos[c]
            if (tripEnter[p][trip] < 0) {
                val fs = t.seqStation[base]
                val e = earliest[fs]
                if (e >= INF) continue
                val slack = if (byTrain[fs]) minTransferSec else 0
                if (e + slack > bestT) continue
                tripEnter[p][trip] = c
            }
            val as2 = t.seqStation[base + 1]
            val at = t.seqArr[base + 1] + offs[p]
            if (at < earliest[as2]) {
                reach(as2, at, true, p * t.nConn + tripEnter[p][trip], p * t.nConn + c, -1)
                for (k in t.fpStart[as2] until t.fpStart[as2 + 1]) {
                    val nb = t.fpTo[k]; val wt = at + t.fpSec[k]
                    if (wt < earliest[nb]) reach(nb, wt, false, -1, -1, as2)
                }
            }
        }
        if (best >= INF) return null
        val target = dst.minByOrNull { earliest[it] } ?: return null

        // backtrack
        val legs = ArrayList<Leg>()
        var st = target; var guard = 0
        while (guard++ < 50) {
            if (foot[st] >= 0) {
                val from = foot[st]
                legs += walkLeg(from, st, earliest[from], earliest[st], midnight)
                st = from
            } else if (exit[st] >= 0) {
                val pe = enter[st] / t.nConn; val ce = enter[st] % t.nConn
                val cx = exit[st] % t.nConn
                val trip = t.connTrip[ce]
                legs += tripLeg(trip, t.connPos[ce], t.connPos[cx] + 1, offs[pe], midnight)
                st = t.seqStation[t.tripStart[trip] + t.connPos[ce]]
            } else break
        }
        legs.reverse()
        if (legs.isEmpty() || legs.all { it.isWalk }) return null
        // drop leading/trailing walks inside a city search
        while (legs.size > 1 && legs.first().isWalk) legs.removeAt(0)
        while (legs.size > 1 && legs.last().isWalk) legs.removeAt(legs.size - 1)
        return Journey("off:" + legs.joinToString { it.line + it.dep }.hashCode().toUInt().toString(16), legs, providerId)
    }

    private fun stopTime(st: Int, arr: Long?, dep: Long?, plat: Int) = StopTime(
        name = t.name(st), stopId = st.toString(),
        lat = t.stationLat[st].toDouble(), lon = t.stationLon[st].toDouble(),
        arrPlanned = arr, depPlanned = dep,
        platformPlanned = if (plat >= 0) t.strings[plat] else null,
    )

    private fun tripLeg(trip: Int, p0: Int, p1: Int, off: Int, midnight: Long): Leg {
        val b = t.tripStart[trip]
        fun ms(sec: Int) = midnight + (sec + off) * 1000L
        val all = (p0..p1).map { p ->
            stopTime(t.seqStation[b + p], ms(t.seqArr[b + p]), ms(t.seqDep[b + p]), t.seqPlatform[b + p])
        }
        return Leg(
            product = Product.refine(Product.entries[t.tripProduct[trip].toInt()], t.strings[t.tripLine[trip]]),
            line = t.strings[t.tripLine[trip]],
            direction = t.strings[t.tripHeadsign[trip]].ifBlank { t.name(t.seqStation[t.tripStart[trip + 1] - 1]) },
            tripId = "off:$trip",
            from = all.first().copy(arrPlanned = null),
            to = all.last().copy(depPlanned = null),
            stops = all.subList(1, all.size - 1),
        )
    }

    private fun walkLeg(a: Int, b: Int, ta: Int, tb: Int, midnight: Long): Leg {
        val d = distanceKm(
            LatLon(t.stationLat[a].toDouble(), t.stationLon[a].toDouble()),
            LatLon(t.stationLat[b].toDouble(), t.stationLon[b].toDouble())
        )
        return Leg(
            product = Product.WALK, line = "Пешком",
            from = stopTime(a, null, midnight + ta * 1000L, -1),
            to = stopTime(b, midnight + tb * 1000L, null, -1),
            walkMeters = (d * 1000).toInt(),
        )
    }
}

