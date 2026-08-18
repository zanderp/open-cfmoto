// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Context

enum class MapUnits(val label: String) {
    METRIC("Metric (km, m)"),
    IMPERIAL("Imperial (mi, ft)"),
}

enum class MapChrome(val label: String, val color: Int) {
    DARK("Dark", 0xFF101418.toInt()),
    BLACK("Black", 0xFF000000.toInt()),
    SLATE("Slate", 0xFF1A2332.toInt()),
    FOREST("Forest", 0xFF0E1A14.toInt()),
}

/**
 * How to pick roads: Fast = quickest normal roads; Fun = twistier / scenic detours. Relocated to the
 * Overtake library (Stage 0b); this `typealias` keeps `RouteMode.FAST` / `.FUN` / `.name` / `.valueOf`
 * / `.label` working unchanged.
 */
typealias RouteMode = dev.overtake.maps.model.RouteMode

/**
 * Map / GPX rider preferences (units, voice, next-stop, chrome, arrive, routing).
 * Day/night tiles reuse [NightPrefs] (same Auto/Day/Night as Android Auto Maps theme).
 */
object MapPrefs {
    private const val PREFS = "opencfmoto_map"
    private const val KEY_UNITS = "units"
    private const val KEY_VOICE = "voice_prompts"
    private const val KEY_NEXT_STOP = "show_next_stop"
    private const val KEY_AUTO_FINISH = "auto_finish_arrive"
    private const val KEY_CHROME = "chrome"
    private const val KEY_BUILDINGS_3D = "buildings_3d"
    private const val KEY_ORS_KEY = "ors_api_key"
    private const val KEY_ROUTE_MODE = "route_mode"
    private const val KEY_AVOID_TOLLS = "avoid_tolls"
    private const val KEY_AVOID_HIGHWAYS = "avoid_highways"
    private const val KEY_CIRCUIT_KM = "fun_circuit_km"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun units(ctx: Context): MapUnits {
        val name = prefs(ctx).getString(KEY_UNITS, MapUnits.METRIC.name)
        return runCatching { MapUnits.valueOf(name!!) }.getOrDefault(MapUnits.METRIC)
    }

    fun setUnits(ctx: Context, units: MapUnits) {
        prefs(ctx).edit().putString(KEY_UNITS, units.name).apply()
    }

    fun cycleUnits(ctx: Context): MapUnits {
        val next = when (units(ctx)) {
            MapUnits.METRIC -> MapUnits.IMPERIAL
            MapUnits.IMPERIAL -> MapUnits.METRIC
        }
        setUnits(ctx, next)
        return next
    }

    fun voicePrompts(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_VOICE, true)

    fun setVoicePrompts(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_VOICE, on).apply()
    }

    /** Show next waypoint / destination details in the cue and status line. */
    fun showNextStop(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_NEXT_STOP, true)

    fun setShowNextStop(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_NEXT_STOP, on).apply()
    }

    fun autoFinishOnArrive(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_AUTO_FINISH, true)

    fun setAutoFinishOnArrive(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_AUTO_FINISH, on).apply()
    }

    fun chrome(ctx: Context): MapChrome {
        val name = prefs(ctx).getString(KEY_CHROME, MapChrome.DARK.name)
        return runCatching { MapChrome.valueOf(name!!) }.getOrDefault(MapChrome.DARK)
    }

    fun setChrome(ctx: Context, chrome: MapChrome) {
        prefs(ctx).edit().putString(KEY_CHROME, chrome.name).apply()
    }

    fun cycleChrome(ctx: Context): MapChrome {
        val vals = MapChrome.entries
        val next = vals[(chrome(ctx).ordinal + 1) % vals.size]
        setChrome(ctx, next)
        return next
    }

    /** Pitch the dash map and show extruded buildings when the style supports them. */
    fun buildings3d(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_BUILDINGS_3D, true)

    fun setBuildings3d(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_BUILDINGS_3D, on).apply()
    }

    /**
     * Optional personal OpenRouteService API key (free tier). When set, routing uses ORS for
     * reliable road-snapped turn-by-turn instead of the public OSRM demo server.
     */
    fun orsApiKey(ctx: Context): String = prefs(ctx).getString(KEY_ORS_KEY, "").orEmpty().trim()

    fun setOrsApiKey(ctx: Context, key: String) {
        prefs(ctx).edit().putString(KEY_ORS_KEY, key.trim()).apply()
    }

    fun routeMode(ctx: Context): RouteMode {
        val name = prefs(ctx).getString(KEY_ROUTE_MODE, RouteMode.FAST.name)
        return runCatching { RouteMode.valueOf(name!!) }.getOrDefault(RouteMode.FAST)
    }

    fun setRouteMode(ctx: Context, mode: RouteMode) {
        prefs(ctx).edit().putString(KEY_ROUTE_MODE, mode.name).apply()
    }

    fun avoidTolls(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_AVOID_TOLLS, false)

    fun setAvoidTolls(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_AVOID_TOLLS, on).apply()
    }

    fun avoidHighways(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_AVOID_HIGHWAYS, false)

    fun setAvoidHighways(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_AVOID_HIGHWAYS, on).apply()
    }

    /** Target length for Fun → Create circuit (km). Clamped 20–150. */
    fun funCircuitKm(ctx: Context): Int =
        prefs(ctx).getInt(KEY_CIRCUIT_KM, 60).coerceIn(20, 150)

    fun setFunCircuitKm(ctx: Context, km: Int) {
        prefs(ctx).edit().putInt(KEY_CIRCUIT_KM, km.coerceIn(20, 150)).apply()
    }

    /** Snapshot used by [OfflineRouter] for every route request. */
    fun routeOptions(ctx: Context): RouteOptions = RouteOptions(
        mode = routeMode(ctx),
        avoidTolls = avoidTolls(ctx),
        avoidHighways = avoidHighways(ctx),
        maxAlternatives = 4,
        circuitKm = funCircuitKm(ctx),
    )
}

/**
 * Routing knobs passed into OSRM / ORS / fun via generators. Relocated to the Overtake library
 * (Stage 0b); this `typealias` points at [dev.overtake.maps.model.RouteOptions], whose fields
 * (mode, avoidTolls, avoidHighways, maxAlternatives, circuitKm) and computed `wantsAvoids` mirror the
 * fork's original — so `RouteOptions(...)`, `.copy(...)` and every field read compile unchanged.
 */
typealias RouteOptions = dev.overtake.maps.model.RouteOptions
