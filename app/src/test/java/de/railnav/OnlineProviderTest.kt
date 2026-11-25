package de.railnav

import de.railnav.data.JourneyQuery
import de.railnav.data.TransitousProvider
import de.railnav.model.Journey
import de.railnav.model.PlanRequest
import de.railnav.model.ReturnMode
import de.railnav.model.Product
import de.railnav.model.Waypoint
import de.railnav.planner.TripPlanner
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Live calls against Transitous. Run with -Donline=true. */
class OnlineProviderTest {
    private val z = ZoneId.of("Europe/Berlin")
    private fun t(ms: Long) = Instant.ofEpochMilli(ms).atZone(z).toLocalTime()
    private fun print(j: Journey) {
        println("  ${t(j.dep)} -> ${t(j.arr)} ${j.durationMin}min tr=${j.transfers}")
        for (l in j.legs) println("     ${l.product} ${l.line.padEnd(9)} ${t(l.dep)} ${l.from.name} [${l.from.platform}] -> ${t(l.arr)} ${l.to.name} [${l.to.platform}] shape=${l.shape.size} rt=${l.realtime}")
    }

    @Test
    fun transitous() = runBlocking {
        assumeTrue(System.getProperty("online") == "true")
        val p = TransitousProvider()
        val leipzig = p.searchPlaces("Leipzig Hbf").first { !it.isCity }
        val dresden = p.searchPlaces("Dresden").also { r -> r.take(4).forEach { println("place: ${it.displayName()} ${it.id}") } }
        val dresdenCity = dresden.first { it.isCity }.copy(radiusKm = 12.0)
        val halle = p.searchPlaces("Halle (Saale) Hbf").first { !it.isCity }
        val time = LocalDate.now().plusDays(1).atTime(LocalTime.of(8, 0)).atZone(z).toInstant().toEpochMilli()

        val planner = TripPlanner(p, 12.0)
        println("Station -> station, rail only")
        val page = planner.search(JourneyQuery(leipzig, p.searchPlaces("Dresden Hbf").first { !it.isCity }, time, products = Product.railOnly))
        page.journeys.forEach(::print)
        assertTrue(page.journeys.isNotEmpty())
        assertTrue(page.journeys.all { j -> j.transitLegs.all { it.product in Product.railOnly } })

        println("Station -> city (any station)")
        val cityPage = planner.search(JourneyQuery(leipzig, dresdenCity, time, products = Product.railOnly))
        cityPage.journeys.forEach(::print)
        assertTrue(cityPage.journeys.all { !it.legs.last().isWalk })

        println("Later page")
        planner.search(JourneyQuery(leipzig, dresdenCity, time, products = Product.railOnly, pageCursor = cityPage.laterCursor))
            .journeys.take(2).forEach(::print)

        println("Multi-stop round trip: Halle -> Leipzig (>=90 min) -> Dresden (>=3h) -> back")
        val plans = planner.plan(
            PlanRequest(
                origin = halle, destination = dresdenCity, vias = listOf(Waypoint(leipzig, 90)),
                time = time, roundTrip = true, minStayAtDestinationMin = 180, products = Product.railOnly,
            ), variants = 2
        )
        for (pl in plans) {
            println("PLAN ${pl.title}")
            pl.segments.forEachIndexed { i, s ->
                println(" seg ${s.from.name} -> ${s.to.name} (alts ${s.alternatives.size})"); print(s.journey)
                if (i < pl.segments.size - 1) {
                    println("  stay ${pl.stayMin(i)} min"); assertTrue(pl.stayMin(i) >= (if (i == 0) 90 else 180))
                }
            }
        }
        assertTrue(plans.isNotEmpty() && plans[0].segments.size == 3)

        println("Refresh leg (live)")
        val now = planner.search(JourneyQuery(leipzig, halle, System.currentTimeMillis(), products = Product.railOnly)).journeys.first()
        val leg = now.transitLegs.first()
        val r = p.refreshLeg(leg)
        println("  refreshed: ${r?.line} ${r?.from?.name} dep=${r?.from?.depPlanned?.let(::t)} rt=${r?.from?.depRt?.let(::t)} pl=${r?.from?.platform}")
    }

    @Test
    fun userCaseHalleBerlinRoundTrip() = runBlocking {
        assumeTrue(System.getProperty("online") == "true")
        val p = TransitousProvider()
        for (q in listOf("Halle", "Brandenburg")) {
            println("places for $q:"); p.searchPlaces(q).take(8).forEach { println("   ${it.displayName()} | ${it.region} | city=${it.isCity}") }
        }
        fun city(q: String, name: String) = runBlocking { p.searchPlaces(q).first { it.isCity && it.name == name }.copy(radiusKm = 12.0) }
        val halle = city("Halle", "Halle"); val md = city("Magdeburg", "Magdeburg")
        val brb = city("Brandenburg", "Brandenburg an der Havel"); val ber = city("Berlin", "Berlin")
        var d = LocalDate.now(); while (d.dayOfWeek.value != 7) d = d.plusDays(1)
        val time = d.atTime(5, 27).atZone(z).toInstant().toEpochMilli()
        val prods = Product.regionalOnly
        for (mode in listOf(ReturnMode.EARLIEST, ReturnMode.LATEST)) {
            println("=== return $mode")
            val plans = TripPlanner(p, 12.0).plan(PlanRequest(
                origin = halle, destination = ber, vias = listOf(Waypoint(md, 60), Waypoint(brb, 60)), strictOrder = false,
                time = time, roundTrip = true, minStayAtDestinationMin = 120, products = prods, returnMode = mode), variants = 1)
            val pl = plans.first()
            pl.segments.forEachIndexed { i, s ->
                println(" seg ${s.from.name} -> ${s.to.name}"); print(s.journey)
                assertTrue(s.journey.transitLegs.all { it.product in prods })
                if (i < pl.segments.size - 1) println("  stay ${pl.stayMin(i)} min")
            }
        }
    }
}
