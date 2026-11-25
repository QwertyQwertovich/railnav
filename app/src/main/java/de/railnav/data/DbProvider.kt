package de.railnav.data

import de.railnav.model.Journey
import de.railnav.model.Leg
import de.railnav.model.Place
import de.railnav.model.Product
import de.railnav.model.StopTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Deutsche Bahn via the (unofficial, undocumented) bahn.de web API — the same backend
 * the website uses. Exact DB real-time data and platforms, but DB may block or change it at any time.
 */
class DbProvider : Provider {
    override val id = "db"
    override val title = "Deutsche Bahn (bahn.de, неофициально)"
    override val supportsRealtime = true

    private val base = "https://www.bahn.de/web/api"
    private val berlin = ZoneId.of("Europe/Berlin")
    private val localFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

    private suspend fun call(req: Request): JsonElement = withContext(Dispatchers.IO) {
        try {
            Net.client.newCall(req).execute().use { r ->
                val body = r.body?.string().orEmpty()
                if (body.contains("OPS_BLOCKED")) throw ProviderException(
                    "bahn.de временно заблокировал запросы (защита от ботов). Попробуйте позже или переключитесь на Transitous в настройках."
                )
                if (!r.isSuccessful) throw ProviderException("bahn.de: HTTP ${r.code} ${body.take(200)}")
                Net.json.parseToJsonElement(body)
            }
        } catch (e: java.io.IOException) {
            throw ProviderException("Нет связи с bahn.de: ${e.message}", e)
        }
    }

    override suspend fun searchPlaces(query: String): List<Place> {
        val url = "$base/reiseloesung/orte".toHttpUrl().newBuilder()
            .addQueryParameter("suchbegriff", query)
            .addQueryParameter("typ", "ALL")
            .addQueryParameter("limit", "10").build()
        val arr = call(Request.Builder().url(url).header("Accept", "application/json").build()).jsonArray
        return arr.mapNotNull { e ->
            val o = e.jsonObject
            if (o.str("type") != "ST") return@mapNotNull null
            Place(
                id = o.str("id"), name = o.str("name") ?: return@mapNotNull null,
                lat = o.dbl("lat") ?: 0.0, lon = o.dbl("lon") ?: 0.0, provider = id
            )
        }
    }

    private fun locId(p: Place): String =
        if (!p.isCity && p.id != null && p.provider == id) (if (p.id.contains('@')) p.id else "A=1@L=${p.id}@")
        else "A=2@O=${p.name}@X=${(p.lon * 1e6).toLong()}@Y=${(p.lat * 1e6).toLong()}@"

    private fun gattungen(ps: Set<Product>): List<String> = ps.flatMap {
        when (it) {
            Product.HIGHSPEED -> listOf("ICE")
            Product.LONG_DISTANCE, Product.NIGHT -> listOf("EC_IC", "IR")
            Product.REGIONAL, Product.REGIONAL_EXPRESS -> listOf("REGIONAL")
            Product.SUBURBAN -> listOf("SBAHN")
            Product.SUBWAY -> listOf("UBAHN")
            Product.TRAM -> listOf("TRAM")
            Product.BUS, Product.COACH -> listOf("BUS", "ANRUFPFLICHTIG")
            Product.FERRY -> listOf("SCHIFF")
            else -> emptyList()
        }
    }.distinct()

    override suspend fun journeys(q: JourneyQuery): JourneyPage {
        val local = LocalDateTime.ofInstant(Instant.ofEpochMilli(q.time), berlin).withSecond(0)
        val body = buildJsonObject {
            put("abfahrtsHalt", locId(q.from))
            put("ankunftsHalt", locId(q.to))
            put("anfrageZeitpunkt", local.format(localFmt))
            put("ankunftSuche", if (q.arriveBy) "ANKUNFT" else "ABFAHRT")
            put("klasse", "KLASSE_2")
            putJsonArray("produktgattungen") { gattungen(q.products).forEach { add(it) } }
            putJsonArray("reisende") {
                addJsonObject {
                    put("typ", "ERWACHSENER")
                    putJsonArray("ermaessigungen") {
                        addJsonObject { put("art", "KEINE_ERMAESSIGUNG"); put("klasse", "KLASSENLOS") }
                    }
                    putJsonArray("alter") {}
                    put("anzahl", 1)
                }
            }
            put("schnelleVerbindungen", true)
            put("sitzplatzOnly", false)
            put("bikeCarriage", false)
            put("reservierungsKontingenteVorhanden", false)
            put("nurDeutschlandTicketVerbindungen", false)
            put("deutschlandTicketVorhanden", false)
            if (q.minTransferMin > 0) put("minUmstiegszeit", q.minTransferMin)
            q.maxTransfers?.let { put("maxUmstiege", it) }
            q.pageCursor?.let { put("pagingReferenz", it) }
        }
        val req = Request.Builder().url("$base/angebote/fahrplan")
            .header("Accept", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val root = call(req).jsonObject
        val list = root["verbindungen"]?.jsonArray.orEmpty().mapNotNull { parseVerbindung(it.jsonObject, q) }
        val ref = root["verbindungReference"] as? JsonObject
        return JourneyPage(list, ref?.str("earlier"), ref?.str("later"))
    }

    override suspend fun board(place: Place, time: Long, arrivals: Boolean, products: Set<Product>): List<de.railnav.model.BoardEntry> {
        if (place.provider != id || place.id == null) throw ProviderException("Для табло DB выберите станцию из поиска DB")
        val extId = Regex("L=([0-9]+)").find(place.id)?.groupValues?.get(1) ?: place.id
        val local = LocalDateTime.ofInstant(Instant.ofEpochMilli(time), berlin)
        val url = "$base/reiseloesung/${if (arrivals) "ankuenfte" else "abfahrten"}".toHttpUrl().newBuilder()
            .addQueryParameter("datum", local.toLocalDate().toString())
            .addQueryParameter("zeit", local.toLocalTime().withNano(0).toString())
            .addQueryParameter("ortExtId", extId)
            .addQueryParameter("ortId", place.id)
            .addQueryParameter("mitVias", "true").addQueryParameter("maxVias", "5")
            .apply { gattungen(products).forEach { addQueryParameter("verkehrsmittel[]", it) } }
            .build()
        val root = call(Request.Builder().url(url).header("Accept", "application/json").build()).jsonObject
        return root["entries"]?.jsonArray.orEmpty().mapNotNull { e ->
            val o = e.jsonObject
            val vm = o["verkehrmittel"] as? JsonObject ?: o["verkehrsmittel"] as? JsonObject
            val line = vm?.str("mittelText") ?: vm?.str("name") ?: return@mapNotNull null
            val planned = parseTime(o.str("zeit")); val live = parseTime(o.str("ezZeit"))
            val stop = StopTime(
                place.name, extId, place.lat, place.lon,
                arrPlanned = if (arrivals) planned else null, arrRt = if (arrivals) live else null,
                depPlanned = if (arrivals) null else planned, depRt = if (arrivals) null else live,
                platformPlanned = o.str("gleis"), platformRt = o.str("ezGleis"),
                cancelled = o["meldungen"]?.jsonArray.orEmpty().any { (it as? JsonObject)?.str("type") == "HALT_AUSFALL" },
            )
            de.railnav.model.BoardEntry(
                product = when (vm?.str("produktGattung")) {
                    "ICE" -> Product.HIGHSPEED; "EC_IC", "IR" -> Product.LONG_DISTANCE
                    "REGIONAL" -> Product.refine(Product.REGIONAL, line); "SBAHN" -> Product.SUBURBAN
                    "UBAHN" -> Product.SUBWAY; "TRAM" -> Product.TRAM; "BUS", "ANRUFPFLICHTIG" -> Product.BUS
                    "SCHIFF" -> Product.FERRY; else -> Product.OTHER
                },
                line = line, direction = o.str("terminus") ?: o.str("abgangsOrt") ?: "", stop = stop, tripId = o.str("journeyId"),
            )
        }
    }

    private fun parseTime(s: String?): Long? = s?.let {
        runCatching { LocalDateTime.parse(it.take(19), localFmt).atZone(berlin).toInstant().toEpochMilli() }.getOrNull()
    }

    /** Time objects look like {"sollzeit": "...", "<something>zeit": "..."} or old-style flat fields. */
    private fun times(o: JsonObject, key: String, flatPlanned: String, flatRt: String): Pair<Long?, Long?> {
        val t = o[key] as? JsonObject
        if (t != null) {
            val planned = parseTime(t.str("sollzeit") ?: t.str("zeit"))
            val rtKey = t.keys.firstOrNull { it != "sollzeit" && it.lowercase().endsWith("zeit") }
            return planned to parseTime(rtKey?.let { t.str(it) })
        }
        return parseTime(o.str(flatPlanned)) to parseTime(o.str(flatRt))
    }

    private fun coordsFromId(id: String?): Pair<Double, Double>? {
        id ?: return null
        val x = Regex("X=(-?\\d+)").find(id)?.groupValues?.get(1)?.toDoubleOrNull() ?: return null
        val y = Regex("Y=(-?\\d+)").find(id)?.groupValues?.get(1)?.toDoubleOrNull() ?: return null
        return y / 1e6 to x / 1e6
    }

    private fun parseHalt(h: JsonObject): StopTime {
        val (aP, aR) = times(h, "ankunft", "ankunftsZeitpunkt", "ezAnkunftsZeitpunkt")
        val (dP, dR) = times(h, "abfahrt", "abfahrtsZeitpunkt", "ezAbfahrtsZeitpunkt")
        val c = coordsFromId(h.str("id"))
        return StopTime(
            name = h.str("name") ?: "?", stopId = h.str("extId"),
            lat = c?.first ?: 0.0, lon = c?.second ?: 0.0,
            arrPlanned = aP, arrRt = aR, depPlanned = dP, depRt = dR,
            platformPlanned = h.str("gleis"),
            platformRt = h.str("ezGleis") ?: h.str("prognoseGleis"),
            cancelled = h.bool("cancelled") == true,
        )
    }

    private fun parseVerbindung(v: JsonObject, q: JourneyQuery): Journey? {
        val legs = mutableListOf<Leg>()
        for (ae in v["verbindungsAbschnitte"]?.jsonArray.orEmpty()) {
            val a = ae.jsonObject
            val vm = a["verkehrsmittel"] as? JsonObject
            val typ = vm?.str("typ")
            val halte = a["halte"]?.jsonArray.orEmpty().map { parseHalt(it.jsonObject) }
            val (dP, dR) = times(a, "abfahrt", "abfahrtsZeitpunkt", "ezAbfahrtsZeitpunkt")
            val (aP, aR) = times(a, "ankunft", "ankunftsZeitpunkt", "ezAnkunftsZeitpunkt")
            val walk = typ != null && typ != "PUBLICTRANSPORT"
            val from = (halte.firstOrNull() ?: StopTime(a.str("abfahrtsOrt") ?: "?", a.str("abfahrtsOrtExtId"), 0.0, 0.0))
                .let { it.copy(depPlanned = it.depPlanned ?: dP, depRt = it.depRt ?: dR) }
            val to = (halte.lastOrNull()?.takeIf { halte.size > 1 } ?: StopTime(a.str("ankunftsOrt") ?: "?", a.str("ankunftsOrtExtId"), 0.0, 0.0))
                .let { it.copy(arrPlanned = it.arrPlanned ?: aP, arrRt = it.arrRt ?: aR) }
            val line = vm?.str("mittelText") ?: vm?.str("name") ?: "?"
            val product = if (walk) Product.WALK else when (vm?.str("produktGattung")) {
                "ICE" -> Product.HIGHSPEED
                "EC_IC", "IR" -> if ((vm.str("kategorie") ?: "") in setOf("NJ", "EN")) Product.NIGHT else Product.LONG_DISTANCE
                "REGIONAL" -> Product.refine(Product.REGIONAL, line)
                "SBAHN" -> Product.SUBURBAN
                "UBAHN" -> Product.SUBWAY
                "TRAM" -> Product.TRAM
                "BUS", "ANRUFPFLICHTIG" -> Product.BUS
                "SCHIFF" -> Product.FERRY
                else -> TransitousProvider.guessFromLine(line) ?: Product.OTHER
            }
            val notes = (a["himMeldungen"]?.jsonArray.orEmpty() + a["risNotizen"]?.jsonArray.orEmpty())
                .mapNotNull { (it as? JsonObject)?.str("text") }
            legs += Leg(
                product = product,
                line = if (walk) "Пешком" else line,
                direction = vm?.str("richtung"),
                tripId = a.str("journeyId"),
                from = from, to = to,
                stops = if (halte.size > 2) halte.subList(1, halte.size - 1) else emptyList(),
                walkMeters = a.int("distanz"),
                notes = notes.distinct(),
                operator = vm?.get("zugattribute")?.jsonArray?.firstNotNullOfOrNull {
                    (it as? JsonObject)?.takeIf { z -> z.str("key") == "BEF" }?.str("value")
                },
                realtime = dR != null || aR != null,
            )
        }
        if (legs.isEmpty()) return null
        // Fill missing coordinates (walk legs) from neighbours / query places.
        for (i in legs.indices) {
            val l = legs[i]
            var f = l.from; var t = l.to
            if (f.lat == 0.0) {
                val src = legs.getOrNull(i - 1)?.to ?: StopTime("", null, q.from.lat, q.from.lon)
                f = f.copy(lat = src.lat, lon = src.lon)
            }
            if (t.lat == 0.0) {
                val src = legs.getOrNull(i + 1)?.from?.takeIf { it.lat != 0.0 } ?: StopTime("", null, q.to.lat, q.to.lon)
                t = t.copy(lat = src.lat, lon = src.lon)
            }
            legs[i] = l.copy(from = f, to = t)
        }
        val key = (v.str("tripId") ?: legs.joinToString { it.line + it.dep }).hashCode().toUInt().toString(16)
        return Journey(id = "db:$key${legs.first().dep}", legs = legs, provider = id)
    }
}

@Suppress("unused")
private fun JsonObject.has(k: String) = this[k] is JsonPrimitive
