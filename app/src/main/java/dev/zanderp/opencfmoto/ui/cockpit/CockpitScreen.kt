// SPDX-License-Identifier: AGPL-3.0-or-later
// The unified Cockpit — ONE surface that is the map. A full-bleed "Overtake" map (the built-in
// BUILTIN provider) is the base; a destination bar sits on top (set where you're going before you
// ride), a quick Mapa|Panel toggle switches between full-map and the widget dash, and the glanceable
// widgets appear when they matter: a turn card while navigating (Maps/Waze guidance, our map), a call
// card when the phone rings, and the now-playing panel in Panel mode. It also has Google-Maps-style
// powers: an in-cockpit search and a locate-me/follow button. Our own Android-Auto — phone free.
package dev.zanderp.opencfmoto.ui.cockpit

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.zanderp.opencfmoto.R
import dev.zanderp.opencfmoto.GpxActivity
import dev.zanderp.opencfmoto.DashRemote
import dev.zanderp.opencfmoto.GpxNav
import dev.overtake.maps.RendererKind
import dev.overtake.maps.contract.MapRenderer
import dev.zanderp.opencfmoto.GpxSession
import dev.zanderp.opencfmoto.cockpit.CallState
import dev.zanderp.opencfmoto.cockpit.NavGuidance
import dev.zanderp.opencfmoto.overtakeOffline
import dev.zanderp.opencfmoto.overtakeRenderer
import dev.zanderp.opencfmoto.NightPrefs
import dev.zanderp.opencfmoto.ui.components.MapThemeToggle
import dev.zanderp.opencfmoto.settings.DashRenderer
import dev.zanderp.opencfmoto.ui.Routes
import dev.zanderp.opencfmoto.ui.theme.LocalCockpitColors
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import dev.zanderp.opencfmoto.LogBus
import dev.zanderp.opencfmoto.NavLauncher
import dev.zanderp.opencfmoto.HudViewActivity
import dev.zanderp.opencfmoto.settings.MapProvider
import dev.zanderp.opencfmoto.settings.SettingsStore
import kotlinx.coroutines.launch
import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import dev.zanderp.opencfmoto.AppHttp
import dev.zanderp.opencfmoto.MapPlace
import dev.zanderp.opencfmoto.MapPlaces
import dev.zanderp.opencfmoto.MapPrefs
import dev.overtake.maps.OvertakeMaps
import dev.overtake.maps.OvertakeMapsConfig
import dev.overtake.maps.search.NominatimSearch
import dev.overtake.maps.model.Route
import dev.zanderp.opencfmoto.overtakeRouter
import dev.zanderp.opencfmoto.routeToTrack
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun CockpitScreen(nav: NavController) {
    val c = LocalCockpitColors.current
    val ctx = LocalContext.current

    // Nav + call widgets are driven by the notification listener (CockpitNotifications); we observe.
    val navState by NavGuidance.state.collectAsStateWithLifecycle()
    val callState by CallState.state.collectAsStateWithLifecycle()

    // The rider's configured map provider decides where "¿A dónde vamos?" routes: Google Maps / Waze
    // do the actual navigation (they can't project to the dash without AA, so our cockpit shows their
    // glanceable turn card, read from the notification), while Propio uses the built-in map.
    val store = remember { SettingsStore(ctx.applicationContext) }
    val provider by store.mapProvider.collectAsStateWithLifecycle(initialValue = MapProvider.BUILTIN)
    val scope = rememberCoroutineScope()
    var showDest by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }

    fun route(dest: String) {
        val d = dest.trim()
        when (provider) {
            MapProvider.GOOGLE -> NavLauncher.navigate(ctx, d, LogBus::log)
            MapProvider.WAZE ->
                if (d.isEmpty()) NavLauncher.openWaze(ctx, LogBus::log)
                else NavLauncher.navigateWaze(ctx, d, LogBus::log)
            else -> ctx.startActivity(Intent(ctx, GpxActivity::class.java)) // Propio / Espejo → own map hub
        }
    }

    // cockpitMode = show the widget dash (music strip); false = just the map, full-bleed.
    var cockpitMode by rememberSaveable { mutableStateOf(true) }

    // The full-bleed map now renders the SELECTED engine (SettingsStore.dashRenderer) through the
    // Overtake library's MapRenderer — the SAME renderer the dash uses, so the cockpit matches the dash
    // (WYSIWYG). The old embed hardcoded raw osmdroid Mapnik and ignored the choice, so MapLibre
    // (Liberty vector + 3D — the Google-Maps-like look the owner wants) never appeared (the bug). NOTE:
    // the library forces MapLibre whenever the host Context is an Activity (this LocalContext is one),
    // so on the phone every choice renders MapLibre — the documented phone-preview behavior.
    val dashRenderer by store.dashRenderer.collectAsStateWithLifecycle(initialValue = DashRenderer.MAPLIBRE)
    val kind = remember(dashRenderer) {
        when (dashRenderer) {
            DashRenderer.OSMDROID -> RendererKind.OSMDROID
            DashRenderer.MAPSFORGE -> RendererKind.MAPSFORGE
            DashRenderer.MAPLIBRE -> RendererKind.MAPLIBRE
        }
    }
    // Strict Mapsforge gate (same as MapScreen): Mapsforge selected but no offline `.map` installed →
    // show a "download a map" card instead of the map (no silent osmdroid fallback). Default true so it
    // never flashes before the off-main check returns; re-checked on resume so it clears after download.
    var mfTick by remember { mutableStateOf(0) }
    var hasMapsforge by remember { mutableStateOf(true) }
    LaunchedEffect(mfTick) {
        hasMapsforge = withContext(Dispatchers.IO) {
            runCatching { overtakeOffline(ctx).hasMapsforgeMaps() }.getOrDefault(true)
        }
    }
    val needsMapsforgeMap = kind == RendererKind.MAPSFORGE && !hasMapsforge

    // Fresh host + renderer per engine choice (and per gate flip) so a switch tears the old one down.
    val host = remember(kind, needsMapsforgeMap) { FrameLayout(ctx) }
    val renderer: MapRenderer = remember(kind, needsMapsforgeMap) { overtakeRenderer(ctx, kind) }

    // Our own GPS drives the map. The old MyLocationNewOverlay was BOTH the dot and the fix source; the
    // renderer draws the puck (setMe) and follows (follow), so we own the LocationManager here — the same
    // pattern GpxDashUi uses. lastFix also feeds the turn-by-turn engine while riding.
    var lastFix by remember { mutableStateOf<Location?>(null) }
    // Follow latch: true while the camera tracks the rider (nav chase, or after "locate me"); drives the
    // FAB tint. The renderer exposes no pan callback, so — like GpxDashUi, which only drops follow via
    // its own buttons — there is no auto-drop-on-pan; the rider re-centers with the locate FAB.
    var following by remember { mutableStateOf(false) }

    // ——— In-cockpit navigation (BUILTIN/"Overtake"): pick → preview → Iniciar → follow + turn card.
    // navDest = the picked place (enables the "Iniciar recorrido" button); navRoute = the fetched route
    // (geometry + OSRM steps, reused to drive turn-by-turn); navigating = actively riding a route;
    // navProgress = the live snapshot recomputed from the rider's fix while navigating.
    var navDest by remember { mutableStateOf<MapPlace?>(null) }
    var navRoute by remember { mutableStateOf<Route?>(null) }
    var navigating by remember { mutableStateOf(false) }
    var navProgress by remember { mutableStateOf<GpxNav.Progress?>(null) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(kind, needsMapsforgeMap) {
        // Host the SELECTED renderer (skip when the Mapsforge gate shows the download card instead).
        if (!needsMapsforgeMap) {
            renderer.attach(ctx, host)
            renderer.setBuildings3d(true) // Liberty + 3D buildings — the premium look (no-op on raster)
            renderer.onCreate(null)
            renderer.onResume()
            // Apply the rider's day/night choice on attach (renderer starts day-neutral). Shared with the
            // on-map toggle + Settings via NightPrefs.
            renderer.applyTheme(NightPrefs.isNightNow(ctx))
        }

        // GPS → puck (setMe) + nav-follow camera. Explicit object (not a SAM lambda) to stay safe on
        // pre-API-30, matching GpxDashUi's listener.
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val listener = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                lastFix = loc
                if (needsMapsforgeMap) return
                val moving = loc.hasSpeed() && loc.speed > 1.2f
                val brng = if (loc.hasBearing() && moving) loc.bearing else 0f
                renderer.setMe(loc.latitude, loc.longitude, brng)
                // Continuous chase ONLY while navigating (heading-up nav camera). In free ride the rider
                // keeps full control of pan/zoom; "locate me" does a one-shot recenter instead of leashing.
                if (navigating && following) {
                    renderer.follow(loc.latitude, loc.longitude, brng, 17.5, headingUp = true, moving = moving)
                }
            }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        fun subscribe() {
            if (lm == null) return
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            runCatching {
                if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 800L, 1.5f, listener, Looper.getMainLooper())
                }
                if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2_000L, 5f, listener, Looper.getMainLooper())
                }
                // Seed the puck/turn card immediately with the freshest last-known fix.
                listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                    .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
                    .maxByOrNull { it.time }
                    ?.let { listener.onLocationChanged(it) }
            }
        }
        subscribe()

        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> {
                    if (!needsMapsforgeMap) {
                        renderer.onResume()
                        renderer.applyTheme(NightPrefs.isNightNow(ctx)) // reflect a theme change from Settings
                    }
                    mfTick++ // re-check the Mapsforge gate (clears after a download)
                    subscribe() // re-arm GPS after a permission grant / return to foreground
                }
                Lifecycle.Event.ON_PAUSE -> {
                    if (!needsMapsforgeMap) renderer.onPause()
                    runCatching { lm?.removeUpdates(listener) }
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(obs)
            runCatching { lm?.removeUpdates(listener) }
            if (!needsMapsforgeMap) {
                runCatching { renderer.onPause() }
                runCatching { renderer.onStop() }
                runCatching { renderer.onDestroy() }
            }
        }
    }
    // MapLibre's style loads async; setCenter no-ops until it's ready. Nudge the initial camera (the
    // rider's last-known fix, else the Bogotá default) a few times after attach so we never flash the
    // whole-world view before the style paints.
    LaunchedEffect(kind, needsMapsforgeMap) {
        if (needsMapsforgeMap) return@LaunchedEffect
        val start = lastKnownLatLon(ctx) ?: (4.6486 to -74.2479)
        repeat(14) {
            renderer.setCenter(start.first, start.second, 15.0)
            delay(90)
        }
    }

    // While navigating, recompute turn-by-turn + remaining/ETA from the SAME engine the dash uses
    // (GpxNav over the fetched route + its OSRM steps). GpxNav is a pure computation (not an observable
    // stream), so — exactly like GpxDashUi — we drive it from the cockpit's own location fixes each
    // second. Pre-route or off-fix it just yields null and the card falls back to route distance/ETA.
    LaunchedEffect(navigating, navRoute) {
        if (!navigating) {
            navProgress = null
            return@LaunchedEffect
        }
        val route = navRoute ?: return@LaunchedEffect
        val nav = runCatching {
            GpxNav(routeToTrack(navDest?.name ?: ctx.getString(R.string.ovk_destination_fallback), route), route.steps)
        }.getOrNull() ?: return@LaunchedEffect
        while (true) {
            // Location comes from our own listener (lastFix); fall back to the system last-known fix.
            // Heading-up rotation is driven by renderer.follow(headingUp = true) in the location listener
            // while navigating — the renderer rotates the map, so there's no manual mapOrientation here.
            val loc = lastFix
            val ll = if (loc != null) loc.latitude to loc.longitude else lastKnownLatLon(ctx)
            if (ll != null) {
                navProgress = runCatching { nav.progress(ll.first, ll.second, loc?.speed ?: 0f) }.getOrNull()
            }
            delay(1000)
        }
    }

    // The in-cockpit search pick — destination pin + its route line — is drawn through the renderer's
    // own API (setDestination / setRouteRemain), so clearing a previous pick just clears those layers.
    fun clearSearchOverlays() {
        renderer.clearRoutes()
        renderer.setDestination(null)
    }

    // Reuse the app's default router (OfflineRouter → offline pack / Valhalla / OSRM, the same path the
    // dash nav uses) off the main thread, then draw the returned geometry as a Polyline under the
    // marker. With no clean current-location fix we skip the line and keep just the centered marker.
    fun drawRouteTo(place: MapPlace) {
        val origin = lastKnownLatLon(ctx) ?: run {
            LogBus.log("[cockpit-search] sin ubicación actual — solo marcador (ruta diferida)")
            return
        }
        // Same engine + ORS-key resolution the search path uses. route() suspends off-main
        // (Dispatchers.IO inside the lib) and resumes here on Main, so drawing is UI-safe.
        val router = overtakeRouter(ctx)
        val opts = MapPrefs.routeOptions(ctx)
        val from = dev.overtake.maps.model.GeoPoint(origin.first, origin.second)
        val to = dev.overtake.maps.model.GeoPoint(place.lat, place.lon)
        scope.launch {
            val route = runCatching { router.route(from, to, opts).routes.firstOrNull() }
                .getOrElse { err -> LogBus.log("[cockpit-search] ruta: ${err.message}"); null }
                ?: return@launch
            navRoute = route // keep geometry + steps for "Iniciar" nav + the turn card
            // The renderer draws the blue route line (same #1A73E8 as the old Polyline) beneath the pin.
            renderer.setRouteRemain(route.points.map { it.lat to it.lon })
        }
    }

    // A Propio search result was tapped: drop the previous pick, drop a fresh destination marker,
    // recenter/zoom on it, then attempt the route. This path never hands off to GpxActivity.
    fun selectBuiltinDestination(place: MapPlace) {
        showSearch = false
        clearSearchOverlays()
        // Picking a new place while already navigating re-targets: end the current run cleanly first so
        // GpxSession / the follow camera don't keep pointing at the old destination.
        if (navigating) {
            runCatching { GpxSession.finishToFreeRide() }
            // Clear the old route on the live dash before the new pick re-targets it (startNavigation
            // sends the fresh DashRemote.navigateTo); otherwise the stale route lingers in between.
            if (DashRemote.isAvailable) runCatching { DashRemote.endNavigation() }
            following = false
            navigating = false
            navProgress = null
        }
        navDest = place // remember the pick so "Iniciar recorrido" knows where to go
        navRoute = null // fresh route pending (drawRouteTo fills it in)
        // Drop the destination pin through the renderer, then frame it (same 16.0 zoom as before).
        renderer.setDestination(place)
        renderer.setCenter(place.lat, place.lon, 16.0)
        drawRouteTo(place)
    }

    // "▶ Iniciar recorrido": start turn-by-turn on our own map WITHOUT leaving the cockpit. Reuse the
    // app's nav engine (GpxSession.prepareNavTo → the existing projection pipeline puts it on the dash
    // when a bike link is live; that half is automatic + bike-gated). Here we also switch the phone map
    // into a Google-style follow: zoom in close and track the rider along the road. Route line + marker
    // stay on the map. Never crashes without a fix — the follow just waits for the first GPS.
    fun startNavigation(place: MapPlace) {
        // Location makes the follow camera work; request it if missing (the nav intent still persists).
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ctx.findActivity()?.let {
                ActivityCompat.requestPermissions(it, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), REQ_LOCATION)
            }
        }
        runCatching { MapPlaces.pushHistory(ctx, place) }
        // Arms NAV_TO so a projection that starts AFTER this picks it up on creation (fresh GpxDashUi).
        runCatching { GpxSession.prepareNavTo(place, overlays = emptyList()) }
        // If a dash is ALREADY projecting to the bike, re-target it in place — route + turn-by-turn —
        // with NO PXC reconnect (a reconnect would drop the bike-dash projection).
        if (DashRemote.isAvailable) runCatching { DashRemote.navigateTo(place) }
        navigating = true
        navProgress = null
        following = true // the location listener now chases each fix, heading-up (Google-Maps nav camera)
        // Kick the close follow camera to the current fix right now (the listener keeps it there on each
        // GPS). Without a fix yet, the first GPS the listener receives starts the chase.
        val ll = lastFix?.let { it.latitude to it.longitude } ?: lastKnownLatLon(ctx)
        if (ll != null) {
            val loc = lastFix
            val moving = loc != null && loc.hasSpeed() && loc.speed > 1.2f
            val brng = if (loc != null && loc.hasBearing() && moving) loc.bearing else 0f
            renderer.setMe(ll.first, ll.second, brng)
            renderer.follow(ll.first, ll.second, brng, 17.5, headingUp = true, moving = moving)
        } else {
            LogBus.log("[cockpit-nav] iniciar sin fix aún — el mapa seguirá al primer GPS")
        }
        LogBus.log("[cockpit-nav] iniciar recorrido → ${place.name}")
    }

    // "Terminar": stop navigating, back to a clean free-ride map. Drops the route/marker overlays, the
    // follow camera and all nav state; GpxSession returns to FREE_RIDE (the dash projection follows).
    fun stopNavigation() {
        runCatching { GpxSession.finishToFreeRide() }
        // Tell the LIVE bike dash the trip ended too, so it clears the route and stops turn-by-turn
        // voice instead of showing a stale, still-talking navigation (no PXC reconnect).
        if (DashRemote.isAvailable) runCatching { DashRemote.endNavigation() }
        clearSearchOverlays()
        following = false // stops the chase in the location listener
        navigating = false
        navDest = null
        navRoute = null
        navProgress = null
        runCatching { renderer.resetNorth() } // heading-up nav ended → back to north-up
        LogBus.log("[cockpit-nav] terminar recorrido")
    }

    // "Locate me": ensure ACCESS_FINE_LOCATION (request + bail if missing — the grant lands on the next
    // tap, mirroring GpxActivity's pattern), then enable the dot + follow, zoom in, and jump to the
    // current fix (the overlay's last fix, else the system last-known). enableFollowLocation() keeps the
    // map centered as new fixes arrive; osmdroid drops follow when the rider pans. Fully guarded.
    fun locateMe() {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ctx.findActivity()?.let {
                ActivityCompat.requestPermissions(it, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), REQ_LOCATION)
            }
            LogBus.log("[cockpit-locate] permiso de ubicación pendiente — solicitado")
            runCatching {
                Toast.makeText(ctx, ctx.getString(R.string.ovk_locate_permission_needed), Toast.LENGTH_SHORT).show()
            }
            return
        }
        following = true // lights the FAB; while navigating the listener keeps chasing each fix
        val ll = lastFix?.let { it.latitude to it.longitude } ?: lastKnownLatLon(ctx)
        if (ll != null) {
            val loc = lastFix
            val moving = loc != null && loc.hasSpeed() && loc.speed > 1.2f
            val brng = if (loc != null && loc.hasBearing() && moving) loc.bearing else 0f
            renderer.setMe(ll.first, ll.second, brng)
            // Recenter now: heading-up while navigating (the running nav camera), north-up otherwise.
            renderer.follow(ll.first, ll.second, brng, if (navigating) 17.5 else 17.0, headingUp = navigating, moving = moving)
        } else {
            LogBus.log("[cockpit-locate] sin fix aún — el mapa seguirá al primer GPS")
            runCatching { Toast.makeText(ctx, ctx.getString(R.string.ovk_finding_location), Toast.LENGTH_SHORT).show() }
        }
    }

    Box(Modifier.fillMaxSize().background(c.ground)) {
        // Base layer — the SELECTED renderer fills the whole surface. When the strict Mapsforge gate is
        // up (Mapsforge chosen but no offline `.map`), a "download a map" card takes the base instead.
        if (needsMapsforgeMap) {
            MapsforgeNeedsMapCard(
                modifier = Modifier.fillMaxSize(),
                onDownload = { nav.navigate(Routes.MAPSFORGE_MAPS) },
            )
        } else {
            // key(kind) so switching engines disposes the old AndroidView subtree and hosts the new one.
            key(kind) {
                AndroidView(factory = { host }, modifier = Modifier.fillMaxSize())
            }
        }

        // Top floating controls: back · destination bar · Mapa|Panel toggle · turn card.
        Column(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                GlyphBox("‹") { nav.popBackStack() }
                DestinationBar(
                    providerLabel = providerLabel(ctx, provider),
                    modifier = Modifier.weight(1f),
                    onSearch = { if (provider == MapProvider.BUILTIN) showSearch = true else showDest = true },
                    onCycleProvider = { scope.launch { store.setMapProvider(nextProvider(provider)) } },
                )
                // Persistent day/night/auto toggle (compact) — flip the map look without leaving the map.
                if (!needsMapsforgeMap) {
                    MapThemeToggle(compact = true, onThemeChanged = { night -> renderer.applyTheme(night) })
                }
            }
            ModeToggle(cockpitMode) { cockpitMode = it }
            // AA mode only: a "Dash view" button opens the live Android Auto HUD (Google Maps / Waze as
            // projected to the dash) mirrored on the phone. Overtake draws its own map and never uses AA,
            // so this is hidden for the built-in provider and never competes with the Overtake pipeline.
            if (provider == MapProvider.GOOGLE || provider == MapProvider.WAZE) {
                DashViewButton { runCatching { HudViewActivity.start(ctx) } }
            }
            if (navState.active) NavCard(navState, Modifier.fillMaxWidth())
        }

        // Bottom widget: an incoming/active call takes priority; then the active-navigation progress
        // card (while riding a route); otherwise the now-playing panel in Cockpit mode. In Mapa mode the
        // map stays clean (nav/call still surface when they matter). Bounded height: these panels contain
        // weight(1f) children, so without a height cap they'd fill the whole Box (opaque) and bury the
        // map + top controls. Cap them as bottom widgets.
        when {
            callState.active -> CallCard(callState, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp))
            navigating -> NavProgressCard(
                progress = navProgress,
                route = navRoute,
                onStop = { stopNavigation() },
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp),
            )
            cockpitMode -> MusicPanel(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(210.dp).padding(12.dp))
        }

        // "▶ Iniciar recorrido" — shown only after a place is picked and before navigation starts. A
        // prominent ignition pill, centered + wrap-content so it clears the bottom-END locate FAB, lifted
        // above whatever bottom widget (music/call) is currently showing.
        if (navDest != null && !navigating) {
            StartRideButton(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = if (callState.active || cockpitMode) 224.dp else 20.dp),
                onClick = { navDest?.let { startNavigation(it) } },
            )
        }

        // "Locate me" FAB — bottom-END, lifted clear of whatever bottom widget is showing (the 210dp
        // music panel, the ~190dp call card, or the shorter nav progress card) so it's never buried; in
        // clean Mapa mode it drops to the corner. While navigating it doubles as the re-center control
        // (osmdroid drops follow on a manual pan). Drawn before the search overlay so search covers it.
        LocateButton(
            following = following,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(
                    end = 16.dp,
                    bottom = when {
                        callState.active -> 224.dp
                        navigating -> 148.dp
                        cockpitMode -> 224.dp
                        else -> 20.dp
                    },
                ),
            onClick = { locateMe() },
        )

        // In-cockpit autocomplete search for the built-in Overtake map — native, drawn over the map,
        // no GpxActivity handoff.
        if (showSearch) {
            CockpitSearchOverlay(
                onDismiss = { showSearch = false },
                onPick = { place -> selectBuiltinDestination(place) },
            )
        }
    }

    if (showDest) {
        DestinationDialog(
            provider = provider,
            onDismiss = { showDest = false },
            onGo = { dest -> showDest = false; route(dest) },
        )
    }
}

@Composable
private fun DestinationBar(
    providerLabel: String,
    modifier: Modifier,
    onSearch: () -> Unit,
    onCycleProvider: () -> Unit,
) {
    val c = LocalCockpitColors.current
    Row(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(c.ground.copy(alpha = 0.92f))
            .border(1.dp, c.line, RoundedCornerShape(12.dp))
            .clickable(onClick = onSearch)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("⌕", color = c.ignition, fontSize = 16.sp)
        Spacer(Modifier.width(10.dp))
        Text(stringResource(R.string.ovk_where_to), color = c.inkDim, fontSize = 14.sp, modifier = Modifier.weight(1f))
        // Provider chip: its OWN click target so a tap CYCLES Propio → Google → Waze and is consumed
        // here (it must not also open the search — that's the rest of the bar's job).
        Text(
            providerLabel,
            color = c.ignition,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(c.surface2.copy(alpha = 0.9f))
                .border(1.dp, c.line, RoundedCornerShape(8.dp))
                .clickable(onClick = onCycleProvider)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun ModeToggle(cockpit: Boolean, onChange: (Boolean) -> Unit) {
    val c = LocalCockpitColors.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.ground.copy(alpha = 0.92f)).border(1.dp, c.line, RoundedCornerShape(10.dp)).padding(3.dp),
    ) {
        Seg(stringResource(R.string.ovk_mode_map), !cockpit, Modifier.weight(1f)) { onChange(false) }
        Seg(stringResource(R.string.ovk_mode_panel), cockpit, Modifier.weight(1f)) { onChange(true) }
    }
}

@Composable
private fun DashViewButton(onClick: () -> Unit) {
    val c = LocalCockpitColors.current
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(c.ignition.copy(alpha = 0.16f))
            .border(1.dp, c.ignition, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "▸ " + stringResource(R.string.ovk_dash_view),
            color = c.ignition,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun Seg(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = LocalCockpitColors.current
    Box(
        modifier.clip(RoundedCornerShape(8.dp)).background(if (selected) c.ignition else Color.Transparent).clickable(onClick = onClick).padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (selected) c.onIgnition else c.inkDim, fontWeight = FontWeight.SemiBold, fontSize = 13.sp) }
}

@Composable
private fun GlyphBox(glyph: String, onClick: () -> Unit) {
    val c = LocalCockpitColors.current
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(c.ground.copy(alpha = 0.92f)).border(1.dp, c.line, RoundedCornerShape(10.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(glyph, color = c.ink, fontSize = 20.sp) }
}

/**
 * Round "locate me" FAB (Google-Maps style): re-centers the map on the rider and turns on follow.
 * Theme-aware via [LocalCockpitColors] — the same ground/line frame as the other floating controls,
 * flipped to the ignition accent while follow is active so the rider can see the map is tracking them.
 */
@Composable
private fun LocateButton(following: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = LocalCockpitColors.current
    Box(
        modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(if (following) c.ignition else c.ground.copy(alpha = 0.92f))
            .border(1.dp, c.line, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text("◎", color = if (following) c.onIgnition else c.ink, fontSize = 24.sp) }
}

/**
 * Active-navigation progress card for the built-in ("Overtake") map. Reuses the dash nav engine's
 * output — a [GpxNav.Progress] computed over the fetched route + its OSRM steps — to show the next
 * maneuver (glyph + Spanish label + distance/road) and, underneath, "Faltan X · Llegada HH:MM". When
 * no live progress is available yet (no GPS fix, or the route is still resolving) it falls back to the
 * route's own distance + ETA, then to "Calculando ruta…". A Terminar control ends the ride. Glassy and
 * theme-aware to match [NavCard] / [MusicPanel].
 */
@Composable
private fun NavProgressCard(
    progress: GpxNav.Progress?,
    route: Route?,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalCockpitColors.current
    val ctx = LocalContext.current
    val units = remember { MapPrefs.units(ctx) }

    val turn = progress?.upcomingTurn
    val remainM = progress?.remainM ?: route?.distanceM
    val etaSec = progress?.etaSec ?: route?.let { GpxNav.etaSeconds(it.distanceM, 0f) }

    // Hoisted out of the (non-composable) `let` lambdas below so the interpolated text is i18n'd.
    val onRouteLabel = stringResource(R.string.ovk_nav_on_route)
    val calculatingLabel = stringResource(R.string.ovk_calculating_route)
    val turnDistanceFmt = stringResource(R.string.ovk_nav_turn_distance)
    val remainingEtaFmt = stringResource(R.string.ovk_nav_remaining_eta)

    val primary = when {
        turn != null -> stringResource(maneuverRes(turn.dir))
        remainM != null -> onRouteLabel
        else -> calculatingLabel
    }
    val turnDetail = turn?.let {
        listOfNotNull(
            turnDistanceFmt.format(GpxNav.formatDistance(it.distanceFromRiderM, units)),
            it.roadName?.takeIf { n -> n.isNotBlank() },
        ).joinToString(" · ")
    }
    val summary = remainM?.let {
        remainingEtaFmt.format(GpxNav.formatDistance(it, units), GpxNav.formatArrival(etaSec))
    }
    val glyph = turn?.let { GpxNav.arrowFor(it.dir) } ?: "➤"

    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(c.ground.copy(alpha = 0.9f))
            .border(1.dp, c.line, RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Maneuver glyph — a filled ignition chip, the strongest "your next move" signal.
            Box(
                Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(c.ignition),
                contentAlignment = Alignment.Center,
            ) { Text(glyph, color = c.onIgnition, fontWeight = FontWeight.Bold, fontSize = 20.sp) }

            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(primary, color = c.ink, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1)
                if (!turnDetail.isNullOrBlank()) {
                    Text(turnDetail, color = c.inkDim, fontSize = 12.sp, maxLines = 1)
                }
            }

            // Terminar — the stop control (fault-tinted, outlined).
            Text(
                stringResource(R.string.ovk_finish),
                color = c.fault,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, c.line, RoundedCornerShape(8.dp))
                    .clickable(onClick = onStop)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
        Text(summary ?: calculatingLabel, color = c.inkDim, fontSize = 12.sp, maxLines = 1)
    }
}

/** Prominent "start the ride" pill (ignition-filled) shown after a place is picked, before start. */
@Composable
private fun StartRideButton(modifier: Modifier, onClick: () -> Unit) {
    val c = LocalCockpitColors.current
    Row(
        modifier
            .clip(RoundedCornerShape(24.dp))
            .background(c.ignition)
            .border(1.dp, c.ignitionHi, RoundedCornerShape(24.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("▶", color = c.onIgnition, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.ovk_start_ride), color = c.onIgnition, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

/** Maneuver label resource from the engine's [GpxNav.TurnDir] (arrows come from GpxNav.arrowFor). */
@StringRes
private fun maneuverRes(dir: GpxNav.TurnDir): Int = when (dir) {
    GpxNav.TurnDir.LEFT -> R.string.ovk_maneuver_left
    GpxNav.TurnDir.RIGHT -> R.string.ovk_maneuver_right
    GpxNav.TurnDir.SHARP_LEFT -> R.string.ovk_maneuver_sharp_left
    GpxNav.TurnDir.SHARP_RIGHT -> R.string.ovk_maneuver_sharp_right
    GpxNav.TurnDir.SLIGHT_LEFT -> R.string.ovk_maneuver_slight_left
    GpxNav.TurnDir.SLIGHT_RIGHT -> R.string.ovk_maneuver_slight_right
    GpxNav.TurnDir.UTURN -> R.string.ovk_maneuver_uturn
    GpxNav.TurnDir.ROUNDABOUT -> R.string.ovk_maneuver_roundabout
    GpxNav.TurnDir.MERGE_LEFT -> R.string.ovk_maneuver_merge_left
    GpxNav.TurnDir.MERGE_RIGHT -> R.string.ovk_maneuver_merge_right
    GpxNav.TurnDir.FORK_LEFT -> R.string.ovk_maneuver_fork_left
    GpxNav.TurnDir.FORK_RIGHT -> R.string.ovk_maneuver_fork_right
    GpxNav.TurnDir.STRAIGHT, GpxNav.TurnDir.CONTINUE -> R.string.ovk_maneuver_straight
    GpxNav.TurnDir.ARRIVE -> R.string.ovk_maneuver_arrive
}

/** Request code for the runtime ACCESS_FINE_LOCATION prompt fired by the locate FAB. */
private const val REQ_LOCATION = 42

/** Walk the ContextWrapper chain to the hosting Activity (needed for runtime permission requests). */
private fun Context.findActivity(): Activity? {
    var c: Context = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

private fun providerLabel(ctx: Context, p: MapProvider): String = when (p) {
    MapProvider.GOOGLE -> "Google Maps" // brand name — not translated
    MapProvider.WAZE -> "Waze" // brand name — not translated
    MapProvider.MIRROR -> ctx.getString(R.string.ovk_provider_mirror)
    else -> "Overtake" // BUILTIN — the native map, branded "Overtake" (label only; enum stays BUILTIN)
}

/**
 * Cycle the map provider Propio → Google → Waze → Propio — the compact replacement for the old
 * 3-segment picker (now a tap on the DestinationBar's provider chip). Anything off-cycle (e.g.
 * Espejo/MIRROR) folds back to Propio.
 */
private fun nextProvider(p: MapProvider): MapProvider = when (p) {
    MapProvider.BUILTIN -> MapProvider.GOOGLE
    MapProvider.GOOGLE -> MapProvider.WAZE
    MapProvider.WAZE -> MapProvider.BUILTIN
    else -> MapProvider.BUILTIN
}

/** Type a destination; "Ir" hands it to the active provider (Google Maps / Waze navigate; Propio hub). */
@Composable
private fun DestinationDialog(provider: MapProvider, onDismiss: () -> Unit, onGo: (String) -> Unit) {
    val c = LocalCockpitColors.current
    val ctx = LocalContext.current
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface1,
        titleContentColor = c.ink,
        textContentColor = c.ink,
        title = { Text(stringResource(R.string.ovk_where_to), fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(stringResource(R.string.ovk_route_with, providerLabel(ctx, provider)), color = c.inkDim, fontSize = 12.sp)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.ovk_address_or_place)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onGo(text) }, enabled = text.isNotBlank()) {
                Text(stringResource(R.string.ovk_go), color = if (text.isNotBlank()) c.ignition else c.inkFaint, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ovk_cancel), color = c.inkDim) } },
    )
}

/**
 * Native in-cockpit search for the built-in ("Overtake") map: a focused field + a live results list,
 * tuned toward Google-Maps quality on the free stack (no paid Places API).
 *
 * Sources, biased to the rider's own fix (fallback: map center):
 *  - the extracted `PlaceSearch` (Overtake library) — its `query()` fans out to the platform Geocoder
 *    (Google-backed on a Play-Services device, for specific local street / neighbourhood addresses
 *    Photon/Nominatim miss) AND to Photon + Nominatim worldwide autocomplete, then cross-source merges,
 *    ranks and dedupes them by the [NominatimSearch.relevance] / [NominatimSearch.dedupeKey] blend;
 *  - [MapPlaces] — the rider's own recents / favourites / home, matched LOCALLY (no network).
 *
 * Feel: with an empty/short query the dropdown instantly lists saved + recent places; while typing it
 * paints local + cached hits at once (never laggy), then folds in the library's ranked network results
 * when the suspend `query()` returns (superseded by [LaunchedEffect] cancellation when the query
 * changes). Every candidate — network and saved — is scored by the single [NominatimSearch.relevance]
 * blend (name-match dominant, prominence + distance ordering within), the rider's own places boosted,
 * then deduped across sources by [NominatimSearch.dedupeKey] so one place is one row. Each row shows the
 * name, a locality subtitle and the distance, like Google.
 */
@Composable
private fun CockpitSearchOverlay(
    onDismiss: () -> Unit,
    onPick: (MapPlace) -> Unit,
) {
    val c = LocalCockpitColors.current
    val ctx = LocalContext.current
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SearchPick>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    // The extracted place-search (Stage 1 of the map-library extraction): a native MapProvider built
    // from the fork's config. We consume only its PlaceSearch here — the renderer/router are pending
    // stubs — so the cross-source geocode + Photon/Nominatim merge/rank/dedupe now lives in the library.
    val search = remember {
        val cfg = OvertakeMapsConfig(
            userAgent = AppHttp.USER_AGENT,
            filesDir = ctx.filesDir,
            defaultOrsApiKey = MapPrefs.orsApiKey(ctx),
        )
        (OvertakeMaps.create(ctx, cfg) as dev.overtake.maps.MapProvider.Native).search
    }

    // Rider bias + the rider's own places, read once when the overlay opens: local, instant, no network.
    // The overlay closes on pick, so these don't need to live-refresh mid-search.
    var biasLat by remember { mutableStateOf<Double?>(null) }
    var biasLon by remember { mutableStateOf<Double?>(null) }
    var recents by remember { mutableStateOf<List<MapPlace>>(emptyList()) }
    var favorites by remember { mutableStateOf<List<MapPlace>>(emptyList()) }
    var homePlace by remember { mutableStateOf<MapPlace?>(null) }

    BackHandler(enabled = true) { onDismiss() }
    LaunchedEffect(Unit) {
        runCatching { focus.requestFocus() }
        // Bias to the rider's own position first (like the dash's runDashSearch); fall back to the map
        // center only with no fix. The map center starts on a hardcoded default city far from the rider,
        // so biasing to it sent a local name to the wrong region (measured miss: "rincón de la flora 1").
        val bias = lastKnownLatLon(ctx) ?: (4.6486 to -74.2479) // default center (Bogotá) when no fix yet
        biasLat = bias.first
        biasLon = bias.second
        recents = runCatching { MapPlaces.history(ctx) }.getOrDefault(emptyList())
        favorites = runCatching { MapPlaces.favorites(ctx) }.getOrDefault(emptyList())
        homePlace = runCatching { MapPlaces.home(ctx) }.getOrNull()
    }

    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2) {
            // Empty/short query is served entirely from local state (rendered below) — no network.
            results = emptyList()
            searching = false
            return@LaunchedEffect
        }
        val bLat = biasLat
        val bLon = biasLon
        // Instant paint: the rider's own matching places + any previously-cached network answer, so the
        // list is populated the moment the user types, before the debounce/network even starts.
        val cached = if (bLat != null && bLon != null) {
            NominatimSearch.cachedBiased(q, bLat, bLon) ?: emptyList()
        } else {
            emptyList()
        }
        results = rankPicks(q, bLat, bLon, cached, recents, favorites, homePlace)
        searching = true
        delay(250) // debounce — cancelled if the query changes before it elapses

        // ONE suspend call runs BOTH network sources off the main thread (platform Geocoder + Photon/
        // Nominatim) and returns them cross-source merged, ranked and deduped by the SAME blend the rows
        // are scored with (that merge moved into the Overtake library). LaunchedEffect(query) cancels
        // this coroutine when the query changes, so a superseded answer never paints — replacing the old
        // seq/Handler marshalling. A failure degrades to no network results; the rider's own local
        // places still show.
        val near = if (bLat != null && bLon != null) dev.overtake.maps.model.GeoPoint(bLat, bLon) else null
        val net = try {
            search.query(q, near)
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (e: Exception) {
            LogBus.log("[cockpit-search] ${e.message ?: e}")
            emptyList()
        }
        results = rankPicks(q, bLat, bLon, net, recents, favorites, homePlace)
        searching = false
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(c.ground)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { /* consume stray taps so they don't fall through to the map underneath */ },
    ) {
        Column(
            Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlyphBox("‹") { onDismiss() }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.ovk_search_address_or_place)) },
                    modifier = Modifier.weight(1f).focusRequester(focus),
                )
            }

            if (query.trim().length < 2) {
                // Empty/short query: the rider's own places, instantly (home + favourites, then recents).
                val (saved, recent) = localGroups(biasLat, biasLon, recents, favorites, homePlace)
                LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                    if (saved.isNotEmpty()) {
                        item { SearchSectionHeader(stringResource(R.string.ovk_search_saved)) }
                        items(saved) { pick -> SuggestionRow(pick, rowSubtitle(ctx, pick)) { onPick(pick.place) } }
                    }
                    if (recent.isNotEmpty()) {
                        item { SearchSectionHeader(stringResource(R.string.ovk_search_recent)) }
                        items(recent) { pick -> SuggestionRow(pick, rowSubtitle(ctx, pick)) { onPick(pick.place) } }
                    }
                }
            } else {
                if (results.isEmpty()) {
                    Text(
                        if (searching) stringResource(R.string.ovk_searching) else stringResource(R.string.ovk_no_results),
                        color = c.inkDim,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                    )
                }
                LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                    items(results) { pick -> SuggestionRow(pick, rowSubtitle(ctx, pick)) { onPick(pick.place) } }
                }
            }
        }
    }
}

/** How a suggestion reached the list — drives the leading glyph and the ranking boost. */
private enum class SearchKind { HOME, FAVORITE, RECENT, RESULT }

/** A ranked suggestion: the place, why it's here, and its distance (km, or <0 when unknown). */
private data class SearchPick(val place: MapPlace, val kind: SearchKind, val distanceKm: Double)

/** Ranking boost for the rider's own places, added on top of [NominatimSearch.relevance]. */
private fun kindBoost(kind: SearchKind): Double = when (kind) {
    SearchKind.HOME -> 300.0
    SearchKind.FAVORITE -> 240.0
    SearchKind.RECENT -> 150.0
    SearchKind.RESULT -> 0.0
}

private fun distanceTo(biasLat: Double?, biasLon: Double?, p: MapPlace): Double =
    if (biasLat != null && biasLon != null) NominatimSearch.haversineKm(biasLat, biasLon, p.lat, p.lon) else -1.0

/**
 * Rank the merged pool (network + the rider's matching own places) into one deduped list, top 12.
 * One scorer for everything ([NominatimSearch.relevance]); saved places are gated by a real textual
 * match ([NominatimSearch.matchScore] ≥ 500) so an unrelated favourite never hijacks a query, then
 * boosted so a genuine hit for one of your places edges out an equal fresh result and shows its icon.
 */
private fun rankPicks(
    q: String,
    biasLat: Double?,
    biasLon: Double?,
    network: List<MapPlace>,
    recents: List<MapPlace>,
    favorites: List<MapPlace>,
    home: MapPlace?,
): List<SearchPick> {
    if (q.length < 2) return emptyList()
    data class Scored(val place: MapPlace, val kind: SearchKind, val score: Double, val dist: Double)
    val pool = ArrayList<Scored>()
    fun consider(p: MapPlace, kind: SearchKind, gate: Boolean) {
        if (gate && NominatimSearch.matchScore(q, p.name, p.subtitle) < 500.0) return
        val dist = distanceTo(biasLat, biasLon, p)
        val score = NominatimSearch.relevance(q, p.name, p.subtitle, p.category, p.prominence, dist) + kindBoost(kind)
        pool.add(Scored(p, kind, score, dist))
    }
    home?.let { consider(it, SearchKind.HOME, gate = true) }
    for (f in favorites) consider(f, SearchKind.FAVORITE, gate = true)
    for (r in recents) consider(r, SearchKind.RECENT, gate = true)
    for (n in network) consider(n, SearchKind.RESULT, gate = false)

    val seen = HashSet<String>()
    val out = ArrayList<SearchPick>()
    for (s in pool.sortedByDescending { it.score }) {
        if (!seen.add(NominatimSearch.dedupeKey(s.place))) continue // keep the first (highest-scored) copy
        out.add(SearchPick(s.place, s.kind, s.dist))
        if (out.size >= 12) break
    }
    return out
}

/**
 * The rider's own places for an empty query: (saved = home then favourites) first, recents second,
 * each deduped so a place saved AND recently visited shows once (as a saved place).
 */
private fun localGroups(
    biasLat: Double?,
    biasLon: Double?,
    recents: List<MapPlace>,
    favorites: List<MapPlace>,
    home: MapPlace?,
): Pair<List<SearchPick>, List<SearchPick>> {
    val seen = HashSet<String>()
    val saved = ArrayList<SearchPick>()
    fun addSaved(p: MapPlace, kind: SearchKind) {
        if (seen.add(NominatimSearch.dedupeKey(p))) saved.add(SearchPick(p, kind, distanceTo(biasLat, biasLon, p)))
    }
    home?.let { addSaved(it, SearchKind.HOME) }
    for (f in favorites) addSaved(f, SearchKind.FAVORITE)
    val recent = ArrayList<SearchPick>()
    for (r in recents) {
        if (seen.add(NominatimSearch.dedupeKey(r))) {
            recent.add(SearchPick(r, SearchKind.RECENT, distanceTo(biasLat, biasLon, r)))
        }
    }
    return saved.take(8) to recent.take(10)
}

/** Small dim section label ("SAVED", "RECENT") above the empty-query groups. */
@Composable
private fun SearchSectionHeader(text: String) {
    val c = LocalCockpitColors.current
    Text(
        text.uppercase(),
        color = c.inkFaint,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(start = 8.dp, top = 10.dp, bottom = 2.dp),
    )
}

/** One suggestion row: a kind glyph, the name in bold, the locality + distance subtitle beneath. */
@Composable
private fun SuggestionRow(pick: SearchPick, subtitle: String, onClick: () -> Unit) {
    val c = LocalCockpitColors.current
    val (glyph, glyphColor) = when (pick.kind) {
        SearchKind.HOME -> "⌂" to c.ignition
        SearchKind.FAVORITE -> "★" to c.ignition
        SearchKind.RECENT -> "↺" to c.inkDim
        SearchKind.RESULT -> "" to c.inkDim
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(30.dp), contentAlignment = Alignment.Center) {
            if (glyph.isNotEmpty()) Text(glyph, color = glyphColor, fontSize = 17.sp)
        }
        Column(Modifier.weight(1f)) {
            Text(
                pick.place.name,
                color = c.ink,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, color = c.inkDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Display subtitle for a row: a compact locality then the distance ("Comuna 2 · Cali · 4.5 km"). */
private fun rowSubtitle(ctx: Context, pick: SearchPick): String {
    val locality = compactLocality(pick.place)
    val dist = distanceLabel(ctx, pick.distanceKm)
    return listOf(locality, dist).filter { it.isNotBlank() }.joinToString(" · ")
}

/** Trim a source subtitle down to a short, human locality (drops the giant Nominatim address tail). */
private fun compactLocality(p: MapPlace): String {
    val s = p.subtitle.trim().replace('_', ' ')
    if (s.isEmpty()) return ""
    if (s.contains(" · ")) return s.take(48) // Photon already gives a compact "type · City" line
    val parts = s.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    if (parts.isEmpty()) return s.take(48)
    val start = if (parts.first().equals(p.name, ignoreCase = true)) 1 else 0
    return parts.drop(start).take(2).joinToString(" · ").ifBlank { parts.first() }
}

/** "850 m" / "4.5 km" / "12 km" — units via resources; empty (hidden) when distance is unknown. */
private fun distanceLabel(ctx: Context, km: Double): String {
    if (km < 0) return ""
    return when {
        km < 1.0 -> ctx.getString(R.string.ovk_dist_m, "${(km * 1000).roundToInt()}")
        km < 10.0 -> ctx.getString(R.string.ovk_dist_km, String.format("%.1f", km))
        else -> ctx.getString(R.string.ovk_dist_km, String.format("%.0f", km))
    }
}

/**
 * Strict Mapsforge gate card — shown full-bleed in place of the map when the Mapsforge renderer is
 * selected but no offline `.map` is installed (no silent osmdroid fallback). Mirrors MapScreen's
 * `MapsforgeNeedsMapCard`; reuses the same `ovk_mf_needs_map_*` strings, themed to the cockpit.
 */
@Composable
private fun MapsforgeNeedsMapCard(modifier: Modifier, onDownload: () -> Unit) {
    val c = LocalCockpitColors.current
    Column(
        modifier.background(c.ground).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.ovk_mf_needs_map_title),
            color = c.ink,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            textAlign = TextAlign.Center,
        )
        Text(
            stringResource(R.string.ovk_mf_needs_map_body),
            color = c.inkDim,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )
        Row(
            Modifier
                .clip(RoundedCornerShape(24.dp))
                .background(c.ignition)
                .border(1.dp, c.ignitionHi, RoundedCornerShape(24.dp))
                .clickable(onClick = onDownload)
                .padding(horizontal = 22.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.ovk_mf_needs_map_cta),
                color = c.onIgnition,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/**
 * Last-known fix as a plain lat/lon, or null when we have neither permission nor a cached fix.
 * Mirrors [dev.zanderp.opencfmoto.GpxActivity]'s own last-known helper (GPS then network). Guarded so
 * a missing permission degrades to "no origin" (marker-only) instead of throwing.
 */
private fun lastKnownLatLon(ctx: Context): Pair<Double, Double>? {
    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
        != PackageManager.PERMISSION_GRANTED
    ) {
        return null
    }
    val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
    val loc = runCatching { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull()
        ?: runCatching { lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }.getOrNull()
    return loc?.let { it.latitude to it.longitude }
}
