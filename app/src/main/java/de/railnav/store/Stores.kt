package de.railnav.store

import android.content.Context
import android.util.Base64
import android.net.Uri
import de.railnav.model.Leg
import de.railnav.model.StopTime
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.util.UUID
import java.util.zip.Deflater
import java.util.zip.Inflater
import de.railnav.data.Net
import de.railnav.model.Journey
import de.railnav.model.Place
import de.railnav.model.Product
import de.railnav.model.TripPlan
import de.railnav.model.Segment
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Saved trips as one JSON file per trip. */
class TripStore(ctx: Context) {
    private val dir = File(ctx.filesDir, "trips").apply { mkdirs() }

    fun all(): List<TripPlan> = dir.listFiles { f -> f.extension == "json" }.orEmpty()
        .mapNotNull { f -> runCatching { Net.json.decodeFromString<TripPlan>(f.readText()) }.getOrNull() }
        .sortedBy { it.dep }

    fun get(id: String): TripPlan? = File(dir, "$id.json").takeIf { it.exists() }
        ?.let { runCatching { Net.json.decodeFromString<TripPlan>(it.readText()) }.getOrNull() }

    fun save(p: TripPlan) {
        val tmp = File(dir, "${p.id}.tmp")
        tmp.writeText(Net.json.encodeToString(p))
        tmp.renameTo(File(dir, "${p.id}.json").also { it.delete() })
    }

    fun delete(id: String) { File(dir, "$id.json").delete() }
}

/** Recently used places for quick selection. */
class RecentPlaces(ctx: Context) {
    private val f = File(ctx.filesDir, "recent_places.json")
    fun all(): List<Place> = runCatching { Net.json.decodeFromString(ListSerializer(Place.serializer()), f.readText()) }.getOrDefault(emptyList())
    fun add(p: Place) {
        val list = (listOf(p) + all().filter { it.name != p.name || it.isCity != p.isCity }).take(12)
        f.writeText(Net.json.encodeToString(ListSerializer(Place.serializer()), list))
    }
}

/** Favourite places (starred in the place picker). */
class FavoritePlaces(ctx: Context) {
    private val f = File(ctx.filesDir, "favorite_places.json")
    fun all(): List<Place> = runCatching { Net.json.decodeFromString(ListSerializer(Place.serializer()), f.readText()) }.getOrDefault(emptyList())
    private fun key(p: Place) = "${p.name}|${p.isCity}"
    fun contains(p: Place) = all().any { key(it) == key(p) }
    fun toggle(p: Place) {
        val cur = all()
        val list = if (cur.any { key(it) == key(p) }) cur.filter { key(it) != key(p) } else cur + p
        f.writeText(Net.json.encodeToString(ListSerializer(Place.serializer()), list))
    }
}

/**
 * Favourite departure time, stored as a rule: "next Sunday 05:00", "tomorrow 07:30", "today 18:00".
 * [weekday] 1..7 (Mon..Sun) for the nearest such day, 0 = today, -1 = tomorrow.
 */
@kotlinx.serialization.Serializable
data class FavoriteTime(val weekday: Int, val hour: Int, val minute: Int) {
    fun label(): String {
        val hm = "%02d:%02d".format(hour, minute)
        return when (weekday) {
            0 -> "Сегодня $hm"
            -1 -> "Завтра $hm"
            else -> "${WEEKDAYS[weekday - 1]} $hm"
        }
    }

    /** The next moment matching the rule (today's time if it is still ahead). */
    fun resolve(now: java.time.ZonedDateTime = java.time.ZonedDateTime.now()): Long {
        var d = now.toLocalDate()
        when (weekday) {
            -1 -> d = d.plusDays(1)
            0 -> {}
            else -> {
                while (d.dayOfWeek.value != weekday) d = d.plusDays(1)
                if (d == now.toLocalDate() && java.time.LocalTime.of(hour, minute).isBefore(now.toLocalTime())) d = d.plusDays(7)
            }
        }
        return d.atTime(hour, minute).atZone(now.zone).toInstant().toEpochMilli()
    }

    companion object {
        val WEEKDAYS = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")
        val WEEKDAYS_LONG = listOf("понедельник", "вторник", "среду", "четверг", "пятницу", "субботу", "воскресенье")
    }
}

class FavoriteTimes(ctx: Context) {
    private val f = File(ctx.filesDir, "favorite_times.json")
    private val ser = ListSerializer(FavoriteTime.serializer())
    fun all(): List<FavoriteTime> = runCatching { Net.json.decodeFromString(ser, f.readText()) }.getOrDefault(emptyList())
    fun add(t: FavoriteTime) { f.writeText(Net.json.encodeToString(ser, (all().filter { it != t } + t))) }
    fun remove(t: FavoriteTime) { f.writeText(Net.json.encodeToString(ser, all().filter { it != t })) }
}

class Settings(ctx: Context) {
    private val sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var provider: String
        get() = sp.getString("provider", "transitous")!!
        set(v) = sp.edit().putString("provider", v).apply()

    /** When online search fails, retry with offline data. */
    var offlineFallback: Boolean
        get() = sp.getBoolean("offlineFallback", true)
        set(v) = sp.edit().putBoolean("offlineFallback", v).apply()

    var products: Set<Product>
        get() = sp.getStringSet("products", null)?.mapNotNull { runCatching { Product.valueOf(it) }.getOrNull() }?.toSet()
            ?.let { if (!sp.getBoolean("productsV2", false) && Product.REGIONAL in it) it + Product.REGIONAL_EXPRESS else it }
            ?: Product.railOnly
        set(v) = sp.edit().putStringSet("products", v.map { it.name }.toSet()).putBoolean("productsV2", true).apply()

    var minTransferMin: Int
        get() = sp.getInt("minTransfer", 5)
        set(v) = sp.edit().putInt("minTransfer", v).apply()

    var cityRadiusKm: Int
        get() = sp.getInt("cityRadius", 12)
        set(v) = sp.edit().putInt("cityRadius", v).apply()

    var notifyBeforeDepMin: Int
        get() = sp.getInt("notifyDep", 10)
        set(v) = sp.edit().putInt("notifyDep", v).apply()

    var notifyBeforeArrMin: Int
        get() = sp.getInt("notifyArr", 5)
        set(v) = sp.edit().putInt("notifyArr", v).apply()

    var offlineAutoUpdate: Boolean
        get() = sp.getBoolean("offlineAuto", false)
        set(v) = sp.edit().putBoolean("offlineAuto", v).apply()

    var feeds: List<String>
        get() = sp.getString("feeds", null)?.split('\n')?.filter { it.isNotBlank() }
            ?: de.railnav.offline.OfflineProvider.DEFAULT_FEEDS
        set(v) = sp.edit().putString("feeds", v.joinToString("\n")).apply()
}

object Share {
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
    private val dateFmt = DateTimeFormatter.ofPattern("EEE dd.MM.yyyy")
    private fun t(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(timeFmt)

    fun journeyText(j: Journey): String = buildString {
        for (l in j.legs) {
            if (l.isWalk) { appendLine("  🚶 ${l.walkMeters?.let { "$it м" } ?: ""} → ${l.to.name}"); continue }
            append("${t(l.dep)} ${l.from.name}")
            l.from.platform?.let { append(" (пл. $it)") }
            appendLine()
            appendLine("   ${l.line}${l.direction?.let { " → $it" } ?: ""}")
            append("${t(l.arr)} ${l.to.name}")
            l.to.platform?.let { append(" (пл. $it)") }
            appendLine()
        }
    }

    fun planText(p: TripPlan): String = buildString {
        appendLine("🚆 ${p.title}")
        appendLine(Instant.ofEpochMilli(p.dep).atZone(ZoneId.systemDefault()).format(dateFmt))
        p.segments.forEachIndexed { i, s ->
            if (p.segments.size > 1) appendLine("\n— ${s.from.name} → ${s.to.name}")
            append(journeyText(s.journey))
            if (i < p.segments.size - 1) {
                val m = p.stayMin(i)
                appendLine("⏱ В ${s.to.name}: ${m / 60} ч ${m % 60} мин")
            }
        }
    }

    /** Public page that shows a shared route and offers the APK / "open in app". */
    const val SHARE_BASE = "https://qwertyqwertovich.github.io/railnav/"
    private const val B64 = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING

    enum class Style(val label: String) { LINK("Только ссылка"), SHORT("Кратко"), FULL("Подробно") }

    fun shortText(p: TripPlan): String = buildString {
        val d = Instant.ofEpochMilli(p.dep).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("EE dd.MM.", java.util.Locale("ru")))
        appendLine("🚆 ${p.title} · $d")
        p.segments.forEachIndexed { i, s ->
            val j = s.journey
            val lines = j.transitLegs.joinToString(", ") { it.line }
            val dur = j.durationMin.let { if (it >= 60) "${it / 60} ч ${it % 60} мин" else "$it мин" }
            if (p.segments.size > 1) append("${s.from.name} → ${s.to.name}: ")
            appendLine("${t(j.dep)} → ${t(j.arr)} · $dur · $lines")
            if (i < p.segments.size - 1) { val m = p.stayMin(i); appendLine("   ⏱ ${m / 60} ч ${m % 60} мин в ${s.to.name}") }
        }
    }.trimEnd()

    fun text(p: TripPlan, style: Style, withLink: Boolean): String = when (style) {
        Style.LINK -> webLink(p)
        Style.SHORT -> shortText(p) + if (withLink) "\n" + webLink(p) else ""
        Style.FULL -> planText(p).trimEnd() + if (withLink) "\n\n" + webLink(p) else ""
    }

    /**
     * Compact encoding (no server needed): station table + legs with minute offsets,
     * raw-deflated and base64url'd. A typical trip gives ~150–300 characters.
     */
    fun encode(p: TripPlan): String {
        val names = ArrayList<String>(); val coords = ArrayList<Int>()
        fun st(s: StopTime): Int {
            val k = names.indexOf(s.name)
            if (k >= 0) return k
            names += s.name; coords += (s.lat * 1e5).toInt(); coords += (s.lon * 1e5).toInt()
            return names.size - 1
        }
        val base = p.dep / 60000
        fun off(ms: Long?) = ms?.let { (it / 60000 - base).toInt() } ?: 0
        val root = buildJsonObject {
            put("v", 1)
            put("t", p.title)
            put("b", base)
            putJsonArray("s") {
                for (sg in p.segments) addJsonArray {
                    add(sg.from.name); add(sg.to.name)
                    for (l in sg.journey.legs) addJsonArray {
                        add(l.product.ordinal); add(if (l.isWalk) "" else l.line); add(l.direction ?: "")
                        add(st(l.from)); add(st(l.to))
                        add(off(l.from.depPlanned ?: l.dep)); add(off(l.to.arrPlanned ?: l.arr))
                        add(l.from.platform ?: ""); add(l.to.platform ?: "")
                    }
                }
            }
            putJsonArray("n") { names.forEach { add(it) } }
            putJsonArray("c") { coords.forEach { add(it) } }
        }
        val raw = root.toString().toByteArray()
        val def = Deflater(9, true); def.setInput(raw); def.finish()
        val buf = ByteArray(raw.size + 64); val n = def.deflate(buf); def.end()
        return Base64.encodeToString(buf.copyOf(n), B64)
    }

    fun decode(data: String): TripPlan? = runCatching {
        val bytes = Base64.decode(data, B64)
        val inf = Inflater(true); inf.setInput(bytes)
        val out = ByteArrayOutputStream(); val buf = ByteArray(4096)
        while (!inf.finished()) {
            val k = inf.inflate(buf)
            if (k == 0 && (inf.needsInput() || inf.needsDictionary())) break
            out.write(buf, 0, k)
        }
        inf.end()
        val o = Net.json.parseToJsonElement(out.toString("UTF-8")).jsonObject
        val base = o["b"]!!.jsonPrimitive.long * 60000
        val names = o["n"]!!.jsonArray.map { it.jsonPrimitive.content }
        val c = o["c"]!!.jsonArray.map { it.jsonPrimitive.int }
        fun stop(i: Int, dep: Long?, arr: Long?, plat: String) = StopTime(
            names[i], null, c[2 * i] / 1e5, c[2 * i + 1] / 1e5, arrPlanned = arr, depPlanned = dep, platformPlanned = plat.ifEmpty { null })
        val segs = o["s"]!!.jsonArray.map { se ->
            val a = se.jsonArray
            val legs = a.drop(2).map { le ->
                val l = le.jsonArray
                val prod = Product.entries.getOrElse(l[0].jsonPrimitive.int) { Product.OTHER }
                val dep = base + l[5].jsonPrimitive.int * 60000L
                val arr = base + l[6].jsonPrimitive.int * 60000L
                Leg(
                    product = prod, line = l[1].jsonPrimitive.content.ifEmpty { "Пешком" },
                    direction = l[2].jsonPrimitive.content.ifEmpty { null },
                    from = stop(l[3].jsonPrimitive.int, dep, null, l[7].jsonPrimitive.content),
                    to = stop(l[4].jsonPrimitive.int, null, arr, l[8].jsonPrimitive.content),
                )
            }
            val j = Journey("shared:" + data.hashCode() + legs.first().dep, legs, "shared")
            Segment(
                Place(name = a[0].jsonPrimitive.content, lat = legs.first().from.lat, lon = legs.first().from.lon),
                Place(name = a[1].jsonPrimitive.content, lat = legs.last().to.lat, lon = legs.last().to.lon),
                j, emptyList(), j.dep
            )
        }
        TripPlan(UUID.randomUUID().toString(), o["t"]!!.jsonPrimitive.content, segs)
    }.getOrNull()

    fun webLink(p: TripPlan) = SHARE_BASE + "#r=" + encode(p)

    fun isShareUri(u: Uri) =
        (u.scheme == "railnav" && u.host == "plan") || (u.host == "qwertyqwertovich.github.io" && (u.path ?: "").startsWith("/railnav"))

    fun decodeAny(u: Uri): TripPlan? {
        u.getQueryParameter("r")?.let { return decode(it) }
        u.fragment?.let { f -> Regex("r=([A-Za-z0-9_-]+)").find(f)?.groupValues?.get(1)?.let { return decode(it) } }
        return u.getQueryParameter("d")?.let { decodeLegacy(it) }
    }

    fun decodeText(text: String): TripPlan? {
        Regex("[#?&]r=([A-Za-z0-9_-]+)").find(text)?.groupValues?.get(1)?.let { return decode(it) }
        return Regex("[?&]d=([A-Za-z0-9_-]+)").find(text)?.groupValues?.get(1)?.let { decodeLegacy(it) }
    }

    /** Old v0.1 links: gzip + base64url of the full plan JSON. */
    private fun decodeLegacy(data: String): TripPlan? = runCatching {
        val json = GZIPInputStream(ByteArrayInputStream(Base64.decode(data, B64))).readBytes().decodeToString()
        Net.json.decodeFromString<TripPlan>(json)
    }.getOrNull()
}
