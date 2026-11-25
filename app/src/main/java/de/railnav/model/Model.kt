package de.railnav.model

import kotlinx.serialization.Serializable
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Transport categories shown as filter chips. */
@Serializable
enum class Product(val label: String, val isRail: Boolean) {
    HIGHSPEED("ICE / TGV / RJ", true),
    LONG_DISTANCE("IC / EC", true),
    NIGHT("Ночные", true),
    REGIONAL("RB", true),
    SUBURBAN("S-Bahn", true),
    SUBWAY("U-Bahn", true),
    TRAM("Трамвай", false),
    BUS("Автобус", false),
    COACH("Межгород. автобус", false),
    FERRY("Паром", false),
    WALK("Пешком", false),
    OTHER("Другое", false),
    // appended last so stored ordinals (offline data) stay valid
    REGIONAL_EXPRESS("RE / IRE / MEX", true);

    companion object {
        val selectable = listOf(HIGHSPEED, LONG_DISTANCE, NIGHT, REGIONAL_EXPRESS, REGIONAL, SUBURBAN, SUBWAY, TRAM, BUS, COACH, FERRY)
        val railOnly = setOf(HIGHSPEED, LONG_DISTANCE, NIGHT, REGIONAL_EXPRESS, REGIONAL, SUBURBAN)
        val regionalOnly = setOf(REGIONAL_EXPRESS, REGIONAL, SUBURBAN)
        private val reLine = Regex("^(RE|IRE|MEX|FEX)", RegexOption.IGNORE_CASE)

        /** Feeds and APIs don't distinguish RE from RB; derive it from the line name. */
        fun refine(p: Product, line: String?): Product =
            if (p == REGIONAL && line != null && reLine.containsMatchIn(line.trim())) REGIONAL_EXPRESS else p
    }
}

@Serializable
data class LatLon(val lat: Double, val lon: Double)

/**
 * A place: either a concrete station (id set, isCity=false) or a whole city
 * ("any station within radiusKm of lat/lon").
 * [provider] tells which data source the id belongs to.
 */
@Serializable
data class Place(
    val id: String? = null,
    val name: String,
    val lat: Double,
    val lon: Double,
    val isCity: Boolean = false,
    val radiusKm: Double = 0.0,
    val provider: String = "",
    val region: String? = null,
) {
    val pos get() = LatLon(lat, lon)
    fun displayName() = if (isCity) "$name (любой вокзал)" else name
    fun asCity(radius: Double, cityName: String = cityNameGuess(name)) =
        copy(id = null, isCity = true, radiusKm = radius, name = cityName)
}

/** Guess the city of a station name: "Leipzig Hbf" -> "Leipzig", "Halle(Saale)Hbf" -> "Halle(Saale)". */
fun cityNameGuess(stationName: String): String {
    val stop = listOf(" Hbf", " Hauptbahnhof", " Bf", " Bahnhof", "Hbf", ",", " - ", "-")
    var s = stationName
    for (t in stop) {
        val i = s.indexOf(t)
        if (i > 1) s = s.substring(0, i)
    }
    return s.trim()
}

@Serializable
data class StopTime(
    val name: String,
    val stopId: String? = null,
    val lat: Double,
    val lon: Double,
    val arrPlanned: Long? = null,   // epoch millis
    val arrRt: Long? = null,
    val depPlanned: Long? = null,
    val depRt: Long? = null,
    val platformPlanned: String? = null,
    val platformRt: String? = null,
    val cancelled: Boolean = false,
) {
    val arr get() = arrRt ?: arrPlanned
    val dep get() = depRt ?: depPlanned
    val platform get() = platformRt ?: platformPlanned
    val platformChanged get() = platformRt != null && platformPlanned != null && platformRt != platformPlanned
    val depDelayMin get() = if (depRt != null && depPlanned != null) ((depRt - depPlanned) / 60000).toInt() else 0
    val arrDelayMin get() = if (arrRt != null && arrPlanned != null) ((arrRt - arrPlanned) / 60000).toInt() else 0
    val pos get() = LatLon(lat, lon)
}

@Serializable
data class Leg(
    val product: Product,
    val line: String,
    val direction: String? = null,
    val tripId: String? = null,
    val from: StopTime,
    val to: StopTime,
    val stops: List<StopTime> = emptyList(), // intermediate stops
    val shape: List<LatLon> = emptyList(),
    val walkMeters: Int? = null,
    val notes: List<String> = emptyList(),
    val color: String? = null,
    val operator: String? = null,
    val realtime: Boolean = false,
) {
    val isWalk get() = product == Product.WALK
    val dep get() = from.dep ?: 0L
    val arr get() = to.arr ?: 0L
    /** Track shape clipped to the first/last stop (feeds sometimes ship the whole block), or straight lines between stops. */
    fun geometry(): List<LatLon> {
        if (shape.size < 2) return listOf(from.pos) + stops.map { it.pos } + listOf(to.pos)
        fun d(a: LatLon, b: LatLon) = (a.lat - b.lat) * (a.lat - b.lat) + (a.lon - b.lon) * (a.lon - b.lon)
        val i0 = shape.indices.minByOrNull { d(shape[it], from.pos) } ?: 0
        val i1 = (i0 until shape.size).minByOrNull { d(shape[it], to.pos) } ?: shape.lastIndex
        if (i1 - i0 < 1) return listOf(from.pos) + stops.map { it.pos } + listOf(to.pos)
        return listOf(from.pos) + shape.subList(i0, i1 + 1) + listOf(to.pos)
    }
}

@Serializable
data class Journey(
    val id: String,
    val legs: List<Leg>,
    val provider: String = "",
) {
    val transitLegs get() = legs.filter { !it.isWalk }
    val dep get() = legs.first().dep
    val arr get() = legs.last().arr
    val plannedDep get() = legs.first().from.depPlanned ?: dep
    val plannedArr get() = legs.last().to.arrPlanned ?: arr
    val durationMin get() = ((arr - dep) / 60000).toInt()
    val transfers get() = (transitLegs.size - 1).coerceAtLeast(0)
    val first get() = legs.first().from
    val last get() = legs.last().to
    val signature get() = transitLegs.joinToString("|") { "${it.line}@${it.dep}" }

    /** All vehicles are of an allowed type (walking and unknown types always pass). */
    fun matches(products: Set<Product>) = transitLegs.all { it.product == Product.OTHER || it.product in products }

    /** Transfers whose real-time connection time is below [minMin] minutes. */
    fun brokenTransfers(minMin: Int): List<Int> {
        val t = transitLegs
        val out = mutableListOf<Int>()
        for (i in 0 until t.size - 1) {
            val slack = (t[i + 1].dep - t[i].arr) / 60000
            if (slack < minMin || t[i + 1].from.cancelled || t[i].to.cancelled) out += i
        }
        return out
    }
}

/** One row of a station departure/arrival board. */
@Serializable
data class BoardEntry(
    val product: Product,
    val line: String,
    val direction: String,
    val stop: StopTime,
    val tripId: String? = null,
    val operator: String? = null,
) {
    fun time(arrivals: Boolean) = if (arrivals) stop.arrPlanned ?: stop.depPlanned else stop.depPlanned ?: stop.arrPlanned
    fun live(arrivals: Boolean) = if (arrivals) stop.arrRt ?: stop.depRt else stop.depRt ?: stop.arrRt
}

/** A waypoint of a multi-stop trip with desired stay. */
@Serializable
data class Waypoint(
    val place: Place,
    val minStayMin: Int = 0,
    val maxStayMin: Int? = null,
    /** Share of spare time this stop gets: 1 = low, 2 = normal, 3 = high. */
    val priority: Int = 2,
) {
    val weight get() = when (priority) { 1 -> 1.0; 3 -> 4.0; else -> 2.0 }
}

@Serializable
enum class ReturnMode { EARLIEST, NOT_BEFORE, LATEST }

@Serializable
data class PlanRequest(
    val origin: Place,
    val destination: Place,
    val vias: List<Waypoint> = emptyList(),
    val strictOrder: Boolean = true,
    val time: Long,
    val arriveBy: Boolean = false,
    val products: Set<Product> = Product.railOnly,
    val roundTrip: Boolean = false,
    val returnTime: Long? = null,
    /** EARLIEST: first train after the stay; NOT_BEFORE: not before returnTime; LATEST: last train arriving by returnTime (default end of day). */
    val returnMode: ReturnMode = ReturnMode.EARLIEST,
    /** Travel mode: origin → vias → back to origin (destination == origin); the last segment is the way home. */
    val circular: Boolean = false,
    val minStayAtDestinationMin: Int = 0,
    val destinationPriority: Int = 2,
    val minTransferMin: Int = 4,
    val maxTransfers: Int? = null,
) {
    val isMulti get() = vias.isNotEmpty() || roundTrip
}

/** One leg of a multi-stop trip: from one waypoint to the next. */
@Serializable
data class Segment(
    val from: Place,
    val to: Place,
    val journey: Journey,
    val alternatives: List<Journey> = emptyList(),
    val requestedDeparture: Long,
    val maxStayExceeded: Boolean = false,
)

@Serializable
data class TripPlan(
    val id: String,
    val title: String,
    val segments: List<Segment>,
    val request: PlanRequest? = null,
    val savedAt: Long = 0,
    val notify: Boolean = false,
) {
    val dep get() = segments.first().journey.dep
    val arr get() = segments.last().journey.arr
    val allJourneys get() = segments.map { it.journey }
    /** Minutes spent in the city between segment i and i+1. */
    fun stayMin(i: Int): Int = ((segments[i + 1].journey.dep - segments[i].journey.arr) / 60000).toInt()
    val transfers get() = segments.sumOf { it.journey.transfers }
}

fun distanceKm(a: LatLon, b: LatLon): Double {
    val r = 6371.0
    val dLat = Math.toRadians(b.lat - a.lat)
    val dLon = Math.toRadians(b.lon - a.lon)
    val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).pow(2)
    return 2 * r * asin(sqrt(h))
}
