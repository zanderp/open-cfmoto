// OpenCfMoto glue (uses AGPLv3 code ported from headunit-revived). Orchestrates the loopback
// "self-mode" Android Auto Projection receiver:
//   1. Listen on TCP 127.0.0.1:5288 (+ NSD _aawireless._tcp).
//   2. Launch Google Android Auto's WirelessStartupActivity pointed at 127.0.0.1:5288 (no VPN).
//   3. Accept the inbound socket, run the AAP version+SSL handshake, point the H.264 decoder at
//      the supplied encoder Surface, and start the message loop → AA video flows into the encoder.
package dev.zanderp.opencfmoto.aa

import android.content.Context
import android.net.ConnectivityManager
import dev.zanderp.opencfmoto.AaVideoBridge
import dev.zanderp.opencfmoto.BikeWifi
import dev.zanderp.opencfmoto.NightPrefs
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.view.Surface
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class AaReceiver(
    private val context: Context,
    private val encoderSurface: Surface,
    private val log: (String) -> Unit,
) {
    companion object {
        const val PORT = 5288

        /**
         * Android Auto's own head unit server — the port the Desktop Head Unit talks to over
         * adb forward. AA 17.4 ships WirelessStartupReceiver with android:enabled="false" and the
         * activity it forwards to is not exported, so every AaSelfMode poke is swallowed silently:
         * result=0, no log, no error. This port is the path Google left open — the rider starts
         * it once from Android Auto's developer settings and gearhead listens here.
         */
        const val HEADUNIT_SERVER_PORT = 5277

        /** Let the three AaSelfMode pokes (~3.6 s of escalation) play out before dialling out. */
        private const val FIRST_DIAL_DELAY_MS = 4_000L
        private const val DIAL_INTERVAL_MS = 2_000L
        private const val DIAL_ATTEMPTS = 20
        private const val DIAL_TIMEOUT_MS = 800
    }

    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var dialThread: Thread? = null
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null

    @Volatile private var transport: AapTransport? = null
    @Volatile private var connection: SocketAccessoryConnection? = null
    /**
     * Held between claiming the session slot and [transport] going live, so an inbound accept and
     * the head-unit-server dial-out can never both start a session.
     */
    @Volatile private var sessionStarting = false
    private val sessionLock = Any()
    @Volatile private var steadyVideoFired = false

    /**
     * Fired when an AA session ends. [userExit] is true when the rider tapped Exit in Android Auto
     * (VIDEO_FOCUS_NATIVE) — the caller should fully stop projection so the dash doesn't freeze on the
     * last frame; false means a transient drop where the server keeps listening for a reconnect.
     */
    @Volatile var onSessionEnded: ((userExit: Boolean) -> Unit)? = null
    private val videoDecoder = VideoDecoder().apply {
        fallbackWidth = ServiceDiscoveryResponse.AA_WIDTH
        fallbackHeight = ServiceDiscoveryResponse.AA_HEIGHT
        onFpsChanged = { fps ->
            log("[AA] decode fps=$fps")
            AaVideoBridge.aaDecoding = true
            if (!steadyVideoFired && fps >= 25) {
                steadyVideoFired = true
                log("[AA] steady video reached (fps=$fps) — signalling ready for bike hand-off")
                try { AaVideoBridge.onSteadyVideo?.invoke() } catch (_: Exception) {}
            }
        }
    }

    /** Ensure Conscrypt/AAP logging are wired before anything touches SSL. */
    fun start() {
        if (running) { log("[AA] already running"); return }
        running = true
        AaLog.sink = log
        ConscryptInitializer.initialize()

        // Loopback listen must not ride a dead bike Network bind (ENONET after Wi‑Fi loss).
        BikeWifi.unbindIfNoBikeNetwork(context)
        try {
            serverSocket = ServerSocket(PORT).apply { reuseAddress = true }
            log("[AA] WirelessServer listening on :$PORT")
        } catch (e: Exception) {
            val msg = e.message.orEmpty()
            if (msg.contains("ENONET", ignoreCase = true) ||
                msg.contains("Network is unreachable", ignoreCase = true)
            ) {
                log("[AA] bind :$PORT hit stale network ($msg) — clearing bind and retrying")
                BikeWifi.unbindProcess(context = context)
                try {
                    serverSocket = ServerSocket(PORT).apply { reuseAddress = true }
                    log("[AA] WirelessServer listening on :$PORT (after unbind)")
                } catch (e2: Exception) {
                    log("[AA] failed to bind :$PORT — ${e2.message}")
                    running = false
                    return
                }
            } else {
                log("[AA] failed to bind :$PORT — $msg")
                running = false
                return
            }
        }

        registerNsd()

        acceptThread = thread(name = "aa-accept", isDaemon = true) { acceptLoop() }
        // Self-mode (launching Google Android Auto) is triggered by MainActivity from the
        // foreground, via AaSelfMode.trigger(), to satisfy background-activity-launch rules.
        // Those pokes never land on AA 17.4+, so we also dial OUT to gearhead's head unit server.
        dialThread = thread(name = "aa-dial-hu", isDaemon = true) {
            try { dialHeadunitServerLoop() } catch (_: InterruptedException) {}
        }
    }

    /**
     * While this session is alive, re-evaluate the auto day/night value every minute and push it if it
     * changed — so a ride that crosses sunset flips the dash to dark on its own. Only [MapTheme.AUTO]
     * is time-driven; Day/Night are fixed and handled by the initial send + the UI toggle.
     */
    private fun startNightAutoLoop(t: AapTransport) = thread(name = "aa-night", isDaemon = true) {
        try {
            while (transport === t && running) {
                Thread.sleep(60_000)
                if (transport !== t) break
                val want = NightPrefs.isNightNow(context)
                if (want != t.nightMode) {
                    log("[AA] auto map theme → ${if (want) "night" else "day"}")
                    t.sendNightMode(want)
                }
            }
        } catch (_: InterruptedException) {
        }
    }

    fun stop() {
        running = false
        AaVideoBridge.aaSessionLive = false
        AaVideoBridge.aaDecoding = false
        AaVideoBridge.touchSink = null
        AaVideoBridge.keySink = null
        AaVideoBridge.scrollSink = null
        AaVideoBridge.previewTouchSink = null
        AaVideoBridge.nightSink = null
        try { transport?.quit() } catch (_: Exception) {}
        transport = null
        try { connection?.disconnect() } catch (_: Exception) {}
        connection = null
        try { videoDecoder.stop("AaReceiver.stop") } catch (_: Exception) {}
        unregisterNsd()
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        acceptThread?.interrupt(); acceptThread = null
        dialThread?.interrupt(); dialThread = null
        releaseSession()
        AaLog.sink = null
        log("[AA] receiver stopped")
    }

    private fun acceptLoop() {
        val ss = serverSocket ?: return
        while (running) {
            val client = try {
                ss.accept()
            } catch (e: Exception) {
                if (running) log("[AA] accept ended: ${e.message}")
                break
            }
            log("[AA] <<< Android Auto connected from ${client.inetAddress?.hostAddress}")
            if (!claimSession()) {
                log("[AA] already have a session — dropping extra connection")
                try { client.close() } catch (_: Exception) {}
                continue
            }
            thread(name = "aa-session", isDaemon = true) { handleConnection(client) }
        }
    }

    private fun handleConnection(client: Socket) {
        val conn = SocketAccessoryConnection(client)
        connection = conn
        val t = AapTransport(videoDecoder, context)
        t.onQuit = { clean ->
            val userExit = t.wasUserExit
            log("[AA] transport quit (clean=$clean, userExit=$userExit)")
            AaVideoBridge.aaSessionLive = false
            AaVideoBridge.aaDecoding = false
            AaVideoBridge.touchSink = null
            AaVideoBridge.keySink = null
            AaVideoBridge.scrollSink = null
            AaVideoBridge.previewTouchSink = null
            AaVideoBridge.nightSink = null
            try { t.microphone?.stop("transport quit") } catch (_: Exception) {}
            transport = null
            releaseSession()
            try { conn.disconnect() } catch (_: Exception) {}
            connection = null
            // Server keeps listening — AA (or the user) can reconnect. On a genuine user Exit we
            // notify the owner so it can fully stop the bike link instead of leaving the dash frozen.
            try { onSessionEnded?.invoke(userExit) } catch (_: Exception) {}
        }
        transport = t
        AaVideoBridge.aaSessionLive = true
        AaVideoBridge.aaDecoding = false
        steadyVideoFired = false

        // Bike touchscreen → Android Auto: EasyConnProber decodes dash touches (PXC cmdType 32) and
        // calls this sink with raw bike-canvas coords + a normalised action. Letterbox-map into AA
        // video space and forward over the AAP INPUT channel. Dropped if the point is in a black bar.
        val input = AaInput(t, log)
        var loggedTouchMap = false
        AaVideoBridge.touchSink = { action, pointerId, cx, cy ->
            val mapped = AaVideoBridge.pipeline?.mapBikeTouchToSource(cx, cy)
            if (mapped != null) {
                if (!loggedTouchMap || action != AaInput.ACTION_MOVE) {
                    log("[AA] touch action=$action p$pointerId bike=($cx,$cy) → AA=(${mapped.first},${mapped.second})")
                    loggedTouchMap = true
                }
                input.sendTouch(action, pointerId, mapped.first, mapped.second)
            }
        }

        // Phone/handlebar D-pad + rotary knob → Android Auto (drives a non-touch dash, and lets the
        // handlebar buttons navigate AA — see MediaButtonBridge). Cleared on transport quit / stop().
        AaVideoBridge.keySink = { keycode -> input.sendKey(keycode) }
        AaVideoBridge.scrollSink = { delta -> input.sendScroll(delta) }

        // In-app HUD preview (HudViewActivity) touches — already in AA source space, sent as-is.
        AaVideoBridge.previewTouchSink = { action, pointerId, sx, sy -> input.sendTouch(action, pointerId, sx, sy) }

        // Map day/night → Android Auto NIGHT sensor (drives Maps' dark/light theme). Seed from the
        // user's Map theme setting, let the UI push live changes, and — in Auto mode — re-evaluate on
        // a timer so the dash follows nightfall mid-ride without any interaction.
        t.nightMode = NightPrefs.isNightNow(context)
        AaVideoBridge.nightSink = { on -> t.sendNightMode(on) }
        startNightAutoLoop(t)

        // Microphone: AA requests it (MICROPHONE_REQUEST) when the Assistant starts; AaMicrophone then
        // streams the phone/helmet mic up the MIC channel so voice destination entry works hands-free.
        t.microphone = AaMicrophone(context, t, log)

        log("[AA] starting AAP handshake (version + SSL)…")
        if (!t.startHandshake(conn)) {
            log("[AA] handshake FAILED")
            transport = null
            releaseSession()
            try { conn.disconnect() } catch (_: Exception) {}
            connection = null
            return
        }
        log("[AA] handshake OK — pointing decoder at encoder surface and starting read loop")
        videoDecoder.setSurface(encoderSurface)
        t.startReading()
        log("[AA] read loop started — expecting ServiceDiscovery then video")
    }

    /** Claim the single session slot; false when one is live or another path got there first. */
    private fun claimSession(): Boolean = synchronized(sessionLock) {
        if (sessionStarting || transport != null) false else { sessionStarting = true; true }
    }

    private fun releaseSession() = synchronized(sessionLock) { sessionStarting = false }

    /**
     * AA 17.4+ fallback: connect OUT to Android Auto's head unit server instead of waiting for
     * gearhead to connect IN. The AAP roles do not change — we are still the head unit, exactly
     * as on an inbound :5288 socket — only the TCP direction flips, so [handleConnection] runs
     * unmodified. Needs the rider to have started the server from Android Auto's developer settings
     * (tap Version 10x, then the overflow menu).
     */
    private fun dialHeadunitServerLoop() {
        Thread.sleep(FIRST_DIAL_DELAY_MS)
        var attempt = 0
        while (running && attempt < DIAL_ATTEMPTS) {
            attempt++
            if (transport != null || sessionStarting) return
            val sock = try {
                connectLoopback(HEADUNIT_SERVER_PORT)
            } catch (e: Exception) {
                if (attempt == 1 || attempt % 5 == 0) {
                    log(
                        "[AA] head unit server :$HEADUNIT_SERVER_PORT no answer " +
                            "(try $attempt/$DIAL_ATTEMPTS) — ${e.javaClass.simpleName}: ${e.message}",
                    )
                }
                null
            }
            if (sock != null) {
                if (!claimSession()) {
                    try { sock.close() } catch (_: Exception) {}
                    return
                }
                log("[AA] >>> dialled OUT to Android Auto head unit server :$HEADUNIT_SERVER_PORT (AA 17.4 path)")
                thread(name = "aa-session", isDaemon = true) { handleConnection(sock) }
                return
            }
            Thread.sleep(DIAL_INTERVAL_MS)
        }
        if (running && transport == null) {
            log(
                "[AA] head unit server never answered on :$HEADUNIT_SERVER_PORT — in Android Auto " +
                    "tap Version 10x, then overflow menu → Start head unit server",
            )
        }
    }

    /**
     * Loopback connect that survives the bike Wi-Fi bind. While the process is bound to the bike
     * [android.net.Network] there is no route to 127.0.0.1 — the same ENONET [start] already
     * works around for the ServerSocket. Drop the bind for the few ms the connect takes, then
     * restore the exact same Network. Established sockets ignore the process bind, so the bike link
     * never notices.
     */
    private fun connectLoopback(port: Int): Socket {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val bound = try { cm?.boundNetworkForProcess } catch (_: Exception) { null }
        try {
            if (bound != null) try { cm?.bindProcessToNetwork(null) } catch (_: Exception) {}
            return Socket().apply { connect(InetSocketAddress("127.0.0.1", port), DIAL_TIMEOUT_MS) }
        } finally {
            if (bound != null) try { cm?.bindProcessToNetwork(bound) } catch (_: Exception) {}
        }
    }

    private fun registerNsd() {
        try {
            nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
            if (nsdManager == null) { log("[AA] NSD unavailable"); return }
            val serviceInfo = NsdServiceInfo().apply {
                serviceName = "AAWireless"
                serviceType = "_aawireless._tcp"
                port = PORT
            }
            registrationListener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) = log("[AA] NSD registered: ${info.serviceName}")
                override fun onRegistrationFailed(info: NsdServiceInfo, err: Int) = log("[AA] NSD reg fail: $err")
                override fun onServiceUnregistered(info: NsdServiceInfo) = log("[AA] NSD unregistered")
                override fun onUnregistrationFailed(info: NsdServiceInfo, err: Int) = log("[AA] NSD unreg fail: $err")
            }
            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            log("[AA] NSD register error: ${e.message}")
        }
    }

    private fun unregisterNsd() {
        try { registrationListener?.let { nsdManager?.unregisterService(it) } } catch (_: Exception) {}
        registrationListener = null
    }
}
