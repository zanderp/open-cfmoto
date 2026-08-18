// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import dev.overtake.maps.search.OverpassSearch
import org.json.JSONObject
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * Lightweight Overpass helpers: nearby maxspeed (road restriction lite) and corridor POIs.
 * Phone internet required; results can be cached for the ride.
 *
 * The POI-CATEGORY search (nearby-by-tag, the search chips' "what's near me") moved to the Overtake
 * library ([OverpassSearch]) in the map-subsystem extraction (Stage 1); [nearbyByTag] / [nearbyByTagAsync]
 * here now DELEGATE to it, so the dash callers stay unchanged. The maxspeed + route-corridor helpers
 * stay here: they speak the app's own `GpxPoint` / `MapUnits` (dash + GPX concerns), not the library's
 * neutral models, so they were out of scope for the maps library.
 */
object OverpassClient {
    private const val ENDPOINT = "https://overpass-api.de/api/interpreter"

    data class SpeedLimit(val kmh: Int?, val label: String)

    // Cache maxspeed by ~110 m cell for the session so we don't re-query the same road.
    private const val SPEED_CACHE_TTL_MS = 10 * 60 * 1000L
    private val speedCache = HashMap<String, Pair<Long, SpeedLimit>>()

    fun maxspeedAsync(
        lat: Double,
        lon: Double,
        onResult: (SpeedLimit) -> Unit,
    ) {
        thread(name = "overpass-speed") {
            onResult(runCatching { maxspeedNear(lat, lon) }.getOrElse { SpeedLimit(null, "—") })
        }
    }

    fun corridorPoisAsync(
        points: List<GpxPoint>,
        onResult: (List<MapPlace>) -> Unit,
        onError: (String) -> Unit,
    ) {
        thread(name = "overpass-poi") {
            try {
                onResult(corridorPois(points))
            } catch (e: Exception) {
                onError(e.message ?: "POI fetch failed")
            }
        }
    }

    /**
     * Real "what's near me" category search: finds OSM features carrying a tag (e.g. amenity=fuel)
     * within a radius of the rider, nearest first. This is what the category chips need — actual
     * fuel stations / cafes around you, not a text search for the word "fuel".
     *
     * The implementation now lives in the Overtake library ([OverpassSearch]); this delegates so the
     * existing dash callers compile and behave unchanged.
     */
    fun nearbyByTagAsync(
        tag: String,
        lat: Double,
        lon: Double,
        radiusM: Int = 6_000,
        maxResults: Int = 30,
        onResult: (List<MapPlace>) -> Unit,
        onError: (String) -> Unit,
    ) = OverpassSearch.nearbyByTagAsync(tag, lat, lon, radiusM, maxResults, onResult, onError)

    fun nearbyByTag(
        tag: String,
        lat: Double,
        lon: Double,
        radiusM: Int = 6_000,
        maxResults: Int = 30,
    ): List<MapPlace> = OverpassSearch.nearbyByTag(tag, lat, lon, radiusM, maxResults)

    fun maxspeedNear(lat: Double, lon: Double, radiusM: Int = 25): SpeedLimit {
        val key = "%.3f,%.3f".format(lat, lon)
        synchronized(speedCache) {
            speedCache[key]?.let { (ts, v) ->
                if (System.currentTimeMillis() - ts < SPEED_CACHE_TTL_MS) return v
            }
        }
        val q = """
            [out:json][timeout:12];
            way(around:$radiusM,$lat,$lon)["maxspeed"];
            out tags center 1;
        """.trimIndent()
        val els = post(q).optJSONArray("elements")
        val result = if (els == null || els.length() == 0) {
            SpeedLimit(null, "—")
        } else {
            val tags = els.getJSONObject(0).optJSONObject("tags")
            val raw = tags?.optString("maxspeed")?.trim().orEmpty()
            if (raw.isEmpty()) SpeedLimit(null, "—") else SpeedLimit(parseMaxspeedKmh(raw), raw)
        }
        synchronized(speedCache) { speedCache[key] = System.currentTimeMillis() to result }
        return result
    }

    fun corridorPois(points: List<GpxPoint>, maxResults: Int = 40): List<MapPlace> {
        if (points.isEmpty()) return emptyList()
        val sample = subsample(points, 60)
        var minLat = sample.first().lat
        var maxLat = sample.first().lat
        var minLon = sample.first().lon
        var maxLon = sample.first().lon
        for (p in sample) {
            minLat = min(minLat, p.lat)
            maxLat = max(maxLat, p.lat)
            minLon = min(minLon, p.lon)
            maxLon = max(maxLon, p.lon)
        }
        // ~300 m pad
        val pad = 0.003
        val south = minLat - pad
        val north = maxLat + pad
        val west = minLon - pad
        val east = maxLon + pad
        val q = """
            [out:json][timeout:25];
            (
              node["amenity"~"fuel|parking|cafe|restaurant|hospital|atm"]($south,$west,$north,$east);
              node["tourism"~"hotel|viewpoint"]($south,$west,$north,$east);
            );
            out body $maxResults;
        """.trimIndent()
        val els = post(q).optJSONArray("elements") ?: return emptyList()
        return buildList {
            for (i in 0 until els.length()) {
                val o = els.getJSONObject(i)
                if (!o.has("lat") || !o.has("lon")) continue
                val tags = o.optJSONObject("tags") ?: continue
                val kind = tags.optString("amenity").ifBlank { tags.optString("tourism") }
                val name = tags.optString("name").ifBlank { kind.replaceFirstChar { it.uppercase() } }
                add(
                    MapPlace(
                        name = name,
                        lat = o.getDouble("lat"),
                        lon = o.getDouble("lon"),
                        category = kind,
                        subtitle = "OSM $kind",
                    ),
                )
            }
        }.distinctBy { "${"%.5f".format(it.lat)},${"%.5f".format(it.lon)}" }
            .take(maxResults)
    }

    fun parseMaxspeedKmh(raw: String): Int? {
        val s = raw.lowercase().trim()
        when (s) {
            "none", "signals", "variable", "walk" -> return null
        }
        if (s.endsWith("mph")) {
            val n = s.removeSuffix("mph").trim().toDoubleOrNull() ?: return null
            return (n * 1.60934).toInt()
        }
        val digits = s.takeWhile { it.isDigit() || it == '.' }
        return digits.toDoubleOrNull()?.toInt()
    }

    /** Pick the posted limit closest in bearing / distance for display (km/h). */
    fun formatLimit(limit: SpeedLimit, units: MapUnits): String {
        val kmh = limit.kmh ?: return "—"
        return when (units) {
            MapUnits.METRIC -> "$kmh"
            MapUnits.IMPERIAL -> "${(kmh * 0.621371).toInt()}"
        }
    }

    private fun subsample(points: List<GpxPoint>, max: Int): List<GpxPoint> {
        if (points.size <= max) return points
        val step = points.size / max
        return points.filterIndexed { i, _ -> i % step == 0 || i == points.lastIndex }
    }

    private fun post(query: String): JSONObject {
        val body = "data=" + AppHttp.encode(query)
        // Overpass fair-use: keep the request rate modest from a single client.
        AppHttp.throttle("overpass-api.de", 1_000)
        return JSONObject(AppHttp.postForm(ENDPOINT, body))
    }

    /** Rough metres between two lat/lon — used by tests / callers. */
    fun approxMetres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = abs(lat1 - lat2) * 111_320.0
        val dLon = abs(lon1 - lon2) * 111_320.0 * cos(Math.toRadians((lat1 + lat2) / 2))
        return kotlin.math.hypot(dLat, dLon)
    }
}
