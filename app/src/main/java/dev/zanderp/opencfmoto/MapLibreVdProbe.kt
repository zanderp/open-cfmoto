// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.widget.FrameLayout
import dev.overtake.maps.OvertakeMaps
import dev.overtake.maps.contract.MapRenderer

/**
 * DEV-MODE ONLY diagnostic probe — has ZERO production impact (reachable solely from the developer
 * settings action; the normal dash keeps rendering with osmdroid).
 *
 * ## Question it settles empirically
 * The bike dash renders a map through a [Presentation] on a PRIVATE [android.hardware.display.VirtualDisplay]
 * whose Surface feeds a [android.media.MediaCodec] H.264 encoder ([VideoPipeline] own-content mode). That
 * pipeline is proven with osmdroid. The dash renderer historically picked osmdroid there
 * (a Presentation context is not an Activity) because of the ASSUMED MapLibre-GL
 * block-artifact-on-motion behaviour. This probe replaces the
 * ONE variable — the renderer — inside that real, proven pipeline and measures the result, instead of
 * building a parallel pipeline that would confound plumbing bugs with MapLibre's actual behaviour.
 *
 * ## What it does (~60 s, then stops cleanly)
 *  1. Runs [VideoPipeline] in own-content mode with `forceDump=true` → the H.264 lands at
 *     `<externalFilesDir>/video/opencfmoto-video-probe.h264` (adb-pullable, see [dumpPath]).
 *  2. Forces the VD Presentation to host a MapLibre `MapView` via the library's
 *     `OvertakeMaps.createDevProbeRenderer` (a probe-only `forceLibre` override of the renderer choice)
 *     — the SAME MapLibre path the phone preview uses.
 *  3. Continuously animates the camera (pan + zoom, ping-ponging between two points every ~1.5 s) over
 *     the app's usual OpenFreeMap style so the map is MOVING — the artifact trigger.
 *  4. Emits a once-per-second encoder frame-rate line on the distinct logcat tag [VideoPipeline.PROBE_TAG]
 *     so the output rate can be watched live, including across a mid-run screen-off toggle.
 *  5. Holds a partial wakelock so a screen-off (`adb shell input keyevent 26`) can't freeze the CPU —
 *     isolating the screen-off question to the RENDERER, not to process suspension.
 *
 * No bike / PXC needed: the encoder drains to a file + in-memory queue regardless of any connection.
 */
object MapLibreVdProbe {
    private val LOGTAG = VideoPipeline.PROBE_TAG
    private val main = Handler(Looper.getMainLooper())

    // Representative bike dash canvas (Ride MO NaviVirtualDisplay is 1024×464; both even for the encoder).
    private const val PROBE_W = 1024
    private const val PROBE_H = 464
    private const val RUN_MS = 60_000L
    private const val TICK_MS = 150L    // camera re-issue cadence (continuous motion)
    private const val LEG_MS = 1_500L   // time to traverse A→B (or B→A): "~1.5 s between two points"
    private val DEFAULT_CENTER = Pair(40.7580, -73.9855) // Times Square — dense OpenFreeMap coverage

    @Volatile private var running = false
    private var video: VideoPipeline? = null
    @Volatile private var engine: MapRenderer? = null
    private var wake: PowerManager.WakeLock? = null

    // Animation state (all touched on the main thread only).
    private var aLat = 0.0; private var aLon = 0.0
    private var bLat = 0.0; private var bLon = 0.0
    private var t = 0.0; private var dir = 1

    val isRunning: Boolean get() = running

    /** Absolute path the probe's H.264 dump lands at (for `adb pull`). */
    fun dumpPath(context: Context): String =
        java.io.File(java.io.File(context.applicationContext.getExternalFilesDir(null), "video"), "opencfmoto-video-probe.h264")
            .absolutePath

    /** Dev-settings action: start when idle, stop when running. Returns a short status for a Toast. */
    fun toggle(context: Context): String = if (running) stop("user-tap") else start(context)

    fun start(context: Context): String {
        if (running) return "MapLibre VD probe already running (~stops at 60 s)"
        val ctx = context.applicationContext
        running = true
        plog("=== MapLibre-on-VD probe START (${PROBE_W}x$PROBE_H, ~${RUN_MS / 1000}s) ===")

        val center = lastKnownCenter(ctx) ?: DEFAULT_CENTER.also {
            plog("no last GPS fix / no location permission — using default center $it (needs internet for tiles)")
        }
        val (cLat, cLon) = center
        // Two points ~1.3 km apart straddling the center; the camera ping-pongs between them.
        aLat = cLat - 0.004; aLon = cLon - 0.006
        bLat = cLat + 0.004; bLon = cLon + 0.006
        t = 0.0; dir = 1
        plog("center=($cLat,$cLon) A=($aLat,$aLon) B=($bLat,$bLon)")

        try {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "opencfmoto:maplibre-vd-probe").apply {
                setReferenceCounted(false)
                acquire(RUN_MS + 5_000L)
            }
            plog("partial wakelock held — a mid-run screen-off cannot freeze the CPU")
        } catch (e: Exception) {
            plog("wakelock failed ($e) — screen-off could pause the CPU and muddy the result")
        }

        val path = dumpPath(ctx)
        plog("H.264 dump → $path")
        plog("watch rate: adb logcat -s $LOGTAG:*   |   screen off mid-run: adb shell input keyevent 26")

        val vp = VideoPipeline(
            context = ctx,
            width = PROBE_W,
            height = PROBE_H,
            log = LogBus::log,
            forceDump = true,
            probeFrameLog = true,
            probePresentationContent = { host, presCtx, _ -> onPresentationReady(host, presCtx) },
            onProbeStop = { releaseMap() },
        )
        video = vp
        main.post {
            vp.start()
            if (!vp.isAlive) {
                plog("VideoPipeline failed to start (encoder?) — aborting")
                stop("start-failed")
            }
        }
        main.postDelayed({ if (running) stop("auto-60s") }, RUN_MS)
        return "Probe started (~${RUN_MS / 1000}s). Dump: $path | logcat -s $LOGTAG:*"
    }

    fun stop(reason: String = "manual"): String {
        if (!running) return "probe not running"
        running = false
        plog("=== probe STOP ($reason) ===")
        main.removeCallbacks(animation)
        try { video?.stop() } catch (e: Exception) { plog("VideoPipeline.stop error: $e") }
        video = null
        // onProbeStop → releaseMap() runs on the pipeline's main.post; also clear here defensively.
        try { wake?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        wake = null
        return "MapLibre VD probe stopped ($reason)"
    }

    /** Runs on the main thread (VideoPipeline invokes the content factory there). */
    private fun onPresentationReady(host: FrameLayout, presCtx: Context) {
        try {
            // Library dev-probe renderer: forceLibre = true → MapLibre (not osmdroid) renders on the VD
            // Presentation regardless of config. attach() hosts its MapView in the VideoPipeline host.
            val eng = OvertakeMaps.createDevProbeRenderer(overtakeMapsConfig(presCtx.applicationContext))
            eng.attach(presCtx, host)
            eng.onCreate(null)
            eng.onResume()
            // The renderer starts day-neutral; the host owns theme (parity with the real dash which
            // calls applyTheme after attach). Match the current day/night so the probe map looks right.
            eng.applyTheme(NightPrefs.isNightNow(presCtx))
            engine = eng
            plog("MapLibre engine created on VD Presentation (forceLibre=true), lifecycle up — animating")
            main.removeCallbacks(animation)
            main.post(animation)
        } catch (e: Exception) {
            plog("MapLibre engine init FAILED on the VD: $e")
        }
    }

    private val animation = object : Runnable {
        override fun run() {
            val eng = engine ?: return
            if (!running) return
            t += (TICK_MS.toDouble() / LEG_MS) * dir
            if (t >= 1.0) { t = 1.0; dir = -1 } else if (t <= 0.0) { t = 0.0; dir = 1 }
            val lat = aLat + (bLat - aLat) * t
            val lon = aLon + (bLon - aLon) * t
            val zoom = 14.0 + (16.5 - 14.0) * t
            val brg = if (dir > 0) bearing(aLat, aLon, bLat, bLon) else bearing(bLat, bLon, aLat, aLon)
            // Reuse the real MapLibre follow() (easeCamera + heading-up + 3D tilt) so the motion matches
            // the actual navigation render load the bike would see.
            try { eng.follow(lat, lon, brg, zoom, headingUp = true, moving = true) } catch (_: Exception) {}
            main.postDelayed(this, TICK_MS)
        }
    }

    /** Runs on the main thread (from [VideoPipeline.stop]'s teardown post). */
    private fun releaseMap() {
        main.removeCallbacks(animation)
        try { engine?.onPause() } catch (_: Exception) {}
        try { engine?.onDestroy() } catch (_: Exception) {}
        engine = null
        plog("MapLibre engine released (no leak)")
    }

    @SuppressLint("MissingPermission")
    private fun lastKnownCenter(ctx: Context): Pair<Double, Double>? = try {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        var best: android.location.Location? = null
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)) {
            val l = runCatching { lm?.getLastKnownLocation(p) }.getOrNull() ?: continue
            if (best == null || l.time > best!!.time) best = l
        }
        best?.let { it.latitude to it.longitude }
    } catch (_: Exception) {
        null
    }

    private fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val dLon = Math.toRadians(lon2 - lon1)
        val y = Math.sin(dLon) * Math.cos(Math.toRadians(lat2))
        val x = Math.cos(Math.toRadians(lat1)) * Math.sin(Math.toRadians(lat2)) -
            Math.sin(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.cos(dLon)
        return ((Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0).toFloat()
    }

    private fun plog(msg: String) {
        android.util.Log.i(LOGTAG, msg)
        LogBus.log("[PROBE] $msg")
    }
}
