// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.util.TypedValue
import android.text.TextWatcher
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import dev.overtake.maps.RendererKind
import dev.overtake.maps.contract.MapRenderer
import dev.overtake.maps.search.NominatimSearch
import dev.overtake.maps.route.offline.OfflinePoiIndex
import dev.zanderp.opencfmoto.aa.AaInput
import dev.zanderp.opencfmoto.settings.DashRenderer
import dev.zanderp.opencfmoto.settings.SettingsStore
import dev.zanderp.opencfmoto.ui.CockpitActivity
import dev.zanderp.opencfmoto.ui.Routes
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import dev.overtake.maps.model.Route

/**
 * Shared Map / GPX dash chrome: binds [R.layout.presentation_gpx] for bike Presentation
 * or phone Dash-view preview (no bike required).
 */
class GpxDashUi(
    private val context: Context,
    private val root: View,
    private val log: (String) -> Unit,
    private val isAlive: () -> Boolean,
    /** True when this dash is projected to the bike HUD: hide the phone-only back/Hub chrome. */
    private val projected: Boolean = false,
) {
    private val main = Handler(Looper.getMainLooper())
    // Routing moved to the Overtake library's suspending Router (Stage 2). This dash isn't a
    // LifecycleOwner, so it owns a Main-confined scope for those calls and cancels it in release().
    private val routeScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var dash: MapRenderer? = null
    private var voice: GpxVoice? = null
    /** Display cutout / system bar insets — applied to chrome margins, not the map (edge-to-edge). */
    private var insetL = 0
    private var insetT = 0
    private var insetR = 0
    private var insetB = 0
    private var lastNavigating = false
    private var locationListener: LocationListener? = null
    private var locationManager: LocationManager? = null
    private var sensorManager: SensorManager? = null
    private var compassListener: SensorEventListener? = null
    private var released = false

    /**
     * The renderer for THIS dash. BOTH the projected bike dash AND the phone preview now honor the
     * persisted choice (`SettingsStore.dashRenderer`, default MAPLIBRE — proven clean on the VD → H.264
     * encoder, probe commit 0c5b7c8). The phone used to hard-return MAPLIBRE, so the WYSIWYG preview
     * ignored the rider's pick and its Mapsforge gate never fired. NOTE: the library still forces
     * MapLibre whenever the host Context is an Activity (the phone preview's context is one), so on the
     * phone this choice drives the Mapsforge gate + RendererKind mapping while MapLibre stays the actual
     * engine — the documented phone-preview behavior. One-shot synchronous DataStore read at dash
     * construction (cold path, warm-cached in practice — like the [MapPrefs] reads in [bind]); never
     * ties the choice to live connectivity.
     */
    private fun resolveDashRenderer(): DashRenderer {
        return runCatching {
            runBlocking { SettingsStore(context.applicationContext).dashRenderer.first() }
        }.getOrElse {
            log("[MAP] dashRenderer read failed ($it) — defaulting MAPLIBRE")
            DashRenderer.MAPLIBRE
        }
    }

    /**
     * Overlay a "Mapsforge needs an offline map — Download" card on the empty map host (built
     * programmatically; this dash is a View layer, not Compose). The button opens the cockpit's
     * offline-maps screen. Shown only when the Mapsforge renderer is selected and no `.map` is
     * installed — the strict alternative to a silent osmdroid fallback.
     */
    private fun showMapsforgeNeedsMapPrompt(host: FrameLayout) {
        val d = context.resources.displayMetrics.density
        fun px(v: Int) = (v * d).toInt()
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xE6101418.toInt())
            setPadding(px(28), px(28), px(28), px(28))
        }
        val title = TextView(context).apply {
            text = context.getString(R.string.ovk_mf_needs_map_title)
            setTextColor(0xFFECEFF3.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val body = TextView(context).apply {
            text = context.getString(R.string.ovk_mf_needs_map_body)
            setTextColor(0xFFB6BEC8.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = px(10) }
        }
        val btn = MaterialButton(context).apply {
            text = context.getString(R.string.ovk_mf_needs_map_cta)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = px(18) }
            setOnClickListener {
                runCatching { CockpitActivity.open(context, Routes.MAPSFORGE_MAPS) }
                    .onFailure { log("[MAP] open Mapsforge maps failed: $it") }
            }
        }
        card.addView(title)
        card.addView(body)
        card.addView(btn)
        host.addView(
            card,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    /**
     * Inflate-ready [root] must be [R.layout.presentation_gpx].
     * @return false if session/track invalid (caller should show a placeholder).
     */
    fun bind(): Boolean {
        if (!GpxSession.active) {
            log("[GPX] session inactive")
            return false
        }
        val mode = GpxSession.mode
        var track = GpxSession.trackFile?.let { f ->
            runCatching { GpxParser.parse(f) }.getOrElse { err ->
                log("[GPX] parse failed: $err"); null
            }
        }
        if (mode == GpxSession.Mode.GPX && track == null) {
            log("[GPX] no track")
            return false
        }
        var gpxOriented = mode != GpxSession.Mode.GPX

        val voiceLocal = GpxVoice(context).also { voice = it }
        var units = MapPrefs.units(context)
        var showNextStop = MapPrefs.showNextStop(context)
        var voiceOn = MapPrefs.voicePrompts(context)
        var autoFinish = MapPrefs.autoFinishOnArrive(context)
        var buildings3d = MapPrefs.buildings3d(context)
        var destArrivedSpoken = false
        var liveMode = mode
        var liveNav = track?.let { GpxNav(it) }
        var liveDest = GpxSession.destination
        /** Distance to dest when nav first got a GPS fix — blocks fake "Arrived" on reopen. */
        var navStartDistM: Double? = null
        var navEngageElapsedRealtime = if (mode == GpxSession.Mode.NAV_TO && GpxSession.navStartedAtMs > 0L) {
            android.os.SystemClock.elapsedRealtime()
        } else {
            0L
        }
        var lastLoc: Location? = null
        var osrmRequested = false
        var lastSpeedFetchMs = 0L
        var offRouteSinceMs = 0L
        var lastAutoRerouteMs = 0L
        val statusColorNormal = 0xFFC8D0D8.toInt()
        val statusColorOff = 0xFFFF8A80.toInt()
        /** Handlebar focus hint: "end" | "go" | "default" (filled after focus nav arms). */
        var onChromeFocus: ((prefer: String) -> Unit)? = null

        root.setBackgroundColor(MapPrefs.chrome(context).color)
        // Map goes edge-to-edge; chrome cards get cutout margins so we don't leave a black strip.
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            insetL = bars.left
            insetT = bars.top
            insetR = bars.right
            insetB = bars.bottom
            v.setPadding(0, 0, 0, 0)
            adaptChromeLayout(lastNavigating)
            insets
        }
        ViewCompat.requestApplyInsets(root)
        val mapHost = root.findViewById<FrameLayout>(R.id.gpx_map_host)
        // Renderer now lives in the Overtake library (Stage 3). Map the fork's persisted DashRenderer
        // to the library's RendererKind, then attach the returned MapRenderer to THIS dash's host.
        val dashRendererChoice = resolveDashRenderer()
        val rendererKind = when (dashRendererChoice) {
            DashRenderer.OSMDROID -> RendererKind.OSMDROID
            DashRenderer.MAPSFORGE -> RendererKind.MAPSFORGE
            DashRenderer.MAPLIBRE -> RendererKind.MAPLIBRE
        }
        val dashLocal = overtakeRenderer(context, rendererKind).also { dash = it }
        dashLocal.attach(context, mapHost)
        dashLocal.setBuildings3d(buildings3d)
        dashLocal.onCreate(null)
        dashLocal.onResume()
        // Strict Mapsforge gate (see DashMapEngine): the library draws NOTHING for MAPSFORGE with no
        // usable `.map` — there is NO silent osmdroid fallback. Overlay a clear "download a map" prompt
        // on the (empty) host so the rider knows why the map is blank and where to fix it.
        if (dashRendererChoice == DashRenderer.MAPSFORGE &&
            !runCatching { overtakeOffline(context).hasMapsforgeMaps() }.getOrDefault(true)
        ) {
            log("[MAP] Mapsforge selected but no .map installed — showing download prompt (no fallback)")
            showMapsforgeNeedsMapPrompt(mapHost)
        }

        val titleView = root.findViewById<TextView>(R.id.gpx_title)
        val statusView = root.findViewById<TextView>(R.id.gpx_status)
        val nextView = root.findViewById<TextView>(R.id.gpx_next)
        val cueView = root.findViewById<TextView>(R.id.gpx_cue)
        val cueIcon = root.findViewById<ImageView>(R.id.gpx_cue_icon)
        val cueDist = root.findViewById<TextView>(R.id.gpx_cue_dist)
        val maneuverRow = root.findViewById<View>(R.id.gpx_maneuver_row)
        val laneView = root.findViewById<TextView>(R.id.gpx_lane)
        val lSpeed = root.findViewById<TextView>(R.id.gpx_l_speed)
        val lAlt = root.findViewById<TextView>(R.id.gpx_l_alt)
        val lLimit = root.findViewById<TextView>(R.id.gpx_l_limit)
        val wSpeed = root.findViewById<TextView>(R.id.gpx_w_speed)
        val wTime = root.findViewById<TextView>(R.id.gpx_w_time)
        val wLeft = root.findViewById<TextView>(R.id.gpx_w_left)
        val wEta = root.findViewById<TextView>(R.id.gpx_w_eta)
        val wAlt = root.findViewById<TextView>(R.id.gpx_w_alt)
        val wLimit = root.findViewById<TextView>(R.id.gpx_w_limit)

        fun altitudeText(progAlt: Double?, loc: Location): String = when {
            progAlt != null -> GpxNav.formatAltitude(progAlt, units)
            loc.hasAltitude() -> GpxNav.formatAltitude(loc.altitude, units)
            else -> "—"
        }
        val rideStats = GpxRideStats()

        fun applyUnitLabels() {
            lSpeed.text = GpxNav.speedUnitLabel(units)
            lAlt.text = if (units == MapUnits.IMPERIAL) "ALT ft" else "ALT m"
            lLimit.text = if (units == MapUnits.IMPERIAL) "LIMIT mph" else "LIMIT"
            root.findViewById<TextView>(R.id.gpx_chip_speed_unit)?.text = GpxNav.speedUnitLabel(units)
        }
        fun applyMapTheme() {
            dashLocal.applyTheme(NightPrefs.isNightNow(context))
            dashLocal.setBuildings3d(buildings3d)
        }
        fun refreshTitle() {
            val nav = liveNav
            titleView.text = when (liveMode) {
                GpxSession.Mode.FREE_RIDE -> "Free ride"
                GpxSession.Mode.NAV_TO -> "→ ${liveDest?.name ?: GpxSession.trackName}"
                GpxSession.Mode.GPX -> buildString {
                    append(GpxSession.trackName)
                    if (nav != null && nav.totalM > 0) {
                        append(" · ").append(GpxNav.formatDistance(nav.totalM, units))
                    }
                    if (nav != null && nav.hasElevation) {
                        append(" · ").append(GpxNav.formatAscent(nav.totalAscentM, units))
                    }
                }
            }
        }
        fun collectPins(): List<MapPlace> {
            val out = ArrayList<MapPlace>()
            // Always read MapPlaces live so Clear/Delete from hub updates without map restart.
            MapPlaces.home(context)?.let { out.add(it) }
            MapPlaces.parked(context)?.let { out.add(it) }
            out.addAll(MapPlaces.markers(context))
            out.addAll(MapPlaces.favorites(context))
            track?.waypoints?.forEach { w ->
                out.add(MapPlace(w.name ?: "Waypoint", w.lat, w.lon, "wpt"))
            }
            // Corridor / session-only overlays. Skip stale MapPlaces snapshots
            // (older builds stuffed home/parked/markers into overlayPlaces).
            for (p in GpxSession.overlayPlaces) {
                if (p.category == "home" || p.category == "pin") continue
                if (p.name == "Parked" || p.subtitle.contains("Parked", ignoreCase = true)) continue
                out.add(p)
            }
            return out.distinctBy { "${"%.5f".format(it.lat)},${"%.5f".format(it.lon)}|${it.name}" }
        }
        var seenPlacesRev = MapPlaces.revision
        fun refreshPins() {
            seenPlacesRev = MapPlaces.revision
            dashLocal.setPins(collectPins())
            dashLocal.setDestination(liveDest)
        }
        // Reassigned after showIdle exists so hub Clear/Delete updates the open map live.
        var syncPlacesIfChanged: () -> Unit = {
            if (MapPlaces.revision != seenPlacesRev) refreshPins()
        }
        /** Closer chase on the bike HUD (Waze-like); phone keeps the standard ladder. */
        fun followZoom(speedKmh: Float): Double {
            val z = GpxNav.zoomForSpeedKmh(speedKmh)
            return if (projected) (z + 1.1).coerceAtMost(18.6) else z
        }
        applyUnitLabels()
        refreshTitle()
        applyMapTheme()

        fun applyRoadRoute(route: Route, name: String) {
            liveNav = GpxNav(routeToTrack(name, route), route.steps)
            val pts = route.points.map { it.lat to it.lon }
            dashLocal.clearPreviewRoute()
            dashLocal.setRouteRemain(pts)
            dashLocal.setRouteDone(emptyList())
            dashLocal.clearToDest()
            // On nav start/reroute go straight into heading-up 3D follow on the
            // rider; only show the whole-route overview when there's no fix yet.
            val loc = lastLoc
            if (loc != null) {
                val moving = loc.hasSpeed() && loc.speed > 1.2f
                val brng = if (loc.hasBearing() && moving) loc.bearing else 0f
                dashLocal.setMe(loc.latitude, loc.longitude, brng)
                dashLocal.follow(
                    loc.latitude, loc.longitude, brng,
                    followZoom(loc.speed * 3.6f),
                    headingUp = true,
                    moving = moving,
                )
            } else {
                dashLocal.zoomToRoute(pts)
            }
            refreshTitle()
        }
        fun requestOsrm(from: Location, reason: String) {
            val dest = liveDest ?: return
            statusView.text = "Routing…"
            // Extracted Router.route() (offline pack / Valhalla / OSRM). Suspends off-main and resumes
            // here on Main via routeScope — same behaviour as the old background-thread + main.post.
            val router = overtakeRouter(context)
            val opts = MapPrefs.routeOptions(context)
            val f = dev.overtake.maps.model.GeoPoint(from.latitude, from.longitude)
            val t = dev.overtake.maps.model.GeoPoint(dest.lat, dest.lon)
            routeScope.launch {
                val result = runCatching { router.route(f, t, opts) }.getOrNull()
                if (!isAlive() || released) return@launch
                val route = result?.routes?.firstOrNull()
                if (route == null) {
                    statusView.text = "No road route · straight line"
                    log("[GPX] route failed ($reason): ${result?.warning ?: "No route available"}")
                    return@launch
                }
                applyRoadRoute(route, dest.name)
                statusView.text = "Route · ${GpxNav.formatDistance(route.distanceM, units)}"
                // Don't announce a "ready" trip that's basically already the pin.
                val worthAnnounce = route.distanceM >= 150.0
                if (voiceOn && reason == "recalc" && worthAnnounce) {
                    voiceLocal.speak("Route updated")
                } else if (voiceOn && reason == "start" && worthAnnounce) {
                    voiceLocal.speak("Route ready")
                }
                log("[GPX] route $reason ${route.points.size} pts ${route.distanceM.toInt()}m")
            }
        }
        val arriveCard = root.findViewById<View>(R.id.gpx_arrive_card)
        val arriveTitle = root.findViewById<TextView>(R.id.gpx_arrive_title)
        val arriveMeta = root.findViewById<TextView>(R.id.gpx_arrive_meta)
        fun hideArriveCard() {
            arriveCard?.visibility = View.GONE
        }
        fun showArriveCard(destName: String?) {
            arriveTitle?.text = if (destName.isNullOrBlank()) "You've arrived" else "Arrived · $destName"
            arriveMeta?.text = "Mark parking, find a spot, or continue free ride"
            arriveCard?.visibility = View.VISIBLE
            root.findViewById<View>(R.id.gpx_preview_card)?.visibility = View.GONE
        }
        fun finishToFreeRideUi(spoken: String, offerParking: Boolean = false) {
            if (liveMode == GpxSession.Mode.FREE_RIDE && !offerParking) return
            val arrivedName = liveDest?.name
            GpxSession.finishToFreeRide()
            liveMode = GpxSession.Mode.FREE_RIDE
            liveNav = null
            liveDest = null
            navStartDistM = null
            navEngageElapsedRealtime = 0L
            dashLocal.clearRoutes()
            dashLocal.setDestination(null)
            maneuverRow.visibility = View.GONE
            laneView.visibility = View.GONE
            nextView.visibility = View.GONE
            wTime.text = "—"
            wLeft.text = "—"
            wEta.text = "—"
            statusView.setTextColor(statusColorNormal)
            refreshTitle()
            statusView.text = spoken
            if (voiceOn && spoken != "Already nearby") voiceLocal.speak(spoken)
            log("[GPX] finished → free ride")
            if (offerParking) showArriveCard(arrivedName) else hideArriveCard()
            // Drop nav chrome immediately (refreshChrome is declared later in bind()).
            root.findViewById<View>(R.id.gpx_hud)?.visibility = View.GONE
            root.findViewById<View>(R.id.gpx_hud_bottom)?.visibility = View.GONE
            root.findViewById<View>(R.id.gpx_preview_card)?.visibility = View.GONE
            root.findViewById<View>(R.id.gpx_search_pill)?.visibility = View.VISIBLE
            if (!offerParking) hideArriveCard()
        }
        /**
         * Real arrival only: we must have started farther away and ridden for a bit.
         * Opening the app / resuming a killed Campina trip while already nearby must NOT flash Arrived.
         */
        fun canDeclareArrival(distM: Double): Boolean {
            if (liveMode != GpxSession.Mode.NAV_TO) return false
            if (navStartDistM == null) {
                navStartDistM = distM
                if (distM < 200.0) {
                    destArrivedSpoken = true // suppress later popups
                    log("[GPX] near dest at nav start (${distM.toInt()}m) — no Arrived sheet")
                    main.post {
                        if (!isAlive() || released) return@post
                        finishToFreeRideUi("Already nearby", offerParking = false)
                    }
                    return false
                }
            }
            val start = navStartDistM ?: return false
            if (start < 200.0) return false
            val elapsed = android.os.SystemClock.elapsedRealtime() - navEngageElapsedRealtime
            val progressed = start - distM >= 80.0
            if (elapsed < 15_000L && !progressed) return false
            return true
        }
        val initialRoutePts = track?.points?.takeIf { it.isNotEmpty() }?.map { it.lat to it.lon }
        if (initialRoutePts != null) {
            dashLocal.setRouteRemain(initialRoutePts)
        }
        refreshPins()
        // Defer camera until MapView is laid out (avoids crash when swapping GPX into preview).
        root.post {
            if (!isAlive() || released) return@post
            try {
                when {
                    initialRoutePts != null -> dashLocal.zoomToRoute(initialRoutePts)
                    liveDest != null -> dashLocal.setCenter(liveDest!!.lat, liveDest!!.lon, 15.5)
                }
            } catch (e: Exception) {
                log("[GPX] initial camera failed: $e")
            }
        }

        var follow = true
        var headingUp = true
        var speedZoom = true
        var inPreview = false
        /** Long-press pin: save/home/marker only — no route / Go until opened from search. */
        var pinSetupOnly = false
        var lastMapBearing = 0f
        // Magnetometer/rotation-vector heading (device facing), used when stopped or
        // when GPS course is unavailable, so the compass reacts to physically turning.
        var deviceHeading = 0f
        var haveDeviceHeading = false
        var pendingPlace: MapPlace? = null
        var pendingRoute: Route? = null
        // Route options (Google/Waze-style alternatives) shown in the preview; index 0 = recommended.
        var pendingRoutes: List<Route> = emptyList()
        var selectedRouteIdx = 0
        // Assigned once search-result rendering helpers exist below (avoids forward-reference).
        var showIdle: () -> Unit = {}
        val followBtn = root.findViewById<Button>(R.id.gpx_follow)
        val muteBtn = root.findViewById<Button>(R.id.gpx_mute)
        val northBtn = root.findViewById<Button>(R.id.gpx_north)
        val dimmer = root.findViewById<View>(R.id.gpx_dimmer)
        val menuPanel = root.findViewById<View>(R.id.gpx_menu_panel)
        val searchPanel = root.findViewById<View>(R.id.gpx_search_panel)
        val searchPill = root.findViewById<View>(R.id.gpx_search_pill)
        val searchPillText = root.findViewById<TextView>(R.id.gpx_search_pill_text)
        val quickBar = root.findViewById<View>(R.id.gpx_quick_bar)
        val hud = root.findViewById<View>(R.id.gpx_hud)
        val hudBottom = root.findViewById<View>(R.id.gpx_hud_bottom)
        val speedChip = root.findViewById<View>(R.id.gpx_speed_chip)
        val chipSpeed = root.findViewById<TextView>(R.id.gpx_chip_speed)
        val chipSpeedUnit = root.findViewById<TextView>(R.id.gpx_chip_speed_unit)
        val chipLimit = root.findViewById<TextView>(R.id.gpx_chip_limit)
        chipSpeedUnit?.text = GpxNav.speedUnitLabel(units)
        val previewCard = root.findViewById<View>(R.id.gpx_preview_card)
        val previewName = root.findViewById<TextView>(R.id.gpx_preview_name)
        val previewMeta = root.findViewById<TextView>(R.id.gpx_preview_meta)
        val previewAlts = root.findViewById<LinearLayout>(R.id.gpx_preview_alts)
        val searchInput = root.findViewById<EditText>(R.id.gpx_search_input)
        val searchStatus = root.findViewById<TextView>(R.id.gpx_search_status)
        val searchResults = root.findViewById<LinearLayout>(R.id.gpx_search_results)
        val searchChips = root.findViewById<LinearLayout>(R.id.gpx_search_chips)
        val recordBtn = root.findViewById<Button>(R.id.gpx_record)

        fun applyPreviewModeChrome() {
            val go = root.findViewById<View>(R.id.gpx_preview_go)
            val circuit = root.findViewById<View>(R.id.gpx_preview_circuit)
            val home = root.findViewById<View>(R.id.gpx_preview_home)
            val marker = root.findViewById<View>(R.id.gpx_preview_marker)
            val altsHost = root.findViewById<View>(R.id.gpx_preview_alts)?.parent as? View
            if (pinSetupOnly) {
                // Dropped-pin sheet: Save / Home / Marker / Done only.
                go?.visibility = View.GONE
                circuit?.visibility = View.GONE
                home?.visibility = View.VISIBLE
                marker?.visibility = View.VISIBLE
                previewAlts.visibility = View.GONE
                altsHost?.visibility = View.GONE
                root.findViewById<TextView>(R.id.gpx_preview_cancel)?.text = "Done"
            } else {
                // Trip preview: Go / Save / Circuit / Cancel — Home & Marker are pin-only.
                go?.visibility = View.VISIBLE
                circuit?.visibility = View.VISIBLE
                home?.visibility = View.GONE
                marker?.visibility = View.GONE
                altsHost?.visibility = View.VISIBLE
                root.findViewById<TextView>(R.id.gpx_preview_cancel)?.text = "Cancel"
            }
        }
        fun refreshChrome() {
            syncPlacesIfChanged()
            val navigating = !inPreview && liveMode != GpxSession.Mode.FREE_RIDE
            searchPill.visibility = if (navigating) View.GONE else View.VISIBLE
            hud.visibility = if (navigating) View.VISIBLE else View.GONE
            hudBottom.visibility = if (navigating) View.VISIBLE else View.GONE
            previewCard.visibility = if (inPreview) View.VISIBLE else View.GONE
            if (inPreview) {
                hideArriveCard()
                applyPreviewModeChrome()
            }
            if (!navigating) {
                searchPillText.text = pendingPlace?.name?.takeIf { inPreview } ?: "Search here"
            } else if (maneuverRow.visibility != View.VISIBLE || cueView.text.isNullOrBlank()) {
                // Never leave an empty black top bar while riding — seed destination until GPS fills turns.
                maneuverRow.visibility = View.VISIBLE
                cueIcon.setImageResource(R.drawable.ic_man_arrive)
                if (cueDist.text.isNullOrBlank() || cueDist.text == "…") cueDist.text = "GPS"
                cueView.text = "To ${liveDest?.name ?: GpxSession.trackName}"
                laneView.visibility = View.GONE
            }
            lastNavigating = navigating
            adaptChromeLayout(navigating)
        }
        fun <T> highlight(selected: T, vararg pairs: Pair<Int, T>) {
            val onColor = ContextCompat.getColor(context, R.color.brand_orange)
            val onText = ContextCompat.getColor(context, R.color.on_brand)
            val offColor = ContextCompat.getColor(context, R.color.surface_high)
            val offText = ContextCompat.getColor(context, R.color.text_primary)
            for ((id, value) in pairs) {
                val btn = root.findViewById<MaterialButton>(id) ?: continue
                val on = value == selected
                btn.backgroundTintList = ColorStateList.valueOf(if (on) onColor else offColor)
                btn.setTextColor(if (on) onText else offText)
            }
        }
        fun refreshSettingsLabels() {
            highlight(
                units,
                R.id.gpx_units_metric to MapUnits.METRIC,
                R.id.gpx_units_imperial to MapUnits.IMPERIAL,
            )
            highlight(
                NightPrefs.theme(context),
                R.id.gpx_theme_auto to MapTheme.AUTO,
                R.id.gpx_theme_day to MapTheme.DAY,
                R.id.gpx_theme_night to MapTheme.NIGHT,
            )
            highlight(
                voiceOn,
                R.id.gpx_voice_on to true,
                R.id.gpx_voice_off to false,
            )
            highlight(
                showNextStop,
                R.id.gpx_next_stop_on to true,
                R.id.gpx_next_stop_off to false,
            )
            highlight(
                autoFinish,
                R.id.gpx_auto_finish_on to true,
                R.id.gpx_auto_finish_off to false,
            )
            highlight(
                MapPrefs.chrome(context),
                R.id.gpx_chrome_dark to MapChrome.DARK,
                R.id.gpx_chrome_black to MapChrome.BLACK,
                R.id.gpx_chrome_slate to MapChrome.SLATE,
                R.id.gpx_chrome_forest to MapChrome.FOREST,
            )
            highlight(
                buildings3d,
                R.id.gpx_buildings_on to true,
                R.id.gpx_buildings_off to false,
            )
            highlight(
                MapPrefs.routeMode(context),
                R.id.gpx_route_fast to RouteMode.FAST,
                R.id.gpx_route_fun to RouteMode.FUN,
            )
            highlight(
                MapPrefs.avoidTolls(context),
                R.id.gpx_avoid_tolls_on to true,
                R.id.gpx_avoid_tolls_off to false,
            )
            highlight(
                MapPrefs.avoidHighways(context),
                R.id.gpx_avoid_hwy_on to true,
                R.id.gpx_avoid_hwy_off to false,
            )
            val circuitKm = when (MapPrefs.funCircuitKm(context)) {
                in 0..45 -> 30
                in 46..80 -> 60
                else -> 100
            }
            highlight(
                circuitKm,
                R.id.gpx_circuit_30 to 30,
                R.id.gpx_circuit_60 to 60,
                R.id.gpx_circuit_100 to 100,
            )
            muteBtn.text = if (voiceOn) "Voice on" else "Voice muted"
            followBtn.text = if (follow) "Follow on" else "Free pan"
            northBtn.text = if (headingUp) "Heading up" else "North up"
            recordBtn.text =
                if (TripLogger.current?.recording == true) "Stop recording" else "Record ride"
            // Auto-log (Setup ▸ Log trips) already runs while this UI is live; the button is a
            // manual override for riders who turned that setting off.
            root.findViewById<Button>(R.id.gpx_create_circuit)?.text = "Circuit"
            root.findViewById<TextView>(R.id.gpx_preview_circuit)?.text = "Circuit"
        }
        fun hideSheets() {
            menuPanel.visibility = View.GONE
            searchPanel.visibility = View.GONE
            dimmer.visibility = View.GONE
            quickBar.visibility = View.VISIBLE
            try {
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.hideSoftInputFromWindow(searchInput.windowToken, 0)
            } catch (_: Exception) {}
        }
        fun showMenu() {
            searchPanel.visibility = View.GONE
            quickBar.visibility = View.GONE
            syncPlacesIfChanged()
            refreshSettingsLabels()
            dimmer.visibility = View.VISIBLE
            menuPanel.visibility = View.VISIBLE
            menuPanel.bringToFront()
        }
        fun showSearch() {
            menuPanel.visibility = View.GONE
            quickBar.visibility = View.GONE
            dimmer.visibility = View.GONE
            // Keep the search header below the punch-hole / status bar.
            searchPanel.setPadding(0, insetT, 0, 0)
            searchPanel.visibility = View.VISIBLE
            searchPanel.bringToFront()
            syncPlacesIfChanged()
            showIdle()
            if (projected) {
                // Presentation / VirtualDisplay has no IME — type on the phone home field.
                searchInput.hint = "Type on your PHONE keyboard…"
                searchInput.isFocusable = false
                searchInput.isFocusableInTouchMode = false
                searchStatus.setTextColor(0xFFE65100.toInt())
                searchStatus.textSize = 16f
                searchStatus.text = "⌨ Type on PHONE — results show here"
                if (!DashRemote.requestTypeOnPhone()) {
                    searchStatus.text = "Open OpenCfMoto on the phone, then type"
                } else if (voiceOn) {
                    try { voiceLocal.speak("Type your search on the phone") } catch (_: Exception) {}
                }
            } else {
                searchInput.hint = "Search places or address"
                searchInput.isFocusable = true
                searchInput.isFocusableInTouchMode = true
                searchStatus.setTextColor(0xFF5F6368.toInt())
                searchStatus.textSize = 13f
                searchInput.requestFocus()
                searchInput.post {
                    try {
                        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                        imm?.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT)
                    } catch (_: Exception) {}
                }
            }
        }
        val recenter = object : Runnable {
            override fun run() {
                if (!isAlive() || released) return
                if (!follow) {
                    follow = true
                    followBtn.text = "Follow on"
                    log("[GPX] auto-recenter (follow on)")
                }
            }
        }
        fun bumpFreePan() {
            follow = false
            followBtn.text = "Free pan"
            main.removeCallbacks(recenter)
            main.postDelayed(recenter, 12_000L)
        }
        fun cancelPreview() {
            inPreview = false
            pinSetupOnly = false
            pendingPlace = null
            pendingRoute = null
            pendingRoutes = emptyList()
            selectedRouteIdx = 0
            previewAlts.removeAllViews()
            previewAlts.visibility = View.GONE
            dashLocal.clearPreviewRoute()
            dashLocal.setDestination(liveDest)
            refreshChrome()
        }
        fun startNavigationTo(place: MapPlace) {
            val cached = pendingRoute
            inPreview = false
            pinSetupOnly = false
            pendingPlace = null
            pendingRoute = null
            pendingRoutes = emptyList()
            selectedRouteIdx = 0
            previewAlts.removeAllViews()
            previewAlts.visibility = View.GONE
            MapPlaces.pushHistory(context, place)
            GpxSession.prepareNavTo(place, GpxSession.overlayPlaces)
            liveMode = GpxSession.Mode.NAV_TO
            liveDest = place
            destArrivedSpoken = false
            navStartDistM = null
            navEngageElapsedRealtime = android.os.SystemClock.elapsedRealtime()
            follow = true
            speedZoom = true
            headingUp = true // Waze-style chase on Go (overview was north-up only for preview)
            hideSheets()
            hideArriveCard()
            refreshPins()
            // Reuse preview route immediately — do NOT wait on a second OSRM round-trip.
            if (cached != null && cached.points.size >= 2) {
                osrmRequested = true
                applyRoadRoute(cached, place.name)
                statusView.text =
                    "Route · ${GpxNav.formatDistance(cached.distanceM, units)}"
                lastLoc?.let { loc ->
                    val moving = loc.hasSpeed() && loc.speed > 1.2f
                    val brng = if (loc.hasBearing() && moving) loc.bearing else 0f
                    dashLocal.setMe(loc.latitude, loc.longitude, brng)
                    dashLocal.follow(
                        loc.latitude, loc.longitude, brng,
                        followZoom(loc.speed * 3.6f),
                        headingUp = true,
                        moving = moving,
                    )
                }
            } else {
                liveNav = null
                osrmRequested = false
                dashLocal.clearPreviewRoute()
                dashLocal.clearRoutes()
                val loc = lastLoc
                if (loc != null) {
                    requestOsrm(loc, "start")
                } else {
                    statusView.text = "GPS… · navigate to ${place.name}"
                    dashLocal.setCenter(place.lat, place.lon, 16.0)
                }
            }
            refreshTitle()
            refreshChrome()
            onChromeFocus?.invoke("end")
            if (voiceOn) voiceLocal.speak("Navigating to ${place.name}")
            log("[GPX] Go → ${place.name} (cached=${cached != null})")
        }
        // Pick which route option is shown highlighted; redraw + refresh the summary/chips.
        fun selectRoute(index: Int, loc: android.location.Location, place: MapPlace) {
            val routes = pendingRoutes
            if (routes.isEmpty()) return
            val idx = index.coerceIn(0, routes.lastIndex)
            selectedRouteIdx = idx
            val route = routes[idx]
            pendingRoute = route
            // Freeze follow/3D chase while previewing — otherwise GPS keep-alive zooms back in
            // and you only see a stub of the route (not a Google/Waze overview).
            follow = false
            speedZoom = false
            headingUp = false // overview is ALWAYS north-up
            // Drop the temporary yellow me→dest line once a real (or fallback) geometry lands.
            dashLocal.clearToDest()
            dashLocal.resetNorth()
            val selPts = route.points.map { it.lat to it.lon }
            val altPts = routes.filterIndexed { i, _ -> i != idx }.map { r -> r.points.map { it.lat to it.lon } }
            dashLocal.setPreviewRoutes(selPts, altPts)
            // Fit every alternative + the me→dest pair so start and finish are always on screen.
            val overview = routes.map { r -> r.points.map { it.lat to it.lon } } +
                listOf(listOf(loc.latitude to loc.longitude, place.lat to place.lon))
            dashLocal.zoomToRoutes(overview)
            val etaSec = route.durationSec.toLong().takeIf { it > 0 }
                ?: GpxNav.etaSeconds(route.distanceM, loc.speed)
            val isDirect = route.label.equals("Direct", ignoreCase = true) ||
                route.steps.any { it.roadName.contains("offline", ignoreCase = true) }
            previewMeta.text = buildString {
                if (isDirect) append("Straight line (no road route) · ")
                append(GpxNav.formatDistance(route.distanceM, units))
                append(" · ")
                append(GpxNav.formatArrival(etaSec))
                if (place.subtitle.isNotBlank()) append(" · ").append(place.subtitle)
            }
            // Chips: Google-style options with mode tag + ETA + distance.
            previewAlts.removeAllViews()
            if (routes.isEmpty()) {
                previewAlts.visibility = View.GONE
            } else {
                previewAlts.visibility = View.VISIBLE
                routes.forEachIndexed { i, r ->
                    val sel = i == idx
                    val eta = r.durationSec.toLong().takeIf { it > 0 }
                        ?: GpxNav.etaSeconds(r.distanceM, loc.speed)
                    val tag = r.label.ifBlank { if (i == 0) "Fast" else "Alt" }
                    val label = "$tag · ${GpxNav.formatArrival(eta)} · ${GpxNav.formatDistance(r.distanceM, units)}"
                    val chip = TextView(context).apply {
                        text = if (sel) "★ $label" else label
                        textSize = 13f
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        setTextColor(if (sel) 0xFFFFFFFF.toInt() else 0xFFC8D0D8.toInt())
                        setBackgroundResource(if (sel) R.drawable.bg_route_chip_sel else R.drawable.bg_route_chip)
                        setPadding(28, 14, 28, 14)
                        isClickable = true
                        isFocusable = true
                        setOnClickListener { selectRoute(i, loc, place) }
                    }
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { rightMargin = 16 }
                    previewAlts.addView(chip, lp)
                }
            }
            onChromeFocus?.invoke("go")
        }
        fun showPinSetup(place: MapPlace) {
            pinSetupOnly = true
            pendingPlace = place
            pendingRoute = null
            pendingRoutes = emptyList()
            selectedRouteIdx = 0
            previewAlts.removeAllViews()
            previewAlts.visibility = View.GONE
            inPreview = true
            follow = false
            speedZoom = false
            hideSheets()
            hideArriveCard()
            dashLocal.setDestination(place)
            dashLocal.clearRoutes()
            dashLocal.clearToDest()
            dashLocal.clearPreviewRoute()
            previewName.text = place.name.ifBlank { "Dropped pin" }
            previewMeta.text = place.subtitle.ifBlank {
                String.format("%.5f, %.5f", place.lat, place.lon)
            }
            dashLocal.setCenter(place.lat, place.lon, 16.0)
            refreshChrome()
            onChromeFocus?.invoke("go")
            statusView.text = "Save this pin — open it later to navigate"
        }
        fun previewPlace(place: MapPlace) {
            pinSetupOnly = false
            pendingPlace = place
            pendingRoute = null
            pendingRoutes = emptyList()
            selectedRouteIdx = 0
            previewAlts.removeAllViews()
            previewAlts.visibility = View.GONE
            inPreview = true
            follow = false
            speedZoom = false
            headingUp = false // overview is ALWAYS north-up
            hideSheets()
            hideArriveCard()
            dashLocal.setDestination(place)
            dashLocal.clearRoutes()
            dashLocal.resetNorth()
            previewName.text = place.name
            previewMeta.text = if (place.subtitle.isNotBlank()) place.subtitle else "Getting route…"
            if (!AppHttp.hasInternetPin()) {
                previewMeta.text = listOfNotNull(
                    previewMeta.text?.toString()?.takeIf { it.isNotBlank() },
                    "No mobile data pin — enable cellular for road routes",
                ).joinToString(" · ")
            }
            refreshChrome()
            onChromeFocus?.invoke("go")
            val loc = lastLoc
            if (loc == null) {
                previewMeta.text = listOfNotNull(
                    place.subtitle.takeIf { it.isNotBlank() },
                    "Waiting for GPS to route",
                ).joinToString(" · ")
                dashLocal.setCenter(place.lat, place.lon, 16.0)
                return
            }
            // Immediate me→dest frame while the road route loads (so the map isn't stuck on me).
            dashLocal.setToDest(loc.latitude, loc.longitude, place.lat, place.lon)
            dashLocal.zoomToRoutes(
                listOf(listOf(loc.latitude to loc.longitude, place.lat to place.lon)),
            )
            // Extracted Router.alternatives() → RouteResult (routes + avoidHonored + warning).
            val router = overtakeRouter(context)
            val opts = MapPrefs.routeOptions(context)
            val f = dev.overtake.maps.model.GeoPoint(loc.latitude, loc.longitude)
            val t = dev.overtake.maps.model.GeoPoint(place.lat, place.lon)
            routeScope.launch {
                val outcome = runCatching { router.alternatives(f, t, opts) }
                if (!isAlive() || released || pendingPlace != place || pinSetupOnly) return@launch
                outcome.onSuccess { result ->
                    if (result.routes.isEmpty()) return@onSuccess
                    pendingRoutes = result.routes
                    selectRoute(0, loc, place)
                    if (!result.avoidHonored || result.warning != null) {
                        val warn = result.warning ?: "Avoid setting not applied"
                        previewMeta.text = listOfNotNull(
                            previewMeta.text?.toString()?.takeIf { it.isNotBlank() },
                            warn,
                        ).joinToString(" · ")
                        statusView.text = warn
                        log("[GPX] $warn")
                    }
                }.onFailure { err ->
                    pendingRoute = null
                    dashLocal.setToDest(loc.latitude, loc.longitude, place.lat, place.lon)
                    dashLocal.setCenter(place.lat, place.lon, 15.0)
                    previewMeta.text = "No road route · straight line"
                    log("[GPX] preview route failed: ${err.message}")
                }
            }
        }
        fun showSearchResults(list: List<MapPlace>) {
            searchResults.removeAllViews()
            searchResults.clipChildren = true
            searchResults.clipToPadding = true
            if (list.isEmpty()) {
                searchStatus.setTextColor(0xFF5F6368.toInt())
                searchStatus.textSize = 13f
                searchStatus.text = "No results"
                return
            }
            searchStatus.setTextColor(0xFF5F6368.toInt())
            searchStatus.textSize = 13f
            searchStatus.text = "${list.size} places"
            for (p in list) {
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(48, 36, 48, 36)
                    setBackgroundColor(0xFFFFFFFF.toInt())
                    isClickable = true
                    isFocusable = true
                    clipChildren = true
                    clipToPadding = true
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    )
                    setOnClickListener { previewPlace(p) }
                }
                row.addView(
                    TextView(context).apply {
                        text = p.name
                        setTextColor(0xFF202124.toInt())
                        textSize = 16f
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        )
                    },
                )
                if (p.subtitle.isNotBlank() || p.category.isNotBlank()) {
                    row.addView(
                        TextView(context).apply {
                            text = listOf(p.category, p.subtitle)
                                .filter { it.isNotBlank() }
                                .joinToString(" · ")
                            setTextColor(0xFF5F6368.toInt())
                            textSize = 13f
                            setPadding(0, 6, 0, 0)
                            maxLines = 2
                            ellipsize = android.text.TextUtils.TruncateAt.END
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                            )
                        },
                    )
                }
                // Save / Marker quick actions (Google-style row affordances).
                val actions = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, 14, 0, 0)
                }
                fun actionChip(label: String, onTap: () -> Unit): TextView =
                    TextView(context).apply {
                        text = label
                        setTextColor(0xFF1A73E8.toInt())
                        textSize = 14f
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        setPadding(0, 8, 40, 8)
                        isClickable = true
                        isFocusable = true
                        setOnClickListener { onTap() }
                    }
                actions.addView(actionChip("★ Save") {
                    MapPlaces.addFavorite(context, p)
                    searchStatus.text = "Saved ${p.name} to favorites"
                })
                actions.addView(actionChip("＋ Marker") {
                    MapPlaces.addMarker(context, p)
                    refreshPins()
                    searchStatus.text = "Marker added for ${p.name}"
                })
                val isSavedMarker = MapPlaces.markers(context).any {
                    kotlin.math.abs(it.lat - p.lat) < 1e-5 && kotlin.math.abs(it.lon - p.lon) < 1e-5
                } || p.category == "pin" || p.category == "parking" || p.category == "home"
                val isFav = MapPlaces.favorites(context).any {
                    kotlin.math.abs(it.lat - p.lat) < 1e-5 && kotlin.math.abs(it.lon - p.lon) < 1e-5
                }
                // History ghosts (e.g. cleared Parked) still need a Delete affordance.
                val canDelete = isSavedMarker || isFav || MapPlaces.isParked(context, p) ||
                    p.category == "parking" || p.category == "home" ||
                    MapPlaces.history(context).any {
                        kotlin.math.abs(it.lat - p.lat) < 1e-5 &&
                            kotlin.math.abs(it.lon - p.lon) < 1e-5
                    }
                if (canDelete) {
                    actions.addView(actionChip("Delete") {
                        MapPlaces.deletePlace(context, p)
                        refreshPins()
                        searchStatus.text = "Deleted ${p.name}"
                        showIdle()
                    }.also { it.setTextColor(0xFFD93025.toInt()) })
                }
                row.addView(actions)
                searchResults.addView(row)
                searchResults.addView(
                    View(context).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, 1,
                        )
                        setBackgroundColor(0xFFDADCE0.toInt())
                    },
                )
            }
        }
        showIdle = {
            val home = listOfNotNull(MapPlaces.home(context))
            val parked = listOfNotNull(MapPlaces.parked(context))
            val favs = MapPlaces.favorites(context)
            val marks = MapPlaces.markers(context)
            val hist = MapPlaces.history(context)
                .filter {
                    it.category != "mode" &&
                        it.category != "parking" && // parked shown via MapPlaces.parked only
                        !(it.lat == 0.0 && it.lon == 0.0)
                }
            val seen = HashSet<String>()
            val idle = (home + parked + favs + marks + hist).filter { p ->
                seen.add("${p.name}|${"%.4f".format(p.lat)}|${"%.4f".format(p.lon)}")
            }.take(12)
            if (idle.isEmpty()) {
                searchResults.removeAllViews()
                searchStatus.setTextColor(0xFF5F6368.toInt())
                searchStatus.textSize = 13f
                searchStatus.text = if (projected) {
                    "Type on PHONE, or tap a category"
                } else {
                    "Search for a place or tap a category"
                }
            } else {
                showSearchResults(idle)
                searchStatus.text = "Recent & favorites"
            }
        }
        syncPlacesIfChanged = {
            if (MapPlaces.revision != seenPlacesRev) {
                refreshPins()
                if (searchInput.text.isNullOrBlank()) showIdle()
            }
        }
        fun runDashSearch(query: String) {
            val q = query.trim()
            if (q.isEmpty()) {
                showIdle()
                return
            }
            searchStatus.text = "Searching…"
            searchResults.removeAllViews()
            val nearLat = lastLoc?.latitude
            val nearLon = lastLoc?.longitude
            // Always hit online (Photon/Nominatim) for place names so big nearby cities
            // rank above offline pack hits / tiny name collisions. Offline is fallback only.
            val offline = OfflinePoiIndex.search(context, q, nearLat, nearLon)
            NominatimSearch.searchAsync(
                q, nearLat, nearLon,
                onResult = { list -> main.post {
                    if (!isAlive() || released) return@post
                    showSearchResults(if (list.isNotEmpty()) list else offline)
                } },
                onError = { err -> main.post {
                    if (!isAlive() || released) return@post
                    if (offline.isNotEmpty()) showSearchResults(offline)
                    else searchStatus.text = err
                } },
            )
        }
        // Tag a nearby-POI list with its distance from the rider (nearest first) for display.
        fun withDistance(list: List<MapPlace>, loc: android.location.Location, label: String): List<MapPlace> =
            list.map { p ->
                val dM = OverpassClient.approxMetres(loc.latitude, loc.longitude, p.lat, p.lon)
                val dist = GpxNav.formatDistance(dM, units)
                val sub = listOf(dist, p.subtitle).filter { it.isNotBlank() }.joinToString(" · ")
                p.copy(category = label, subtitle = sub)
            }.sortedBy { OverpassClient.approxMetres(loc.latitude, loc.longitude, it.lat, it.lon) }
        fun runDashPoi(chip: PoiChip) {
            val loc = lastLoc
            if (loc == null) {
                searchStatus.text = "Need GPS for nearby POI"
                return
            }
            searchStatus.text = "Finding ${chip.label} nearby…"
            searchResults.removeAllViews()
            // Offline area first (instant, no data). Then a real Overpass "around me" tag search —
            // actual fuel stations / cafes near you, sorted by distance, not a name search.
            val offline = OfflinePoiIndex.nearbyCategory(
                context, chip.label, loc.latitude, loc.longitude,
            )
            if (offline.isNotEmpty()) {
                showSearchResults(withDistance(offline, loc, chip.label))
                return
            }
            OverpassClient.nearbyByTagAsync(
                chip.query, loc.latitude, loc.longitude,
                onResult = { list ->
                    main.post {
                        if (!isAlive() || released) return@post
                        if (list.isEmpty()) {
                            searchStatus.text = "No ${chip.label.lowercase()} found nearby"
                        } else {
                            searchStatus.text = "${list.size} ${chip.label.lowercase()} nearby"
                            showSearchResults(withDistance(list, loc, chip.label))
                        }
                    }
                },
                onError = { err -> main.post {
                    if (!isAlive() || released) return@post
                    searchStatus.text = err
                } },
            )
        }

        muteBtn.text = if (voiceOn) "Voice on" else "Voice muted"
        refreshSettingsLabels()
        statusView.text = when (liveMode) {
            GpxSession.Mode.FREE_RIDE -> "GPS… · free ride"
            GpxSession.Mode.NAV_TO -> "GPS… · navigate to ${liveDest?.name ?: "?"}"
            GpxSession.Mode.GPX -> {
                val nav = liveNav
                if (nav != null && nav.totalM > 0) {
                    "GPS… · ${GpxNav.formatDistance(nav.totalM, units)} track"
                } else "GPS…"
            }
        }
        wLeft.text = when {
            liveNav != null && liveNav!!.totalM > 0 ->
                GpxNav.formatDistance(liveNav!!.totalM, units)
            else -> "—"
        }

        searchChips.removeAllViews()
        for (chip in NominatimSearch.POI_CHIPS) {
            searchChips.addView(
                TextView(context).apply {
                    text = chip.label
                    setTextColor(0xFF3C4043.toInt())
                    textSize = 14f
                    setPadding(36, 18, 36, 18)
                    setBackgroundResource(R.drawable.bg_chip_pill)
                    isClickable = true
                    isFocusable = true
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).also { it.marginEnd = 16 }
                    layoutParams = lp
                    setOnClickListener { runDashPoi(chip) }
                },
            )
        }

        root.findViewById<View>(R.id.gpx_open_menu).setOnClickListener { showMenu() }
        searchPill.setOnClickListener { showSearch() }
        val quickMute = root.findViewById<View>(R.id.gpx_btn_mute)
        val quickNorth = root.findViewById<View>(R.id.gpx_btn_north)
        fun syncQuickLabels() {
            quickMute.alpha = if (voiceOn) 1f else 0.4f
            // Compass needle points to true north: rotate opposite the map bearing when heading-up.
            quickNorth.rotation = if (headingUp) -lastMapBearing else 0f
            quickNorth.alpha = if (headingUp) 1f else 0.55f
            northBtn.text = if (headingUp) "Heading up" else "North up"
        }
        // Best available heading: GPS course while riding, else compass, else last known.
        fun bestBearing(): Float {
            val loc = lastLoc
            val moving = loc?.let { it.hasSpeed() && it.speed > 1.2f } ?: false
            return when {
                loc != null && loc.hasBearing() && moving -> loc.bearing
                haveDeviceHeading -> deviceHeading
                else -> lastMapBearing
            }
        }
        // Filled after applyProgress exists — compass ticks refresh off-road turn arrows.
        var onHeadingTick: (() -> Unit)? = null
        syncQuickLabels()

        // Real compass: rotation-vector sensor drives the needle, and (while stopped in
        // heading-up mode) rotates the map to where the device is physically pointing.
        run {
            val smgr = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            val rotSensor = smgr?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            if (smgr != null && rotSensor != null) {
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                val rotMat = FloatArray(9)
                val adjusted = FloatArray(9)
                val orient = FloatArray(3)
                var smoothSin = 0.0
                var smoothCos = 0.0
                var haveSmooth = false
                var lastApplyMs = 0L
                val cl = object : SensorEventListener {
                    override fun onSensorChanged(e: SensorEvent) {
                        if (released || !isAlive()) return
                        SensorManager.getRotationMatrixFromVector(rotMat, e.values)
                        @Suppress("DEPRECATION")
                        val disp = wm?.defaultDisplay?.rotation ?: Surface.ROTATION_0
                        val (ax, ay) = when (disp) {
                            Surface.ROTATION_90 ->
                                SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
                            Surface.ROTATION_180 ->
                                SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
                            Surface.ROTATION_270 ->
                                SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
                            else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
                        }
                        SensorManager.remapCoordinateSystem(rotMat, ax, ay, adjusted)
                        SensorManager.getOrientation(adjusted, orient)
                        val r = orient[0].toDouble() // azimuth, radians
                        if (!haveSmooth) {
                            smoothSin = Math.sin(r); smoothCos = Math.cos(r); haveSmooth = true
                        } else {
                            val a = 0.15
                            smoothSin += a * (Math.sin(r) - smoothSin)
                            smoothCos += a * (Math.cos(r) - smoothCos)
                        }
                        var az = Math.toDegrees(Math.atan2(smoothSin, smoothCos)).toFloat()
                        if (az < 0f) az += 360f
                        deviceHeading = az
                        haveDeviceHeading = true

                        val now = SystemClock.uptimeMillis()
                        if (now - lastApplyMs < 120) return
                        lastApplyMs = now
                        val moving = lastLoc?.let { it.hasSpeed() && it.speed > 1.2f } ?: false
                        // Off-road arrow follows the phone even while standing still.
                        onHeadingTick?.invoke()
                        if (moving) return // GPS course drives the map/needle while riding
                        if (headingUp) {
                            lastMapBearing = az
                            quickNorth.rotation = -az
                            val loc = lastLoc
                            if (follow && !inPreview && loc != null) {
                                dashLocal.follow(
                                    loc.latitude, loc.longitude, az,
                                    if (speedZoom) followZoom(0f) else if (projected) 17.2 else 16.0,
                                    headingUp = true,
                                    moving = false,
                                )
                            }
                        } else {
                            quickNorth.rotation = 0f
                        }
                    }

                    override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
                }
                runCatching {
                    smgr.registerListener(cl, rotSensor, SensorManager.SENSOR_DELAY_UI)
                }
                sensorManager = smgr
                compassListener = cl
            }
        }
        val closePill = root.findViewById<View>(R.id.gpx_pill_close)
        val hubPill = root.findViewById<View>(R.id.gpx_pill_hub)
        val circuitPill = root.findViewById<View>(R.id.gpx_pill_circuit)
        if (projected) {
            // On the bike HUD there's no "back" and no Hub — those are phone-only, and starting an
            // Activity from the Presentation's display context crashes. Hide them entirely.
            closePill.visibility = View.GONE
            hubPill.visibility = View.GONE
            // Circuit stays — riders need a one-tap return loop on the bike.
            circuitPill?.visibility = View.VISIBLE
        } else {
            // On the phone map the back arrow already returns to the hub, so the separate Hub
            // button is redundant — keep just the back arrow + Circuit.
            hubPill.visibility = View.GONE
            circuitPill?.visibility = View.VISIBLE
            closePill.setOnClickListener { (context as? Activity)?.finish() }
        }
        root.findViewById<View>(R.id.gpx_btn_recenter).setOnClickListener {
            follow = true
            followBtn.text = "Follow on"
            main.removeCallbacks(recenter)
            val loc = lastLoc
            if (loc != null) {
                val moving = loc.hasSpeed() && loc.speed > 1.2f
                val brng = if (headingUp) bestBearing() else 0f
                lastMapBearing = brng
                dashLocal.follow(
                    loc.latitude, loc.longitude, brng,
                    followZoom(loc.speed * 3.6f),
                    headingUp = headingUp,
                    moving = moving,
                )
                syncQuickLabels()
                statusView.text = "Centered on GPS"
            } else {
                statusView.text = "No GPS yet — wait for a fix"
            }
        }
        root.findViewById<View>(R.id.gpx_btn_overview).setOnClickListener {
            bumpFreePan()
            follow = false
            headingUp = false
            dashLocal.resetNorth()
            syncQuickLabels()
            val remain = liveNav?.let { n ->
                val loc = lastLoc
                if (loc != null) {
                    val prog = n.progress(
                        loc.latitude, loc.longitude, loc.speed,
                        if (loc.hasAltitude()) loc.altitude else null, units,
                    )
                    if (prog != null) n.pointsRemain(prog.nearestIndex).map { it.lat to it.lon }
                    else n.pointsRemain(0).map { it.lat to it.lon }
                } else n.pointsRemain(0).map { it.lat to it.lon }
            } ?: initialRoutePts
            if (remain != null && remain.size >= 2) {
                dashLocal.zoomToRoute(remain)
                statusView.text = "Route overview · north up"
            } else {
                liveDest?.let { dashLocal.setCenter(it.lat, it.lon, 14.0) }
                    ?: lastLoc?.let { dashLocal.setCenter(it.latitude, it.longitude, 14.0) }
                statusView.text = "Overview · north up"
            }
        }
        root.findViewById<View>(R.id.gpx_btn_zoom_in).setOnClickListener {
            bumpFreePan()
            speedZoom = false
            dashLocal.zoomBy(1.0)
        }
        root.findViewById<View>(R.id.gpx_btn_zoom_out).setOnClickListener {
            bumpFreePan()
            speedZoom = false
            dashLocal.zoomBy(-1.0)
        }
        root.findViewById<View>(R.id.gpx_btn_mute).setOnClickListener {
            voiceOn = !voiceOn
            MapPrefs.setVoicePrompts(context, voiceOn)
            refreshSettingsLabels()
            syncQuickLabels()
        }
        root.findViewById<View>(R.id.gpx_btn_north).setOnClickListener {
            headingUp = !headingUp
            val loc = lastLoc
            if (!headingUp) {
                dashLocal.resetNorth()
                lastMapBearing = 0f
            } else {
                // Adopt the current heading immediately so the needle and map agree.
                val brng = bestBearing()
                lastMapBearing = brng
                if (follow && loc != null) {
                    val moving = loc.hasSpeed() && loc.speed > 1.2f
                    dashLocal.follow(
                        loc.latitude, loc.longitude, brng,
                        followZoom(loc.speed * 3.6f),
                        headingUp = true,
                        moving = moving,
                    )
                }
            }
            syncQuickLabels()
            statusView.text = if (headingUp) "Heading up" else "North up"
        }
        root.findViewById<View>(R.id.gpx_menu_close).setOnClickListener { hideSheets() }
        root.findViewById<View>(R.id.gpx_search_close).setOnClickListener { hideSheets() }
        dimmer.setOnClickListener { hideSheets() }
        // Phone → dash search bridge: the phone keyboard types into this (projected) search.
        DashRemote.setHandler { q ->
            main.post {
                if (!isAlive() || released) return@post
                menuPanel.visibility = View.GONE
                quickBar.visibility = View.GONE
                dimmer.visibility = View.GONE
                searchPanel.visibility = View.VISIBLE
                searchPanel.bringToFront()
                searchInput.setText(q)
                searchInput.setSelection(q.length)
                runDashSearch(q)
            }
        }
        // Phone/cockpit → dash navigate bridge: re-target the LIVE (already-projected) dash to a
        // destination — route + turn-by-turn — with NO PXC reconnect. Same path as the "Go" button.
        DashRemote.setNavHandler { place ->
            main.post {
                if (!isAlive() || released) return@post
                startNavigationTo(place)
            }
        }
        // Phone/cockpit → dash theme bridge: the on-map day/night/auto toggle flips the LIVE dash in
        // place (no PXC reconnect). The phone already wrote NightPrefs; applyMapTheme re-reads it.
        DashRemote.setThemeHandler {
            main.post {
                if (!isAlive() || released) return@post
                applyMapTheme()
            }
        }
        root.findViewById<View>(R.id.gpx_search_go).setOnClickListener {
            runDashSearch(searchInput.text?.toString().orEmpty())
        }
        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                runDashSearch(searchInput.text?.toString().orEmpty()); true
            } else false
        }
        val suggestRunnable = Runnable {
            if (!isAlive() || released) return@Runnable
            if (searchPanel.visibility != View.VISIBLE) return@Runnable
            val q = searchInput.text?.toString()?.trim().orEmpty()
            if (q.length >= 2) runDashSearch(q)
        }
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                main.removeCallbacks(suggestRunnable)
                val q = s?.toString()?.trim().orEmpty()
                if (q.length < 2) {
                    showIdle()
                    return
                }
                searchStatus.text = "Suggestions…"
                main.postDelayed(suggestRunnable, 350)
            }
        })
        root.findViewById<Button>(R.id.gpx_preview_go).setOnClickListener {
            val place = pendingPlace ?: return@setOnClickListener
            startNavigationTo(place)
        }
        fun wirePreviewAction(id: Int, onTap: () -> Unit) {
            root.findViewById<View>(id)?.apply {
                isClickable = true
                isFocusable = true
                setOnClickListener { onTap() }
            }
        }
        wirePreviewAction(R.id.gpx_preview_cancel) { cancelPreview() }
        wirePreviewAction(R.id.gpx_preview_save) {
            val p = pendingPlace ?: return@wirePreviewAction
            MapPlaces.addFavorite(context, p)
            refreshPins()
            statusView.text = "Saved ${p.name} to favorites"
            if (voiceOn) voiceLocal.speak("Saved")
            if (pinSetupOnly) cancelPreview()
        }
        wirePreviewAction(R.id.gpx_preview_home) {
            val p = pendingPlace ?: return@wirePreviewAction
            MapPlaces.setHome(context, p.copy(name = if (p.name == "Dropped pin") "Home" else p.name))
            refreshPins()
            statusView.text = "Home set"
            if (voiceOn) voiceLocal.speak("Home set")
            if (pinSetupOnly) cancelPreview()
        }
        wirePreviewAction(R.id.gpx_preview_marker) {
            val p = pendingPlace ?: return@wirePreviewAction
            MapPlaces.addMarker(context, p)
            refreshPins()
            statusView.text = "Marker added"
            if (voiceOn) voiceLocal.speak("Marker added")
            if (pinSetupOnly) cancelPreview()
        }
        root.findViewById<Button>(R.id.gpx_arrive_park)?.setOnClickListener {
            val loc = lastLoc
            if (loc == null) {
                statusView.text = "No GPS for parking"
                return@setOnClickListener
            }
            val place = MapPlace(
                "Parked", loc.latitude, loc.longitude, "parking", "Marked on arrival",
            )
            MapPlaces.setParked(context, place)
            refreshPins()
            hideArriveCard()
            statusView.text = "Parking marked"
            if (voiceOn) voiceLocal.speak("Parking marked")
        }
        root.findViewById<Button>(R.id.gpx_arrive_find)?.setOnClickListener {
            hideArriveCard()
            showSearch()
            val parking = NominatimSearch.POI_CHIPS.firstOrNull {
                it.label.equals("Parking", ignoreCase = true)
            }
            if (parking != null) runDashPoi(parking)
            else runDashSearch("parking")
        }
        root.findViewById<Button>(R.id.gpx_arrive_done)?.setOnClickListener {
            hideArriveCard()
            statusView.text = "Free ride"
        }
        refreshChrome()

        followBtn.setOnClickListener {
            follow = !follow
            followBtn.text = if (follow) "Follow on" else "Free pan"
            main.removeCallbacks(recenter)
        }
        northBtn.setOnClickListener {
            headingUp = !headingUp
            val loc = lastLoc
            if (!headingUp) {
                dashLocal.resetNorth()
                lastMapBearing = 0f
            } else {
                val brng = bestBearing()
                lastMapBearing = brng
                if (follow && loc != null) {
                    val moving = loc.hasSpeed() && loc.speed > 1.2f
                    dashLocal.follow(
                        loc.latitude, loc.longitude, brng,
                        followZoom(loc.speed * 3.6f),
                        headingUp = true,
                        moving = moving,
                    )
                }
            }
            syncQuickLabels()
        }
        muteBtn.setOnClickListener {
            voiceOn = !voiceOn
            MapPrefs.setVoicePrompts(context, voiceOn)
            refreshSettingsLabels()
            syncQuickLabels()
        }
        root.findViewById<Button>(R.id.gpx_zoom_in).setOnClickListener {
            bumpFreePan()
            speedZoom = false
            dashLocal.zoomBy(1.0)
        }
        root.findViewById<Button>(R.id.gpx_zoom_out).setOnClickListener {
            bumpFreePan()
            speedZoom = false
            dashLocal.zoomBy(-1.0)
        }
        fun endRouteNow() {
            if (inPreview) {
                cancelPreview()
                return
            }
            if (liveMode == GpxSession.Mode.FREE_RIDE) {
                statusView.text = "Already free ride"
                hideSheets()
                return
            }
            // Manual stop ≠ arrival — never flash "Arrived at …" / parking sheet.
            finishToFreeRideUi("Navigation finished", offerParking = false)
            refreshChrome()
            hideSheets()
            onChromeFocus?.invoke("default")
        }
        root.findViewById<Button>(R.id.gpx_end).setOnClickListener { endRouteNow() }
        root.findViewById<Button>(R.id.gpx_end_bar).setOnClickListener { endRouteNow() }
        root.findViewById<Button>(R.id.gpx_park).setOnClickListener {
            val loc = lastLoc
            if (loc == null) {
                statusView.text = "No GPS for parking"
                return@setOnClickListener
            }
            val existing = MapPlaces.parked(context)
            if (existing != null &&
                GpxNav.haversineM(loc.latitude, loc.longitude, existing.lat, existing.lon) < 40.0
            ) {
                // Same spot → clear. Farther away → replace with a new parking pin.
                MapPlaces.clearParked(context)
                refreshPins()
                statusView.text = "Parked spot cleared"
                if (voiceOn) voiceLocal.speak("Parking cleared")
                hideSheets()
                return@setOnClickListener
            }
            val place = MapPlace(
                "Parked", loc.latitude, loc.longitude, "parking", "Marked on dash",
            )
            MapPlaces.setParked(context, place)
            refreshPins()
            statusView.text = if (existing != null) "Parking updated" else "Parking marked"
            if (voiceOn) voiceLocal.speak(if (existing != null) "Parking updated" else "Parking marked")
            hideSheets()
        }
        root.findViewById<Button>(R.id.gpx_go_home)?.setOnClickListener {
            val home = MapPlaces.home(context)
            if (home == null) {
                statusView.text = "No Home set — long-press map or use Set Home"
                hideSheets()
                return@setOnClickListener
            }
            hideSheets()
            previewPlace(home)
        }
        root.findViewById<Button>(R.id.gpx_set_home)?.setOnClickListener {
            val loc = lastLoc
            if (loc == null) {
                statusView.text = "No GPS to set Home"
                return@setOnClickListener
            }
            val place = MapPlace("Home", loc.latitude, loc.longitude, "home", "Set on dash")
            MapPlaces.setHome(context, place)
            refreshPins()
            statusView.text = "Home set here"
            if (voiceOn) voiceLocal.speak("Home set")
            hideSheets()
        }
        root.findViewById<Button>(R.id.gpx_recalc).setOnClickListener {
            follow = true
            followBtn.text = "Follow on"
            main.removeCallbacks(recenter)
            units = MapPrefs.units(context)
            showNextStop = MapPrefs.showNextStop(context)
            autoFinish = MapPrefs.autoFinishOnArrive(context)
            buildings3d = MapPrefs.buildings3d(context)
            root.setBackgroundColor(MapPrefs.chrome(context).color)
            applyUnitLabels()
            applyMapTheme()
            refreshTitle()
            refreshSettingsLabels()
            val loc = lastLoc
            when {
                liveMode == GpxSession.Mode.NAV_TO && liveDest != null && loc != null -> {
                    requestOsrm(loc, "recalc")
                }
                liveNav != null && loc != null -> {
                    val prog = liveNav!!.progress(
                        loc.latitude, loc.longitude, loc.speed,
                        if (loc.hasAltitude()) loc.altitude else null, units,
                    )
                    if (prog != null) {
                        val pts = liveNav!!.pointsRemain(prog.nearestIndex)
                        if (pts.isNotEmpty()) {
                            dashLocal.setCenter(pts.first().lat, pts.first().lon, 15.0)
                        }
                        statusView.setTextColor(statusColorNormal)
                        statusView.text = "Snap · back on track"
                        if (voiceOn) voiceLocal.speak("Back on track")
                    }
                }
                loc != null -> {
                    dashLocal.setCenter(loc.latitude, loc.longitude)
                    statusView.text = "Recentered"
                    if (voiceOn) voiceLocal.speak("Centered")
                }
                else -> statusView.text = "No GPS"
            }
            hideSheets()
        }

        fun wireSeg(id: Int, action: () -> Unit) {
            root.findViewById<MaterialButton>(id)?.setOnClickListener { action() }
        }
        wireSeg(R.id.gpx_units_metric) {
            units = MapUnits.METRIC
            MapPrefs.setUnits(context, units)
            applyUnitLabels()
            refreshSettingsLabels()
        }
        wireSeg(R.id.gpx_units_imperial) {
            units = MapUnits.IMPERIAL
            MapPrefs.setUnits(context, units)
            applyUnitLabels()
            refreshSettingsLabels()
        }
        wireSeg(R.id.gpx_theme_auto) {
            NightPrefs.setTheme(context, MapTheme.AUTO)
            applyMapTheme()
            refreshSettingsLabels()
        }
        wireSeg(R.id.gpx_theme_day) {
            NightPrefs.setTheme(context, MapTheme.DAY)
            applyMapTheme()
            refreshSettingsLabels()
        }
        wireSeg(R.id.gpx_theme_night) {
            NightPrefs.setTheme(context, MapTheme.NIGHT)
            applyMapTheme()
            refreshSettingsLabels()
        }
        wireSeg(R.id.gpx_voice_on) {
            voiceOn = true
            MapPrefs.setVoicePrompts(context, true)
            refreshSettingsLabels()
        }
        wireSeg(R.id.gpx_voice_off) {
            voiceOn = false
            MapPrefs.setVoicePrompts(context, false)
            refreshSettingsLabels()
        }
        wireSeg(R.id.gpx_next_stop_on) {
            showNextStop = true
            MapPrefs.setShowNextStop(context, true)
            refreshSettingsLabels()
        }
        wireSeg(R.id.gpx_next_stop_off) {
            showNextStop = false
            MapPrefs.setShowNextStop(context, false)
            refreshSettingsLabels()
        }
        wireSeg(R.id.gpx_auto_finish_on) {
            autoFinish = true
            MapPrefs.setAutoFinishOnArrive(context, true)
            refreshSettingsLabels()
        }
        wireSeg(R.id.gpx_auto_finish_off) {
            autoFinish = false
            MapPrefs.setAutoFinishOnArrive(context, false)
            refreshSettingsLabels()
        }
        wireSeg(R.id.gpx_chrome_dark) {
            MapPrefs.setChrome(context, MapChrome.DARK)
            root.setBackgroundColor(MapPrefs.chrome(context).color)
            refreshSettingsLabels()
        }
        wireSeg(R.id.gpx_chrome_black) {
            MapPrefs.setChrome(context, MapChrome.BLACK)
            root.setBackgroundColor(MapPrefs.chrome(context).color)
            refreshSettingsLabels()
        }
        wireSeg(R.id.gpx_chrome_slate) {
            MapPrefs.setChrome(context, MapChrome.SLATE)
            root.setBackgroundColor(MapPrefs.chrome(context).color)
            refreshSettingsLabels()
        }
        wireSeg(R.id.gpx_chrome_forest) {
            MapPrefs.setChrome(context, MapChrome.FOREST)
            root.setBackgroundColor(MapPrefs.chrome(context).color)
            refreshSettingsLabels()
        }
        wireSeg(R.id.gpx_buildings_on) {
            buildings3d = true
            MapPrefs.setBuildings3d(context, true)
            dashLocal.setBuildings3d(true)
            refreshSettingsLabels()
        }
        wireSeg(R.id.gpx_buildings_off) {
            buildings3d = false
            MapPrefs.setBuildings3d(context, false)
            dashLocal.setBuildings3d(false)
            refreshSettingsLabels()
        }
        wireSeg(R.id.gpx_route_fast) {
            MapPrefs.setRouteMode(context, RouteMode.FAST)
            refreshSettingsLabels()
            pendingPlace?.let { previewPlace(it) }
        }
        wireSeg(R.id.gpx_route_fun) {
            MapPrefs.setRouteMode(context, RouteMode.FUN)
            refreshSettingsLabels()
            pendingPlace?.let { previewPlace(it) }
        }
        wireSeg(R.id.gpx_avoid_tolls_on) {
            MapPrefs.setAvoidTolls(context, true)
            refreshSettingsLabels()
            pendingPlace?.let { previewPlace(it) }
        }
        wireSeg(R.id.gpx_avoid_tolls_off) {
            MapPrefs.setAvoidTolls(context, false)
            refreshSettingsLabels()
            pendingPlace?.let { previewPlace(it) }
        }
        wireSeg(R.id.gpx_avoid_hwy_on) {
            MapPrefs.setAvoidHighways(context, true)
            refreshSettingsLabels()
            pendingPlace?.let { previewPlace(it) }
        }
        wireSeg(R.id.gpx_avoid_hwy_off) {
            MapPrefs.setAvoidHighways(context, false)
            refreshSettingsLabels()
            pendingPlace?.let { previewPlace(it) }
        }
        wireSeg(R.id.gpx_circuit_30) {
            MapPrefs.setFunCircuitKm(context, 30)
            refreshSettingsLabels()
        }
        wireSeg(R.id.gpx_circuit_60) {
            MapPrefs.setFunCircuitKm(context, 60)
            refreshSettingsLabels()
        }
        wireSeg(R.id.gpx_circuit_100) {
            MapPrefs.setFunCircuitKm(context, 100)
            refreshSettingsLabels()
        }
        fun startCircuit() {
            val loc = lastLoc
            if (loc == null) {
                statusView.text = "Need GPS for a circuit"
                hideSheets()
                return
            }
            val place = pendingPlace
            if (place == null) {
                statusView.text = "Pick a destination, then Circuit (there & back)"
                hideSheets()
                return
            }
            val sameSpot = GpxNav.haversineM(
                loc.latitude, loc.longitude, place.lat, place.lon,
            ) < 120.0
            if (sameSpot) {
                statusView.text = "Pick a place away from you, then Circuit"
                hideSheets()
                return
            }
            hideSheets()
            statusView.text = "Building there & back to ${place.name}…"
            pendingRoute = null
            pendingRoutes = emptyList()
            selectedRouteIdx = 0
            inPreview = true
            follow = false
            speedZoom = false
            dashLocal.setDestination(place)
            dashLocal.clearRoutes()
            previewName.text = place.name
            previewMeta.text = "There & back · returning home"
            refreshChrome()
            // Extracted Router.circuit() = out-and-back to the picked place, one route (or empty +
            // warning). Old empty/error path set previewMeta + statusView to the message; preserved.
            val router = overtakeRouter(context)
            val opts = MapPrefs.routeOptions(context)
            val f = dev.overtake.maps.model.GeoPoint(loc.latitude, loc.longitude)
            val t = dev.overtake.maps.model.GeoPoint(place.lat, place.lon)
            routeScope.launch {
                val res = runCatching { router.circuit(f, t, opts) }.getOrNull()
                if (!isAlive() || released || pendingPlace != place) return@launch
                val routes = res?.routes.orEmpty()
                if (routes.isEmpty()) {
                    val msg = res?.warning ?: "Couldn't build there-and-back circuit"
                    previewMeta.text = msg
                    statusView.text = msg
                    return@launch
                }
                pendingRoutes = routes
                selectRoute(0, loc, place)
                previewMeta.text = listOfNotNull(
                    previewMeta.text?.toString()?.takeIf { it.isNotBlank() },
                    "There & back · returns home",
                ).joinToString(" · ")
            }
            if (voiceOn) voiceLocal.speak("Building there and back circuit")
        }
        root.findViewById<Button>(R.id.gpx_create_circuit)?.setOnClickListener { startCircuit() }
        wirePreviewAction(R.id.gpx_preview_circuit) { startCircuit() }
        root.findViewById<View>(R.id.gpx_pill_circuit)?.setOnClickListener { startCircuit() }
        if (GpxSession.pendingCircuit) {
            GpxSession.pendingCircuit = false
            main.postDelayed({
                if (!isAlive() || released) return@postDelayed
                if (pendingPlace != null) startCircuit()
                else statusView.text = "Pick a destination, then Circuit (there & back)"
            }, 600)
        }
        // Legacy cycle buttons (gone in layout) — keep working if an old layout is inflated.
        root.findViewById<Button>(R.id.gpx_set_units)?.setOnClickListener {
            units = MapPrefs.cycleUnits(context)
            applyUnitLabels()
            refreshSettingsLabels()
        }
        root.findViewById<Button>(R.id.gpx_set_theme)?.setOnClickListener {
            NightPrefs.cycle(context)
            applyMapTheme()
            refreshSettingsLabels()
        }
        root.findViewById<Button>(R.id.gpx_set_voice)?.setOnClickListener {
            voiceOn = !voiceOn
            MapPrefs.setVoicePrompts(context, voiceOn)
            refreshSettingsLabels()
        }
        root.findViewById<Button>(R.id.gpx_set_next)?.setOnClickListener {
            showNextStop = !showNextStop
            MapPrefs.setShowNextStop(context, showNextStop)
            refreshSettingsLabels()
        }
        root.findViewById<Button>(R.id.gpx_set_arrive)?.setOnClickListener {
            autoFinish = !autoFinish
            MapPrefs.setAutoFinishOnArrive(context, autoFinish)
            refreshSettingsLabels()
        }
        root.findViewById<Button>(R.id.gpx_set_chrome)?.setOnClickListener {
            MapPrefs.cycleChrome(context)
            root.setBackgroundColor(MapPrefs.chrome(context).color)
            refreshSettingsLabels()
        }
        root.findViewById<Button>(R.id.gpx_set_3d)?.setOnClickListener {
            buildings3d = !buildings3d
            MapPrefs.setBuildings3d(context, buildings3d)
            dashLocal.setBuildings3d(buildings3d)
            refreshSettingsLabels()
        }
        recordBtn.setOnClickListener {
            val rec = TripLogger.get(context)
            if (rec.recording) {
                rec.stopAndSave()
                statusView.text = "Ride recording saved"
                if (voiceOn) voiceLocal.speak("Recording saved")
            } else {
                val ok = rec.start()
                statusView.text = if (ok) "Recording ride…" else "Can't record — check GPS / permission"
                if (ok && voiceOn) voiceLocal.speak("Recording started")
            }
            refreshSettingsLabels()
        }

        dashLocal.setOnLongPress { lat, lon ->
            main.post {
                if (!isAlive() || released) return@post
                hideSheets()
                val place = MapPlace(
                    "Dropped pin", lat, lon, "pin",
                    String.format("%.5f, %.5f", lat, lon),
                )
                if (voiceOn) voiceLocal.speak("Pin dropped")
                showPinSetup(place)
            }
        }

        GpxSession.setTouchTarget(root)

        // Handlebar / Controls pad → same ButtonMap actions as AA (knob = focus, Select = OK).
        if (projected) {
            var focusIdx = 0
            var focusedView: View? = null
            val focusColor = 0xFFFF9800.toInt()
            val focusFill = 0x66FF9800
            val endBar = root.findViewById<View>(R.id.gpx_end_bar)
            fun clearFocusPaint(v: View?) {
                if (v == null) return
                // Never scale focus — 1.18× dragged place names / chips outside the panel.
                v.scaleX = 1f
                v.scaleY = 1f
                v.elevation = 0f
                v.translationZ = 0f
                v.alpha = 1f
                try {
                    v.foreground = null
                } catch (_: Exception) {
                }
            }
            fun paintFocus(v: View) {
                clearFocusPaint(focusedView)
                focusedView = v
                v.scaleX = 1f
                v.scaleY = 1f
                v.elevation = 12f
                v.translationZ = 8f
                v.alpha = 1f
                try {
                    val d = context.resources.displayMetrics.density
                    val ring = android.graphics.drawable.GradientDrawable().apply {
                        setColor(focusFill)
                        setStroke((5 * d).toInt(), focusColor)
                        cornerRadius = 12 * d
                    }
                    v.foreground = ring
                } catch (_: Exception) {
                }
                try {
                    // Keep focused row inside the ScrollView without expanding past edges.
                    v.requestRectangleOnScreen(
                        android.graphics.Rect(0, 0, v.width.coerceAtLeast(1), v.height.coerceAtLeast(1)),
                        true,
                    )
                } catch (_: Exception) {
                }
            }
            fun collectFocusables(): List<View> {
                val out = ArrayList<View>()
                fun add(v: View?) {
                    if (v == null) return
                    if (v.visibility != View.VISIBLE || !v.isShown || !v.isEnabled) return
                    if (!v.isClickable && !v.isFocusable) return
                    out.add(v)
                }
                fun addChildren(host: ViewGroup?) {
                    if (host == null || host.visibility != View.VISIBLE) return
                    for (i in 0 until host.childCount) add(host.getChildAt(i))
                }
                when {
                    arriveCard.visibility == View.VISIBLE -> {
                        add(root.findViewById(R.id.gpx_arrive_park))
                        add(root.findViewById(R.id.gpx_arrive_find))
                        add(root.findViewById(R.id.gpx_arrive_done))
                    }
                    previewCard.visibility == View.VISIBLE -> {
                        add(root.findViewById(R.id.gpx_preview_go))
                        add(root.findViewById(R.id.gpx_preview_circuit))
                        add(root.findViewById(R.id.gpx_preview_cancel))
                        add(root.findViewById(R.id.gpx_preview_save))
                        add(root.findViewById(R.id.gpx_preview_home))
                        add(root.findViewById(R.id.gpx_preview_marker))
                        addChildren(previewAlts)
                    }
                    searchPanel.visibility == View.VISIBLE -> {
                        addChildren(searchChips)
                        addChildren(searchResults)
                        add(root.findViewById(R.id.gpx_search_go))
                        add(root.findViewById(R.id.gpx_search_close))
                    }
                    menuPanel.visibility == View.VISIBLE -> {
                        add(root.findViewById(R.id.gpx_end))
                        add(root.findViewById(R.id.gpx_park))
                        add(root.findViewById(R.id.gpx_go_home))
                        add(root.findViewById(R.id.gpx_set_home))
                        add(root.findViewById(R.id.gpx_recalc))
                        add(recordBtn)
                        add(root.findViewById(R.id.gpx_create_circuit))
                        add(followBtn)
                        add(northBtn)
                        add(muteBtn)
                        add(root.findViewById(R.id.gpx_zoom_out))
                        add(root.findViewById(R.id.gpx_zoom_in))
                        add(root.findViewById(R.id.gpx_menu_close))
                    }
                    else -> {
                        // Navigating: End first so Select stops the route without hunting.
                        if (hudBottom.visibility == View.VISIBLE) add(endBar)
                        if (searchPill.visibility == View.VISIBLE) {
                            add(searchPill)
                            add(circuitPill)
                        }
                        add(root.findViewById(R.id.gpx_btn_recenter))
                        add(root.findViewById(R.id.gpx_btn_overview))
                        add(root.findViewById(R.id.gpx_btn_zoom_in))
                        add(root.findViewById(R.id.gpx_btn_zoom_out))
                        add(root.findViewById(R.id.gpx_btn_mute))
                        add(root.findViewById(R.id.gpx_btn_north))
                        add(root.findViewById(R.id.gpx_open_menu))
                    }
                }
                return out
            }
            val goBtn = root.findViewById<View>(R.id.gpx_preview_go)
            fun ensureFocus(prefer: String = "default") {
                val list = collectFocusables()
                if (list.isEmpty()) {
                    clearFocusPaint(focusedView)
                    focusedView = null
                    return
                }
                when (prefer) {
                    "go" -> {
                        val i = goBtn?.let { list.indexOf(it) } ?: -1
                        if (i >= 0) focusIdx = i
                    }
                    "end" -> {
                        val i = endBar?.let { list.indexOf(it) } ?: -1
                        if (i >= 0) focusIdx = i
                    }
                    else -> if (focusedView !in list) focusIdx = 0
                }
                focusIdx = focusIdx.coerceIn(0, list.lastIndex)
                paintFocus(list[focusIdx])
            }
            fun moveFocus(delta: Int) {
                val list = collectFocusables()
                if (list.isEmpty()) return
                val cur = focusedView?.let { list.indexOf(it) } ?: -1
                focusIdx = if (cur >= 0) {
                    (cur + delta).mod(list.size)
                } else {
                    0
                }
                paintFocus(list[focusIdx])
            }
            fun defaultPrefer(): String = when {
                previewCard.visibility == View.VISIBLE -> "go"
                liveMode != GpxSession.Mode.FREE_RIDE && !inPreview -> "end"
                else -> "default"
            }
            fun toggleMenu() {
                if (menuPanel.visibility == View.VISIBLE) {
                    hideSheets()
                    ensureFocus(defaultPrefer())
                } else {
                    showMenu()
                    ensureFocus()
                }
            }
            fun toggleSearch() {
                if (searchPanel.visibility == View.VISIBLE) {
                    hideSheets()
                    ensureFocus(defaultPrefer())
                } else {
                    showSearch()
                    ensureFocus()
                }
            }
            fun handleMapKey(code: Int) {
                if (released || !isAlive()) return
                when (code) {
                    AaInput.KEY_ENTER -> {
                        ensureFocus()
                        val v = focusedView
                        if (v != null) v.performClick() else log("[GPX] Select — nothing focused")
                    }
                    AaInput.KEY_BACK -> {
                        when {
                            arriveCard.visibility == View.VISIBLE -> hideArriveCard()
                            previewCard.visibility == View.VISIBLE -> cancelPreview()
                            searchPanel.visibility == View.VISIBLE ||
                                menuPanel.visibility == View.VISIBLE -> hideSheets()
                            else -> log("[GPX] Back — idle")
                        }
                        ensureFocus(defaultPrefer())
                    }
                    // Universal map: ×2 → D-pad ←/→ (focus). HOME (Select hold) = menu.
                    AaInput.KEY_HOME -> toggleMenu()
                    AaInput.KEY_ASSISTANT -> toggleSearch()
                    AaInput.KEY_LEFT, AaInput.KEY_UP -> moveFocus(-1)
                    AaInput.KEY_RIGHT, AaInput.KEY_DOWN -> moveFocus(+1)
                    else -> log("[GPX] unused key $code")
                }
            }
            MapInputBridge.scrollSink = { delta ->
                main.post {
                    if (released || !isAlive()) return@post
                    moveFocus(delta)
                }
            }
            MapInputBridge.keySink = { code ->
                main.post {
                    if (released || !isAlive()) return@post
                    handleMapKey(code)
                }
            }
            onChromeFocus = { prefer ->
                main.post {
                    if (released || !isAlive()) return@post
                    ensureFocus(prefer)
                }
            }
            main.post { ensureFocus(defaultPrefer()) }
            log("[GPX] handlebar focus nav armed (ButtonMap → Map)")
        }

        log(
            "[GPX] dash map — mode=$liveMode " +
                "pts=${track?.points?.size ?: 0} wpt=${track?.waypoints?.size ?: 0} " +
                "len=${liveNav?.let { GpxNav.formatDistance(it.totalM, units) } ?: "—"}",
        )

        fun applyProgress(prog: GpxNav.Progress?, speedKmh: Float, loc: Location) {
            wSpeed.text = GpxNav.formatSpeed(speedKmh, units)
            val nav = liveNav
            val dest = liveDest
            if (prog != null && nav != null) {
                dashLocal.setRouteDone(
                    nav.pointsDone(prog.nearestIndex).map { it.lat to it.lon },
                )
                dashLocal.setRouteRemain(
                    nav.pointsRemain(prog.nearestIndex).map { it.lat to it.lon },
                )
                wTime.text = GpxNav.formatEta(prog.etaSec)
                wEta.text = GpxNav.formatArrival(prog.etaSec)
                wLeft.text = GpxNav.formatDistance(prog.remainM, units)
                wAlt.text = altitudeText(prog.altitudeM, loc)
                statusView.setTextColor(
                    if (prog.offTrackM > 80) statusColorOff else statusColorNormal,
                )
                // The maneuver card always carries content while navigating (Google-style).
                // Off the road: compass vs bearing-to-snap → turn left / right / go ahead
                // until back on the route; then normal turn-by-turn.
                val turn = prog.upcomingTurn
                maneuverRow.visibility = View.VISIBLE
                when {
                    prog.offTrackM > GpxNav.OFF_ROUTE_GUIDE_M -> {
                        val toSnap = GpxNav.bearingDeg(
                            loc.latitude, loc.longitude, prog.snapLat, prog.snapLon,
                        )
                        val dir = GpxNav.dirToFace(bestBearing(), toSnap)
                        cueIcon.setImageResource(GpxNav.iconResFor(dir))
                        cueDist.text = GpxNav.formatDistance(prog.offTrackM, units)
                        cueView.text = GpxNav.joinRouteWord(dir)
                        laneView.visibility = View.GONE
                        if (voiceOn) voiceLocal.onJoinRoute(dir, prog.offTrackM, units)
                    }
                    turn != null -> {
                        cueIcon.setImageResource(GpxNav.iconResFor(turn.dir))
                        cueDist.text = GpxNav.formatDistance(turn.distanceFromRiderM, units)
                        cueView.text = turn.roadName ?: maneuverWord(turn.dir)
                        if (turn.laneLabel != null) {
                            laneView.visibility = View.VISIBLE
                            laneView.text = turn.laneLabel
                        } else {
                            laneView.visibility = View.GONE
                        }
                        if (voiceOn) voiceLocal.onProgress(prog, units, loc.speed)
                    }
                    else -> {
                        // On route, no turn ahead → show the run-in to the destination.
                        cueIcon.setImageResource(R.drawable.ic_man_arrive)
                        cueDist.text = GpxNav.formatDistance(prog.remainM, units)
                        cueView.text = "To ${dest?.name ?: GpxSession.trackName}"
                        laneView.visibility = View.GONE
                        if (voiceOn) voiceLocal.onProgress(prog, units, loc.speed)
                    }
                }
                nextView.visibility = View.GONE
                if (prog.remainM < 40 && !destArrivedSpoken && canDeclareArrival(prog.remainM)) {
                    destArrivedSpoken = true
                    if (voiceOn) voiceLocal.speak("Destination reached")
                    if (autoFinish) {
                        main.postDelayed({
                            finishToFreeRideUi("Arrived", offerParking = true)
                            refreshChrome()
                        }, 2_000L)
                    } else {
                        showArriveCard(dest?.name)
                    }
                }
            } else if (dest != null) {
                val dM = GpxNav.haversineM(loc.latitude, loc.longitude, dest.lat, dest.lon)
                val etaSec = GpxNav.etaSeconds(dM, loc.speed)
                wTime.text = GpxNav.formatEta(etaSec)
                wEta.text = GpxNav.formatArrival(etaSec)
                wLeft.text = GpxNav.formatDistance(dM, units)
                wAlt.text = altitudeText(null, loc)
                dashLocal.setToDest(loc.latitude, loc.longitude, dest.lat, dest.lon)
                // No road polyline yet — compass toward the pin (same left/right/ahead).
                val toDest = GpxNav.bearingDeg(
                    loc.latitude, loc.longitude, dest.lat, dest.lon,
                )
                val dir = GpxNav.dirToFace(bestBearing(), toDest)
                maneuverRow.visibility = View.VISIBLE
                cueIcon.setImageResource(GpxNav.iconResFor(dir))
                cueDist.text = GpxNav.formatDistance(dM, units)
                cueView.text = when (dir) {
                    GpxNav.TurnDir.STRAIGHT, GpxNav.TurnDir.CONTINUE -> "Go ahead"
                    else -> GpxNav.joinRouteWord(dir)
                }
                laneView.visibility = View.GONE
                nextView.visibility = View.GONE
                if (voiceOn && dM > GpxNav.OFF_ROUTE_GUIDE_M) {
                    voiceLocal.onJoinRoute(dir, dM, units)
                }
                if (dM < 80 && !destArrivedSpoken && canDeclareArrival(dM)) {
                    destArrivedSpoken = true
                    if (voiceOn) voiceLocal.speak("Arriving at ${dest.name}")
                    if (autoFinish) {
                        main.postDelayed({
                            finishToFreeRideUi("Arrived", offerParking = true)
                            refreshChrome()
                        }, 2_500L)
                    } else {
                        showArriveCard(dest.name)
                    }
                }
            } else {
                wTime.text = "—"
                wLeft.text = "—"
                wEta.text = "—"
                wAlt.text = altitudeText(null, loc)
                maneuverRow.visibility = View.GONE
                laneView.visibility = View.GONE
                nextView.visibility = View.GONE
                statusView.setTextColor(statusColorNormal)
            }
        }

        // Compass turns update the join-route arrow without waiting for a GPS tick.
        onHeadingTick = headingTick@{
            if (released || !isAlive() || inPreview) return@headingTick
            val loc = lastLoc ?: return@headingTick
            val nav = liveNav
            val prog = nav?.progress(
                loc.latitude, loc.longitude, loc.speed,
                if (loc.hasAltitude()) loc.altitude else null, units,
            )
            val needGuide = when {
                prog != null && prog.offTrackM > GpxNav.OFF_ROUTE_GUIDE_M -> true
                prog == null && liveDest != null -> true
                else -> false
            }
            if (needGuide) applyProgress(prog, loc.speed * 3.6f, loc)
        }

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        locationManager = lm
        val listener = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                if (!isAlive() || released) return
                lastLoc = loc
                syncPlacesIfChanged()
                val speedKmhEarly = loc.speed * 3.6f
                chipSpeed.text = GpxNav.formatSpeed(speedKmhEarly, units)
                chipSpeedUnit?.text = GpxNav.speedUnitLabel(units)
                rideStats.onLocation(loc.latitude, loc.longitude, loc.speed)
                val alt = if (loc.hasAltitude()) loc.altitude else null
                val dest = liveDest
                if (liveMode == GpxSession.Mode.GPX && !gpxOriented && track != null) {
                    gpxOriented = true
                    val heading = if (loc.hasBearing() && loc.speed > 1.0f) loc.bearing else null
                    val (oriented, flipped) = track!!.orientedForRider(loc.latitude, loc.longitude, heading)
                    if (flipped) {
                        track = oriented
                        liveNav = GpxNav(oriented)
                        log("[GPX] reversed track to match rider heading")
                        if (voiceOn) voiceLocal.speak("Following the track the other way")
                        refreshTitle()
                    }
                }
                val nav = liveNav
                if (liveMode == GpxSession.Mode.NAV_TO && dest != null && nav == null && !osrmRequested) {
                    osrmRequested = true
                    requestOsrm(loc, "start")
                }
                val now = System.currentTimeMillis()
                if (now - lastSpeedFetchMs > 12_000L) {
                    lastSpeedFetchMs = now
                    OverpassClient.maxspeedAsync(loc.latitude, loc.longitude) { lim ->
                        main.post {
                            if (!isAlive() || released) return@post
                            val label = OverpassClient.formatLimit(lim, units)
                            wLimit.text = label
                            // EU-style round sign: just the number (no yellow "LIMIT" text).
                            if (label.isNotBlank() && label != "—") {
                                chipLimit.visibility = View.VISIBLE
                                chipLimit.text = label
                            } else {
                                chipLimit.visibility = View.GONE
                            }
                        }
                    }
                }
                val prog = nav?.progress(loc.latitude, loc.longitude, loc.speed, alt, units)
                val speedKmh = loc.speed * 3.6f
                statusView.text = when {
                    nav != null -> GpxNav.formatHud(
                        speedKmh, rideStats.avgKmh(), prog, nav.totalM, units, showNextStop,
                    )
                    dest != null -> {
                        val dM = GpxNav.haversineM(
                            loc.latitude, loc.longitude, dest.lat, dest.lon,
                        )
                        "${GpxNav.formatSpeed(speedKmh, units)} ${GpxNav.speedUnitLabel(units)} · " +
                            "${GpxNav.formatDistance(dM, units)} to ${dest.name} · " +
                            "avg ${GpxNav.formatSpeed(rideStats.avgKmh(), units)}"
                    }
                    else ->
                        "${GpxNav.formatSpeed(speedKmh, units)} ${GpxNav.speedUnitLabel(units)} · " +
                            "free ride · avg ${GpxNav.formatSpeed(rideStats.avgKmh(), units)}"
                }
                applyProgress(prog, speedKmh, loc)
                // Auto-reroute: sustained off-route in NAV_TO recalculates from the current spot.
                // Stickiness (Google-Maps): recompute fires ONLY here (genuine off-route) or on an explicit user re-route — NEVER on a connectivity change; no network callback recomputes a route.
                if (liveMode == GpxSession.Mode.NAV_TO && dest != null && prog != null) {
                    if (prog.offTrackM > 80) {
                        if (offRouteSinceMs == 0L) offRouteSinceMs = now
                        val offForMs = now - offRouteSinceMs
                        val cooledDown = now - lastAutoRerouteMs > 15_000L
                        if (offForMs > 8_000L && cooledDown && loc.hasAccuracy() && loc.accuracy < 60f) {
                            lastAutoRerouteMs = now
                            offRouteSinceMs = 0L
                            log("[GPX] auto-reroute (off ${prog.offTrackM.toInt()}m for ${offForMs / 1000}s)")
                            if (voiceOn) voiceLocal.speak("Rerouting")
                            requestOsrm(loc, "recalc")
                        }
                    } else {
                        offRouteSinceMs = 0L
                    }
                }
                val moving = loc.hasSpeed() && loc.speed > 1.2f
                val brng = when {
                    loc.hasBearing() && moving -> loc.bearing
                    headingUp && haveDeviceHeading -> deviceHeading
                    prog != null -> prog.trackBearingDeg
                    dest != null -> GpxNav.bearingDeg(
                        loc.latitude, loc.longitude, dest.lat, dest.lon,
                    )
                    else -> 0f
                }
                // Glue the puck to the route line when we're clearly on it — smooth, no
                // hopping between sparse points or landing ahead/behind the road.
                val onRoute = prog != null && prog.offTrackM < 40.0
                val meLat = if (onRoute) prog!!.snapLat else loc.latitude
                val meLon = if (onRoute) prog!!.snapLon else loc.longitude
                dashLocal.setMe(meLat, meLon, brng)
                if (follow && !inPreview) {
                    val z = if (speedZoom) followZoom(speedKmh) else if (projected) 17.2 else 16.0
                    dashLocal.follow(
                        meLat, meLon, brng, z,
                        headingUp = headingUp,
                        moving = moving,
                    )
                    lastMapBearing = if (headingUp) brng else 0f
                    quickNorth.rotation = if (headingUp) -lastMapBearing else 0f
                }
            }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        locationListener = listener
        try {
            val ok = ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.ACCESS_FINE_LOCATION,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (ok) {
                val provider = when {
                    lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                    lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                    else -> null
                }
                var subscribed = false
                if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    lm.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER, 800L, 1.5f, listener, Looper.getMainLooper(),
                    )
                    subscribed = true
                }
                if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    lm.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER, 2_000L, 5f, listener,
                        Looper.getMainLooper(),
                    )
                    subscribed = true
                }
                if (subscribed) {
                    listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                        .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
                        .maxByOrNull { it.time }
                        ?.let { listener.onLocationChanged(it) }
                } else {
                    statusView.text = "no GPS"
                }
            } else {
                statusView.text = "location off"
            }
        } catch (e: Exception) {
            log("[GPX] location failed: $e")
            statusView.text = "GPS error"
        }
        // Same auto-log path as AA projection — rides on the built-in map land in Saved trips.
        TripAutoLog.setMapUiLive(context, true)
        return true
    }

    private fun maneuverWord(dir: GpxNav.TurnDir): String = when (dir) {
        GpxNav.TurnDir.LEFT -> "Turn left"
        GpxNav.TurnDir.RIGHT -> "Turn right"
        GpxNav.TurnDir.SHARP_LEFT -> "Sharp left"
        GpxNav.TurnDir.SHARP_RIGHT -> "Sharp right"
        GpxNav.TurnDir.SLIGHT_LEFT -> "Slight left"
        GpxNav.TurnDir.SLIGHT_RIGHT -> "Slight right"
        GpxNav.TurnDir.UTURN -> "U-turn"
        GpxNav.TurnDir.ROUNDABOUT -> "Roundabout"
        GpxNav.TurnDir.MERGE_LEFT, GpxNav.TurnDir.MERGE_RIGHT -> "Merge"
        GpxNav.TurnDir.FORK_LEFT -> "Keep left"
        GpxNav.TurnDir.FORK_RIGHT -> "Keep right"
        GpxNav.TurnDir.STRAIGHT, GpxNav.TurnDir.CONTINUE -> "Continue"
        GpxNav.TurnDir.ARRIVE -> "Arrive"
    }

    fun applyTheme() {
        dash?.applyTheme(NightPrefs.isNightNow(context))
    }

    /** Phone keeps configChanges so layout-land isn't re-inflated on rotate — adapt here. */
    fun onConfigurationChanged() {
        if (released) return
        val navigating = root.findViewById<View>(R.id.gpx_hud)?.visibility == View.VISIBLE
        adaptChromeLayout(navigating)
    }

    /**
     * Waze-style chrome that works on any size:
     * - Portrait: full-width top banner + full-width bottom metrics.
     * - Landscape: compact left cards with a hard max width + FAB lane on the right,
     *   FABs parked bottom-right so they never sit under the bars.
     */
    private fun adaptChromeLayout(navigating: Boolean = false) {
        val hud = root.findViewById<LinearLayout>(R.id.gpx_hud) ?: return
        val bottom = root.findViewById<LinearLayout>(R.id.gpx_hud_bottom) ?: return
        val fabs = root.findViewById<LinearLayout>(R.id.gpx_quick_bar) ?: return
        val searchPill = root.findViewById<View>(R.id.gpx_search_pill)
        val speedChip = root.findViewById<View>(R.id.gpx_speed_chip)
        val cue = root.findViewById<TextView>(R.id.gpx_cue)
        val cueDist = root.findViewById<TextView>(R.id.gpx_cue_dist)
        val altCol = (root.findViewById<View>(R.id.gpx_l_alt)?.parent as? View)
        val wTime = root.findViewById<TextView>(R.id.gpx_w_time)
        val wLeft = root.findViewById<TextView>(R.id.gpx_w_left)
        val wEta = root.findViewById<TextView>(R.id.gpx_w_eta)
        val wAlt = root.findViewById<TextView>(R.id.gpx_w_alt)
        val d = root.resources.displayMetrics.density
        val land = root.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val fabLane = (88 * d).toInt()
        val edge = (8 * d).toInt()

        fun lpOf(v: View): FrameLayout.LayoutParams {
            val cur = v.layoutParams
            val lp = if (cur is FrameLayout.LayoutParams) {
                FrameLayout.LayoutParams(cur) // copy — mutating in place sometimes ignores gravity
            } else {
                FrameLayout.LayoutParams(cur?.width ?: ViewGroup.LayoutParams.WRAP_CONTENT,
                    cur?.height ?: ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            v.layoutParams = lp
            return lp
        }

        // Bottom bar always: TIME · ARRIVE · LEFT · ALT (speed/limit live on the gauge).
        altCol?.visibility = View.VISIBLE

        // Keep search pill + search sheet clear of the punch-hole / status bar.
        searchPill?.let {
            lpOf(it).apply {
                gravity = Gravity.TOP or Gravity.START
                marginStart = (12 * d).toInt() + insetL
                topMargin = insetT + edge
                marginEnd = (12 * d).toInt() + insetR
            }
        }
        root.findViewById<View>(R.id.gpx_search_panel)?.setPadding(
            0, insetT, 0, 0,
        )

        if (land) {
            // Bike projection: denser chrome so FABs/speed don't cover TIME/ARRIVE/LEFT.
            val metricSp = if (projected) 26f else 32f
            val leftSp = if (projected) 30f else 38f
            val cueSp = if (projected) 18f else 22f
            val cueDistSp = if (projected) 30f else 36f
            val fabEnd = if (projected) (6 * d).toInt() + insetR else (12 * d).toInt() + insetR
            val speedBottom = if (projected) {
                // Clear the metrics card (~72dp) + gap so the gauge sits above TIME/ARRIVE/LEFT.
                (88 * d).toInt() + insetB
            } else {
                (12 * d).toInt() + insetB
            }

            // Left cards — roomy, content centered in each cell; leave FAB lane on the right.
            hud.setBackgroundResource(R.drawable.bg_nav_card)
            bottom.setBackgroundResource(R.drawable.bg_nav_card)
            hud.gravity = Gravity.CENTER_VERTICAL
            lpOf(hud).apply {
                width = ViewGroup.LayoutParams.WRAP_CONTENT
                gravity = Gravity.TOP or Gravity.START
                marginStart = edge + insetL
                topMargin = edge + insetT
                marginEnd = fabLane + insetR
                leftMargin = marginStart
                rightMargin = marginEnd
            }
            hud.setPadding(
                (18 * d).toInt(), (14 * d).toInt(), (22 * d).toInt(), (14 * d).toInt(),
            )
            // Plenty of width in landscape — don't clip road names early.
            cue?.maxWidth = (420 * d).toInt()
            cue?.maxEms = 24
            cue?.includeFontPadding = false
            cueDist?.includeFontPadding = false
            cueDist?.textSize = cueDistSp
            cue?.textSize = cueSp
            root.findViewById<View>(R.id.gpx_maneuver_row)?.let { row ->
                if (row is LinearLayout) row.gravity = Gravity.CENTER_VERTICAL
            }

            lpOf(bottom).apply {
                width = ViewGroup.LayoutParams.WRAP_CONTENT
                gravity = Gravity.BOTTOM or Gravity.START
                marginStart = edge + insetL
                bottomMargin = edge + insetB
                marginEnd = fabLane + insetR
                leftMargin = marginStart
                rightMargin = marginEnd
            }
            bottom.setPadding(
                (22 * d).toInt(), (14 * d).toInt(), (22 * d).toInt(), (14 * d).toInt(),
            )
            listOfNotNull(wTime, wEta, wAlt).forEach {
                it.textSize = metricSp
                it.includeFontPadding = false
                it.gravity = Gravity.CENTER_HORIZONTAL
            }
            wLeft?.textSize = leftSp
            wLeft?.includeFontPadding = false
            wLeft?.gravity = Gravity.CENTER_HORIZONTAL
            // Equal, centered columns with breathing room (also when portrait XML is reused
            // after rotate because HudViewActivity keeps configChanges).
            val colGap = ((if (projected) 20 else 32) * d).toInt()
            val colMin = ((if (projected) 80 else 100) * d).toInt()
            listOfNotNull(wTime, wEta, wLeft, wAlt).forEachIndexed { i, tv ->
                val col = tv.parent as? LinearLayout ?: return@forEachIndexed
                col.gravity = Gravity.CENTER_HORIZONTAL
                col.orientation = LinearLayout.VERTICAL
                val lp = (col.layoutParams as? LinearLayout.LayoutParams)
                    ?: LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                lp.width = ViewGroup.LayoutParams.WRAP_CONTENT
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                lp.weight = 0f
                lp.marginEnd = if (i < 3) colGap else (24 * d).toInt()
                col.minimumWidth = if (tv === wLeft) ((if (projected) 90 else 110) * d).toInt() else colMin
                col.layoutParams = lp
            }
            (bottom.getChildAt(0) as? LinearLayout)?.gravity = Gravity.CENTER_VERTICAL
            // Status line under the metrics adds empty vertical space — fold into the row via End.
            root.findViewById<View>(R.id.gpx_status)?.visibility = View.GONE

            // FABs flush END on the bike (same fix as phone portrait); phone landscape keeps a small inset.
            lpOf(fabs).apply {
                width = ViewGroup.LayoutParams.WRAP_CONTENT
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                gravity = Gravity.TOP or Gravity.END
                marginEnd = fabEnd
                rightMargin = fabEnd
                topMargin = edge + insetT
                bottomMargin = 0
                marginStart = 0
                leftMargin = 0
            }
            // Horizontal speed + limit, flush with the metrics bar bottom, left of the FAB lane.
            speedChip?.visibility = View.VISIBLE
            val gauge = speedChip?.findViewById<View>(R.id.gpx_chip_speed)?.parent as? View
            if (projected && gauge != null) {
                val glp = gauge.layoutParams
                glp.width = (84 * d).toInt()
                glp.height = (84 * d).toInt()
                gauge.layoutParams = glp
            }
            root.findViewById<TextView>(R.id.gpx_chip_speed)?.textSize = if (projected) 32f else 36f
            root.findViewById<TextView>(R.id.gpx_chip_speed_unit)?.textSize = if (projected) 11f else 12f
            val limitTv = root.findViewById<TextView>(R.id.gpx_chip_limit)
            limitTv?.textSize = if (projected) 20f else 22f
            (limitTv?.layoutParams as? ViewGroup.MarginLayoutParams)?.let { mlp ->
                mlp.topMargin = 0
                mlp.marginStart = (12 * d).toInt()
                limitTv.layoutParams = mlp
            }
            (speedChip as? LinearLayout)?.orientation = LinearLayout.HORIZONTAL
            (speedChip as? LinearLayout)?.gravity = Gravity.CENTER_VERTICAL
            speedChip?.let {
                lpOf(it).apply {
                    gravity = Gravity.BOTTOM or Gravity.END
                    // Clear the FAB column on the right; sit on the same baseline as the metrics card.
                    marginEnd = fabLane + insetR
                    rightMargin = marginEnd
                    bottomMargin = edge + insetB
                    topMargin = 0
                    marginStart = 0
                    leftMargin = 0
                }
            }
        } else {
            // Portrait: bar paints under the punch-hole; content padded below the camera.
            hud.setBackgroundResource(R.drawable.bg_nav_top_edge)
            bottom.setBackgroundResource(R.drawable.bg_nav_bottom_edge)
            hud.gravity = Gravity.CENTER
            lpOf(hud).apply {
                width = ViewGroup.LayoutParams.MATCH_PARENT
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                marginStart = 0
                marginEnd = 0
                leftMargin = 0
                rightMargin = 0
                topMargin = 0 // extend under cutout
            }
            hud.setPadding(
                (16 * d).toInt() + insetL,
                insetT + (10 * d).toInt(), // text/icons clear of punch-hole
                (16 * d).toInt() + insetR,
                (12 * d).toInt(),
            )
            cue?.maxWidth = (420 * d).toInt()
            cue?.maxEms = 20
            cueDist?.textSize = 32f
            cue?.textSize = 20f

            lpOf(bottom).apply {
                width = ViewGroup.LayoutParams.MATCH_PARENT
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                marginStart = 0
                marginEnd = 0
                leftMargin = 0
                rightMargin = 0
                bottomMargin = insetB
            }
            bottom.setPadding(
                (12 * d).toInt() + insetL,
                (10 * d).toInt(),
                (12 * d).toInt() + insetR,
                (10 * d).toInt(),
            )
            // Compact metrics so "12.3 km" fits; End lives on the row below.
            listOfNotNull(wTime, wEta, wAlt).forEach {
                it.textSize = 22f
                it.maxLines = 1
                it.ellipsize = android.text.TextUtils.TruncateAt.END
                it.gravity = Gravity.CENTER_HORIZONTAL
                it.includeFontPadding = false
            }
            wLeft?.textSize = 24f
            wLeft?.maxLines = 1
            wLeft?.ellipsize = android.text.TextUtils.TruncateAt.END
            wLeft?.gravity = Gravity.CENTER_HORIZONTAL
            wLeft?.includeFontPadding = false
            listOfNotNull(wTime, wEta, wLeft, wAlt).forEach { tv ->
                val col = tv.parent as? LinearLayout ?: return@forEach
                col.gravity = Gravity.CENTER_HORIZONTAL
                col.minimumWidth = 0
                val lp = (col.layoutParams as? LinearLayout.LayoutParams)
                    ?: LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                lp.width = 0
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                lp.weight = if (tv === wLeft) 1.2f else 1f
                lp.marginEnd = 0
                col.layoutParams = lp
            }
            (bottom.getChildAt(0) as? LinearLayout)?.gravity = Gravity.CENTER_VERTICAL
            root.findViewById<View>(R.id.gpx_status)?.visibility = View.VISIBLE

            // Pin FABs flush to the right edge (clear any leftover left margins from land).
            lpOf(fabs).apply {
                width = ViewGroup.LayoutParams.WRAP_CONTENT
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                marginStart = 0
                leftMargin = 0
                marginEnd = (8 * d).toInt() + insetR
                rightMargin = marginEnd
                topMargin = 0
                bottomMargin = 0
            }
            fabs.bringToFront()
            // Gauge sits under the top banner while navigating (banner is full-bleed).
            speedChip?.visibility = View.VISIBLE
            root.findViewById<TextView>(R.id.gpx_chip_speed)?.textSize = 36f
            root.findViewById<TextView>(R.id.gpx_chip_speed_unit)?.textSize = 12f
            root.findViewById<TextView>(R.id.gpx_chip_limit)?.textSize = 20f
            speedChip?.let {
                lpOf(it).apply {
                    if (navigating) {
                        gravity = Gravity.TOP or Gravity.END
                        marginStart = 0
                        leftMargin = 0
                        marginEnd = (8 * d).toInt() + insetR
                        rightMargin = marginEnd
                        // Banner = cutout padding + ~content height.
                        topMargin = insetT + (100 * d).toInt()
                        bottomMargin = 0
                    } else {
                        gravity = Gravity.BOTTOM or Gravity.START
                        marginStart = (12 * d).toInt() + insetL
                        leftMargin = marginStart
                        bottomMargin = (12 * d).toInt() + insetB
                        marginEnd = 0
                        rightMargin = 0
                        topMargin = 0
                    }
                }
            }
        }
        hud.requestLayout()
        bottom.requestLayout()
        fabs.requestLayout()
        speedChip?.requestLayout()
    }

    fun release() {
        if (released) return
        released = true
        routeScope.cancel()
        // Only the PROJECTED dash owns the handlebar sinks (it sets them in bind()'s `if (projected)`
        // block). The phone-preview instance (projected=false) never sets them, so it must NOT clear
        // them here — doing so silently kills handlebar input on the live bike dash when the preview
        // is closed. See MapInputBridge / bind().
        if (projected) MapInputBridge.clear()
        DashRemote.setHandler(null)
        DashRemote.setNavHandler(null)
        DashRemote.setThemeHandler(null)
        main.removeCallbacksAndMessages(null)
        locationListener?.let { listener ->
            try { locationManager?.removeUpdates(listener) } catch (_: Exception) {}
        }
        locationListener = null
        compassListener?.let { cl ->
            try { sensorManager?.unregisterListener(cl) } catch (_: Exception) {}
        }
        compassListener = null
        sensorManager = null
        try { voice?.shutdown() } catch (_: Exception) {}
        voice = null
        try {
            dash?.onPause()
            dash?.onStop()
            dash?.onDestroy()
        } catch (_: Exception) {}
        dash = null
        GpxSession.clearTouchTarget()
        // End map-side auto-log unless AA is still streaming (shared recorder stays up then).
        TripAutoLog.setMapUiLive(context, false)
    }
}
