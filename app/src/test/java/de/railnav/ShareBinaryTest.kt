package de.railnav

import de.railnav.model.Journey
import de.railnav.model.Leg
import de.railnav.model.Place
import de.railnav.model.Product
import de.railnav.model.Segment
import de.railnav.model.StopTime
import de.railnav.model.TripPlan
import de.railnav.store.ShareBinary
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Base64
import java.util.zip.Deflater

class ShareBinaryTest {
    private fun st(n: String, la: Double, lo: Double, dep: Long? = null, arr: Long? = null, pl: String? = null) =
        StopTime(n, null, la, lo, arrPlanned = arr, depPlanned = dep, platformPlanned = pl)

    @Test
    fun roundTripAndLength() {
        val t0 = 29_640_000L * 60000 // some minute in 2026
        val m = 60_000L
        val halle = st("Halle (Saale) Hbf", 51.47751, 11.98708, dep = t0, pl = "4")
        val md = st("Magdeburg Hbf", 52.13077, 11.62702, arr = t0 + 73 * m, pl = "2")
        val l1 = Leg(Product.REGIONAL_EXPRESS, "RE30", "Magdeburg Hbf", from = halle, to = md)
        val l2 = Leg(Product.SUBURBAN, "S1", "Schönebeck-Bad Salzelmen",
            from = st("Magdeburg Hbf", 52.13077, 11.62702, dep = t0 + 200 * m, pl = "5"),
            to = st("Magdeburg-Buckau", 52.10900, 11.63000, arr = t0 + 206 * m))
        val l3 = Leg(Product.REGIONAL_EXPRESS, "RE30", "Halle (Saale) Hbf",
            from = st("Magdeburg-Buckau", 52.10900, 11.63000, dep = t0 + 212 * m, pl = "3"),
            to = st("Halle (Saale) Hbf", 51.47751, 11.98708, arr = t0 + 290 * m, pl = "8"))
        val p = TripPlan("x", "t", listOf(
            Segment(Place(name = "Halle", lat = 0.0, lon = 0.0), Place(name = "Magdeburg", lat = 0.0, lon = 0.0), Journey("a", listOf(l1)), emptyList(), t0),
            Segment(Place(name = "Magdeburg", lat = 0.0, lon = 0.0), Place(name = "Halle", lat = 0.0, lon = 0.0), Journey("b", listOf(l2, l3)), emptyList(), t0),
        ))
        val raw = ShareBinary.encode(p)
        val def = Deflater(9, true); def.setInput(raw); def.finish()
        val buf = ByteArray(raw.size + 64); val n = def.deflate(buf); def.end()
        val enc = Base64.getUrlEncoder().withoutPadding()
        val link = if (n < raw.size) "#q=" + enc.encodeToString(buf.copyOf(n)) else "#b=" + enc.encodeToString(raw)
        println("raw=${raw.size} deflated=$n link=${link.length} chars")
        println("LINK $link")
        val back = ShareBinary.decode(raw, 1)
        assertEquals("Halle → Magdeburg → Halle", back.title)
        assertEquals(3, back.segments.sumOf { it.journey.legs.size })
        assertEquals(t0 + 290 * m, back.arr)
        assertEquals("8", back.segments[1].journey.legs[1].to.platform)
        assertEquals(52.1308, back.segments[0].journey.legs[0].to.lat, 1e-4)
    }
}
