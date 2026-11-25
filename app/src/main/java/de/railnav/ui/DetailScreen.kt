package de.railnav.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.railnav.model.Journey
import de.railnav.model.Leg
import de.railnav.model.Segment
import de.railnav.store.Share

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(vm: AppViewModel, requestNotifications: () -> Unit) {
    val p = vm.current ?: return
    val ctx = LocalContext.current
    var delayDialog by remember { mutableStateOf<Pair<Int, Int>?>(null) } // segment, leg index
    var showShare by remember { mutableStateOf(false) }
    var showMap by remember { mutableStateOf(true) }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(p.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 17.sp) },
            navigationIcon = { IconButton(onClick = { vm.back() }) { Icon(Icons.Filled.ArrowBack, "Назад") } },
            actions = {
                IconButton(onClick = { showMap = !showMap }) { Icon(if (showMap) Icons.Filled.ExpandLess else Icons.Filled.Map, "Карта") }
                IconButton(onClick = { vm.refreshCurrent() }) { Icon(Icons.Filled.Refresh, "Обновить") }
                IconButton(onClick = { showShare = true }) { Icon(Icons.Filled.Share, "Поделиться") }
                IconButton(onClick = {
                    if (vm.isCurrentSaved) vm.deleteSaved(p) else vm.saveCurrent()
                }) { Icon(if (vm.isCurrentSaved) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder, "Сохранить") }
            }
        )
    }) { pad ->
        val planList = remember(p) { listOf(p) }
        Column(Modifier.padding(pad).fillMaxSize()) {
        if (vm.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (showMap) RouteMap(planList, 0, {}, Modifier.fillMaxWidth().height(260.dp))
        LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
            item {
                vm.info?.let { Banner(it) }
                vm.error?.let { Banner(it, error = true) }
                Row(Modifier.padding(16.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Уведомления о поездке", fontWeight = FontWeight.Medium)
                        Text("отправление, прибытие, пересадки, платформы, опоздания", fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(p.notify, { on -> if (on) requestNotifications(); vm.saveCurrent(notify = on) })
                }
                if (p.segments.first().journey.provider == "offline") {
                    Banner("Маршрут из офлайн-расписания: без реального времени, платформы могут отсутствовать")
                }
                HorizontalDivider()
            }
            p.segments.forEachIndexed { si, seg ->
                item(key = "seg$si") {
                    SegmentBlock(vm, si, seg, onDelay = { legIdx -> delayDialog = si to legIdx })
                    if (si < p.segments.size - 1) {
                        val m = p.stayMin(si)
                        Card(Modifier.fillMaxWidth().padding(16.dp, 4.dp)) {
                            Text(
                                if (m >= 0) "⏱ Время в ${seg.to.name}: ${durStr(m)}" else "⚠ Не успеваете: ${-m} мин не хватает",
                                Modifier.padding(12.dp), fontWeight = FontWeight.Medium,
                                color = if (m < 0) Color(0xFFD32F2F) else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
        }
    }

    delayDialog?.let { (si, li) ->
        val leg = p.segments[si].journey.legs[li]
        var minutes by remember { mutableStateOf(maxOf(leg.to.arrDelayMin, 10).toString()) }
        var cancelled by remember { mutableStateOf(leg.from.cancelled) }
        AlertDialog(
            onDismissRequest = { delayDialog = null },
            title = { Text("${leg.line} опаздывает?") },
            text = {
                Column {
                    Text("Найдём варианты до ${p.segments[si].to.name} с учётом опоздания.")
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(minutes, { minutes = it.filter { c -> c.isDigit() }.take(3) },
                        label = { Text("Опоздание, мин") }, enabled = !cancelled,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(cancelled, { cancelled = it }); Text("Поезд отменён (искать от ${leg.from.name})")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.findAlternatives(si, li, minutes.toIntOrNull() ?: 0, cancelled); delayDialog = null
                }) { Text("Искать") }
            },
            dismissButton = { TextButton(onClick = { delayDialog = null }) { Text("Отмена") } }
        )
    }

    if (showShare) ShareDialog(p) { showShare = false }
}

@Composable
private fun ShareDialog(p: de.railnav.model.TripPlan, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var style by remember { mutableStateOf(Share.Style.SHORT) }
    var withLink by remember { mutableStateOf(true) }
    var text by remember(style, withLink) { mutableStateOf(Share.text(p, style, withLink)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Поделиться маршрутом") },
        text = {
            Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    Share.Style.entries.forEachIndexed { i, st ->
                        SegmentedButton(style == st, { style = st },
                            SegmentedButtonDefaults.itemShape(i, Share.Style.entries.size)) {
                            Text(st.label, fontSize = 12.sp, maxLines = 1)
                        }
                    }
                }
                if (style != Share.Style.LINK) Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(withLink, { withLink = it }); Text("добавить ссылку", fontSize = 14.sp)
                }
                Text("Текст можно поправить перед отправкой:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp))
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().padding(top = 4.dp),
                    textStyle = MaterialTheme.typography.bodySmall, minLines = 4)
                Text("По ссылке откроется страница с маршрутом: оттуда его можно открыть в RailNav или скачать приложение.",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, text), "Поделиться"))
                onDismiss()
            }) { Text("Отправить") }
        },
        dismissButton = {
            TextButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(text)); onDismiss() }) { Text("Копировать") }
        }
    )
}

@Composable
private fun SegmentBlock(vm: AppViewModel, si: Int, seg: Segment, onDelay: (Int) -> Unit) {
    var showAlt by remember { mutableStateOf(false) }
    val j = seg.journey
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        if ((vm.current?.segments?.size ?: 1) > 1) {
            Text("${si + 1}. ${seg.from.name} → ${seg.to.name}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("${dayStr(j.dep)} · ${durStr(j.durationMin)} · пересадок ${j.transfers}", fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (seg.maxStayExceeded) Text("Стоянка дольше желаемого максимума — раньше поездов нет", color = Color(0xFFF57C00), fontSize = 13.sp)
        } else {
            JourneySummary(j)
        }
        Spacer(Modifier.height(8.dp))
        val minTr = vm.settings.minTransferMin
        j.legs.forEachIndexed { li, leg ->
            LegBlock(leg, onDelay = if (!leg.isWalk) ({ onDelay(li) }) else null)
            val next = j.legs.getOrNull(li + 1)
            if (next != null && !leg.isWalk && !next.isWalk) {
                val slack = ((next.dep - leg.arr) / 60000).toInt()
                TransferNote(slack, slack < minTr || next.from.cancelled)
            }
        }
        if (seg.alternatives.isNotEmpty()) {
            TextButton(onClick = { showAlt = !showAlt }) {
                Text(if (showAlt) "Скрыть другие варианты" else "Другие варианты этого участка (${seg.alternatives.size})")
            }
            if (showAlt) seg.alternatives.sortedBy { it.dep }.forEach { alt ->
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { vm.chooseSegmentAlternative(si, alt) }) {
                    Column(Modifier.padding(10.dp)) { JourneySummary(alt) }
                }
            }
        }
    }
}

@Composable
private fun TransferNote(slack: Int, risky: Boolean) {
    Row(Modifier.padding(start = 56.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        if (risky) Icon(Icons.Filled.Warning, null, tint = Color(0xFFD32F2F), modifier = Modifier.width(16.dp))
        Text(
            " Пересадка $slack мин" + if (risky) " — может не хватить" else "",
            color = if (risky) Color(0xFFD32F2F) else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp
        )
    }
}

@Composable
fun LegBlock(leg: Leg, onDelay: (() -> Unit)?) {
    var expanded by remember { mutableStateOf(false) }
    val c = productColor(leg.product)
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Column(Modifier.width(56.dp)) {
            TimeWithDelay(leg.from.depPlanned ?: leg.dep, leg.from.depRt.takeIf { !leg.isWalk }, stacked = true)
            Spacer(Modifier.weight(1f))
            TimeWithDelay(leg.to.arrPlanned ?: leg.arr, leg.to.arrRt.takeIf { !leg.isWalk }, stacked = true)
        }
        Box(Modifier.width(4.dp).fillMaxHeight().padding(vertical = 4.dp).background(if (leg.isWalk) Color.LightGray else c))
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(leg.from.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 2)
                Platform(leg.from, false)
            }
            if (leg.from.cancelled) Text("Отменён!", color = Color(0xFFD32F2F), fontWeight = FontWeight.Bold)
            Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                LineBadge(leg)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (leg.isWalk) "Пешком ${leg.walkMeters?.let { "$it м, " } ?: ""}${durStr(((leg.arr - leg.dep) / 60000).toInt())}"
                    else leg.direction?.let { "→ $it" } ?: "",
                    fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            leg.operator?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            leg.notes.take(3).forEach { Text("ℹ $it", fontSize = 12.sp, color = Color(0xFFF57C00)) }
            if (leg.stops.isNotEmpty()) {
                Text(
                    if (expanded) "Скрыть остановки" else "${leg.stops.size} остановок · ${durStr(((leg.arr - leg.dep) / 60000).toInt())}",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { expanded = !expanded }.padding(vertical = 4.dp)
                )
                if (expanded) leg.stops.forEach { s ->
                    Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        StopTimes(s)
                        Text(s.name, fontSize = 13.sp, modifier = Modifier.weight(1f),
                            color = if (s.cancelled) Color(0xFFD32F2F) else Color.Unspecified)
                        Platform(s, false)
                    }
                }
            }
            if (onDelay != null) {
                val late = maxOf(leg.from.depDelayMin, leg.to.arrDelayMin)
                if (late >= 3 || leg.from.cancelled || leg.to.cancelled) {
                    // Live data says the train is late: show a prominent button.
                    androidx.compose.material3.Button(
                        onClick = onDelay, modifier = Modifier.padding(vertical = 4.dp),
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F), contentColor = Color.White)
                    ) {
                        Text(if (leg.from.cancelled || leg.to.cancelled) "⚠ Отменён — найти альтернативы" else "⚠ Опаздывает на $late мин — альтернативы", fontSize = 13.sp)
                    }
                } else {
                    TextButton(onClick = onDelay, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                        modifier = Modifier.height(28.dp)) {
                        Text("Опоздание?", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(leg.to.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 2)
                Platform(leg.to, true)
            }
        }
    }
    Spacer(Modifier.height(6.dp))
}

@Suppress("unused")
private fun Journey.legIndex(l: Leg) = legs.indexOf(l)

@Suppress("unused")
private val spacing = Arrangement.spacedBy(4.dp)
