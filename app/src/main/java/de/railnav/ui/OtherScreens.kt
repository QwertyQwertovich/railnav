package de.railnav.ui

import android.app.AlarmManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.railnav.data.Providers

@Composable
fun SavedScreen(vm: AppViewModel) {
    LaunchedEffect(Unit) { vm.refreshSaved() }
    val now = System.currentTimeMillis()
    val (upcoming, past) = vm.saved.partition { it.arr > now }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Сохранённые поездки", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        if (vm.saved.isEmpty()) item {
            Text("Пока пусто. Откройте найденный маршрут и нажмите закладку. Сохранённые поездки доступны без сети.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (upcoming.isNotEmpty()) item { Text("Предстоящие", fontWeight = FontWeight.Medium) }
        items(upcoming, key = { it.id }) { p -> SavedItem(vm, p) }
        if (past.isNotEmpty()) item { Text("Прошедшие", fontWeight = FontWeight.Medium) }
        items(past, key = { it.id }) { p -> SavedItem(vm, p) }
    }
}

@Composable
private fun SavedItem(vm: AppViewModel, p: de.railnav.model.TripPlan) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(p.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${dayStr(p.dep)} ${hhmm(p.dep)} → ${hhmm(p.arr)}", fontSize = 13.sp)
            }
            if (p.notify) Icon(Icons.Filled.NotificationsActive, "Уведомления включены", tint = MaterialTheme.colorScheme.primary)
            IconButton(onClick = { vm.deleteSaved(p) }) { Icon(Icons.Filled.Delete, "Удалить") }
        }
        PlanCard(p, null) { vm.open(p) }
    }
}

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val s = vm.settings
    val ctx = LocalContext.current
    var provider by remember { mutableStateOf(s.provider) }
    var fallback by remember { mutableStateOf(s.offlineFallback) }
    var minTr by remember { mutableStateOf(s.minTransferMin.toFloat()) }
    var radius by remember { mutableStateOf(s.cityRadiusKm.toFloat()) }
    var nDep by remember { mutableStateOf(s.notifyBeforeDepMin.toFloat()) }
    var nArr by remember { mutableStateOf(s.notifyBeforeArrMin.toFloat()) }
    var feeds by remember { mutableStateOf(s.feeds.joinToString("\n")) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Настройки", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        Card {
            Column(Modifier.padding(12.dp)) {
                Text("Источник расписаний", fontWeight = FontWeight.Medium)
                for ((id, label) in Providers.choices) {
                    Row(Modifier.fillMaxWidth().selectable(provider == id) { provider = id; s.provider = id }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(provider == id, { provider = id; s.provider = id })
                        Text(label, fontSize = 14.sp)
                    }
                }
                if (provider == "db") Text(
                    "API bahn.de неофициальный: DB может временно блокировать частые запросы. Тогда приложение покажет ошибку — переключитесь на Transitous.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.tertiary
                )
                if (provider != "offline") Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Без сети использовать офлайн-расписание", Modifier.weight(1f), fontSize = 14.sp)
                    Switch(fallback, { fallback = it; s.offlineFallback = it })
                }
            }
        }

        Card {
            Column(Modifier.padding(12.dp)) {
                Text("Мин. время пересадки: ${minTr.toInt()} мин", fontSize = 14.sp)
                Slider(minTr, { minTr = it }, valueRange = 0f..20f, steps = 19, onValueChangeFinished = { s.minTransferMin = minTr.toInt() })
                Text("Радиус «любой вокзал города»: ${radius.toInt()} км", fontSize = 14.sp)
                Slider(radius, { radius = it }, valueRange = 3f..30f, onValueChangeFinished = { s.cityRadiusKm = radius.toInt() })
            }
        }

        Card {
            Column(Modifier.padding(12.dp)) {
                Text("Уведомления", fontWeight = FontWeight.Medium)
                Text("За ${nDep.toInt()} мин до отправления", fontSize = 14.sp)
                Slider(nDep, { nDep = it }, valueRange = 0f..60f, onValueChangeFinished = { s.notifyBeforeDepMin = nDep.toInt() })
                Text("За ${nArr.toInt()} мин до прибытия / пересадки", fontSize = 14.sp)
                Slider(nArr, { nArr = it }, valueRange = 0f..30f, onValueChangeFinished = { s.notifyBeforeArrMin = nArr.toInt() })
                if (Build.VERSION.SDK_INT >= 31 && !ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()) {
                    Text("Чтобы уведомления приходили точно в срок, разрешите точные будильники:", fontSize = 12.sp)
                    OutlinedButton(onClick = {
                        ctx.startActivity(Intent(AndroidSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}")))
                    }) { Text("Разрешить точные будильники") }
                }
            }
        }

        Card(Modifier.fillMaxWidth().clickable { vm.go(Screen.OfflineData) }) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CloudDownload, null)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Офлайн-расписание", fontWeight = FontWeight.Medium)
                    Text(vm.offlineInfo?.let { "загружено · ${vm.offlineSizeMb} МБ" } ?: "не загружено — поиск без сети недоступен",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
            }
        }
        Card {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                var cityMain by remember { mutableStateOf(s.cityMainStations) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Город — основные вокзалы", fontSize = 14.sp)
                        Text("Halle → Hbf; где нет Hbf (Jena) — главные станции. Выкл.: любая станция в радиусе.",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(cityMain, { cityMain = it; s.cityMainStations = it })
                }
            }
        }

        Card {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                var auto by remember { mutableStateOf(s.autoUpdateCheck) }
                Text("Обновления · версия ${de.railnav.store.Updates.currentVersion}", fontWeight = FontWeight.Medium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Проверять автоматически", Modifier.weight(1f), fontSize = 14.sp)
                    Switch(auto, { auto = it; s.autoUpdateCheck = it; de.railnav.store.Updates.scheduleDaily(ctx, it) })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { vm.checkForUpdate(manual = true) }) { Text("Проверить сейчас") }
                    if (vm.update != null) Button(onClick = { vm.updateDialog = true }) { Text("Обновить до ${vm.update!!.version}") }
                }
                vm.updateStatus?.let { Text(it, fontSize = 13.sp) }
            }
        }

        Text(
            "Данные: Transitous / MOTIS (transitous.org), gtfs.de, © OpenStreetMap contributors.",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlternativesScreen(vm: AppViewModel) {
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Альтернативы", fontSize = 17.sp) },
            navigationIcon = { IconButton(onClick = { vm.back() }) { Icon(Icons.Filled.ArrowBack, "Назад") } }
        )
    }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text(vm.altTitle, fontWeight = FontWeight.Medium)
                Text("Выберите вариант — он заменит оставшуюся часть маршрута, следующие участки пересчитаются.",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (vm.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                vm.error?.let { Banner(it, error = true) }
            }
            items(vm.altResults, key = { it.id }) { j ->
                Card(Modifier.fillMaxWidth().clickable { vm.applyAlternative(j) }) {
                    Column(Modifier.padding(12.dp)) { JourneySummary(j) }
                }
            }
        }
    }
}

@Suppress("unused") private val divider = @Composable { HorizontalDivider() }
