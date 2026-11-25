package de.railnav.data

import de.railnav.model.Journey
import de.railnav.model.Leg
import de.railnav.model.Place
import de.railnav.model.Product
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

data class JourneyQuery(
    val from: Place,
    val to: Place,
    val time: Long,
    val arriveBy: Boolean = false,
    val products: Set<Product>,
    val minTransferMin: Int = 4,
    val maxTransfers: Int? = null,
    val count: Int = 5,
    val pageCursor: String? = null,
)

data class JourneyPage(
    val journeys: List<Journey>,
    val earlierCursor: String? = null,
    val laterCursor: String? = null,
)

class ProviderException(message: String, cause: Throwable? = null) : Exception(message, cause)

interface Provider {
    val id: String
    val title: String
    val supportsRealtime: Boolean

    suspend fun searchPlaces(query: String): List<Place>
    suspend fun journeys(q: JourneyQuery): JourneyPage

    /** Station board: departures (or arrivals) around [time]. */
    suspend fun board(place: Place, time: Long, arrivals: Boolean, products: Set<Product>): List<de.railnav.model.BoardEntry> =
        throw ProviderException("Этот источник не поддерживает табло станции")

    /** The whole run of a train (all stops) or null if unsupported. */
    suspend fun trip(tripId: String): Leg? = null

    /** Returns an updated copy of the leg with live times/platforms or null if unsupported. */
    suspend fun refreshLeg(leg: Leg): Leg? = null

    /** Converts a place coming from another provider into one this provider can route. */
    suspend fun resolve(place: Place): Place {
        if (place.isCity || place.provider == id) return place
        val cands = searchPlaces(place.name)
        return cands.filter { !it.isCity }
            .minByOrNull { de.railnav.model.distanceKm(it.pos, place.pos) }
            ?.takeIf { de.railnav.model.distanceKm(it.pos, place.pos) < 3.0 }
            ?: place.copy(id = null, provider = id) // fall back to coordinates
    }
}

object Net {
    const val USER_AGENT = "RailNav/0.1 (Android; personal route planner)"
    val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build())
        }
        .build()
}
