// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.app.Application
import org.maplibre.android.MapLibre

/** Process entry — installs crash capture before any Activity runs. */
class OpenCfMotoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Overtake library injection seams (Stage 0b of the map-subsystem extraction). Installed first,
        // before any map/routing/search code runs, so: (1) the library's deliberately-silent failures
        // forward into our single-session LogBus, and (2) its HTTP pins to the SAME validated (cellular)
        // uplink the fork holds while bound to the bike's internet-less Wi-Fi. Both are lazy — the
        // network lambda is only invoked when the lib actually makes a request — so ordering vs
        // AppHttp.init(this) below is safe.
        dev.overtake.OvertakeLog.logger = { tag, msg, err ->
            LogBus.log("[$tag] $msg" + (err?.let { " :: $it" } ?: ""))
        }
        dev.overtake.maps.net.OvertakeHttp.networkProvider = { AppHttp.internetNetwork() }
        // Routing (Stage 2) needs the fork's connectivity-aware chooser: proactively (re)pin the
        // cellular uplink before a request burst, and expose whether that pin is specifically cellular
        // (a diagnostics log label). Both lazy — invoked only when the lib actually routes.
        dev.overtake.maps.net.OvertakeHttp.uplinkEnsurer = { AppHttp.ensureCellularUplink() }
        dev.overtake.maps.net.OvertakeHttp.cellularPinChecker = { AppHttp.isCellularPin() }
        // Identify our client to OSM/Nominatim/Overpass (their usage policies require it), matching the
        // fork's own User-Agent, so search/POI HTTP the library makes is courteous from the first call.
        dev.overtake.maps.net.OvertakeHttp.userAgent = AppHttp.USER_AGENT
        try {
            LogBus.includeSecrets = AppSettings.includeSecretsInLogs(this)
        } catch (_: Exception) {
        }
        CrashGuard.install(this)
        CrashGuard.hydrateLogBus(this)
        // After hydrate so Share Logs still show prior crash, then stamp this process build.
        LogBus.logSessionBanner()
        AppHttp.init(this)
        try {
            AnonymousTelemetry.onAppStart(this)
        } catch (_: Exception) {
        }
        try {
            MapLibre.getInstance(this)
            // Pin MapLibre style/tile HTTP to cellular while the process is bound to bike Wi‑Fi.
            org.maplibre.android.module.http.HttpRequestUtil.setOkHttpClient(AppHttp.mapLibreOkHttpClient())
        } catch (e: Exception) {
            android.util.Log.w("OpenCfMoto", "MapLibre init failed: $e")
        }
    }
}
