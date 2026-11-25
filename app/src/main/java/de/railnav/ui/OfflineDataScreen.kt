package de.railnav.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Known GTFS feeds offered as checkboxes. */
data class FeedPreset(val url: String, val title: String, val size: String, val note: String, val heavy: Boolean = false)

val FEED_PRESETS = listOf(
    FeedPreset("https://download.gtfs.de/germany/fv_free/latest.zip", "Германия — поезда дальнего следования",
        "0,5 МБ", "ICE, IC/EC, FlixTrain, ночные"),
    FeedPreset("https://download.gtfs.de/germany/rv_free/latest.zip", "Германия — региональные поезда",
        "15 МБ", "RE, RB, S-Bahn по всей Германии"),
    FeedPreset("https://download.gtfs.de/germany/nv_free/latest.zip", "Германия — местный транспорт",
        "280 МБ", "автобусы, трамваи, U-Bahn; очень большой объём, может не хватить памяти телефона", heavy = true),
)

private val dmy = DateTimeFormatter.ofPattern("dd.MM.yyyy")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineDataScreen(vm: AppViewModel) {
    val s = vm.settings
    var selected by remember { mutableStateOf(s.feeds.toSet()) }
    var custom by remember { mutableStateOf(s.feeds.filter { u -> FEED_PRESETS.none { it.url == u } }.joinToString("\n")) }
    var showCustom by remember { mutableStateOf(custom.isNotBlank()) }
    var auto by remember { mutableStateOf(s.offlineAutoUpdate) }
    LaunchedEffect(vm.offlineInfo) { vm.refreshOfflineStatus() }

    fun save() {
        val list = FEED_PRESETS.map { it.url }.filter { it in selected } + custom.lines().map { it.trim() }.filter { it.startsWith("http") }
        s.feeds = list
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Офлайн-расписание") },
            navigationIcon = { IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {

            // Status
            val until = vm.offlineValidUntil
            val stale = until != null && until.isBefore(LocalDate.now().plusDays(7))
            Card(colors = CardDefaults.cardColors(containerColor = when {
                vm.offlineInfo == null -> MaterialTheme.colorScheme.surfaceVariant
                stale -> Color(0x33F57C00)
                else -> Color(0x332E7D32)
            })) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                    Icon(if (vm.offlineInfo != null && !stale) Icons.Filled.CheckCircle else Icons.Filled.Warning, null,
                        tint = if (vm.offlineInfo != null && !stale) Color(0xFF2E7D32) else Color(0xFFF57C00))
                    Spacer(Modifier.width(10.dp))
                    Column {
                        if (vm.offlineInfo == null) {
                            Text("Данные не загружены", fontWeight = FontWeight.Medium)
                            Text("Без сети приложение сможет показать только сохранённые поездки.", fontSize = 13.sp)
                        } else {
                            Text("Загружено · ${vm.offlineSizeMb} МБ", fontWeight = FontWeight.Medium)
                            vm.offlineImportedAt?.let { Text("Обновлено: " + Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(dmy), fontSize = 13.sp) }
                            until?.let { Text("Расписание действует до ${it.format(dmy)}" + if (stale) " — пора обновить" else "", fontSize = 13.sp) }
                            vm.offlineInfo?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
            }

            Text("Что скачать", fontWeight = FontWeight.Medium)
            for (f in FEED_PRESETS) {
                Card(Modifier.fillMaxWidth().clickable {
                    selected = if (f.url in selected) selected - f.url else selected + f.url; save()
                }) {
                    Row(Modifier.padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(f.url in selected, { selected = if (it) selected + f.url else selected - f.url; save() })
                        Column(Modifier.weight(1f)) {
                            Text(f.title, fontWeight = FontWeight.Medium)
                            Text(f.note, fontSize = 12.sp, color = if (f.heavy) Color(0xFFF57C00) else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(f.size, fontSize = 13.sp, modifier = Modifier.padding(end = 12.dp))
                    }
                }
            }
            TextButton(onClick = { showCustom = !showCustom }) { Text(if (showCustom) "Скрыть свои ссылки" else "Свои ссылки на GTFS (другие страны)…") }
            if (showCustom) {
                OutlinedTextField(custom, { custom = it; save() }, Modifier.fillMaxWidth(),
                    label = { Text("ссылки на .zip, по одной на строку") }, textStyle = MaterialTheme.typography.bodySmall, minLines = 2)
                Text("Каталоги фидов: gtfs.de, transitous.org/sources, mobilitydatabase.org", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Обновлять автоматически")
                    Text("раз в неделю по Wi-Fi, когда батарея не разряжена", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(auto, { auto = it; vm.setOfflineAutoUpdate(it) })
            }

            if (vm.importing) {
                val f = vm.importFraction
                if (f != null) LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            vm.importProgress?.let { Text(it, fontSize = 13.sp) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { save(); vm.startImport() }, enabled = !vm.importing && s.feeds.isNotEmpty()) {
                    Text(if (vm.offlineInfo == null) "Скачать" else "Обновить сейчас")
                }
                if (vm.offlineInfo != null) OutlinedButton(onClick = { vm.deleteOffline() }, enabled = !vm.importing) { Text("Удалить") }
            }
            Text("Скачивание и обработка занимают от нескольких секунд до пары минут. Данные: gtfs.de (CC BY 4.0).",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
        }
    }
}
