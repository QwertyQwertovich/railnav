package de.railnav.ui

import android.graphics.RectF
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.gson.JsonPrimitive
import de.railnav.model.LatLon
import de.railnav.model.TripPlan
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.style.expressions.Expression.eq
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.literal
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineDasharray
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.PropertyFactory.textAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.textAnchor
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.PropertyFactory.textFont
import org.maplibre.android.style.layers.PropertyFactory.textHaloColor
import org.maplibre.android.style.layers.PropertyFactory.textHaloWidth
import org.maplibre.android.style.layers.PropertyFactory.textOffset
import org.maplibre.android.style.layers.PropertyFactory.textOptional
import org.maplibre.android.style.layers.PropertyFactory.textSize
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

private const val STYLE_LIGHT = "https://tiles.openfreemap.org/styles/positron"
private const val STYLE_DARK = "https://tiles.openfreemap.org/styles/dark"
private const val SRC_ROUTES = "routes"
private const val SRC_STOPS = "stops"
private const val SRC_INTER = "inter"
private const val SRC_TRAINS = "trains"

private var cacheConfigured = false

/**
 * Vector map (MapLibre + OpenFreeMap) showing all [plans] at once in their own colours.
 * The [selected] plan is drawn on top with a white casing and labelled stops. Tap a line to select it.
 * Viewed tiles go to MapLibre's ambient cache and stay available offline.
 */
@Composable
fun RouteMap(plans: List<TripPlan>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, bottomPaddingDp: Int = 24) {
    val ctx = LocalContext.current
    val density = ctx.resources.displayMetrics.density
    val pad = (40 * density).toInt()
    val padBottom = ((bottomPaddingDp + 24) * density).toInt()
    val dark = isSystemInDarkTheme()
    val mapView = remember {
        MapLibre.getInstance(ctx.applicationContext)
        MapLayers.load(ctx.applicationContext)
        if (!cacheConfigured) {
            cacheConfigured = true
            OfflineManager.getInstance(ctx.applicationContext).setMaximumAmbientCacheSize(400L * 1024 * 1024, null)
        }
        MapView(ctx).apply { onCreate(null) }
    }
    var ready by remember { mutableStateOf<Pair<MapLibreMap, Style>?>(null) }
    val select by rememberUpdatedState(onSelect)
    val fittedFor = remember { arrayOfNulls<Any>(1) }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> {}
            }
        }
        lifecycle.addObserver(obs) // replays START/RESUME for the current state
        onDispose {
            lifecycle.removeObserver(obs)
            val st = lifecycle.currentState
            if (st.isAtLeast(Lifecycle.State.RESUMED)) mapView.onPause()
            if (st.isAtLeast(Lifecycle.State.STARTED)) mapView.onStop()
            mapView.onDestroy()
        }
    }

    LaunchedEffect(dark) {
        mapView.getMapAsync { map ->
            map.uiSettings.isRotateGesturesEnabled = false
            map.uiSettings.isTiltGesturesEnabled = false
            map.uiSettings.isLogoEnabled = false
            map.uiSettings.setAttributionMargins(8, 0, 0, 8)
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(51.0, 10.5), 5.0))
            map.setStyle(Style.Builder().fromUri(if (dark) STYLE_DARK else STYLE_LIGHT)) { style ->
                addLayers(style, dark)
                fittedFor[0] = null
                ready = map to style
            }
            map.addOnMapClickListener { ll ->
                val p = map.projection.toScreenLocation(ll)
                val r = 24f
                val hit = map.queryRenderedFeatures(RectF(p.x - r, p.y - r, p.x + r, p.y + r), "route-other", "route-sel", "route-walk")
                    .firstOrNull()?.getNumberProperty("i")?.toInt()
                if (hit != null) { select(hit); true } else false
            }
        }
    }

    LaunchedEffect(ready, plans, selected) {
        val (map, style) = ready ?: return@LaunchedEffect
        (style.getSource(SRC_ROUTES) as? GeoJsonSource)?.setGeoJson(routeFeatures(plans, selected))
        (style.getSource(SRC_STOPS) as? GeoJsonSource)?.setGeoJson(stopFeatures(plans.getOrNull(selected)))
        (style.getSource(SRC_INTER) as? GeoJsonSource)?.setGeoJson(interFeatures(plans.getOrNull(selected)))
        if (fittedFor[0] !== plans) {
            fittedFor[0] = plans
            val pts = plans.flatMap { p -> p.allJourneys.flatMap { j -> j.legs.flatMap { listOf(it.from.pos, it.to.pos) } } }
                .filter { it.lat != 0.0 }
            if (pts.size >= 2) {
                val b = LatLngBounds.Builder().includes(pts.map { LatLng(it.lat, it.lon) }).build()
                runCatching { map.moveCamera(CameraUpdateFactory.newLatLngBounds(b, pad, pad, pad, padBottom)) }
            } else if (pts.size == 1) map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(pts[0].lat, pts[0].lon), 11.0))
        }
    }

    // Train positions by (live) schedule, refreshed every 20 s.
    LaunchedEffect(ready, plans, selected) {
        val (_, style) = ready ?: return@LaunchedEffect
        while (true) {
            (style.getSource(SRC_TRAINS) as? GeoJsonSource)?.setGeoJson(trainFeatures(plans, selected, System.currentTimeMillis()))
            kotlinx.coroutines.delay(20_000)
        }
    }

    LaunchedEffect(ready, MapLayers.orm, MapLayers.opnv) {
        val (_, style) = ready ?: return@LaunchedEffect
        runCatching { MapLayers.apply(style) }
    }

    androidx.compose.foundation.layout.Box(modifier) {
        AndroidView(factory = { mapView }, modifier = Modifier.matchParentSize())
        MapLayersButton(Modifier.align(androidx.compose.ui.Alignment.TopEnd))
    }
}

private fun addLayers(style: Style, dark: Boolean) {
    style.addSource(GeoJsonSource(SRC_ROUTES, FeatureCollection.fromFeatures(emptyList())))
    style.addSource(GeoJsonSource(SRC_STOPS, FeatureCollection.fromFeatures(emptyList())))
    style.addSource(GeoJsonSource(SRC_INTER, FeatureCollection.fromFeatures(emptyList())))
    style.addSource(GeoJsonSource(SRC_TRAINS, FeatureCollection.fromFeatures(emptyList())))
    val casing = if (dark) "#101418" else "#FFFFFF"
    style.addLayer(LineLayer("route-other", SRC_ROUTES).withFilter(eq(get("kind"), literal("other"))).withProperties(
        lineColor(get("color")), lineWidth(4f), lineOpacity(0.55f), lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)))
    style.addLayer(LineLayer("route-casing", SRC_ROUTES).withFilter(eq(get("kind"), literal("sel"))).withProperties(
        lineColor(casing), lineWidth(10f), lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)))
    style.addLayer(LineLayer("route-sel", SRC_ROUTES).withFilter(eq(get("kind"), literal("sel"))).withProperties(
        lineColor(get("color")), lineWidth(6f), lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)))
    style.addLayer(LineLayer("route-walk", SRC_ROUTES).withFilter(eq(get("kind"), literal("walk"))).withProperties(
        lineColor("#808080"), lineWidth(3f), lineDasharray(arrayOf(1.5f, 1.5f))))
    // Intermediate stops: only when zoomed in enough to not clutter the map
    style.addLayer(CircleLayer("inter", SRC_INTER).withProperties(
        circleRadius(3.5f), circleColor(casing), circleStrokeColor(get("color")), circleStrokeWidth(2f)).apply { minZoom = 8f })
    style.addLayer(SymbolLayer("inter-labels", SRC_INTER).withProperties(
        textField(get("label")), textFont(arrayOf("Noto Sans Regular")), textSize(11f),
        textColor(if (dark) "#BBBBBB" else "#505050"), textHaloColor(casing), textHaloWidth(1.4f),
        textAnchor(Property.TEXT_ANCHOR_LEFT), textOffset(arrayOf(0.8f, 0f)), textOptional(true)).apply { minZoom = 10f })
    style.addLayer(CircleLayer("stops", SRC_STOPS).withProperties(
        circleRadius(get("r")), circleColor(casing), circleStrokeColor(get("color")), circleStrokeWidth(3f)))
    style.addLayer(SymbolLayer("stop-labels", SRC_STOPS).withProperties(
        textField(get("label")), textFont(arrayOf("Noto Sans Regular")), textSize(12f),
        textColor(if (dark) "#EEEEEE" else "#202020"), textHaloColor(casing), textHaloWidth(1.6f),
        textAnchor(Property.TEXT_ANCHOR_LEFT), textOffset(arrayOf(0.9f, 0f)), textOptional(true), textAllowOverlap(false)))
    // Trains: coloured dot with white ring and the line name above
    style.addLayer(CircleLayer("trains", SRC_TRAINS).withProperties(
        circleRadius(9f), circleColor(get("color")), circleStrokeColor("#FFFFFF"), circleStrokeWidth(3f)))
    // white arrow inside the dot, rotated in the direction of travel
    style.addImage("train-arrow", arrowBitmap(), true)
    style.addLayer(SymbolLayer("train-arrows", SRC_TRAINS).withProperties(
        org.maplibre.android.style.layers.PropertyFactory.iconImage("train-arrow"),
        org.maplibre.android.style.layers.PropertyFactory.iconRotate(get("bearing")),
        org.maplibre.android.style.layers.PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
        org.maplibre.android.style.layers.PropertyFactory.iconColor("#FFFFFF"),
        org.maplibre.android.style.layers.PropertyFactory.iconSize(0.55f),
        org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap(true),
        org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement(true)))
    style.addLayer(SymbolLayer("train-labels", SRC_TRAINS).withProperties(
        textField(get("label")), textFont(arrayOf("Noto Sans Bold")), textSize(12f),
        textColor(if (dark) "#FFFFFF" else "#101010"), textHaloColor(casing), textHaloWidth(2f),
        textAnchor(Property.TEXT_ANCHOR_BOTTOM), textOffset(arrayOf(0f, -1.1f)), textAllowOverlap(true)))
}

private fun interFeatures(p: TripPlan?): FeatureCollection {
    p ?: return FeatureCollection.fromFeatures(emptyList())
    val color = hex(0)
    val out = ArrayList<Feature>()
    for (l in p.allJourneys.flatMap { it.transitLegs }) for (s in l.stops) {
        if (s.lat == 0.0) continue
        val f = Feature.fromGeometry(Point.fromLngLat(s.lon, s.lat))
        f.addStringProperty("label", "${s.name}  ${hhmm(s.dep ?: s.arr)}")
        f.addStringProperty("color", color)
        out += f
    }
    return FeatureCollection.fromFeatures(out)
}

/** Where each running train of the plans should be now, interpolated between stops (live times preferred). */
private fun trainFeatures(plans: List<TripPlan>, selected: Int, now: Long): FeatureCollection {
    val out = ArrayList<Feature>()
    plans.forEachIndexed { i, p ->
        for (l in p.allJourneys.flatMap { it.transitLegs }) {
            val raw = trainPosition(l, now) ?: continue
            // snap onto the track shape so the dot sits on the line, not on a straight chord
            val shape = l.geometry()
            val k = shape.indices.minByOrNull { (shape[it].lat - raw.lat) * (shape[it].lat - raw.lat) + (shape[it].lon - raw.lon) * (shape[it].lon - raw.lon) }
            val pos = if (l.shape.size >= 2 && k != null) shape[k] else raw
            // direction of travel: along the shape around the dot
            val bearing = if (k != null && shape.size >= 2) {
                val a = shape[if (k == shape.lastIndex) k - 1 else k]; val b = shape[if (k == shape.lastIndex) k else k + 1]
                bearingDeg(a, b)
            } else 0.0
            val f = Feature.fromGeometry(Point.fromLngLat(pos.lon, pos.lat))
            f.addNumberProperty("bearing", bearing)
            f.addStringProperty("color", String.format("#%06X", productColor(l.product).toArgb() and 0xFFFFFF))
            f.addStringProperty("label", if (i == selected || plans.size == 1) l.line else "")
            out += f
        }
    }
    return FeatureCollection.fromFeatures(out)
}

private fun bearingDeg(a: LatLon, b: LatLon): Double {
    val dy = b.lat - a.lat
    val dx = (b.lon - a.lon) * Math.cos(Math.toRadians((a.lat + b.lat) / 2))
    return (Math.toDegrees(Math.atan2(dx, dy)) + 360) % 360
}

/** Upward-pointing arrow used as an SDF icon (tinted via iconColor). */
private fun arrowBitmap(): android.graphics.Bitmap {
    val n = 64
    val bmp = android.graphics.Bitmap.createBitmap(n, n, android.graphics.Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }
    val path = android.graphics.Path().apply {
        moveTo(n / 2f, 6f); lineTo(n - 10f, n - 10f); lineTo(n / 2f, n - 22f); lineTo(10f, n - 10f); close()
    }
    c.drawPath(path, p)
    return bmp
}

fun trainPosition(l: de.railnav.model.Leg, now: Long): LatLon? {
    val pts = listOf(l.from) + l.stops + listOf(l.to)
    if (pts.any { it.lat == 0.0 }) return null
    val first = l.from.dep ?: return null; val last = l.to.arr ?: return null
    if (now < first || now > last) return null
    for (k in 0 until pts.size - 1) {
        val a = pts[k]; val b = pts[k + 1]
        val dep = a.dep ?: a.arr ?: continue
        val arrA = a.arr ?: dep
        if (now in arrA..dep) return a.pos // standing at the station
        val arr = b.arr ?: b.dep ?: continue
        if (now in dep..arr) {
            val f = if (arr > dep) (now - dep).toDouble() / (arr - dep) else 0.0
            return LatLon(a.lat + (b.lat - a.lat) * f, a.lon + (b.lon - a.lon) * f)
        }
    }
    return null
}

private fun hex(i: Int) = String.format("#%06X", RouteColors[i % RouteColors.size].toArgb() and 0xFFFFFF)

private fun routeFeatures(plans: List<TripPlan>, selected: Int): FeatureCollection {
    val out = ArrayList<Feature>()
    val order = plans.indices.sortedBy { if (it == selected) 1 else 0 }
    for (i in order) for (j in plans[i].allJourneys) for (l in j.legs) {
        val pts = simplify(l.geometry().filter { it.lat != 0.0 })
        if (pts.size < 2) continue
        val f = Feature.fromGeometry(LineString.fromLngLats(pts.map { Point.fromLngLat(it.lon, it.lat) }))
        f.addNumberProperty("i", i)
        f.addStringProperty("color", if (l.isWalk) "#808080" else hex(i))
        f.addStringProperty("kind", when { l.isWalk -> "walk"; i == selected -> "sel"; else -> "other" })
        out += f
    }
    return FeatureCollection.fromFeatures(out)
}

private fun stopFeatures(p: TripPlan?): FeatureCollection {
    p ?: return FeatureCollection.fromFeatures(emptyList())
    val color = hex(0)
    val out = ArrayList<Feature>()
    val legs = p.allJourneys.flatMap { it.transitLegs }
    val seen = HashSet<String>()
    legs.forEachIndexed { k, l ->
        for ((s, dep) in listOf(l.from to true, l.to to false)) {
            if (s.lat == 0.0 || !seen.add(s.name)) continue
            val f = Feature.fromGeometry(Point.fromLngLat(s.lon, s.lat))
            val t = hhmm(if (dep) s.dep else s.arr)
            f.addStringProperty("label", "${s.name}  $t${s.platform?.let { " · пл. $it" } ?: ""}")
            f.addStringProperty("color", color)
            f.properties()?.add("r", JsonPrimitive(if (k == 0 && dep || k == legs.size - 1 && !dep) 7 else 5))
            out += f
        }
    }
    return FeatureCollection.fromFeatures(out)
}

/** Douglas–Peucker simplification (~15 m) to keep the map smooth with long train shapes. */
private fun simplify(pts: List<LatLon>, eps: Double = 0.00015): List<LatLon> {
    if (pts.size < 3) return pts
    val keep = BooleanArray(pts.size); keep[0] = true; keep[pts.size - 1] = true
    val stack = ArrayDeque<Pair<Int, Int>>(); stack.addLast(0 to pts.size - 1)
    while (stack.isNotEmpty()) {
        val (a, b) = stack.removeLast()
        var maxD = 0.0; var idx = -1
        val ax = pts[a].lon; val ay = pts[a].lat; val bx = pts[b].lon; val by = pts[b].lat
        val dx = bx - ax; val dy = by - ay; val len2 = dx * dx + dy * dy
        for (k in a + 1 until b) {
            val px = pts[k].lon - ax; val py = pts[k].lat - ay
            val d = if (len2 == 0.0) px * px + py * py else {
                val t = ((px * dx + py * dy) / len2).coerceIn(0.0, 1.0)
                val ex = px - t * dx; val ey = py - t * dy; ex * ex + ey * ey
            }
            if (d > maxD) { maxD = d; idx = k }
        }
        if (idx >= 0 && maxD > eps * eps) { keep[idx] = true; stack.addLast(a to idx); stack.addLast(idx to b) }
    }
    return pts.filterIndexed { i, _ -> keep[i] }
}
