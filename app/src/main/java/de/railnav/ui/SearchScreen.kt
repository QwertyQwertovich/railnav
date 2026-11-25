package de.railnav.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DirectionsRailway
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LocationCity
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Train
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import de.railnav.model.Place
import de.railnav.model.Product
import de.railnav.model.ReturnMode
import de.railnav.model.Waypoint
import de.railnav.store.FavoriteTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

private val STAY_OPTIONS = listOf(0, 10, 15, 30, 45, 60, 90, 120, 180, 240, 300, 360, 480, 600, 720, 1440)
private fun stayLabel(m: Int) = when {
    m == 0 -> "без остановки"
    m < 60 -> "$m мин"
    m % 60 == 0 -> "${m / 60} ч"
    else -> "${m / 60} ч ${m % 60} мин"
}

fun productsSummary(p: Set<Product>): String = when (p) {
    Product.railOnly -> "Все поезда"
    Product.regionalOnly -> "Только региональные: RE, RB, S-Bahn"
    Product.selectable.toSet() -> "Любой транспорт"
    else -> Product.selectable.filter { it in p }.joinToString(", ") { it.label.substringBefore(" /") }.ifEmpty { "ничего не выбрано" }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(vm: AppViewModel) {
    var picker by remember { mutableStateOf<String?>(null) } // "origin" | "dest" | "via:N" | "addVia"
    var showTransport by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Поиск маршрута", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

            Card {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            PlaceField(if (vm.travel) "Старт (сюда же вернётесь)" else "Откуда", vm.origin) { picker = "origin" }
                            vm.vias.forEachIndexed { i, w ->
                                ViaRow(i, w, label = if (vm.travel) "Город ${i + 1}" else "Через (${i + 1})",
                                    onPick = { picker = "via:$i" },
                                    onStay = { m -> vm.vias[i] = w.copy(minStayMin = m) },
                                    onMaxStay = { m -> vm.vias[i] = w.copy(maxStayMin = m) },
                                    onRemove = { vm.vias.removeAt(i) },
                                    onPriority = { p -> vm.vias[i] = w.copy(priority = p) })
                            }
                            if (!vm.travel) PlaceField(
                                if (vm.roundTrip) "Куда (самая дальняя точка)" else "Куда", vm.destination,
                                placeholder = if (vm.roundTrip && vm.vias.isNotEmpty()) "можно не указывать: развернётесь в последнем городе" else null,
                                onClear = if (vm.destination != null) ({ vm.destination = null }) else null,
                            ) { picker = "dest" }
                        }
                        if (!vm.travel) IconButton(onClick = { vm.swap() }) { Icon(Icons.Filled.SwapVert, "Поменять местами") }
                    }
                    TextButton(onClick = { picker = "addVia" }) {
                        Icon(Icons.Filled.Add, null); Spacer(Modifier.width(4.dp)); Text(if (vm.travel) "Добавить город" else "Промежуточный город или станция")
                    }
                    if (vm.vias.size >= 2) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Строгий порядок точек")
                                Text(
                                    if (vm.strictOrder) "в указанном порядке" else "порядок подберётся по кратчайшему пути",
                                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(vm.strictOrder, { vm.strictOrder = it })
                        }
                    }
                }
            }

            Card {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        SegmentedButton(!vm.arriveBy, { vm.arriveBy = false }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Отправление", maxLines = 1) }
                        SegmentedButton(vm.arriveBy, { vm.arriveBy = true }, SegmentedButtonDefaults.itemShape(1, 2),
                            enabled = vm.vias.isEmpty() && !vm.roundTrip && !vm.travel) { Text("Прибытие", maxLines = 1) }
                    }
                    DateTimeRow(vm.time, { vm.time = it }, nowButton = true)
                    FavoriteTimesRow(vm)
                }
            }

            Card {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        val modes = listOf("Туда", "Туда-обратно", "Путешествие")
                        val cur = if (vm.travel) 2 else if (vm.roundTrip) 1 else 0
                        modes.forEachIndexed { i, label ->
                            SegmentedButton(cur == i, {
                                vm.roundTrip = i == 1; vm.travel = i == 2
                                if (i > 0) vm.arriveBy = false
                            }, SegmentedButtonDefaults.itemShape(i, modes.size)) { Text(label, maxLines = 1, fontSize = 12.sp) }
                        }
                    }
                    Hint(when {
                        vm.travel -> "старт и города из списка, в конце — возврат в старт"
                        vm.roundTrip -> "туда и обратно: покажет, сколько времени будет в каждом городе"
                        else -> "в одну сторону"
                    })
                    if (vm.roundTrip || vm.travel) {
                        if (!vm.travel) Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("В конечной точке:", fontSize = 14.sp)
                            Spacer(Modifier.width(4.dp))
                            StayChoice("мин", vm.stayAtDestMin) { vm.stayAtDestMin = it }
                            Spacer(Modifier.weight(1f))
                            PriorityChoice(vm.destPriority) { vm.destPriority = it }
                        }
                        Text(if (vm.travel) "Домой:" else "Обратно:", fontWeight = FontWeight.Medium)
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            val modes = listOf(ReturnMode.EARLIEST to "Рано", ReturnMode.NOT_BEFORE to "Не раньше", ReturnMode.LATEST to "Поздно")
                            modes.forEachIndexed { i, (m, label) ->
                                SegmentedButton(vm.returnMode == m, {
                                    vm.returnMode = m
                                    vm.returnTime = if (m == ReturnMode.NOT_BEFORE) vm.time + (vm.stayAtDestMin + 240) * 60_000L else null
                                },
                                    SegmentedButtonDefaults.itemShape(i, modes.size)) { Text(label, maxLines = 1, fontSize = 13.sp) }
                            }
                        }
                        when (vm.returnMode) {
                            ReturnMode.EARLIEST -> Hint("первым поездом после минимальной стоянки")
                            ReturnMode.NOT_BEFORE -> {
                                Hint("первым поездом не раньше указанного времени")
                                DateTimeRow(vm.returnTime ?: vm.time,
                                    { vm.returnTime = it }, nowButton = false)
                            }
                            ReturnMode.LATEST -> {
                                Hint(if (vm.returnTime == null) "последним поездом, который довезёт домой до конца дня"
                                else "последним поездом с прибытием домой до указанного времени")
                                ReturnByRow(vm)
                            }
                        }
                    }
                }
            }

            OutlinedButton(onClick = { showTransport = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.DirectionsRailway, null)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("Транспорт", fontSize = 12.sp)
                    Text(productsSummary(vm.products), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }

            TextButton(onClick = {
                val text = clipboard.getText()?.text.orEmpty()
                if (!vm.importText(text)) vm.error = "В буфере обмена нет ссылки railnav://"
            }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.ContentPaste, null); Spacer(Modifier.width(8.dp)); Text("Открыть маршрут из буфера обмена")
            }
        }

        // Fixed search bar above the navigation bar
        Surface(tonalElevation = 3.dp, shadowElevation = 6.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                vm.error?.let { if (vm.screen == Screen.Search) Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
                Button(onClick = { vm.search() }, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                    Icon(Icons.Filled.Search, null); Spacer(Modifier.width(8.dp)); Text("Найти маршрут", fontSize = 16.sp)
                }
            }
        }
    }

    if (showTransport) {
        ModalBottomSheet(onDismissRequest = { showTransport = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            TransportSheet(vm.products, { vm.products = it }) { showTransport = false }
        }
    }

    picker?.let { target ->
        PlacePicker(vm, onDismiss = { picker = null }) { place ->
            vm.rememberPlace(place)
            when {
                target == "origin" -> vm.origin = place
                target == "dest" -> vm.destination = place
                target == "addVia" -> vm.vias.add(Waypoint(place, minStayMin = 60))
                target.startsWith("via:") -> {
                    val i = target.removePrefix("via:").toInt()
                    vm.vias[i] = vm.vias[i].copy(place = place)
                }
            }
            picker = null
        }
    }
}

/** "Быть дома до": quick buttons 20:00 / 22:00 / 0:00 / 1:00 or a custom time. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReturnByRow(vm: AppViewModel) {
    val zone = ZoneId.systemDefault()
    val day = Instant.ofEpochMilli(vm.time).atZone(zone).toLocalDate()
    fun at(h: Int) = (if (h < 5) day.plusDays(1) else day).atTime(h, 0).atZone(zone).toInstant().toEpochMilli()
    val presets = listOf(20, 22, 0, 1)
    var custom by remember { mutableStateOf(vm.returnTime != null && presets.none { at(it) == vm.returnTime }) }
    Text("Быть дома до:")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        // no time chosen = midnight (same as the 0:00 button)
        for (h in presets) FilterChip(!custom && (vm.returnTime == at(h) || (h == 0 && vm.returnTime == null)),
            { vm.returnTime = at(h); custom = false },
            label = { Text("%d:00".format(h)) })
        FilterChip(custom, { custom = true; if (vm.returnTime == null) vm.returnTime = at(21) }, label = { Text("своё время") })
    }
    if (custom) vm.returnTime?.let { rt -> DateTimeRow(rt, { vm.returnTime = it }, nowButton = false) }
}

@Composable
private fun Hint(text: String) = Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TransportSheet(selected: Set<Product>, onChange: (Set<Product>) -> Unit, onDone: () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Транспорт", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedButton(onClick = { onChange(Product.railOnly) }) { Text("Все поезда") }
            OutlinedButton(onClick = { onChange(Product.regionalOnly) }) { Text("RE + RB + S") }
            OutlinedButton(onClick = { onChange(Product.selectable.toSet()) }) { Text("Всё") }
        }
        Text("Поезда", fontWeight = FontWeight.Medium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (p in Product.selectable.filter { it.isRail }) ProductChip(p, p in selected) { onChange(if (p in selected) selected - p else selected + p) }
        }
        Text("Городской и другой транспорт", fontWeight = FontWeight.Medium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (p in Product.selectable.filter { !it.isRail }) ProductChip(p, p in selected) { onChange(if (p in selected) selected - p else selected + p) }
        }
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Готово") }
    }
}

@Composable
private fun ProductChip(p: Product, on: Boolean, onClick: () -> Unit) {
    FilterChip(selected = on, onClick = onClick, label = { Text(p.label, maxLines = 1) },
        leadingIcon = { Icon(productIcon(p), null, Modifier.size(18.dp)) })
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun FavoriteTimesRow(vm: AppViewModel) {
    var saveDialog by remember { mutableStateOf(false) }
    var deleteAsk by remember { mutableStateOf<FavoriteTime?>(null) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (t in vm.favoriteTimes) {
            Surface(
                shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier.combinedClickable(onClick = { vm.time = t.resolve() }, onLongClick = { deleteAsk = t })
            ) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Schedule, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp))
                    Text(t.label(), fontSize = 13.sp, maxLines = 1)
                }
            }
        }
        Surface(shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.clickable { saveDialog = true }) {
            Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Add, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp))
                Text("запомнить время", fontSize = 13.sp, maxLines = 1)
            }
        }
    }
    if (saveDialog) {
        val z = Instant.ofEpochMilli(vm.time).atZone(ZoneId.systemDefault())
        val options = listOf(
            FavoriteTime(z.dayOfWeek.value, z.hour, z.minute) to "Ближайшее ${FavoriteTime.WEEKDAYS_LONG[z.dayOfWeek.value - 1]} в ${hhmm(vm.time)}",
            FavoriteTime(-1, z.hour, z.minute) to "Завтра в ${hhmm(vm.time)}",
            FavoriteTime(0, z.hour, z.minute) to "Сегодня в ${hhmm(vm.time)}",
        )
        AlertDialog(
            onDismissRequest = { saveDialog = false },
            title = { Text("Запомнить время отправления") },
            text = {
                Column {
                    Text("Выберите правило (время берётся из поля выше). Удалить: долгое нажатие на кнопку.", fontSize = 13.sp)
                    options.forEach { (t, label) ->
                        TextButton(onClick = { vm.addFavoriteTime(t); saveDialog = false }) { Text(label) }
                    }
                }
            },
            confirmButton = {}, dismissButton = { TextButton(onClick = { saveDialog = false }) { Text("Отмена") } }
        )
    }
    deleteAsk?.let { t ->
        AlertDialog(
            onDismissRequest = { deleteAsk = null },
            title = { Text("Удалить «${t.label()}»?") },
            confirmButton = { TextButton(onClick = { vm.removeFavoriteTime(t); deleteAsk = null }) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { deleteAsk = null }) { Text("Отмена") } }
        )
    }
}

@Composable
private fun PlaceField(label: String, place: Place?, placeholder: String? = null, onClear: (() -> Unit)? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).clickable(onClick = onClick).padding(vertical = 8.dp)) {
            Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (place?.isCity == true) Icons.Filled.LocationCity else Icons.Filled.Train, null, Modifier.padding(end = 6.dp).size(20.dp))
                Column {
                    Text(place?.displayName() ?: placeholder ?: "Выберите станцию или город",
                        fontSize = if (place == null && placeholder != null) 14.sp else 17.sp,
                        color = if (place == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                    place?.region?.let { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
                }
            }
        }
        if (onClear != null) IconButton(onClick = onClear) { Icon(Icons.Filled.Close, "Очистить", Modifier.size(18.dp)) }
    }
    HorizontalDivider()
}

@Composable
private fun ViaRow(i: Int, w: Waypoint, label: String, onPick: () -> Unit, onStay: (Int) -> Unit, onMaxStay: (Int?) -> Unit, onRemove: () -> Unit,
                   onPriority: (Int) -> Unit = {}) {
    Column(Modifier.padding(start = 12.dp)) {
        PlaceField(label, w.place, onClear = onRemove, onClick = onPick)
        // min and max stay on one line
        Row(verticalAlignment = Alignment.CenterVertically) {
            StayChoice("мин", w.minStayMin, onChange = onStay)
            Spacer(Modifier.weight(1f))
            PriorityChoice(w.priority, onPriority)
            Spacer(Modifier.weight(1f))
            StayChoice("макс", w.maxStayMin ?: -1, allowNone = true) { onMaxStay(if (it < 0) null else it) }
        }
    }
}

@Composable
private fun StayChoice(label: String, value: Int, allowNone: Boolean = false, onChange: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp)) {
            Text("$label ", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Text(if (value < 0) "любая" else stayLabel(value), fontSize = 14.sp, maxLines = 1)
        }
        DropdownMenu(open, { open = false }) {
            if (allowNone) DropdownMenuItem(text = { Text("любая") }, onClick = { onChange(-1); open = false })
            for (m in STAY_OPTIONS) DropdownMenuItem(text = { Text(stayLabel(m)) }, onClick = { onChange(m); open = false })
        }
    }
}

/** Priority of a stop when spare time is shared out (only matters with a fixed return time). */
@Composable
private fun PriorityChoice(value: Int, onChange: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val labels = mapOf(1 to "★", 2 to "★★", 3 to "★★★")
    Box {
        TextButton(onClick = { open = true }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp)) {
            Text(labels[value] ?: "★★", fontSize = 14.sp, maxLines = 1)
        }
        DropdownMenu(open, { open = false }) {
            Text("Приоритет: сколько свободного времени\nдостанется этому городу", fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            for ((k, l) in listOf(1 to "★  низкий", 2 to "★★  обычный", 3 to "★★★  высокий"))
                DropdownMenuItem(text = { Text(l) }, onClick = { onChange(k); open = false })
        }
    }
}

@Composable
private fun StayPicker(label: String, value: Int, allowNone: Boolean = false, onChange: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Box {
            TextButton(onClick = { open = true }) { Text(if (value < 0) "не важно" else stayLabel(value), maxLines = 1) }
            DropdownMenu(open, { open = false }) {
                if (allowNone) DropdownMenuItem(text = { Text("не важно") }, onClick = { onChange(-1); open = false })
                for (m in STAY_OPTIONS) DropdownMenuItem(text = { Text(stayLabel(m)) }, onClick = { onChange(m); open = false })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateTimeRow(time: Long, onChange: (Long) -> Unit, nowButton: Boolean) {
    var showDate by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }
    val zone = ZoneId.systemDefault()
    val zdt = Instant.ofEpochMilli(time).atZone(zone)
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { showDate = true }) { Text(dayStr(time), maxLines = 1) }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(onClick = { showTime = true }) { Text(hhmm(time), fontWeight = FontWeight.Bold, maxLines = 1) }
        Spacer(Modifier.weight(1f))
        if (nowButton) TextButton(onClick = { onChange(System.currentTimeMillis()) }) { Text("Сейчас", maxLines = 1) }
    }
    if (showDate) {
        val st = rememberDatePickerState(initialSelectedDateMillis = zdt.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(onDismissRequest = { showDate = false }, confirmButton = {
            TextButton(onClick = {
                st.selectedDateMillis?.let {
                    val d = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                    onChange(d.atTime(zdt.toLocalTime()).atZone(zone).toInstant().toEpochMilli())
                }
                showDate = false
            }) { Text("OK") }
        }) { DatePicker(st) }
    }
    if (showTime) {
        val st = rememberTimePickerState(zdt.hour, zdt.minute, true)
        AlertDialog(onDismissRequest = { showTime = false }, text = { TimePicker(st) }, confirmButton = {
            TextButton(onClick = {
                val d: LocalDate = zdt.toLocalDate()
                onChange(d.atTime(LocalTime.of(st.hour, st.minute)).atZone(zone).toInstant().toEpochMilli())
                showTime = false
            }) { Text("OK") }
        })
    }
}

@Composable
fun PlacePicker(vm: AppViewModel, onDismiss: () -> Unit, onPick: (Place) -> Unit) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Place>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(query) {
        if (query.trim().length < 2) { results = emptyList(); return@LaunchedEffect }
        delay(350)
        busy = true; err = null
        try { results = vm.searchPlaces(query.trim()) } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            err = e.message
        }
        busy = false
    }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column {
                Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
                    OutlinedTextField(
                        query, { query = it }, Modifier.weight(1f).focusRequester(focus),
                        placeholder = { Text("Станция или город") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { scope.launch { runCatching { results = vm.searchPlaces(query) } } }),
                        trailingIcon = { if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
                    )
                }
                err?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
                LazyColumn {
                    if (query.trim().length < 2) {
                        if (vm.favorites.isNotEmpty()) {
                            item { SectionTitle("Избранное") }
                            items(vm.favorites, key = { "f" + it.name + it.isCity }) { p -> PlaceRow(vm, p, Icons.Filled.Star, onPick) }
                        }
                        val recents = vm.recents.filter { r -> !vm.isFavorite(r) }
                        if (recents.isNotEmpty()) {
                            item { SectionTitle("Недавние") }
                            items(recents, key = { "r" + it.name + it.isCity }) { p -> PlaceRow(vm, p, Icons.Filled.History, onPick) }
                        }
                        if (vm.favorites.isEmpty()) item {
                            Text("Нажмите ☆ у станции или города, чтобы добавить в избранное.", Modifier.padding(16.dp),
                                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        items(results, key = { "q" + it.name + it.isCity + it.id + it.lat }) { p ->
                            PlaceRow(vm, p, if (p.isCity) Icons.Filled.LocationCity else Icons.Filled.Place, onPick)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(t: String) = Text(t, Modifier.padding(16.dp, 8.dp), fontWeight = FontWeight.Medium)

@Composable
private fun PlaceRow(vm: AppViewModel, p: Place, icon: androidx.compose.ui.graphics.vector.ImageVector, onPick: (Place) -> Unit) {
    ListItem(
        headlineContent = { Text(if (p.isCity) "${p.name} — любой вокзал" else p.name) },
        supportingContent = {
            Text(listOfNotNull(if (p.isCity) "город" else "станция", p.region,
                if (p.isCity && p.radiusKm > 0) "≈ ${"%.0f".format(p.radiusKm)} км" else null).joinToString(" · "), fontSize = 12.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        },
        leadingContent = { Icon(icon, null) },
        trailingContent = {
            IconButton(onClick = { vm.toggleFavorite(p) }) {
                Icon(if (vm.isFavorite(p)) Icons.Filled.Star else Icons.Filled.StarBorder, "Избранное",
                    tint = if (vm.isFavorite(p)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        modifier = Modifier.clickable { onPick(p) }
    )
}
