package de.railnav.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.railnav.model.Journey
import de.railnav.model.Segment
import de.railnav.model.TripPlan

/** Full run of one train: all stops with planned/live times; the user's part is highlighted. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripScreen(vm: AppViewModel) {
    val leg = vm.tripLeg
    Scaffold(topBar = {
        TopAppBar(
            title = {
                Column {
                    Text(leg?.line ?: "Поезд", fontSize = 18.sp, maxLines = 1)
                    leg?.direction?.let { Text("→ $it", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            },
            navigationIcon = { IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } }
        )
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (vm.tripLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            vm.tripError?.let { Banner(it, error = leg == null) }
            if (leg == null) return@Column
            val all = remember(leg) { listOf(leg.from) + leg.stops + listOf(leg.to) }
            val hl = vm.tripHighlight
            val i0 = hl?.let { h -> if (h.first == "*start*") 0 else all.indexOfFirst { it.name == h.first } } ?: -1
            val i1 = hl?.let { h -> if (h.second == "*end*") all.size - 1 else all.indexOfLast { it.name == h.second } } ?: -1
            val plan = remember(leg) {
                TripPlan("trip", leg.line, listOf(Segment(
                    de.railnav.model.Place(name = leg.from.name, lat = leg.from.lat, lon = leg.from.lon),
                    de.railnav.model.Place(name = leg.to.name, lat = leg.to.lat, lon = leg.to.lon),
                    Journey("trip", listOf(leg)), emptyList(), leg.dep)))
            }
            val plans = remember(plan) { listOf(plan) }
            RouteMap(plans, 0, {}, Modifier.fillMaxWidth().height(220.dp))
            Row(Modifier.padding(16.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
                ProductBadge(leg.product, leg.line)
                Spacer(Modifier.width(8.dp))
                Text(listOfNotNull(leg.operator, "${all.size} остановок").joinToString(" · "), fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val listState = rememberLazyListState()
            LaunchedEffect(i0) { if (i0 > 2) listState.scrollToItem(i0 - 2) }
            val color = productColor(leg.product)
            val now = System.currentTimeMillis()
            LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
                itemsIndexed(all) { i, s ->
                    val mine = i0 >= 0 && i1 >= 0 && i in i0..i1
                    val passed = (s.dep ?: s.arr ?: Long.MAX_VALUE) < now
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        StopTimes(s, width = 104.dp)
                        Box(Modifier.width(22.dp).height(44.dp), contentAlignment = Alignment.Center) {
                            Box(Modifier.width(4.dp).fillMaxSize().background(if (mine) color else color.copy(alpha = 0.35f)))
                            Box(Modifier.size(if (i == i0 || i == i1) 14.dp else 9.dp).background(
                                if (mine) color else MaterialTheme.colorScheme.surfaceVariant, CircleShape))
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(s.name, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                            fontWeight = if (i == i0 || i == i1) FontWeight.Bold else FontWeight.Normal,
                            color = when {
                                s.cancelled -> Color(0xFFD32F2F)
                                passed && !mine -> MaterialTheme.colorScheme.onSurfaceVariant
                                else -> MaterialTheme.colorScheme.onSurface
                            })
                        Platform(s, false)
                    }
                }
            }
        }
    }
}
