package de.railnav.ui

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet

/** Optional raster overlays: OpenRailwayMap styles and the ÖPNVKarte public transport base map. */
object MapLayers {
    val ormStyles = listOf(
        "standard" to "Инфраструктура",
        "maxspeed" to "Скорости",
        "signals" to "Сигнализация",
        "electrification" to "Электрификация",
        "gauge" to "Колея",
    )
    var orm by mutableStateOf<String?>(null)
    var opnv by mutableStateOf(false)
    private var loaded = false

    fun load(ctx: Context) {
        if (loaded) return; loaded = true
        val sp = ctx.getSharedPreferences("map_layers", Context.MODE_PRIVATE)
        orm = sp.getString("orm", null); opnv = sp.getBoolean("opnv", false)
    }

    fun save(ctx: Context) {
        ctx.getSharedPreferences("map_layers", Context.MODE_PRIVATE).edit()
            .putString("orm", orm).putBoolean("opnv", opnv).apply()
    }

    /** Adds/removes raster layers below the route lines so routes stay on top. */
    fun apply(style: Style) {
        fun remove(id: String) { style.getLayer(id)?.let { style.removeLayer(it) }; style.getSource(id)?.let { style.removeSource(it) } }
        remove("opnv"); remove("orm")
        if (opnv) {
            val ts = TileSet("2.1.0", "https://tileserver.memomaps.de/tilegen/{z}/{x}/{y}.png").apply {
                attribution = "© memomaps.de CC-BY-SA, © OpenStreetMap"; maxZoom = 18f
            }
            style.addSource(RasterSource("opnv", ts, 256))
            style.addLayerBelow(RasterLayer("opnv", "opnv"), "route-other")
        }
        orm?.let { st ->
            val ts = TileSet("2.1.0",
                "https://a.tiles.openrailwaymap.org/$st/{z}/{x}/{y}.png",
                "https://b.tiles.openrailwaymap.org/$st/{z}/{x}/{y}.png",
                "https://c.tiles.openrailwaymap.org/$st/{z}/{x}/{y}.png").apply {
                attribution = "© OpenRailwayMap (CC-BY-SA), © OpenStreetMap"; maxZoom = 19f
            }
            style.addSource(RasterSource("orm", ts, 256))
            style.addLayerBelow(RasterLayer("orm", "orm"), "route-other")
        }
    }
}

@Composable
fun MapLayersButton(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        FilledTonalIconButton(onClick = { open = true }, modifier = Modifier.padding(8.dp).size(40.dp)) {
            Icon(Icons.Filled.Layers, "Слои карты")
        }
        DropdownMenu(open, { open = false }) {
            Text("OpenRailwayMap", Modifier.padding(horizontal = 16.dp, vertical = 6.dp), fontSize = 13.sp)
            DropdownMenuItem(text = { Text("Выкл.") }, onClick = { MapLayers.orm = null; MapLayers.save(ctx); open = false },
                leadingIcon = { if (MapLayers.orm == null) Icon(Icons.Filled.Check, null) })
            for ((id, label) in MapLayers.ormStyles) DropdownMenuItem(text = { Text(label) },
                onClick = { MapLayers.orm = id; MapLayers.save(ctx); open = false },
                leadingIcon = { if (MapLayers.orm == id) Icon(Icons.Filled.Check, null) })
            HorizontalDivider()
            DropdownMenuItem(text = { Text("ÖPNVKarte (транспортная карта)") },
                onClick = { MapLayers.opnv = !MapLayers.opnv; MapLayers.save(ctx); open = false },
                leadingIcon = { if (MapLayers.opnv) Icon(Icons.Filled.Check, null) })
        }
    }
}
