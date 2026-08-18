// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Context
import dev.overtake.maps.MapProvider
import dev.overtake.maps.OfflineManager
import dev.overtake.maps.OvertakeMaps
import dev.overtake.maps.OvertakeMapsConfig
import dev.overtake.maps.RendererKind
import dev.overtake.maps.contract.MapRenderer
import dev.overtake.maps.contract.Router
import dev.overtake.maps.model.Route

/**
 * Fork-side bridge to the Overtake routing engine (Stage 2 of the map-subsystem extraction). Two
 * small concerns, both kept HERE so the rest of the fork touches the library through one seam:
 *
 *  1. **The `GpxPoint` ↔ `GeoPoint` boundary.** The routing library speaks the neutral
 *     [dev.overtake.maps.model.GeoPoint]; the fork's GPX stack ([GpxNav] / [GpxParser] / [GpxTrack])
 *     keeps speaking [GpxPoint]. The ONLY place a library route crosses into GPX-land is when
 *     [GpxNav] consumes a route's geometry — [routeToTrack] does that single conversion (it replaces
 *     the old `OsrmRouter.toTrack`, which moved to the library minus its `GpxTrack` return type).
 *  2. **Building the [Router]** from the fork's effective config (User-Agent, filesDir, ORS key), so
 *     every route caller drives the exact same engine (and the same key resolution) the search path
 *     already uses. Built fresh per request so a mid-session ORS-key change is picked up, exactly as
 *     the fork's old `OfflineRouter.effectiveOrsKey(ctx)` read it live.
 *
 * Note: the library's `GeoPoint` is always FULLY QUALIFIED in fork code — the map UI imports
 * `org.osmdroid.util.GeoPoint`, a name clash — but this file only needs [Route], so no import is.
 */

/** The rider's own ORS key if set, otherwise the app's bundled default (may be blank). */
fun effectiveOrsKey(ctx: Context): String {
    val user = MapPrefs.orsApiKey(ctx).trim()
    return if (user.isNotEmpty()) user else BuildConfig.ORS_API_KEY.trim()
}

/** The Overtake map config the fork builds its provider (renderer + routing + search) from. */
fun overtakeMapsConfig(ctx: Context): OvertakeMapsConfig = OvertakeMapsConfig(
    userAgent = AppHttp.USER_AGENT,
    filesDir = ctx.filesDir,
    defaultOrsApiKey = effectiveOrsKey(ctx),
    // MapLibre style/tile HTTP rides the same uplink-pinned OkHttp client the fork holds while bound to
    // the bike's internet-less Wi‑Fi (rebuilt live as the uplink changes). Used only by the renderer.
    okHttpClientProvider = { AppHttp.mapLibreOkHttpClient() },
)

/**
 * The extracted map renderer (Stage 3): MapLibre GL vector or osmdroid raster per [rendererKind]. The
 * caller attaches it to its own host ViewGroup and drives its lifecycle + overlays. Same config seam
 * the routing + search + offline paths use.
 */
fun overtakeRenderer(ctx: Context, rendererKind: RendererKind): MapRenderer =
    (
        OvertakeMaps.create(ctx, overtakeMapsConfig(ctx).copy(rendererKind = rendererKind))
            as MapProvider.Native
    ).renderer

/**
 * The extracted routing engine (offline BRouter / Overpass graph → Valhalla / ORS / OSRM, with the
 * connectivity-aware chooser). Same engine the dash + cockpit search paths use.
 */
fun overtakeRouter(ctx: Context): Router =
    (OvertakeMaps.create(ctx, overtakeMapsConfig(ctx)) as MapProvider.Native).router

/**
 * The extracted offline-data manager: downloaded areas (+ their sizes), download/delete of an area
 * (around me / a searched place, detail level), and the bike-dashboard raster cache size/clear. Same
 * config seam the routing + search paths use, so every offline operation drives the shared engine.
 */
fun overtakeOffline(ctx: Context): OfflineManager =
    (OvertakeMaps.create(ctx, overtakeMapsConfig(ctx)) as MapProvider.Native).offline

/**
 * Build a [GpxTrack] from a library [Route]'s geometry so [GpxNav] can drive turn-by-turn over it.
 * This is the one `GeoPoint` → `GpxPoint` conversion the fork performs.
 */
fun routeToTrack(name: String, route: Route): GpxTrack =
    GpxTrack(
        name = name,
        points = route.points.map { GpxPoint(lat = it.lat, lon = it.lon, ele = it.ele) },
        waypoints = emptyList(),
    )
