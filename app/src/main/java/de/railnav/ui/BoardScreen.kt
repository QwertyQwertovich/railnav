package de.railnav.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsRailway
import androidx.compose.material.icons.filled.LocationCity
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Train
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.railnav.model.BoardEntry
import de.railnav.model.Product
import kotlinx.coroutines.delay

@Composable
fun BoardScreen(vm: AppViewModel) {
    var picker by remember { mutableStateOf(false) }

    // reload on parameter change; refresh every minute while showing "now"
    LaunchedEffect(vm.boardPlace, vm.boardArrivals, vm.boardTime, vm.products) {
        if (vm.boardPlace == null) return@LaunchedEffect
        while (true) {
            vm.loadBoard()
            if (vm.boardTime != null) break
            delay(60_000)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Табло", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (vm.boardPlace != null) IconButton(onClick = { vm.loadBoard() }) { Icon(Icons.Filled.Refresh, "Обновить") }
            }
            OutlinedButton(onClick = { picker = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(if (vm.boardPlace?.isCity == true) Icons.Filled.LocationCity else Icons.Filled.Train, null)
                Spacer(Modifier.width(8.dp))
                Text(vm.boardPlace?.displayName() ?: "Выберите станцию", modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(!vm.boardArrivals, { vm.boardArrivals = false }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Отправление", maxLines = 1) }
                SegmentedButton(vm.boardArrivals, { vm.boardArrivals = true }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Прибытие", maxLines = 1) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (vm.boardTime == null) {
                    Text("Сейчас (обновляется каждую минуту)", Modifier.weight(1f), fontSize = 14.sp)
                    TextButton(onClick = { vm.boardTime = System.currentTimeMillis() + 3600_000L }) { Text("другое время") }
                } else {
                    androidx.compose.foundation.layout.Box(Modifier.weight(1f)) { DateTimeRow(vm.boardTime!!, { vm.boardTime = it }, nowButton = false) }
                    TextButton(onClick = { vm.boardTime = null }) { Text("Сейчас") }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.DirectionsRailway, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                Text(productsSummary(vm.products) + " (меняется в поиске)", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (vm.boardLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        vm.boardError?.let { Banner(it, error = vm.boardEntries.isEmpty()) }
        if (vm.boardPlace == null) {
            Text("Выберите станцию, чтобы увидеть ближайшие отправления и прибытия с данными реального времени.",
                Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
            items(vm.boardEntries, key = { (it.tripId ?: it.line) + it.time(vm.boardArrivals) }) { e ->
                BoardRow(e, vm.boardArrivals)
                HorizontalDivider()
            }
            if (vm.boardEntries.isNotEmpty()) item {
                TextButton(onClick = { vm.boardUseInSearch() }, modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                    Text(if (vm.boardArrivals) "Построить маршрут сюда" else "Построить маршрут отсюда")
                }
            }
        }
    }

    if (picker) PlacePicker(vm, onDismiss = { picker = false }) { p ->
        vm.rememberPlace(p); vm.boardPlace = p; picker = false
    }
}

@Composable
private fun BoardRow(e: BoardEntry, arrivals: Boolean) {
    val planned = e.time(arrivals); val live = e.live(arrivals)
    val delay = if (planned != null && live != null) ((live - planned) / 60000).toInt() else null
    val cancelled = e.stop.cancelled
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(58.dp)) {
            Text(hhmm(planned), fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 1, softWrap = false,
                textDecoration = if (cancelled) TextDecoration.LineThrough else null)
            if (delay != null && !cancelled) Text(if (delay == 0) "✓" else hhmm(live), color = liveColor(delay),
                fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, softWrap = false)
        }
        ProductBadge(e.product, e.line)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text((if (arrivals) "из " else "") + e.direction, maxLines = 2, overflow = TextOverflow.Ellipsis,
                textDecoration = if (cancelled) TextDecoration.LineThrough else null)
            if (cancelled) Text("Отменён", color = Color(0xFFD32F2F), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            else if (delay != null && delay >= 5) Text("+$delay мин", color = liveColor(delay), fontSize = 12.sp)
        }
        Column(horizontalAlignment = Alignment.End) {
            e.stop.platform?.let {
                Text("пл. $it", fontSize = 14.sp, maxLines = 1, softWrap = false,
                    color = if (e.stop.platformChanged) Color(0xFFD32F2F) else MaterialTheme.colorScheme.onSurface,
                    fontWeight = if (e.stop.platformChanged) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
}

@Composable
fun ProductBadge(p: Product, line: String) {
    Row(
        Modifier.clip(RoundedCornerShape(6.dp)).background(productColor(p)).padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(productIcon(p), null, tint = Color.White, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(3.dp))
        Text(line, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
    }
}

