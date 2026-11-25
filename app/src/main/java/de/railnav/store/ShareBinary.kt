package de.railnav.store

import de.railnav.model.Journey
import de.railnav.model.Leg
import de.railnav.model.Place
import de.railnav.model.Product
import de.railnav.model.Segment
import de.railnav.model.StopTime
import de.railnav.model.TripPlan
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * Compact binary share format (v2), raw-deflated and base64url'd into "#q=…".
 *
 * Layout (all integers are unsigned LEB128 varints, "z" = zigzag-signed):
 *   u8 version=2 · baseMinute · nStrings · {len, utf8}* ·
 *   nStations · {nameIdx, z dLat, z dLon (1e-4°, delta to previous)}* ·
 *   nSegs · {fromIdx, toIdx, nLegs · {u8 product, line+1, dir+1, fromSt, toSt, z depGap, duration, platA+1, platB+1}*}*
 * Times are minutes; depGap is relative to the previous leg's arrival (or base).
 */
object ShareBinary {
    private class W {
        val out = ByteArrayOutputStream()
        fun u(v: Long) { var x = v; while (x >= 0x80) { out.write(((x and 0x7f) or 0x80).toInt()); x = x ushr 7 }; out.write(x.toInt()) }
        fun u(v: Int) = u(v.toLong())
        fun z(v: Long) = u((v shl 1) xor (v shr 63))
        fun b(v: Int) = out.write(v)
    }

    private class R(val a: ByteArray) {
        var i = 0
        fun u(): Long { var r = 0L; var s = 0; while (true) { val x = a[i++].toInt() and 0xff; r = r or ((x and 0x7f).toLong() shl s); if (x < 0x80) return r; s += 7 } }
        fun ui() = u().toInt()
        fun z(): Long { val v = u(); return (v ushr 1) xor -(v and 1) }
        fun b() = a[i++].toInt() and 0xff
    }

    fun encode(p: TripPlan): ByteArray {
        val strings = ArrayList<String>(); val sIdx = HashMap<String, Int>()
        fun s(x: String) = sIdx.getOrPut(x) { strings += x; strings.size - 1 }
        val stations = ArrayList<StopTime>(); val stIdx = HashMap<String, Int>()
        fun st(x: StopTime) = stIdx.getOrPut(x.name) { stations += x; s(x.name); stations.size - 1 }

        val base = p.dep / 60000
        // first pass: collect strings & stations in a stable order
        data class L(val prod: Int, val line: Int, val dir: Int, val a: Int, val b: Int, val dep: Long, val arr: Long, val pa: Int, val pb: Int)
        val segs = p.segments.map { sg ->
            Triple(s(sg.from.name), s(sg.to.name), sg.journey.legs.map { l ->
                L(l.product.ordinal, if (l.isWalk) -1 else s(l.line), l.direction?.let { s(it) } ?: -1, st(l.from), st(l.to),
                    (l.from.depPlanned ?: l.dep) / 60000, (l.to.arrPlanned ?: l.arr) / 60000,
                    l.from.platform?.let { s(it) } ?: -1, l.to.platform?.let { s(it) } ?: -1)
            })
        }
        val w = W()
        w.b(2); w.u(base)
        w.u(strings.size)
        for (x in strings) { val bytes = x.toByteArray(); w.u(bytes.size); w.out.write(bytes) }
        w.u(stations.size)
        var pl = 0L; var po = 0L
        for (x in stations) {
            val la = Math.round(x.lat * 1e4); val lo = Math.round(x.lon * 1e4)
            w.u(sIdx[x.name]!!); w.z(la - pl); w.z(lo - po); pl = la; po = lo
        }
        w.u(segs.size)
        var prev = base
        for ((f, t, legs) in segs) {
            w.u(f); w.u(t); w.u(legs.size)
            for (l in legs) {
                w.b(l.prod); w.u(l.line + 1); w.u(l.dir + 1); w.u(l.a); w.u(l.b)
                w.z(l.dep - prev); w.u(maxOf(0L, l.arr - l.dep)); w.u(l.pa + 1); w.u(l.pb + 1)
                prev = l.arr
            }
        }
        return w.out.toByteArray()
    }

    fun decode(bytes: ByteArray, idSeed: Int): TripPlan {
        val r = R(bytes)
        require(r.b() == 2) { "unknown share format" }
        val base = r.u()
        val strings = List(r.ui()) { val n = r.ui(); val s = String(bytes, r.i, n, Charsets.UTF_8); r.i += n; s }
        var la = 0L; var lo = 0L
        val stations = List(r.ui()) {
            val name = strings[r.ui()]; la += r.z(); lo += r.z()
            Triple(name, la / 1e4, lo / 1e4)
        }
        var prev = base
        val segs = List(r.ui()) {
            val from = strings[r.ui()]; val to = strings[r.ui()]
            val legs = List(r.ui()) {
                val prod = Product.entries.getOrElse(r.b()) { Product.OTHER }
                val line = r.ui() - 1; val dir = r.ui() - 1
                val a = stations[r.ui()]; val b = stations[r.ui()]
                val dep = prev + r.z(); val arr = dep + r.u(); prev = arr
                val pa = r.ui() - 1; val pb = r.ui() - 1
                Leg(
                    product = prod, line = if (line >= 0) strings[line] else "Пешком",
                    direction = if (dir >= 0) strings[dir] else null,
                    from = StopTime(a.first, null, a.second, a.third, depPlanned = dep * 60000, platformPlanned = if (pa >= 0) strings[pa] else null),
                    to = StopTime(b.first, null, b.second, b.third, arrPlanned = arr * 60000, platformPlanned = if (pb >= 0) strings[pb] else null),
                )
            }
            val j = Journey("shared:$idSeed:${legs.first().dep}", legs, "shared")
            Segment(Place(name = from, lat = legs.first().from.lat, lon = legs.first().from.lon),
                Place(name = to, lat = legs.last().to.lat, lon = legs.last().to.lon), j, emptyList(), j.dep)
        }
        val title = (listOf(segs.first().from.name) + segs.map { it.to.name }).joinToString(" → ")
        return TripPlan(UUID.randomUUID().toString(), title, segs)
    }
}
