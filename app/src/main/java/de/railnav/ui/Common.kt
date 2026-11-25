package de.railnav.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.DirectionsBoat
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.DirectionsSubway
import androidx.compose.material.icons.filled.Train
import androidx.compose.material.icons.filled.Tram
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.railnav.model.Journey
import de.railnav.model.Leg
import de.railnav.model.Product
import de.railnav.model.StopTime
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val hm = DateTimeFormatter.ofPattern("HH:mm")
private val dm = DateTimeFormatter.ofPattern("EE, dd.MM.", java.util.Locale("ru"))
fun hhmm(ms: Long?): String = ms?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(hm) } ?: "--:--"
fun dayStr(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(dm)
fun durStr(min: Int): String = if (min >= 60) "${min / 60} ч ${min % 60} мин" else "$min мин"

val RouteColors = listOf(
    Color(0xFFC62828), Color(0xFF1565C0), Color(0xFF2E7D32), Color(0xFFEF6C00),
    Color(0xFF6A1B9A), Color(0xFF00838F), Color(0xFF4E342E), Color(0xFFAD1457),
)

fun productColor(p: Product): Color = when (p) {
    Product.HIGHSPEED -> Color(0xFFB71C1C)
    Product.LONG_DISTANCE -> Color(0xFFE53935)
    Product.NIGHT -> Color(0xFF283593)
    Product.REGIONAL_EXPRESS -> Color(0xFF8D3B2E)
    Product.REGIONAL -> Color(0xFF6D5D4B)
    Product.SUBURBAN -> Color(0xFF2E7D32)
    Product.SUBWAY -> Color(0xFF1565C0)
    Product.TRAM -> Color(0xFFC2185B)
    Product.BUS, Product.COACH -> Color(0xFF6A1B9A)
    Product.FERRY -> Color(0xFF00838F)
    else -> Color(0xFF757575)
}

fun productIcon(p: Product): ImageVector = when (p) {
    Product.SUBWAY -> Icons.Filled.DirectionsSubway
    Product.TRAM -> Icons.Filled.Tram
    Product.BUS, Product.COACH -> Icons.Filled.DirectionsBus
    Product.FERRY -> Icons.Filled.DirectionsBoat
    Product.WALK -> Icons.AutoMirrored.Filled.DirectionsWalk
    else -> Icons.Filled.Train
}

@Composable
fun LineBadge(leg: Leg, small: Boolean = false) {
    val c = productColor(leg.product)
    Row(
        Modifier.clip(RoundedCornerShape(6.dp)).background(c).padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(productIcon(leg.product), null, tint = Color.White, modifier = Modifier.size(if (small) 12.dp else 16.dp))
        if (!leg.isWalk) {
            Spacer(Modifier.width(3.dp))
            Text(leg.line, color = Color.White, fontSize = if (small) 11.sp else 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

/** Planned time plus real-time (green on time, red when late). */
@Composable
fun TimeWithDelay(planned: Long?, rt: Long?, big: Boolean = false, stacked: Boolean = false) {
    val delay = if (planned != null && rt != null) ((rt - planned) / 60000).toInt() else null
    // Live time next to the planned one: ✓ when on time, otherwise the actual time (orange/red).
    val delayText: @Composable () -> Unit = {
        if (delay != null) Text(
            if (delay == 0) "✓" else hhmm(rt),
            color = liveColor(delay),
            fontSize = if (big && !stacked) 17.sp else 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false
        )
    }
    if (stacked) {
        Column {
            Text(hhmm(planned), fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, softWrap = false)
            delayText()
        }
    } else Row(verticalAlignment = Alignment.Bottom) {
        Text(hhmm(planned), fontWeight = FontWeight.Bold, fontSize = if (big) 20.sp else 15.sp, maxLines = 1, softWrap = false)
        if (delay != null) { Spacer(Modifier.width(4.dp)); delayText() }
    }
}

fun liveColor(delayMin: Int): Color =
    if (delayMin >= 5) Color(0xFFD32F2F) else if (delayMin > 0) Color(0xFFF57C00) else if (delayMin < 0) Color(0xFF1E88E5) else Color(0xFF2E7D32)

/** Arrival and departure of an intermediate stop: one time, or "arr / dep" when they differ by more than a minute. */
@Composable
fun StopTimes(s: StopTime, width: androidx.compose.ui.unit.Dp = 96.dp) {
    val split = s.arrPlanned != null && s.depPlanned != null && s.depPlanned - s.arrPlanned > 60_000
    Column(Modifier.width(width)) {
        if (split) {
            TimeLine("пр", s.arrPlanned, s.arrRt)
            TimeLine("от", s.depPlanned, s.depRt)
        } else TimeLine(null, s.depPlanned ?: s.arrPlanned, s.depRt ?: s.arrRt)
    }
}

@Composable
private fun TimeLine(label: String?, planned: Long?, rt: Long?) {
    val delay = if (planned != null && rt != null) ((rt - planned) / 60000).toInt() else null
    Row(verticalAlignment = Alignment.Bottom) {
        if (label != null) Text("$label ", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
        Text(hhmm(planned), fontSize = 13.sp, maxLines = 1, softWrap = false)
        if (delay != null) {
            Spacer(Modifier.width(3.dp))
            Text(if (delay == 0) "✓" else hhmm(rt), fontSize = 13.sp, color = liveColor(delay), fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
        }
    }
}

@Composable
fun Platform(s: StopTime, arrival: Boolean) {
    val p = s.platform ?: return
    Text(
        "пл. $p",
        color = if (s.platformChanged) Color(0xFFD32F2F) else MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = if (s.platformChanged) FontWeight.Bold else FontWeight.Normal,
        fontSize = 13.sp, maxLines = 1, softWrap = false
    )
}

/** Proportional timeline bar of a journey (like the overview bar in DB Navigator). */
@Composable
fun JourneyBar(j: Journey, modifier: Modifier = Modifier) {
    val total = (j.arr - j.dep).coerceAtLeast(1).toFloat()
    Row(modifier.fillMaxWidth().height(22.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        var cursor = j.dep
        for (l in j.legs) {
            val gap = (l.dep - cursor).coerceAtLeast(0)
            if (gap > 0) Spacer(Modifier.weight(gap / total))
            val w = ((l.arr - l.dep).coerceAtLeast(60_000) / total)
            Box(
                Modifier.weight(w).height(22.dp).clip(RoundedCornerShape(4.dp))
                    .background(if (l.isWalk) Color(0xFFBDBDBD) else productColor(l.product)),
                contentAlignment = Alignment.Center
            ) {
                if (!l.isWalk) Text(l.line, color = Color.White, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Clip)
            }
            cursor = maxOf(cursor, l.arr)
        }
    }
}

@Composable
fun JourneySummary(j: Journey, color: Color? = null) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                if (color != null) Box(Modifier.size(12.dp).clip(RoundedCornerShape(6.dp)).background(color))
                if (color != null) Spacer(Modifier.width(8.dp))
                TimeWithDelay(j.first.depPlanned ?: j.dep, j.first.depRt, big = true)
                Text(" — ", fontSize = 18.sp, maxLines = 1, softWrap = false)
                TimeWithDelay(j.last.arrPlanned ?: j.arr, j.last.arrRt, big = true)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(durStr(j.durationMin), fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false)
                Text(if (j.transfers == 0) "без пересадок" else "пересадок: ${j.transfers}", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
            }
        }
        Spacer(Modifier.height(6.dp))
        JourneyBar(j)
        Spacer(Modifier.height(4.dp))
        Text(
            "${j.first.name}${j.first.platform?.let { " (пл. $it)" } ?: ""} → ${j.last.name}",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        val warn = j.legs.any { it.from.cancelled || it.to.cancelled }
        if (warn) Text("Есть отмены!", color = Color(0xFFD32F2F), fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}
