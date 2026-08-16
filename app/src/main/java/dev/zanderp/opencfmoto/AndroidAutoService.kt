// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import dev.zanderp.opencfmoto.aa.AaReceiver

/**
 * Foreground service that hosts the Android Auto receiver end-to-end (M4):
 *   VideoPipeline (H.264 encoder, external-source mode) + AaReceiver (loopback AAP receiver).
 *
 * Running as a foreground service with a partial wake lock keeps the whole decode→encode→PXC
 * chain alive while the phone is backgrounded or the screen is locked. The encoder's input
 * Surface is published to [AaVideoBridge] so [EasyConnProber] streams the re-encoded Android
 * Auto video to the bike dash.
 */
class AndroidAutoService : Service() {

    private var pipeline: VideoPipeline? = null
    private var receiver: AaReceiver? = null
    private var mediaButtons: MediaButtonBridge? = null
    private var wakeLock: PowerManager.WakeLock? = null
    /** Dim screen wake while GPX Presentation is up (VirtualDisplay stalls if the phone sleeps hard). */
    private var screenWakeLock: PowerManager.WakeLock? = null
    // Wi-Fi locks that keep the bike link fast for the whole streaming session (see [acquireWifiLocks]).
    // Two are held on purpose: LOW_LATENCY only engages while the app is foreground AND the screen is
    // on, but riders are told to ride with the screen OFF — so HIGH_PERF (which holds regardless of
    // screen state) is the workhorse that keeps Wi-Fi out of power-save while pocketed, and LOW_LATENCY
    // adds extra latency reduction when the Dash view is open / riding screen-on.
    private var wifiLockLowLatency: WifiManager.WifiLock? = null
    private var wifiLockHighPerf: WifiManager.WifiLock? = null
    private val watchdogHandler = Handler(Looper.getMainLooper())
    private var lastRecoveryAt = 0L
    /** While set, stall watchdog must not forceReconnect — AA→Map codec swap needs a quiet window. */
    @Volatile private var softSwitchGraceUntil = 0L

    // Adaptive video tuning (PowerMode.AUTO): thermal + link-driven bitrate/fps, run each watchdog tick.
    private val adaptive by lazy { AdaptiveVideoController(applicationContext, LogBus::log) }

    // Hybrid reconnect supervisor state (see tickReconnectSupervisor / parkAa / doResume).
    @Volatile private var aaParked = false          // AA torn down to save battery while bike is gone
    @Volatile private var sawStream = false         // we reached STREAMING at least once this session
    @Volatile private var resumeSteadyReached = false
    private var wifiDownSince = 0L
    private var watchdogStarted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        active = this
        when (intent?.action) {
            // Notification "Stop" — same full teardown as the main screen's Stop button.
            ACTION_STOP -> {
                LogBus.log("[AA] Stop tapped from notification")
                fullTeardown()
                return START_NOT_STICKY
            }
            ACTION_GPX_WAKE -> {
                // Map Presentation only — do NOT start the AA receiver here. Starting AA would
                // publish AaVideoBridge.pipeline and steal the bike's next REQ_RV_DATA_START away
                // from the owned GPX VideoPipeline (blank / stuck "connected" dash).
                if (!tryStartAsForeground(allowMicrophone = false)) {
                    return START_NOT_STICKY
                }
                ensureMediaButtons()
                startWatchdog()
                isRunning = true
                applyGpxScreenWake(true)
                reacquireLocks()
                AppHttp.ensureCellularUplink()
                return START_STICKY
            }
        }
        // Sticky/null-action restarts (Intent FLAG_FROM_BACKGROUND) must not claim the microphone
        // FGS type — targetSdk 36 throws and AMS crash-loops the service.
        val stickyRestart = intent == null || (flags and START_FLAG_REDELIVERY) != 0 ||
            (intent.flags and Intent.FLAG_FROM_BACKGROUND) != 0
        if (!tryStartAsForeground(allowMicrophone = !stickyRestart)) {
            return START_NOT_STICKY
        }
        startReceiver()
        startWatchdog()
        isRunning = true
        return START_STICKY
    }

    /**
     * Auto-recovery watchdog. Every [WATCHDOG_TICK_MS] it checks the bike link and, if enabled:
     *  - STREAMING but no frames for [STALL_MS] → the dash stopped pulling (half-open link / stall):
     *    force a clean reconnect (drops sockets → the prober re-probes).
     *  - ERROR (reconnect budget spent) → periodically re-arm so a bike back in range re-links itself.
     * A cooldown between actions stops it from thrashing. The rider can disable it in Setup.
     */
    private fun startWatchdog() {
        if (watchdogStarted) return
        watchdogStarted = true
        watchdogHandler.postDelayed(object : Runnable {
            override fun run() {
                if (!isRunning) return
                try { tickReconnectSupervisor() } catch (_: Exception) {}
                try { tickWatchdog() } catch (_: Exception) {}
                try { adaptive.onTick(pipeline) } catch (_: Exception) {}
                try { tickTripLogging() } catch (_: Exception) {}
                watchdogHandler.postDelayed(this, WATCHDOG_TICK_MS)
            }
        }, WATCHDOG_TICK_MS)
    }

    /**
     * Auto-log the ride while AA is streaming and/or the built-in map UI is live (see [TripAutoLog]).
     * A transient RECONNECTING blip with the map still up is left alone so one ride isn't split.
     */
    private fun tickTripLogging() {
        TripAutoLog.sync(this)
    }

    private fun tickWatchdog() {
        if (!AppSettings.autoRecovery(this)) return
        val prober = BikeLink.prober ?: return
        if (!prober.isRunning) return
        val now = System.currentTimeMillis()
        if (now < softSwitchGraceUntil) return
        when {
            ConnectionState.phase == Phase.STREAMING &&
                prober.msSinceLastFrame() > STALL_MS &&
                now - lastRecoveryAt > ACTION_COOLDOWN_MS -> {
                LogBus.log("[watchdog] streaming stalled (${prober.msSinceLastFrame()}ms, no frames) — recovering")
                lastRecoveryAt = now
                prober.forceReconnect()
            }
            ConnectionState.phase == Phase.ERROR &&
                now - lastRecoveryAt > ERROR_COOLDOWN_MS -> {
                lastRecoveryAt = now
                prober.rearmFromError()
            }
        }
    }

    /**
     * Hybrid reconnect supervisor (the "stop AA + watch for the bike" behaviour).
     *
     * While Wi-Fi is up, brief drops are handled by the fast paths ([EasyConnProber] re-probe and
     * [BikeLink.onWifiReacquired]) — AA stays live for an instant resume. But if the bike's Wi-Fi has
     * been gone for [GRACE_MS] (rider stopped/parked the bike), we **park** AA: tear down the live
     * transcode to save battery/heat and let [BikeWifi] keep watching for the AP. When the AP returns
     * ([BikeWifi] re-acquires the network → [requestResume]) we rebuild AA and reconnect.
     */
    private fun tickReconnectSupervisor() {
        if (ConnectionState.phase == Phase.STREAMING) sawStream = true
        if (!AppSettings.autoRecovery(this)) return
        if (!isRunning || aaParked || !sawStream) return
        // Never tear down while the Map / GPX Presentation is live — backgrounding or a brief
        // Wi‑Fi blip must not kill the dash map (wake lock + VirtualDisplay stay up).
        if (GpxSession.active) {
            wifiDownSince = 0L
            if (screenWakeLock?.isHeld != true) applyGpxScreenWake(true)
            return
        }
        // Only park for a real Wi-Fi outage — a dash-only drop with Wi-Fi still up is the prober's job.
        val apConnected = BikeWifi.currentNetwork != null
        val p2pConnected = BikeWifiP2p.isConnected
        if (!bikeTransportConnected(apConnected, p2pConnected)) {
            if (AppSettings.keepWifiAfterDisconnect(this)) {
                wifiDownSince = 0L
                return
            }
            val now = System.currentTimeMillis()
            if (wifiDownSince == 0L) {
                wifiDownSince = now
                LogBus.log("[AA] bike transport unavailable (ap=$apConnected p2p=$p2pConnected) — starting grace")
            }
            else if (now - wifiDownSince > GRACE_MS) parkAa()
        } else {
            if (wifiDownSince != 0L) {
                LogBus.log("[AA] bike transport recovered (ap=$apConnected p2p=$p2pConnected)")
            }
            wifiDownSince = 0L
        }
    }

    /**
     * Tear AA down after the grace window so a parked/stopped bike doesn't cook the phone. Keeps the
     * foreground service alive (so [BikeWifi]'s network callback keeps watching for the AP) but drops
     * the wake lock so the phone can doze. Resumed by [doResume] when the bike's Wi-Fi comes back.
     */
    /** Stop the AA receiver + transcode (and any futile probing) but keep the service alive. */
    private fun tearDownAaKeepService() {
        AaVideoBridge.onSteadyVideo = null
        // Don't bank the trip if the map HUD is still live — [TripAutoLog] keeps recording.
        try { TripAutoLog.sync(this) } catch (_: Exception) {}
        try { BikeLink.prober?.stop() } catch (_: Exception) {}
        try { mediaButtons?.stop() } catch (_: Exception) {}
        mediaButtons = null
        try { receiver?.stop() } catch (_: Exception) {}
        receiver = null
        AaVideoBridge.pipeline = null
        try { pipeline?.stop() } catch (_: Exception) {}
        pipeline = null
        if (GpxSession.active) {
            // Map Presentation still needs CPU + screen wake even if AA video is parked.
            reacquireLocks()
            applyGpxScreenWake(true)
            LogBus.log("[GPX] keeping wake locks — map session still active")
        } else {
            try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
            releaseScreenWake()
            releaseWifiLocks()   // parked: let the radio idle again while we wait for the bike
        }
    }

    private fun parkAa() {
        if (aaParked) return
        aaParked = true
        LogBus.log("[AA] bike Wi-Fi gone > ${GRACE_MS / 1000}s — parking Android Auto to save battery; watching for the bike")
        ConnectionState.set(Phase.WAITING_FOR_BIKE, "will resume when the bike is back")
        tearDownAaKeepService()
        updateNotification(
            "Waiting for bike",
            "Projection paused to save battery — resumes when the bike is back",
        )
    }

    /**
     * The bike's Wi-Fi is back — rebuild the AA pipeline and reconnect. Re-triggering Google Android
     * Auto uses `startActivity`, which Android 12+ restricts from the background, so this is a
     * best-effort auto-resume; if AA doesn't come up in time we re-park (to keep battery low) and post
     * a tap-to-resume notification — [MainActivity] then finishes from the foreground (BAL-safe).
     */
    private fun doResume() {
        if (!aaParked) return
        aaParked = false
        wifiDownSince = 0L
        resumeSteadyReached = false
        LogBus.log("[AA] bike Wi-Fi back — resuming Android Auto (auto attempt)")
        // Reset the trailing detail (the park set it to "will resume…") back to the bike name so it
        // doesn't linger onto STREAMING as "Connected … — will resume when the bike is back".
        ConnectionState.set(Phase.STARTING_AA, BikeMemory.lastBikeName(applicationContext) ?: "")
        reacquireLocks()
        updateNotification(getString(R.string.notif_aa_title), getString(R.string.notif_aa_reconnecting))

        startReceiver()
        if (receiver == null) { resumeFailedFallback(); return }

        BikeLink.beginHandoff(this)
        AaVideoBridge.onSteadyVideo = {
            AaVideoBridge.onSteadyVideo = null
            resumeSteadyReached = true
            ConnectionState.set(Phase.AA_VIDEO_LIVE)
            LogBus.log("→ Android Auto video is live (resume)")
            BikeLink.markAaVideoSteady()
        }
        BikeLink.markWifiReady(BikeWifi.currentNetwork)
        dev.zanderp.opencfmoto.aa.AaSelfMode.trigger(applicationContext, log = LogBus::log)

        // If AA can't self-start from the background (BAL), retry once then hand off to the foreground.
        watchdogHandler.postDelayed({
            if (!isRunning || aaParked || resumeSteadyReached) return@postDelayed
            LogBus.log("[AA] resume: no AA video yet — re-triggering self-mode once")
            dev.zanderp.opencfmoto.aa.AaSelfMode.trigger(applicationContext, log = LogBus::log)
            watchdogHandler.postDelayed({
                if (!isRunning || aaParked || resumeSteadyReached) return@postDelayed
                resumeFailedFallback()
            }, RESUME_STEADY_TIMEOUT_MS)
        }, RESUME_STEADY_TIMEOUT_MS)
    }

    /** Background AA relaunch was blocked — park again (battery) and prompt the rider to tap to resume. */
    private fun resumeFailedFallback() {
        if (!isRunning || resumeSteadyReached) return
        aaParked = true
        LogBus.log("[AA] background AA relaunch blocked — parking + prompting tap-to-resume")
        ConnectionState.set(Phase.WAITING_FOR_BIKE, "tap to resume")
        tearDownAaKeepService()
        postResumeFallbackNotification()
    }

    /** (Re)acquire every lock the streaming session needs to stay alive backgrounded / screen-off. */
    private fun reacquireLocks() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OpenCfMoto:AndroidAuto")
                    .apply { setReferenceCounted(false) }
            }
            if (wakeLock?.isHeld != true) wakeLock?.acquire(4 * 60 * 60 * 1000L)
        } catch (_: Exception) {}
        acquireWifiLocks()
    }

    /** Hold a bright/dim screen wake while the GPX Presentation / VirtualDisplay is streaming. */
    fun applyGpxScreenWake(on: Boolean) {
        try {
            if (on) {
                reacquireLocks() // CPU + Wi‑Fi locks — screen-off must not stall the map pipeline
                if (screenWakeLock?.isHeld == true) return
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                @Suppress("DEPRECATION")
                // SCREEN_BRIGHT keeps the VirtualDisplay painting when the rider pockets the phone;
                // SCREEN_DIM alone is ignored on several OEMs once the Activity is backgrounded.
                val wl = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "OpenCfMoto:GpxPresentation",
                )
                wl.setReferenceCounted(false)
                wl.acquire(4 * 60 * 60 * 1000L)
                screenWakeLock = wl
                LogBus.log("[GPX] screen wake lock held (Presentation stay-alive)")
            } else {
                if (GpxSession.active) {
                    LogBus.log("[GPX] ignoring screen-wake release — map session still active")
                    return
                }
                releaseScreenWake()
            }
        } catch (e: Exception) {
            LogBus.log("[GPX] screen wake failed: $e")
        }
    }

    private fun releaseScreenWake() {
        try {
            screenWakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        screenWakeLock = null
    }

    /**
     * Hold Wi-Fi locks so the bike link doesn't drop into power-save mid-ride. A streaming session is
     * real-time video over the bike AP with the phone screen off — exactly when Android would otherwise
     * park the radio between beacons and add latency/jitter. HIGH_PERF keeps Wi-Fi awake regardless of
     * screen state (the screen-off case); LOW_LATENCY additionally cuts latency while foreground +
     * screen-on. Both are safe no-ops when Wi-Fi isn't the active transport. Released on park/stop so a
     * parked bike lets the radio idle again.
     */
    private fun acquireWifiLocks() {
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (wifiLockLowLatency == null) {
                wifiLockLowLatency = wm.createWifiLock(
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "OpenCfMoto:LowLatency",
                ).apply { setReferenceCounted(false) }
            }
            if (wifiLockHighPerf == null) {
                @Suppress("DEPRECATION") // deprecated but still the reliable screen-off, no-power-save mode
                val mode = WifiManager.WIFI_MODE_FULL_HIGH_PERF
                wifiLockHighPerf = wm.createWifiLock(mode, "OpenCfMoto:HighPerf")
                    .apply { setReferenceCounted(false) }
            }
            var acquired = false
            if (wifiLockLowLatency?.isHeld != true) { wifiLockLowLatency?.acquire(); acquired = true }
            if (wifiLockHighPerf?.isHeld != true) { wifiLockHighPerf?.acquire(); acquired = true }
            if (acquired) LogBus.log("[AA] Wi-Fi locks held (high-perf + low-latency) — keeping the bike link hot")
        } catch (_: Exception) {}
    }

    private fun releaseWifiLocks() {
        try { if (wifiLockLowLatency?.isHeld == true) wifiLockLowLatency?.release() } catch (_: Exception) {}
        try { if (wifiLockHighPerf?.isHeld == true) wifiLockHighPerf?.release() } catch (_: Exception) {}
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.notif_aa_channel), NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    /** Content intent → open MainActivity; [resume] adds the flag that makes it re-project on open. */
    private fun buildNotification(title: String, text: String, resume: Boolean): Notification {
        val open = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (resume) putExtra(EXTRA_RESUME, true)
        }
        val pi = PendingIntent.getActivity(this, if (resume) 1 else 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stopIntent = Intent(this, AndroidAutoService::class.java).apply { action = ACTION_STOP }
        val stopPi = PendingIntent.getService(this, 2, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pi)
            .setOnlyAlertOnce(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_stop), getString(R.string.main_stop), stopPi,
                ).build(),
            )
            .build()
    }

    private fun updateNotification(title: String, text: String, resume: Boolean = false) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotification(title, text, resume))
        } catch (_: Exception) {}
    }

    /** Guaranteed BAL-safe resume path: prompt the rider to tap; [MainActivity] then re-projects. */
    private fun postResumeFallbackNotification() {
        LogBus.log("[AA] auto-resume couldn't relaunch Android Auto in the background — prompting to tap")
        val hint = if (!SetupHelper.canAutoResume(this))
            "Tap to resume — or enable “Display over other apps” in Setup for hands-free resume"
        else
            getString(R.string.notif_tap_resume)
        updateNotification(getString(R.string.notif_bike_reconnected), hint, resume = true)
    }

    /**
     * Promote to a typed FGS. Prefer the richest type the process is allowed to claim; never call
     * bare [startForeground] on API 29+ (that inherits all manifest types, including microphone,
     * and crashes sticky restarts when mic isn't eligible).
     *
     * @param allowMicrophone include mic type when RECORD_AUDIO is granted (skip on sticky/bg restart)
     * @return false if every typed attempt failed — caller should [stopSelf] + START_NOT_STICKY
     */
    fun updateForegroundType(allowMicrophone: Boolean = true): Boolean {
        val notification = buildNotification(
            getString(R.string.notif_aa_title),
            getString(R.string.notif_aa_receiving),
            resume = false,
        )
        val hasLocation = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val hasMicrophone = allowMicrophone &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification)
            return true
        }

        // Ladder: richest → safest. Each step is try/caught so a mic/location denial can't kill the process.
        val candidates = ArrayList<Int>(3)
        var rich = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (hasLocation) rich = rich or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        if (hasMicrophone && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            rich = rich or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        candidates.add(rich)
        if (hasMicrophone && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            var noMic = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            if (hasLocation) noMic = noMic or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            if (noMic != rich) candidates.add(noMic)
        }
        if (hasLocation) {
            candidates.add(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        }
        // Always end with connectedDevice-only.
        if (candidates.last() != ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE) {
            candidates.add(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        }

        var lastError: Exception? = null
        for (fgType in candidates.distinct()) {
            try {
                startForeground(NOTIF_ID, notification, fgType)
                LogBus.log("[AA] foreground service type updated (fgType=$fgType, mic=$hasMicrophone)")
                return true
            } catch (e: Exception) {
                lastError = e
                LogBus.log("[AA] startForeground($fgType) failed: $e")
            }
        }
        LogBus.log("[AA] startForeground exhausted typed ladder — last: $lastError")
        return false
    }

    /** @return false if FGS promote failed (service should stop, not stick). */
    private fun tryStartAsForeground(allowMicrophone: Boolean): Boolean {
        ensureChannel()
        if (!updateForegroundType(allowMicrophone)) {
            LogBus.log("[AA] cannot start foreground service — stopping (won't sticky-loop)")
            try { stopSelf() } catch (_: Exception) {}
            return false
        }
        reacquireLocks()
        LogBus.log("[AA] foreground service up (wake + Wi-Fi locks held)")
        return true
    }

    /** Re-promote with mic when the rider/session is clearly foreground-eligible (AA live). */
    fun upgradeForegroundForMicrophone() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        updateForegroundType(allowMicrophone = true)
    }

    private fun startReceiver() {
        if (receiver != null) { LogBus.log("[AA] receiver already started"); return }
        try {
            // Resume/Connect after a park: process may still be pinned to a dead bike Network.
            BikeWifi.unbindIfNoBikeNetwork(applicationContext)
            // Compositor mode: the AA decoder renders into the compositor's input surface; the
            // compositor letterboxes it into the bike's canvas (encoder created later, sized to the
            // bike's REQ_CONFIG_CAPTURE dims — see EasyConnProber / VideoPipeline.configureBikeCanvas).
            val vp = VideoPipeline(applicationContext, 0, 0, LogBus::log, compositor = true)
            vp.start()
            val surface = vp.decoderInputSurface()
            if (surface == null) {
                LogBus.log("[AA] compositor input surface null — cannot start receiver")
                vp.stop()
                stopSelf()
                return
            }
            pipeline = vp
            AaVideoBridge.pipeline = vp
            adaptive.reset()   // fresh session: start adaptation from the user's target
            receiver = AaReceiver(applicationContext, surface, LogBus::log).also {
                it.onSessionEnded = { userExit -> onAaSessionEnded(userExit) }
                it.start()
            }
            // Capture the bike's handlebar buttons (Bluetooth AVRCP) → AA / Map navigation.
            ensureMediaButtons()
        } catch (e: Exception) {
            LogBus.log("[AA] receiver start failed: $e")
            stopSelf()
        }
    }

    /** Keep AVRCP capture alive for AA and for Map (same ButtonMap presets). */
    private fun ensureMediaButtons() {
        if (mediaButtons != null) return
        if (!canCaptureHandlebarButtons()) {
            LogBus.log("[BTN] skipped — Bluetooth off or no Nearby/BLUETOOTH_CONNECT (no focus/volume hijack)")
            return
        }
        mediaButtons = MediaButtonBridge(applicationContext, LogBus::log).also { it.start() }
    }

    /** Handlebar bridge needs BT on + CONNECT (API 31+); otherwise it fights phone media for nothing. */
    private fun canCaptureHandlebarButtons(): Boolean {
        if (!BluetoothHelper.hasConnectPermission(applicationContext)) return false
        return try {
            val mgr = getSystemService(BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
            mgr?.adapter?.isEnabled == true
        } catch (_: Exception) {
            false
        }
    }

    /** Stop AA decode/encode only — keep FGS, Wi‑Fi locks, and [MediaButtonBridge]. */
    private fun parkAaVideoOnly() {
        AaVideoBridge.onSteadyVideo = null
        // Avoid onSessionEnded(userExit) → fullTeardown while we swap sources.
        try { receiver?.onSessionEnded = null } catch (_: Exception) {}
        try { receiver?.stop() } catch (_: Exception) {}
        receiver = null
        AaVideoBridge.pipeline = null
        try { pipeline?.stop(abandonNavigation = false) } catch (_: Exception) {}
        pipeline = null
        ensureMediaButtons()
        reacquireLocks()
        applyGpxScreenWake(true)
        AppHttp.ensureCellularUplink()
    }

    /**
     * Soft AA→Map: stop Android Auto video, keep FGS + Wi‑Fi + PXC + handlebar bridge, attach a
     * GPX Presentation to the existing prober. Does not rejoin the bike AP.
     */
    private fun parkAaForMap(): Boolean {
        if (!GpxSession.active) {
            LogBus.log("[AA→MAP] no map session — abort soft-switch")
            return false
        }
        val prober = BikeLink.prober
        if (prober == null || !prober.isRunning) {
            LogBus.log("[AA→MAP] prober not live — caller should join Wi‑Fi")
            return false
        }
        LogBus.log("[AA→MAP] parking AA video; keeping bike link + handlebar bridge")
        softSwitchGraceUntil = System.currentTimeMillis() + 8_000L
        // Drop shared AA frames immediately so 114 doesn't poll a dead codec (stall → reconnect).
        try { prober.detachVideoSource() } catch (_: Exception) {}
        parkAaVideoOnly()
        updateNotification(
            getString(R.string.notif_map_title),
            getString(R.string.notif_map_text),
        )
        // Attach now — delayed attach left a multi-second black/unresponsive gap and often lost
        // the media plane until a full Stop/Start.
        val ok = prober.attachOwnedGpxVideo()
        if (ok) {
            LogBus.log("[AA→MAP] Map video attached")
            ConnectionState.set(Phase.STREAMING)
        } else {
            LogBus.log("[AA→MAP] Map attach failed — try Stop then Map")
            ConnectionState.set(Phase.ERROR, "Map switch failed — Stop, then Map")
        }
        return ok
    }

    /**
     * The Android Auto session ended. If the rider tapped Exit in AA ([userExit]), fully stop
     * projection — otherwise the bike keeps pulling the compositor's last frame and the dash freezes
     * on a stale image while the app still looks "connected". A transient drop is left alone so the
     * receiver can accept a reconnect. Runs on the main thread to avoid tearing down from the AAP
     * poll thread that just fired this callback.
     */
    private fun onAaSessionEnded(userExit: Boolean) {
        if (!userExit) {
            LogBus.log("[AA] session dropped — receiver still listening for reconnect")
            return
        }
        LogBus.log("[AA] user exited Android Auto — stopping projection to the dash")
        watchdogHandler.post { fullTeardown() }
    }

    /**
     * Drop the whole projection: stop the bike link (dash stops pulling the compositor's last frame,
     * so no frozen image), leave the bike Wi-Fi, mark STOPPED, and stop the service (which tears down
     * the receiver, pipeline, and wake lock in [onDestroy]). Mirrors [MainActivity]'s Stop path so the
     * notification Stop action behaves the same. Used by explicit Stop / Connect-Stop / AA-exit /
     * notification ACTION_STOP — not by swiping the app away ([onTaskRemoved] keeps the HUD streaming).
     */
    private fun fullTeardown() {
        AaVideoBridge.onSteadyVideo = null
        try { BikeLink.prober?.stop() } catch (_: Exception) {}
        try { ProjectionHolder.projection?.stop() } catch (_: Exception) {}
        ProjectionHolder.projection = null
        try { ProjectionService.stop(applicationContext) } catch (_: Exception) {}
        try { DashClockBle.stop() } catch (_: Exception) {}
        try { BikeWifi.releaseSession(applicationContext, LogBus::log) } catch (_: Exception) {}
        ConnectionState.set(Phase.STOPPED, "")
        // Bank the trip unless the map UI is still bound (phone preview); onDestroy syncs again.
        try { TripAutoLog.sync(this) } catch (_: Exception) {}
        stopSelf()   // onDestroy tears down the receiver, pipeline, and wake lock
    }

    /**
     * App task removed (swipe from recents / leave to launcher). Keep the FGS + wake locks +
     * video pipeline running so the bike HUD map keeps streaming until the rider taps Stop.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        LogBus.log("[AA] app task removed — keeping projection / FGS alive for bike HUD")
        reacquireLocks()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        isRunning = false
        aaParked = false
        if (active === this) active = null
        watchdogHandler.removeCallbacksAndMessages(null)
        try { TripAutoLog.sync(this) } catch (_: Exception) {}
        try { mediaButtons?.stop() } catch (_: Exception) {}
        mediaButtons = null
        try { receiver?.stop() } catch (_: Exception) {}
        receiver = null
        AaVideoBridge.pipeline = null
        try { pipeline?.stop() } catch (_: Exception) {}
        pipeline = null
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
        releaseScreenWake()
        releaseWifiLocks()
        wifiLockLowLatency = null
        wifiLockHighPerf = null
        LogBus.log("[AA] foreground service stopped")
        super.onDestroy()
    }

    companion object {
        private const val NOTIF_ID = 2
        private const val CHANNEL_ID = "opencfmoto_androidauto"

        // Watchdog cadence / thresholds.
        private const val WATCHDOG_TICK_MS = 5_000L
        private const val STALL_MS = 8_000L            // no frames this long while STREAMING = stalled
        private const val ACTION_COOLDOWN_MS = 20_000L // min gap between forced reconnects
        private const val ERROR_COOLDOWN_MS = 20_000L  // min gap between re-arm attempts after ERROR
        private const val GRACE_MS = 180_000L          // SoftAP blinks; 60s was parking mid-ride
        private const val RESUME_STEADY_TIMEOUT_MS = 12_000L // wait for AA video before falling back

        internal fun bikeTransportConnected(apConnected: Boolean, p2pConnected: Boolean): Boolean =
            apConnected || p2pConnected

        /** Intent extra: MainActivity should re-project on open (BAL-safe resume after a park). */
        const val EXTRA_RESUME = "dev.zanderp.opencfmoto.RESUME_PROJECTION"

        @Volatile var isRunning = false
            private set

        // Running instance, so the Wi-Fi layer can ask a parked service to resume without an Intent.
        @Volatile private var active: AndroidAutoService? = null

        /** True while AA is parked (torn down, waiting for the bike's Wi-Fi to return). */
        val isParked: Boolean get() = active?.aaParked == true

        /** Ask a parked service to rebuild AA and reconnect (called by [BikeWifi] on AP re-acquire). */
        fun requestResume() {
            val svc = active ?: return
            svc.watchdogHandler.post { svc.doResume() }
        }

        /**
         * The foreground ([MainActivity]) is taking over the resume (it will rebuild AA itself), so drop
         * the parked flag and reacquire the locks — otherwise the supervisor/Wi-Fi hooks would fight
         * the foreground path.
         */
        fun notifyForegroundResuming() {
            active?.let { it.aaParked = false; it.wifiDownSince = 0L; it.reacquireLocks() }
        }

        fun updateForegroundType() {
            active?.updateForegroundType(allowMicrophone = true)
        }

        fun upgradeForegroundForMicrophone() {
            active?.upgradeForegroundForMicrophone()
        }

        /**
         * Screen + CPU wake for GPX Presentation. Starts the FGS if needed so backgrounding /
         * screen-off cannot stall the VirtualDisplay (previously a no-op when AA wasn't up yet).
         */
        fun setGpxScreenWake(ctx: Context, on: Boolean) {
            val svc = active
            if (svc != null) {
                svc.applyGpxScreenWake(on)
                return
            }
            if (!on) return
            // Bring the FGS up (wake path) so backgrounding can't kill the VirtualDisplay.
            val i = Intent(ctx, AndroidAutoService::class.java).setAction(ACTION_GPX_WAKE)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
                else ctx.startService(i)
                LogBus.log("[GPX] starting FGS for Presentation stay-alive")
            } catch (e: Exception) {
                LogBus.log("[GPX] could not start FGS for wake: $e")
            }
        }

        /**
         * Soft-switch a live AA (or FGS) session to Map / GPX on the same bike link.
         * Call from the main thread. Returns false when the caller should fall back to a fresh Wi‑Fi join.
         */
        fun switchToMapProjection(ctx: Context): Boolean {
            val svc = active
            if (svc != null) return svc.parkAaForMap()
            // No AA service — drop any orphaned shared pipeline so 112/attach won't reuse AA.
            AaVideoBridge.onSteadyVideo = null
            AaVideoBridge.pipeline = null
            val prober = BikeLink.prober
            if (prober != null && prober.isRunning && GpxSession.active) {
                LogBus.log("[AA→MAP] no AA service — attaching GPX on live prober")
                return prober.attachOwnedGpxVideo()
            }
            return false
        }

        const val ACTION_STOP = "dev.zanderp.opencfmoto.ACTION_STOP_AA"
        const val ACTION_GPX_WAKE = "dev.zanderp.opencfmoto.ACTION_GPX_WAKE"

        fun start(ctx: Context) {
            val i = Intent(ctx, AndroidAutoService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, AndroidAutoService::class.java))
        }
    }
}
