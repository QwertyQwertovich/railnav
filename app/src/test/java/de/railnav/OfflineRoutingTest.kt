package de.railnav

import de.railnav.model.Journey
import de.railnav.model.Product
import de.railnav.offline.CsaRouter
import de.railnav.offline.GtfsImporter
import de.railnav.offline.Timetable
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Runs only when -Dgtfs.dir points at a folder with fv.zip / rv.zip from gtfs.de. */
class OfflineRoutingTest {
    private val dir = System.getProperty("gtfs.dir")?.let(::File)

    private fun print(j: Journey) {
        val z = ZoneId.of("Europe/Berlin")
        fun t(ms: Long) = java.time.Instant.ofEpochMilli(ms).atZone(z).toLocalTime()
        println("  ${t(j.dep)} -> ${t(j.arr)} (${j.durationMin} min, ${j.transfers} transfers)")
        for (l in j.legs) println("     ${l.line.padEnd(10)} ${t(l.dep)} ${l.from.name} [${l.from.platform}] -> ${t(l.arr)} ${l.to.name} [${l.to.platform}]  stops=${l.stops.size}")
    }

    @Test
    fun importAndRoute() {
        assumeTrue(dir != null && File(dir, "rv.zip").exists())
        val t0 = System.currentTimeMillis()
        val imp = GtfsImporter { println(it) }
        imp.addFeed(File(dir, "fv.zip"), "fv_free")
        imp.addFeed(File(dir, "rv.zip"), "rv_free")
        val built = imp.build()
        println("import: ${System.currentTimeMillis() - t0} ms; ${built.feedInfo}; connections=${built.nConn}")
        val f = File(dir, "tt.bin"); built.save(f)
        val t1 = System.currentTimeMillis()
        val tt = Timetable.load(f)
        println("file ${f.length() / 1_000_000} MB, load ${System.currentTimeMillis() - t1} ms")

        fun st(name: String) = (0 until tt.nStations).first { tt.name(it) == name }
        val z = ZoneId.of(tt.timezone)
        val date = LocalDate.now().plusDays(1)
        val all = Product.entries.fold(0) { m, p -> m or (1 shl p.ordinal) }
        val regional = setOf(Product.REGIONAL, Product.SUBURBAN).fold(0) { m, p -> m or (1 shl p.ordinal) }

        val cases = listOf(
            Triple("Leipzig Hbf", "Dresden Hbf", all),
            Triple("Halle(Saale)Hbf", "Heidelberg Hbf", all),
            Triple("Berlin Hbf", "München Hbf", all),
            Triple("Leipzig Hbf", "Erfurt Hbf", regional),
        )
        for ((a, b, mask) in cases) {
            val r = CsaRouter(tt, mask, 5 * 60)
            var start = date.atTime(LocalTime.of(8, 0)).atZone(z).toInstant().toEpochMilli()
            println("$a -> $b ${if (mask == regional) "(regional only)" else ""}")
            repeat(3) {
                val q0 = System.nanoTime()
                val j = r.route(intArrayOf(st(a)), intArrayOf(st(b)), start, z)
                val ms = (System.nanoTime() - q0) / 1_000_000
                assertNotNull("no route $a -> $b", j)
                println("  query $ms ms"); print(j!!)
                assertTrue(j.dep >= start)
                if (mask == regional) assertTrue(j.transitLegs.all { it.product in setOf(Product.REGIONAL, Product.SUBURBAN) })
                start = j.dep + 60_000
            }
        }
    }
}
