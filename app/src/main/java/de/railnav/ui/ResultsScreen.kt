package de.railnav.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.railnav.model.TripPlan

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultsScreen(vm: AppViewModel) {
    var tab by remember { mutableStateOf(0) }
    val title = vm.results.firstOrNull()?.title
        ?: listOfNotNull(vm.origin?.name, vm.destination?.name ?: vm.vias.lastOrNull()?.place?.name).joinToString(" → ")
    Scaffold(topBar = {
        TopAppBar(
            title = {
                Column {
                    Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 17.sp)
                    Text(buildString {
                        append(dayStr(vm.time)).append(", ").append(hhmm(vm.time))
                        if (vm.vias.isNotEmpty()) append(" · через ${vm.vias.size}")
                        if (vm.roundTrip) append(" · туда и обратно")
                    }, fontSize = 12.sp)
                }
            },
            navigationIcon = { IconButton(onClick = { vm.back() }) { Icon(Icons.Filled.ArrowBack, "Назад") } }
        )
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            TabRow(tab) {
                Tab(tab == 0, { tab = 0 }, text = { Text("Список") })
                Tab(tab == 1, { tab = 1 }, text = { Text("Карта (${vm.results.size})") })
            }
            if (vm.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            vm.info?.let { Banner(it) }
            RecheckNote()
            vm.error?.let { Banner(it, error = true) }
            if (tab == 0) ResultList(vm) else ResultMap(vm)
        }
    }
}

/** Reminder to double-check routes in the official app before travelling. */
@Composable
fun RecheckNote() {
    Text(
        "⚠ Маршрут построен по открытым данным — перед поездкой рекомендуется перепроверить его в DB Navigator.",
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
fun Banner(text: String, error: Boolean = false) {
    Text(
        text, Modifier.fillMaxWidth().padding(12.dp, 8.dp),
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
        fontSize = 14.sp
    )
}

@Composable
private fun ResultList(vm: AppViewModel) {
    val state = rememberLazyListState()
    LazyColumn(state = state, contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (vm.canPage && vm.results.isNotEmpty()) item {
            TextButton(onClick = { vm.page(false) }, Modifier.fillMaxWidth()) { Icon(Icons.Filled.ExpandLess, null); Text("Раньше") }
        }
        itemsIndexed(vm.results, key = { _, p -> p.id }) { i, p ->
            PlanCard(p, RouteColors[i % RouteColors.size], selected = i == vm.selectedResult) {
                vm.selectedResult = i; vm.open(p)
            }
        }
        if (vm.canPage && vm.results.isNotEmpty()) item {
            TextButton(onClick = { vm.page(true) }, Modifier.fillMaxWidth()) { Icon(Icons.Filled.ExpandMore, null); Text("Позже") }
        }
    }
}

@Composable
fun PlanCard(p: TripPlan, color: Color?, selected: Boolean = false, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        border = if (selected && color != null) BorderStroke(2.dp, color) else null,
        colors = CardDefaults.cardColors()
    ) {
        Column(Modifier.padding(12.dp)) {
            if (p.segments.size == 1) {
                JourneySummary(p.segments[0].journey, color)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${hhmm(p.dep)} → ${hhmm(p.arr)}", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Spacer(Modifier.weight(1f))
                    Text("${dayStr(p.dep)} – ${dayStr(p.arr)}", fontSize = 12.sp)
                }
                Text("Всего: ${durStr(((p.arr - p.dep) / 60000).toInt())}, пересадок ${p.transfers}", fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                p.segments.forEachIndexed { i, s ->
                    Text("${s.from.name} → ${s.to.name}", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                    Text("${hhmm(s.journey.dep)} – ${hhmm(s.journey.arr)} · ${durStr(s.journey.durationMin)}", fontSize = 13.sp)
                    JourneyBar(s.journey, Modifier.padding(vertical = 4.dp))
                    if (i < p.segments.size - 1) {
                        val m = p.stayMin(i)
                        Text(
                            "⏱ В ${s.to.name}: ${durStr(m)}" + if (p.segments[i + 1].maxStayExceeded) " (больше желаемого)" else "",
                            color = if (m < 0) Color(0xFFD32F2F) else MaterialTheme.colorScheme.tertiary, fontSize = 13.sp,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

/** Small card for the map carousel: times, duration, transfers and a bar per segment. */
@Composable
fun CompactPlanCard(p: TripPlan, color: Color, selected: Boolean, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        border = if (selected) BorderStroke(2.dp, color) else null,
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(10.dp).height(10.dp).background(color, androidx.compose.foundation.shape.CircleShape))
                Spacer(Modifier.width(6.dp))
                Text("${hhmm(p.dep)} → ${hhmm(p.arr)}", fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 1, softWrap = false)
                Spacer(Modifier.weight(1f))
                Text(durStr(((p.arr - p.dep) / 60000).toInt()), fontSize = 13.sp, maxLines = 1, softWrap = false)
            }
            Text(
                (if (p.segments.size > 1) "${p.segments.size} участка · " else "") + "пересадок ${p.transfers}" +
                    if (selected) " · нажмите ещё раз — подробнее" else "",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            p.segments.forEach { JourneyBar(it.journey, Modifier.padding(top = 4.dp)) }
        }
    }
}

@Composable
private fun ResultMap(vm: AppViewModel) {
    val listState = rememberLazyListState()
    LaunchedEffect(vm.selectedResult) {
        if (vm.results.isNotEmpty()) listState.animateScrollToItem(vm.selectedResult.coerceIn(0, vm.results.size - 1))
    }
    Box(Modifier.fillMaxSize()) {
        RouteMap(vm.results, vm.selectedResult, { vm.selectedResult = it }, Modifier.fillMaxSize(), bottomPaddingDp = 180)
        LazyRow(
            state = listState,
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(vm.results, key = { _, p -> p.id }) { i, p ->
                Box(Modifier.width(290.dp)) {
                    CompactPlanCard(p, RouteColors[i % RouteColors.size], selected = i == vm.selectedResult) {
                        if (vm.selectedResult == i) vm.open(p) else vm.selectedResult = i
                    }
                }
            }
        }
    }
}
