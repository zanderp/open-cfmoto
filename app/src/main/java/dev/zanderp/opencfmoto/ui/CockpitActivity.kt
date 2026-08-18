// SPDX-License-Identifier: AGPL-3.0-or-later
// The Compose shell of the CFMOTO-first redesign. Ships as a second launcher ("Cockpit") so the
// new UI can be previewed on the real bike while the classic MainActivity keeps working; it becomes
// the primary entry as screens migrate over.
package dev.zanderp.opencfmoto.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.zanderp.opencfmoto.R
import dev.zanderp.opencfmoto.AppSettings
import dev.zanderp.opencfmoto.BikeLink
import dev.zanderp.opencfmoto.BikeMemory
import dev.zanderp.opencfmoto.ConnectionState
import dev.zanderp.opencfmoto.GpxSession
import dev.zanderp.opencfmoto.LogBus
import dev.zanderp.opencfmoto.Phase
import dev.zanderp.opencfmoto.ProjectionHolder
import dev.zanderp.opencfmoto.ProjectionService
import dev.zanderp.opencfmoto.SetupHelper
import dev.zanderp.opencfmoto.connection.AutoConnectManager
import dev.zanderp.opencfmoto.connection.CfmotoConnect
import dev.zanderp.opencfmoto.settings.SettingsStore
import dev.zanderp.opencfmoto.ui.cockpit.CockpitScreen
import dev.zanderp.opencfmoto.ui.controls.ControlsScreen
import dev.zanderp.opencfmoto.ui.dashboard.DashboardScreen
import dev.zanderp.opencfmoto.ui.garage.GarageScreen
import dev.zanderp.opencfmoto.ui.map.MapScreen
import dev.zanderp.opencfmoto.ui.mirror.MirrorScreen
import dev.zanderp.opencfmoto.ui.scan.ScanScreen
import dev.zanderp.opencfmoto.ui.settings.ButtonMappingScreen
import dev.zanderp.opencfmoto.ui.settings.CustomResolutionScreen
import dev.zanderp.opencfmoto.ui.settings.LanguageScreen
import dev.zanderp.opencfmoto.ui.settings.LogScreen
import dev.zanderp.opencfmoto.ui.settings.MapHubScreen
import dev.zanderp.opencfmoto.ui.settings.MapsforgeMapsScreen
import dev.zanderp.opencfmoto.ui.settings.OfflinePacksScreen
import dev.zanderp.opencfmoto.ui.settings.OnboardingScreen
import dev.zanderp.opencfmoto.ui.settings.ScreenMarginsScreen
import dev.zanderp.opencfmoto.ui.settings.SettingsScreen
import dev.zanderp.opencfmoto.ui.settings.SetupScreen
import dev.zanderp.opencfmoto.ui.theme.CockpitTheme
import dev.zanderp.opencfmoto.ui.theme.LocalCockpitColors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class CockpitActivity : AppCompatActivity() {

    // Activity-result consent for MediaProjection is Activity-bound, so the cockpit host owns it (the
    // Compose MirrorScreen calls startPhoneMirror() below). Ported from MainActivity.projectionLauncher;
    // the only change is the isForeground poll runs on a main-looper Handler (this Activity has no
    // logView to post on) and, once armed, it goes straight to the shared CfmotoConnect.startMirrorLink
    // (no confirmation dialog — a dialog left dismissed would strand the projection armed-but-unused).
    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != RESULT_OK || result.data == null) {
            // Consent declined → ProjectionService was never started (that call is below), so just log.
            LogBus.log("screen-capture consent declined")
            return@registerForActivityResult
        }
        // FGS of type mediaProjection must be RUNNING before getMediaProjection() on API 34+.
        // startForegroundService is async, so poll the service's foreground flag (~every 100ms).
        ProjectionService.start(this)
        val code = result.resultCode
        val data = result.data!!
        val handler = Handler(Looper.getMainLooper())
        val maxTries = 50 // 50 * 100ms = 5s ceiling
        val poll = object : Runnable {
            var tries = 0
            override fun run() {
                if (ProjectionService.isForeground) {
                    try {
                        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                        ProjectionHolder.projection = mpm.getMediaProjection(code, data)
                        GpxSession.clear()
                        LogBus.log("screen-capture armed (FGS up after ${tries * 100}ms) — casting phone to dash")
                        CfmotoConnect.startMirrorLink(this@CockpitActivity)
                    } catch (e: Exception) {
                        LogBus.log("getMediaProjection failed: $e")
                        ProjectionService.stop(this@CockpitActivity)
                    }
                } else if (tries++ < maxTries) {
                    handler.postDelayed(this, 100)
                } else {
                    LogBus.log("foreground service did not start within 5s — aborting mirror")
                    ProjectionService.stop(this@CockpitActivity)
                }
            }
        }
        handler.post(poll)
    }

    // One-time CompanionDeviceManager association picker. The system returns an IntentSender we launch
    // here; the actual association + presence observation is created in AutoConnectManager's callback
    // (onAssociationCreated → startObserving), so this result only needs to log the picker outcome.
    private val associateLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK) LogBus.log("[CDM] bike picked — association creating")
        else LogBus.log("[CDM] association picker dismissed")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 15+ enforces edge-to-edge; declare it explicitly (transparent bars) and then keep
        // content within the safe area so the top/bottom bars never collide with the system status
        // and navigation controls. The window behind the bars shows the cockpit ground, so they blend.
        enableEdgeToEdge()
        // Optional deep-link: a Routes value to open on top of the dashboard (e.g. the dash's
        // "Mapsforge needs a map" prompt sends the rider straight to the offline-maps screen).
        val startRoute = intent?.getStringExtra(EXTRA_OPEN_ROUTE)
        // First-run gate: resolve the start destination BEFORE composing so the dashboard never
        // flashes ahead of the onboarding (no post-hoc redirect that the eye can catch).
        val startDestination = resolveStartDestination(startRoute)
        setContent {
            CockpitTheme {
                val c = LocalCockpitColors.current
                Box(Modifier.fillMaxSize().background(c.ground).safeDrawingPadding()) {
                    CockpitApp(startRoute, startDestination)
                }
            }
        }
    }

    /**
     * Decide whether the first-run onboarding ([OnboardingScreen]) opens ahead of the dashboard.
     *
     * The `onboardingDone` flag is read SYNCHRONOUSLY here — once, at cold start, before `setContent`
     * and therefore before any other DataStore consumer (the theme, Settings) begins collecting — so
     * the returned route is the graph's real start and the dashboard is never shown first (no flicker).
     * The tiny blocking read of a single preferences key at process start is safe: nothing else is
     * touching this DataStore yet, so there is no actor contention to deadlock on.
     *
     * The rule errs toward NOT trapping the rider (§ first-frame default): a deep-link (they came from
     * inside the app), an already-granted runtime-permission set (an upgrader from before this flow
     * existed, or anyone who granted them elsewhere), or the persisted "done" flag all go straight to
     * the dashboard. Only a genuinely fresh install — flag unset AND permissions still missing — lands
     * on the onboarding, and even there a "Skip" affordance sets the flag so it is shown at most once.
     */
    private fun resolveStartDestination(startRoute: String?): String {
        if (startRoute != null) return Routes.DASHBOARD
        val done = runCatching {
            runBlocking { SettingsStore(applicationContext).onboardingDone.first() }
        }.getOrDefault(false)
        return if (done || SetupHelper.permissionsGranted(this)) Routes.DASHBOARD else Routes.ONBOARDING
    }

    /**
     * Begin phone → dash mirroring: request screen-capture consent; on grant [projectionLauncher] arms
     * the MediaProjection and calls [CfmotoConnect.startMirrorLink]. Public so the Compose MirrorScreen
     * can trigger it. Ported from MainActivity.onMirrorPressed (same dup-tap guard + intent build).
     *
     * Standalone: no live bike link is needed to start — it re-uses the SAVED bike QR (BikeMemory) exactly
     * as the classic mirror does; startMirrorLink surfaces an ERROR state if no bike is paired yet.
     */
    fun startPhoneMirror() {
        // parsec82 TRK 702 X: re-tapping Mirror while frames still flowed tore down a live PXC session.
        // Ignore duplicate taps when mirroring and a frame arrived recently; allow retry when stalled.
        val mirroring =
            ConnectionState.phase == Phase.MIRRORING || ConnectionState.phase == Phase.RECONNECTING
        val ms = BikeLink.prober?.msSinceLastFrame() ?: Long.MAX_VALUE
        if (mirroring && ms < 15_000L) {
            LogBus.log("→ Mirror: already mirroring (last frame ${ms}ms ago) — ignore duplicate tap")
            Toast.makeText(
                this,
                getString(R.string.ovk_mirror_already_projecting),
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        if (BikeMemory.lastQr(this) == null) {
            LogBus.log("→ Mirror: no hay moto emparejada — escanea el QR primero")
            Toast.makeText(this, getString(R.string.ovk_mirror_pair_first), Toast.LENGTH_LONG).show()
            return
        }
        LogBus.log("→ Mirror: cast phone screen to dash")
        ensureLocationPermission()
        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val intent = if (Build.VERSION.SDK_INT >= 34) {
                mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForUserChoice())
            } else {
                Toast.makeText(
                    this,
                    getString(R.string.ovk_mirror_needs_android14),
                    Toast.LENGTH_LONG,
                ).show()
                mpm.createScreenCaptureIntent()
            }
            projectionLauncher.launch(intent)
        } catch (e: Exception) {
            LogBus.log("mirror start failed ($e)")
        }
    }

    /**
     * One-time setup for BACKGROUND auto-connect: associate the paired bike's Wi‑Fi SSID with the system
     * CompanionDeviceManager so it can WAKE the app ([dev.zanderp.opencfmoto.connection.CockpitCompanionService])
     * when the bike appears — even with the app closed. Public so the Compose Settings row can call it.
     * Needs Android 13+ (CDM Wi‑Fi filter); on older phones the foreground fallback still auto-connects.
     */
    fun enableBackgroundAutoConnect() {
        if (Build.VERSION.SDK_INT < 33 || !AutoConnectManager.isSupported(this)) {
            Toast.makeText(this, getString(R.string.ovk_autoconnect_bg_unsupported), Toast.LENGTH_LONG).show()
            return
        }
        val qr = BikeMemory.lastQr(this) ?: run {
            Toast.makeText(this, getString(R.string.ovk_mirror_pair_first), Toast.LENGTH_LONG).show()
            return
        }
        // Turn the feature gate on (AppSettings is the single source of truth both auto paths read).
        AppSettings.setAutoConnect(this, true)
        // Match ONLY this bike's SoftAP SSID (quoted → exact, not a broad prefix) so no other network wakes us.
        val ssidRegex = java.util.regex.Pattern.quote(qr.ssid)
        ensureLocationPermission()
        AutoConnectManager.associate(this, ssidRegex) { intentSender ->
            try {
                associateLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
            } catch (e: Exception) {
                LogBus.log("[CDM] launch association picker failed: $e")
            }
        }
        Toast.makeText(this, getString(R.string.ovk_autoconnect_bg_setup_started), Toast.LENGTH_LONG).show()
    }

    /**
     * Some OEMs require fine location to associate via WifiNetworkSpecifier. Inlined from
     * CfmotoConnect.ensureLocationPermission (private there) — fire-and-forget like the classic
     * MainActivity: no result callback; if denied, the rider grants and taps again.
     */
    private fun ensureLocationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1,
            )
        }
    }

    companion object {
        /** Intent extra: a [Routes] value to open as the start destination (e.g. from the dash gate). */
        const val EXTRA_OPEN_ROUTE = "open_route"

        /** Launch the cockpit shell and open [route] (a [Routes] constant) on top of the dashboard. */
        fun open(ctx: Context, route: String) {
            ctx.startActivity(
                Intent(ctx, CockpitActivity::class.java).apply {
                    putExtra(EXTRA_OPEN_ROUTE, route)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        }
    }
}

object Routes {
    const val DASHBOARD = "dashboard"
    const val SETTINGS = "settings"
    const val GARAGE = "garage"
    const val SCAN = "scan"
    const val CONTROLS = "controls"
    const val MIRROR = "mirror"
    const val MAP = "map"
    const val COCKPIT = "cockpit"
    const val SCREEN_MARGINS = "screen_margins"
    const val CUSTOM_RESOLUTION = "custom_resolution"
    const val SETUP = "setup"
    const val BUTTON_MAPPING = "button_mapping"
    const val MAP_HUB = "map_hub"
    const val OFFLINE_PACKS = "offline_packs"
    const val MAPSFORGE_MAPS = "mapsforge_maps"
    const val ONBOARDING = "onboarding"
    const val LANGUAGE = "language"
    const val LOG = "log"
}

@Composable
fun CockpitApp(startRoute: String? = null, startDestination: String = Routes.DASHBOARD) {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = startDestination) {
        composable(Routes.DASHBOARD) { DashboardScreen(nav) }
        composable(Routes.SETTINGS) { SettingsScreen(nav) }
        composable(Routes.GARAGE) { GarageScreen(nav) }
        composable(Routes.SCAN) { ScanScreen(nav) }
        composable(Routes.CONTROLS) { ControlsScreen(nav) }
        composable(Routes.MIRROR) { MirrorScreen(nav) }
        composable(Routes.MAP) { MapScreen(nav) }
        composable(Routes.COCKPIT) { CockpitScreen(nav) }
        composable(Routes.SCREEN_MARGINS) { ScreenMarginsScreen(nav) }
        composable(Routes.CUSTOM_RESOLUTION) { CustomResolutionScreen(nav) }
        composable(Routes.SETUP) { SetupScreen(nav) }
        composable(Routes.ONBOARDING) { OnboardingScreen(nav) }
        composable(Routes.LANGUAGE) { LanguageScreen(nav) }
        composable(Routes.LOG) { LogScreen(nav) }
        composable(Routes.BUTTON_MAPPING) { ButtonMappingScreen(nav) }
        composable(Routes.MAP_HUB) { MapHubScreen(nav) }
        composable(Routes.OFFLINE_PACKS) { OfflinePacksScreen(nav) }
        composable(Routes.MAPSFORGE_MAPS) { MapsforgeMapsScreen(nav) }
    }
    // Deep-link: after the graph is set, jump onto the requested route (so Back returns to the dash).
    if (startRoute != null) {
        LaunchedEffect(startRoute) { nav.navigate(startRoute) }
    }
}
