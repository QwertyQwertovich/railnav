package de.railnav.ui

import android.app.Application
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.railnav.data.FallbackProvider
import de.railnav.data.JourneyQuery
import de.railnav.data.Provider
import de.railnav.data.Providers
import de.railnav.model.Journey
import de.railnav.model.Place
import de.railnav.model.PlanRequest
import de.railnav.model.Product
import de.railnav.model.Segment
import de.railnav.model.StopTime
import de.railnav.model.TripPlan
import de.railnav.model.Waypoint
import de.railnav.notify.TripNotifications
import de.railnav.planner.TripPlanner
import de.railnav.store.FavoritePlaces
import de.railnav.store.FavoriteTime
import de.railnav.store.FavoriteTimes
import de.railnav.model.ReturnMode
import de.railnav.store.RecentPlaces
import de.railnav.store.Settings
import de.railnav.store.Share
import de.railnav.store.TripStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.UUID

sealed class Screen {
    data object Search : Screen()
    data object Results : Screen()
    data object Detail : Screen()
    data object Alternatives : Screen()
    data object Saved : Screen()
    data object Settings : Screen()
    data object Board : Screen()
    data object OfflineData : Screen()
    data object Trip : Screen()
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val settings = Settings(app)
    private val store = TripStore(app)
    private val recentStore = RecentPlaces(app)
    private val favStore = FavoritePlaces(app)
    private val favTimeStore = FavoriteTimes(app)
    var favorites by mutableStateOf(favStore.all())
    var favoriteTimes by mutableStateOf(favTimeStore.all())
    fun toggleFavorite(p: Place) { favStore.toggle(p); favorites = favStore.all() }
    fun isFavorite(p: Place) = favorites.any { it.name == p.name && it.isCity == p.isCity }
    fun addFavoriteTime(t: FavoriteTime) { favTimeStore.add(t); favoriteTimes = favTimeStore.all() }
    fun removeFavoriteTime(t: FavoriteTime) { favTimeStore.remove(t); favoriteTimes = favTimeStore.all() }

    // navigation
    val stack = mutableStateListOf<Screen>(Screen.Search)
    val screen get() = stack.last()
    fun go(s: Screen) { if (stack.last() != s) stack.add(s) }
    fun tab(s: Screen) { stack.clear(); stack.add(s) }
    fun back(): Boolean { if (stack.size <= 1) return false; stack.removeAt(stack.size - 1); return true }

    // search form
    var origin by mutableStateOf<Place?>(null)
    var destination by mutableStateOf<Place?>(null)
    val vias = mutableStateListOf<Waypoint>()
    var time by mutableStateOf(System.currentTimeMillis())
    var arriveBy by mutableStateOf(false)
    var products by mutableStateOf(settings.products)
    var strictOrder by mutableStateOf(true)
    var roundTrip by mutableStateOf(false)
    var returnTime by mutableStateOf<Long?>(null)
    var returnMode by mutableStateOf(ReturnMode.EARLIEST)
    /** Travel mode = round trip without a destination: start → cities → back to start (order may be optimised). */
    var travel by mutableStateOf(false)
    val travelMode get() = travel

    // full train run (from board or a leg)
    var tripLeg by mutableStateOf<de.railnav.model.Leg?>(null)
    var tripHighlight by mutableStateOf<Pair<String, String>?>(null) // boarding/alighting station names
    var tripLoading by mutableStateOf(false)
    var tripError by mutableStateOf<String?>(null)

    fun openTrip(providerId: String, tripId: String, from: String? = null, to: String? = null, fallback: de.railnav.model.Leg? = null) {
        tripLeg = fallback; tripHighlight = if (from != null && to != null) from to to else null
        tripError = null; tripLoading = true
        go(Screen.Trip)
        viewModelScope.launch {
            try {
                val prov = if (tripId.startsWith("off:")) Providers.offline(getApplication()) else Providers.get(getApplication(), providerId)
                val full = prov.trip(tripId)
                if (full != null) tripLeg = full else if (fallback == null) tripError = "Не удалось загрузить трассу поезда"
                else tripError = "Полная трасса недоступна для этого источника — показан ваш участок"
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                tripError = e.message
            } finally { tripLoading = false }
        }
    }

    var boardProducts by mutableStateOf(settings.products)
    fun boardShift(later: Boolean) {
        val list = boardEntries
        val base = boardTime ?: System.currentTimeMillis()
        boardTime = if (later) (list.lastOrNull()?.time(boardArrivals)?.plus(60_000) ?: (base + 3600_000))
        else base - 3600_000
    }

    // station board
    var boardPlace by mutableStateOf<Place?>(null)
    var boardArrivals by mutableStateOf(false)
    var boardTime by mutableStateOf<Long?>(null) // null = now
    var boardEntries by mutableStateOf<List<de.railnav.model.BoardEntry>>(emptyList())
    var boardLoading by mutableStateOf(false)
    var boardError by mutableStateOf<String?>(null)

    fun loadBoard() {
        val p = boardPlace ?: return
        boardLoading = true; boardError = null
        viewModelScope.launch {
            try {
                val prov = provider()
                boardEntries = prov.board(p, boardTime ?: System.currentTimeMillis(), boardArrivals, boardProducts)
                if (boardEntries.isEmpty()) boardError = "Нет поездов в ближайшие часы"
                else if ((prov as? FallbackProvider)?.usedOffline == true) boardError = "Нет сети — показано офлайн-расписание"
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                boardError = e.message; boardEntries = emptyList()
            } finally { boardLoading = false }
        }
    }

    /** From a station board: use this station as start (departures) or destination (arrivals) of a search. */
    fun boardUseInSearch() {
        val p = boardPlace ?: return
        if (boardArrivals) destination = p else origin = p
        boardTime?.let { time = it }
        tab(Screen.Search)
    }
    var stayAtDestMin by mutableStateOf(120)
    var destPriority by mutableStateOf(2)
    var recents by mutableStateOf(recentStore.all())

    // results
    var results by mutableStateOf<List<TripPlan>>(emptyList())
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var info by mutableStateOf<String?>(null)
    private var lastQuery: JourneyQuery? = null
    private var laterCursor: String? = null
    private var earlierCursor: String? = null
    var canPage by mutableStateOf(false)
    var selectedResult by mutableStateOf(0)

    // detail
    var current by mutableStateOf<TripPlan?>(null)
    val saved = mutableStateListOf<TripPlan>().apply { addAll(store.all()) }
    val isCurrentSaved get() = current?.let { c -> saved.any { it.id == c.id } } == true

    // delay alternatives
    var altTitle by mutableStateOf("")
    var altResults by mutableStateOf<List<Journey>>(emptyList())
    private var altContext: Triple<Int, List<de.railnav.model.Leg>, Place>? = null

    // offline import
    var importProgress by mutableStateOf<String?>(null)
    var importing by mutableStateOf(false)
    var offlineInfo by mutableStateOf(Providers.offline(app).info())

    private var job: Job? = null

    // app updates
    var update by mutableStateOf<de.railnav.store.UpdateInfo?>(null)
    var updateDialog by mutableStateOf(false)
    var updateProgress by mutableStateOf<Float?>(null)
    var updating by mutableStateOf(false)
    var updateStatus by mutableStateOf<String?>(null)

    /** Startup check (at most every 12 h) or a manual check from settings. */
    fun checkForUpdate(manual: Boolean = false) {
        if (!manual && (!settings.autoUpdateCheck || System.currentTimeMillis() - settings.lastUpdateCheck < 12 * 3600_000L)) return
        viewModelScope.launch {
            if (manual) updateStatus = "Проверяю…"
            val info = runCatching { de.railnav.store.Updates.check() }.getOrNull()
            settings.lastUpdateCheck = System.currentTimeMillis()
            update = info
            if (info != null) updateDialog = true
            if (manual) updateStatus = if (info == null) "У вас последняя версия (${de.railnav.store.Updates.currentVersion})" else null
        }
    }

    fun installUpdate() {
        val info = update ?: return
        if (updating) return
        updating = true; updateStatus = "Скачиваю ${info.version}…"
        viewModelScope.launch {
            val err = de.railnav.store.Updates.downloadAndInstall(getApplication(), info) { updateProgress = it }
            updating = false; updateProgress = null
            updateStatus = err ?: "Подтвердите установку в системном окне"
        }
    }

    private fun provider(): Provider {
        val p = Providers.get(getApplication(), settings.provider)
        return if (p.id != "offline" && settings.offlineFallback) FallbackProvider(p, Providers.offline(getApplication())) else p
    }

    private fun planner(p: Provider = provider()) = TripPlanner(p, settings.cityRadiusKm.toDouble(), settings.cityMainStations)

    suspend fun searchPlaces(q: String): List<Place> {
        val res = provider().searchPlaces(q)
        val radius = settings.cityRadiusKm.toDouble()
        fun r(p: Place) = cityRadius(p).takeIf { it > 0 } ?: radius
        // Offer "any station of the city" for the best station hit.
        val firstStop = res.firstOrNull { !it.isCity }
        val list = if (res.any { it.isCity }) res.map { if (it.isCity) it.copy(radiusKm = r(it)) else it }
        else (if (firstStop != null) listOf(firstStop.asCity(radius).let { c -> c.copy(radiusKm = r(c)) }) else emptyList()) + res
        // Main stations (Hbf) on top, keeping the provider's order otherwise.
        fun isHbf(p: Place) = !p.isCity && Regex("Hbf|Hauptbahnhof", RegexOption.IGNORE_CASE).containsMatchIn(p.name) &&
            p.region?.endsWith(")") != true
        return list.withIndex().sortedWith(compareBy({ if (isHbf(it.value)) 0 else 1 }, { it.index })).map { it.value }
    }

    /** Auto radius from the city table, else the default from settings. */
    fun cityRadius(p: Place): Double {
        if (!p.isCity) return 0.0
        val auto = if (settings.cityRadiusAuto) de.railnav.store.CityRadius.lookup(getApplication(), p) else null
        return auto ?: settings.cityRadiusKm.toDouble()
    }

    /** Re-apply the current radius rule to (possibly older, saved) city places before searching. */
    private fun fresh(p: Place) = if (p.isCity) p.copy(radiusKm = cityRadius(p)) else p

    fun rememberPlace(p: Place) { recentStore.add(p); recents = recentStore.all() }

    fun swap() { val o = origin; origin = destination; destination = o }

    fun search() {
        origin = origin?.let { fresh(it) }; destination = destination?.let { fresh(it) }
        for (k in vias.indices) vias[k] = vias[k].copy(place = fresh(vias[k].place))
        boardPlace = boardPlace?.let { fresh(it) }
        val o = origin ?: run { error = "Укажите, откуда ехать"; return }
        if (travelMode && vias.isEmpty()) { error = "Добавьте хотя бы один город для поездки"; return }
        // Round trip without a separate destination: the last stop is where you turn back.
        var viaList = vias.toList()
        val d = if (travelMode) o else destination ?: if (roundTrip && viaList.isNotEmpty()) {
            val last = viaList.last(); viaList = viaList.dropLast(1); stayAtDestMin = maxOf(stayAtDestMin, last.minStayMin); last.place
        } else run { error = if (roundTrip) "Укажите, куда ехать, или добавьте промежуточные города" else "Укажите, куда ехать"; return }
        settings.products = products
        error = null; info = null; loading = true; results = emptyList(); selectedResult = 0
        go(Screen.Results)
        job?.cancel()
        job = viewModelScope.launch {
            try {
                val prov = provider()
                val req = PlanRequest(
                    origin = o, destination = d, vias = viaList, strictOrder = strictOrder,
                    time = time, arriveBy = arriveBy && !travelMode, products = products, roundTrip = roundTrip && !travelMode,
                    returnTime = if ((roundTrip || travelMode) && returnMode != ReturnMode.EARLIEST) returnTime else null,
                    returnMode = if (roundTrip || travelMode) returnMode else ReturnMode.EARLIEST,
                    minStayAtDestinationMin = if (roundTrip && !travelMode) stayAtDestMin else 0,
                    destinationPriority = destPriority,
                    circular = travelMode,
                    minTransferMin = settings.minTransferMin,
                )
                if (req.isMulti) {
                    canPage = false
                    results = planner(prov).plan(req, variants = 4)
                } else {
                    val q = JourneyQuery(o, d, time, arriveBy, products, settings.minTransferMin, count = 6)
                    lastQuery = q
                    val page = planner(prov).search(q)
                    laterCursor = page.laterCursor; earlierCursor = page.earlierCursor
                    canPage = true
                    results = page.journeys.map { single(it, o, d, req) }
                }
                if (results.isEmpty()) error = "Маршруты не найдены"
                if ((prov as? FallbackProvider)?.usedOffline == true) info = "Нет сети — показано офлайн-расписание (без реального времени)"
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                error = e.message ?: e.toString()
            } finally { loading = false }
        }
    }

    private fun single(j: Journey, o: Place, d: Place, req: PlanRequest?) =
        TripPlan(UUID.randomUUID().toString(), "${o.name} → ${d.name}", listOf(Segment(o, d, j, emptyList(), j.dep)), req)

    fun page(later: Boolean) {
        val q = lastQuery ?: return
        val cursor = if (later) laterCursor else earlierCursor
        loading = true
        viewModelScope.launch {
            try {
                val page = planner().search(q.copy(pageCursor = cursor,
                    time = if (cursor == null) (if (later) results.lastOrNull()?.dep?.plus(60_000) else results.firstOrNull()?.dep?.minus(3600_000)) ?: q.time else q.time))
                val add = page.journeys.map { single(it, q.from, q.to, results.firstOrNull()?.request) }
                val known = results.map { it.segments[0].journey.signature }.toSet()
                val fresh = add.filter { it.segments[0].journey.signature !in known }
                if (later) { results = results + fresh; laterCursor = page.laterCursor }
                else { results = fresh + results; earlierCursor = page.earlierCursor; selectedResult += fresh.size }
            } catch (e: Exception) {
                error = e.message
            } finally { loading = false }
        }
    }

    fun open(p: TripPlan) { current = p; go(Screen.Detail) }

    fun openSaved(id: String, alternatives: Boolean) {
        val p = store.get(id) ?: return
        current = p
        tab(Screen.Saved); go(Screen.Detail)
        if (alternatives) {
            // find the first endangered transfer and offer alternatives from there
            for ((si, s) in p.segments.withIndex()) {
                val broken = s.journey.brokenTransfers(settings.minTransferMin).firstOrNull() ?: continue
                val leg = s.journey.transitLegs[broken]
                findAlternatives(si, s.journey.legs.indexOf(leg), leg.to.arrDelayMin, cancelled = false)
                return
            }
        }
    }

    fun importLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (!Share.isShareUri(data)) return
        val p = Share.decodeAny(data)
        if (p == null) { error = "Не удалось прочитать ссылку на маршрут"; return }
        current = p.copy(id = UUID.randomUUID().toString(), notify = false)
        enrichShared()
        go(Screen.Detail)
    }

    fun importText(text: String): Boolean {
        val p = Share.decodeText(text) ?: return false
        current = p.copy(id = UUID.randomUUID().toString(), notify = false)
        enrichShared()
        go(Screen.Detail)
        return true
    }

    var enriching by mutableStateOf(false)

    /**
     * A route opened from a link only has the essentials (no shapes, intermediate stops, trip ids).
     * Look the same trains up online and swap in the full journeys where they match.
     */
    fun enrichShared() {
        val p = current ?: return
        if (p.segments.none { it.journey.provider == "shared" }) return
        enriching = true; info = "Загружаю полную информацию о маршруте…"; error = null
        viewModelScope.launch {
            var ok = 0
            try {
                val prov = provider()
                val segs = p.segments.map { s ->
                    val j = s.journey
                    val first = j.transitLegs.firstOrNull() ?: return@map s
                    val last = j.transitLegs.last()
                    val from = Place(name = first.from.name, lat = first.from.lat, lon = first.from.lon)
                    val to = Place(name = last.to.name, lat = last.to.lat, lon = last.to.lon)
                    val q = JourneyQuery(from, to, first.dep - 60_000, false, Product.selectable.toSet(), 0, count = 6)
                    val res = runCatching { planner(prov).search(q).journeys }.getOrNull().orEmpty()
                    val lines = j.transitLegs.map { it.line }
                    fun near(a: Long, b: Long) = kotlin.math.abs(a - b) <= 120_000
                    val match = res.firstOrNull { r -> near(r.transitLegs.firstOrNull()?.from?.depPlanned ?: r.dep, first.dep) && r.transitLegs.map { it.line } == lines }
                        ?: res.firstOrNull { r -> near(r.plannedDep, first.dep) && near(r.plannedArr, last.arr) }
                    if (match != null) { ok++; s.copy(journey = match) } else s
                }
                if (current?.id == p.id) current = p.copy(segments = segs)
                info = when (ok) {
                    p.segments.size -> "Полная информация загружена (реальное время, остановки, трасса)"
                    0 -> "Не удалось подгрузить — показаны данные из ссылки"
                    else -> "Подгружено частично ($ok из ${p.segments.size} участков)"
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                info = "Не удалось подгрузить — показаны данные из ссылки"
            } finally { enriching = false }
        }
    }

    fun saveCurrent(notify: Boolean = current?.notify ?: false) {
        val p = (current ?: return).copy(savedAt = System.currentTimeMillis(), notify = notify)
        store.save(p); current = p
        saved.removeAll { it.id == p.id }; saved.add(p); saved.sortBy { it.dep }
        TripNotifications.schedule(getApplication(), p)
    }

    fun deleteSaved(p: TripPlan) {
        TripNotifications.cancel(getApplication(), p)
        store.delete(p.id); saved.removeAll { it.id == p.id }
        if (current?.id == p.id) current = current?.copy(notify = false)
    }

    fun refreshSaved() { saved.clear(); saved.addAll(store.all()) }

    /** Updates live data for the open plan. */
    fun refreshCurrent() {
        val p = current ?: return
        val prov = Providers.get(getApplication(), p.segments.first().journey.provider)
        if (!prov.supportsRealtime) { info = "Для этого источника нет данных реального времени"; return }
        loading = true
        viewModelScope.launch {
            try {
                val now = System.currentTimeMillis()
                val upd = p.copy(segments = p.segments.map { s ->
                    s.copy(journey = s.journey.copy(legs = s.journey.legs.map { l ->
                        if (l.isWalk || l.arr < now - 600_000) l else runCatching { prov.refreshLeg(l) }.getOrNull() ?: l
                    }))
                })
                current = upd
                if (isCurrentSaved) saveCurrent(upd.notify)
                info = "Обновлено"
            } catch (e: Exception) { error = e.message } finally { loading = false }
        }
    }

    /** Picks another journey for segment [index] and re-chains the following segments. */
    fun chooseSegmentAlternative(index: Int, j: Journey) {
        val p = current ?: return
        loading = true
        viewModelScope.launch {
            try {
                val prov = Providers.get(getApplication(), j.provider)
                current = planner(prov).rechainFrom(p, index, j)
                if (isCurrentSaved) saveCurrent()
            } catch (e: Exception) { error = e.message } finally { loading = false }
        }
    }

    private fun stopPlace(s: StopTime, provider: String) = Place(s.stopId, s.name, s.lat, s.lon, provider = provider)

    /**
     * Train is late (or cancelled): search from the arrival station of that leg (or its departure
     * station if cancelled) to the end of the segment, starting at the delayed time.
     */
    fun findAlternatives(segIndex: Int, legIndex: Int, delayMin: Int, cancelled: Boolean) {
        val p = current ?: return
        val seg = p.segments[segIndex]
        val legs = seg.journey.legs
        val leg = legs[legIndex]
        val (fromStop, t, keep) = if (cancelled) Triple(leg.from, maxOf(leg.dep, System.currentTimeMillis()), legs.subList(0, legIndex))
        else Triple(leg.to, leg.arr + delayMin * 60_000L, legs.subList(0, legIndex + 1).mapIndexed { i, l ->
            if (i == legIndex) l.copy(to = l.to.copy(arrRt = l.arr + delayMin * 60_000L)) else l
        })
        val from = stopPlace(fromStop, seg.journey.provider)
        altContext = Triple(segIndex, keep, from)
        altTitle = if (cancelled) "${leg.line} отменён: из ${fromStop.name}" else "${leg.line} +$delayMin мин: из ${fromStop.name}"
        altResults = emptyList(); error = null; loading = true
        go(Screen.Alternatives)
        viewModelScope.launch {
            try {
                val prov = Providers.get(getApplication(), seg.journey.provider).let {
                    if (it.id != "offline" && settings.offlineFallback) FallbackProvider(it, Providers.offline(getApplication())) else it
                }
                val req = p.request
                val q = JourneyQuery(from, seg.to, t, false, req?.products ?: products, settings.minTransferMin, count = 6)
                altResults = planner(prov).search(q).journeys
                if (altResults.isEmpty()) error = "Альтернатив не найдено"
            } catch (e: Exception) { error = e.message } finally { loading = false }
        }
    }

    fun applyAlternative(j: Journey) {
        val (segIndex, keep, _) = altContext ?: return
        val p = current ?: return
        val merged = Journey(j.id + "m", keep + j.legs, j.provider)
        val prov = Providers.get(getApplication(), j.provider)
        loading = true
        viewModelScope.launch {
            try {
                current = if (p.request != null && segIndex < p.segments.size - 1) planner(prov).rechainFrom(p, segIndex, merged)
                else p.copy(segments = p.segments.toMutableList().also { it[segIndex] = it[segIndex].copy(journey = merged) })
                if (isCurrentSaved) saveCurrent()
                back()
            } catch (e: Exception) { error = e.message } finally { loading = false }
        }
    }

    var importFraction by mutableStateOf<Float?>(null)

    fun startImport() {
        if (importing) return
        importing = true; importFraction = null
        viewModelScope.launch {
            try {
                Providers.offline(getApplication()).importFeeds(settings.feeds) { msg, frac -> importProgress = msg; importFraction = frac }
                refreshOfflineStatus()
            } catch (e: Exception) {
                importProgress = "Ошибка: ${e.message}"
            } catch (e: OutOfMemoryError) {
                importProgress = "Не хватило памяти: выберите меньший набор данных"
            } finally { importing = false }
        }
    }

    var offlineSizeMb by mutableStateOf(Providers.offline(app).sizeMb())
    var offlineImportedAt by mutableStateOf(Providers.offline(app).importedAt())
    var offlineValidUntil by mutableStateOf<java.time.LocalDate?>(null)

    fun refreshOfflineStatus() {
        val off = Providers.offline(getApplication())
        offlineSizeMb = off.sizeMb(); offlineImportedAt = off.importedAt(); offlineInfo = off.info()
        viewModelScope.launch { offlineValidUntil = if (off.hasData()) off.validUntil() else null }
    }

    fun setOfflineAutoUpdate(on: Boolean) {
        settings.offlineAutoUpdate = on
        de.railnav.offline.OfflineUpdateWorker.schedule(getApplication(), on)
    }

    fun deleteOffline() { Providers.offline(getApplication()).deleteData(); offlineInfo = null; importProgress = null }

    fun toggleProduct(p: Product) { products = if (p in products) products - p else products + p }
}
