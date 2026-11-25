package de.railnav.store

import android.content.Context
import de.railnav.model.LatLon
import de.railnav.model.Place
import de.railnav.model.distanceKm
import java.text.Normalizer

/**
 * Built-in table of ~1500 cities (Germany ≥10k inhabitants + large European cities) with an
 * effective radius derived from the municipal area (Wikidata): r = √(area/π) · 1.15, 2.5…30 km.
 * Berlin ≈ 19 km, Leipzig ≈ 11 km, Jena ≈ 7 km.
 */
object CityRadius {
    private class City(val name: String, val key: String, val pos: LatLon, val radius: Double)
    @Volatile private var cities: List<City>? = null

    private fun norm(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}"), "").replace("ß", "ss")
    private fun token(s: String) = norm(s).split(' ', '(', ',', '-', '/').firstOrNull { it.length >= 2 }.orEmpty()

    private fun load(ctx: Context): List<City> = cities ?: synchronized(this) {
        cities ?: ctx.assets.open("cities.tsv").bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.mapNotNull { l ->
                val p = l.split('\t')
                if (p.size < 4) null else City(p[0], token(p[0]), LatLon(p[1].toDouble(), p[2].toDouble()), p[3].toDouble())
            }.toList()
        }.also { cities = it }
    }

    /** Radius for a city place, or null when the city is not in the table. */
    fun lookup(ctx: Context, p: Place): Double? {
        val all = load(ctx)
        val t = token(p.name)
        // same name (first word) nearby, e.g. "Halle" ↔ "Halle (Saale)"
        all.filter { it.key == t && distanceKm(it.pos, p.pos) < 35 }.minByOrNull { distanceKm(it.pos, p.pos) }?.let { return it.radius }
        // otherwise: a table entry practically at the same spot
        return all.filter { distanceKm(it.pos, p.pos) < 3 }.minByOrNull { distanceKm(it.pos, p.pos) }?.radius
    }
}
