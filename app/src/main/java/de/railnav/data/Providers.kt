package de.railnav.data

import android.content.Context
import de.railnav.offline.OfflineProvider

object Providers {
    private var transitous: TransitousProvider? = null
    private var db: DbProvider? = null
    private var offline: OfflineProvider? = null

    @Synchronized
    fun offline(ctx: Context): OfflineProvider =
        offline ?: OfflineProvider(ctx.applicationContext).also { offline = it }

    @Synchronized
    fun get(ctx: Context, id: String): Provider = when (id) {
        "db" -> db ?: DbProvider().also { db = it }
        "offline" -> offline(ctx)
        else -> transitous ?: TransitousProvider().also { transitous = it }
    }

    val choices = listOf(
        "transitous" to "Transitous (MOTIS) — вся Европа, открытые данные",
        "db" to "Deutsche Bahn (bahn.de) — неофициальный API, может блокироваться",
        "offline" to "Только офлайн (скачанное расписание GTFS)",
    )
}

/** Tries the online provider and falls back to offline data when the network is unavailable. */
class FallbackProvider(private val primary: Provider, private val offline: OfflineProvider) : Provider {
    override val id get() = primary.id
    override val title get() = primary.title
    override val supportsRealtime get() = primary.supportsRealtime
    var usedOffline = false; private set

    override suspend fun searchPlaces(query: String): List<Place> = try {
        primary.searchPlaces(query).also { usedOffline = false }
    } catch (e: ProviderException) {
        if (!offline.hasData()) throw e
        usedOffline = true; offline.searchPlaces(query)
    }

    override suspend fun journeys(q: JourneyQuery): JourneyPage = try {
        primary.journeys(q).also { usedOffline = false }
    } catch (e: ProviderException) {
        if (!offline.hasData()) throw e
        usedOffline = true
        offline.journeys(q.copy(pageCursor = null))
    }

    override suspend fun refreshLeg(leg: de.railnav.model.Leg) = primary.refreshLeg(leg)

    override suspend fun board(place: Place, time: Long, arrivals: Boolean, products: Set<de.railnav.model.Product>) = try {
        primary.board(place, time, arrivals, products).also { usedOffline = false }
    } catch (e: ProviderException) {
        if (!offline.hasData()) throw e
        usedOffline = true; offline.board(place, time, arrivals, products)
    }
    override suspend fun resolve(place: Place): Place = try { primary.resolve(place) } catch (e: ProviderException) { place }
}

private typealias Place = de.railnav.model.Place
