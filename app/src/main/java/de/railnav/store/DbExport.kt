package de.railnav.store

import android.content.Context
import android.content.Intent
import android.net.Uri
import de.railnav.model.Segment
import de.railnav.model.StopTime
import de.railnav.model.TripPlan
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Opens a journey in DB (bahn.de / DB Navigator) as a pre-filled connection search:
 * same stations (by EVA number when known) and departure time. DB's own "vbid" share links
 * can only be created through its API, so this is a search link, not the exact saved journey.
 */
object DbExport {
    private val evaRe = Regex("(?<![0-9])(80[0-9]{5})(?![0-9])")
    private val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

    private fun eva(s: StopTime): String? = s.stopId?.let { evaRe.find(it)?.groupValues?.get(1) }
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    fun url(seg: Segment): String {
        val legs = seg.journey.transitLegs.ifEmpty { seg.journey.legs }
        val a = legs.first().from; val b = legs.last().to
        val time = Instant.ofEpochMilli(a.depPlanned ?: legs.first().dep).atZone(ZoneId.of("Europe/Berlin")).format(fmt)
        val p = mutableListOf("sts=true", "so=" + enc(a.name), "zo=" + enc(b.name), "kl=2", "r=13:16:KLASSENLOS:1")
        // Station by EVA number when known; otherwise by coordinates (bahn.de resolves the nearest stop).
        fun loc(s: StopTime, k: String) {
            val e = eva(s)
            if (e != null) { p += "${k}id=" + enc("A=1@L=$e@"); p += "${k}ei=$e"; p += "${k}t=ST" }
            else {
                p += "${k}id=" + enc("A=2@O=${s.name}@X=${Math.round(s.lon * 1e6)}@Y=${Math.round(s.lat * 1e6)}@")
                p += "${k}t=ADR"
            }
        }
        loc(a, "so"); loc(b, "zo")
        p += listOf("hd=$time", "hza=D", "ar=false", "s=true", "d=false", "hz=%5B%5D", "fm=false", "bp=false")
        return "https://www.bahn.de/buchung/fahrplan/suche#" + p.joinToString("&")
    }

    fun open(ctx: Context, plan: TripPlan, segment: Int = 0) {
        val seg = plan.segments.getOrNull(segment) ?: return
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url(seg))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
