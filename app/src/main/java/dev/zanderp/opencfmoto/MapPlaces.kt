// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * The place model, relocated to the Overtake library (Stage 0b). This `typealias` points the fork's
 * `MapPlace` at [dev.overtake.maps.model.MapPlace], whose field shape (name, lat, lon, category,
 * subtitle, prominence) mirrors the fork's original EXACTLY — so every positional/named construction,
 * the [MapPlaces] save/load below, and the [NominatimSearch] scorer compile unchanged. `prominence`
 * is a transient, NON-persisted ranking signal (Photon/Nominatim `importance` + settlement extent),
 * kept last so positional constructors of the five durable fields are unaffected; save/load writes
 * only those five, so a reloaded favourite/recent comes back at 0.0 (boosted by being pinned, not this).
 */
typealias MapPlace = dev.overtake.maps.model.MapPlace

/**
 * A one-tap POI category chip, relocated to the Overtake library. This `typealias` (formerly a
 * top-level alias in `NominatimSearch.kt`, which moved into the library in Stage 1) keeps the fork's
 * `PoiChip` name resolvable in package `dev.zanderp.opencfmoto` — so the dash's `runPoi(chip: PoiChip)`
 * / `runDashPoi(chip: PoiChip)` and the library's `NominatimSearch.POI_CHIPS` (a `List<PoiChip>`)
 * compile unchanged. Fields `label` + `query` mirror the fork's original.
 */
typealias PoiChip = dev.overtake.maps.model.PoiChip

/**
 * Favorites, map markers, and recent places/tracks for OpenCfMoto Map / GPX (local only).
 */
object MapPlaces {
    private const val PREFS = "opencfmoto_map_places"
    private const val KEY_FAV = "favorites"
    private const val KEY_MARKERS = "markers"
    private const val KEY_HISTORY = "history"
    private const val KEY_PARKED = "parked"
    private const val KEY_HOME = "home"
    private const val MAX_HISTORY = 30

    /** Bumped on every write so open map UIs can refresh pins/lists live. */
    @Volatile
    var revision: Int = 0
        private set

    fun favorites(ctx: Context): List<MapPlace> = load(ctx, KEY_FAV)
    fun markers(ctx: Context): List<MapPlace> = load(ctx, KEY_MARKERS)
    fun history(ctx: Context): List<MapPlace> = load(ctx, KEY_HISTORY)

    /** Last “parked here” pin (at most one). */
    fun parked(ctx: Context): MapPlace? = load(ctx, KEY_PARKED).firstOrNull()

    /** Saved “Home” pin (navigate back from anywhere). */
    fun home(ctx: Context): MapPlace? = load(ctx, KEY_HOME).firstOrNull()

    fun setParked(ctx: Context, place: MapPlace) {
        // Replace previous parked pin (only one active parking spot).
        parked(ctx)?.let { old ->
            removeMarker(ctx, old)
            removeHistory(ctx, old)
        }
        val pin = place.copy(category = "parking", name = place.name.ifBlank { "Parked" })
        save(ctx, KEY_PARKED, listOf(pin))
        addMarker(ctx, pin)
        pushHistory(ctx, pin.copy(subtitle = "Parked here"))
    }

    fun clearParked(ctx: Context) {
        val p = parked(ctx)
        p?.let {
            removeMarker(ctx, it)
            removeHistory(ctx, it)
        }
        // Ghost “Parked” rows (history left after an earlier clear / stale snapshot).
        if (p == null) {
            save(ctx, KEY_HISTORY, history(ctx).filterNot { it.category == "parking" })
        }
        save(ctx, KEY_PARKED, emptyList())
    }

    /** True when [place] is the active parked bike pin. */
    fun isParked(ctx: Context, place: MapPlace): Boolean {
        val p = parked(ctx) ?: return false
        return same(p, place)
    }

    fun setHome(ctx: Context, place: MapPlace) {
        val home = place.copy(category = "home", name = place.name.ifBlank { "Home" }, subtitle = "Home")
        save(ctx, KEY_HOME, listOf(home))
        addFavorite(ctx, home)
        addMarker(ctx, home)
        pushHistory(ctx, home)
    }

    fun clearHome(ctx: Context) {
        save(ctx, KEY_HOME, emptyList())
    }

    /** Full replace used by [MapDataBackup] import. */
    fun replaceAll(
        ctx: Context,
        favorites: List<MapPlace>,
        markers: List<MapPlace>,
        history: List<MapPlace>,
        parked: MapPlace?,
        home: MapPlace? = home(ctx),
    ) {
        save(ctx, KEY_FAV, favorites.take(100))
        save(ctx, KEY_MARKERS, markers.take(50))
        save(ctx, KEY_HISTORY, history.take(MAX_HISTORY))
        save(ctx, KEY_PARKED, listOfNotNull(parked))
        save(ctx, KEY_HOME, listOfNotNull(home))
    }

    fun addFavorite(ctx: Context, place: MapPlace) {
        save(ctx, KEY_FAV, (listOf(place) + favorites(ctx).filterNot { same(it, place) }).take(100))
    }

    fun removeFavorite(ctx: Context, place: MapPlace) {
        save(ctx, KEY_FAV, favorites(ctx).filterNot { same(it, place) })
    }

    fun addMarker(ctx: Context, place: MapPlace) {
        save(ctx, KEY_MARKERS, (listOf(place) + markers(ctx).filterNot { same(it, place) }).take(50))
    }

    fun removeMarker(ctx: Context, place: MapPlace) {
        save(ctx, KEY_MARKERS, markers(ctx).filterNot { same(it, place) })
    }

    fun pushHistory(ctx: Context, place: MapPlace) {
        save(ctx, KEY_HISTORY, (listOf(place) + history(ctx).filterNot { same(it, place) }).take(MAX_HISTORY))
    }

    fun removeHistory(ctx: Context, place: MapPlace) {
        save(ctx, KEY_HISTORY, history(ctx).filterNot { same(it, place) })
    }

    /**
     * Remove a place from parked / home / markers / favorites / history in one shot
     * (search Delete, hub Delete, etc.).
     */
    fun deletePlace(ctx: Context, place: MapPlace) {
        if (isParked(ctx, place) || place.category == "parking") {
            clearParked(ctx)
        }
        home(ctx)?.let { h ->
            if (same(h, place)) clearHome(ctx)
        }
        removeMarker(ctx, place)
        removeFavorite(ctx, place)
        removeHistory(ctx, place)
    }

    private fun same(a: MapPlace, b: MapPlace) =
        kotlin.math.abs(a.lat - b.lat) < 1e-5 && kotlin.math.abs(a.lon - b.lon) < 1e-5

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun load(ctx: Context, key: String): List<MapPlace> {
        val raw = prefs(ctx).getString(key, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        MapPlace(
                            name = o.optString("name"),
                            lat = o.getDouble("lat"),
                            lon = o.getDouble("lon"),
                            category = o.optString("category"),
                            subtitle = o.optString("subtitle"),
                        ),
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun save(ctx: Context, key: String, list: List<MapPlace>) {
        val arr = JSONArray()
        for (p in list) {
            arr.put(
                JSONObject()
                    .put("name", p.name)
                    .put("lat", p.lat)
                    .put("lon", p.lon)
                    .put("category", p.category)
                    .put("subtitle", p.subtitle),
            )
        }
        prefs(ctx).edit().putString(key, arr.toString()).apply()
        revision++
    }
}
