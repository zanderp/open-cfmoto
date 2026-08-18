// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.Network
import android.net.wifi.WifiManager
import java.io.BufferedInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * EasyConn / Carbit PXC client for CFMoto MotoPlay.
 *
 * Topology (verified in cfmoto-tcp-v5.log): the PHONE is the SERVER.
 *  1. Discover EasyConn via NSD `_EasyConn._tcp.`, else wake `:10930`, else nearby port scan.
 *  2. Open TCP servers on 10920, 10921, 10922 bound to our bike-network IP.
 *  3. Send ECP_PXC_MDNS_RESPOND (cmd 0x70000010, JSON) to the discovered endpoint;
 *     bike replies {"status":true} and we close that socket.
 *  4. The bike then connects BACK to our listening ports and drives the PXC handshake
 *     (channel selects, CLIENT_INFO, SN check, heartbeats) — handled by [PxcHandshake].
 */
class EasyConnProber(
    private val context: Context,
    private val log: (String) -> Unit,
) {
    companion object {
        const val PORT_MEDIA_DATA = 10920   // MediaProjectService data
        const val PORT_MEDIA_CTRL = 10921   // MediaProjectService ctrl
        const val PORT_PXC_CTRL   = 10922   // PXCService ctrl (channel selects + CLIENT_INFO)
        const val BIKE_PROBE_PORT = 10930   // bike's EasyConn mDNS/probe endpoint
        const val SPOOFED_PACKAGE = "com.cfmoto.cfmotointernational"
        private val LISTEN_PORTS = intArrayOf(PORT_PXC_CTRL, PORT_MEDIA_CTRL, PORT_MEDIA_DATA)
        /** How many times to auto re-probe after a link drop before giving up (user taps Connect). */
        /** Cap before Phase.ERROR; watchdog [rearmFromError] resets this so a bike back in range recovers. */
        private const val MAX_RECONNECT_ATTEMPTS = 20
        /** Proactive PXC heartbeat interval on each :10922 channel socket (CAR_CTRL + CAR_DATA). */
        private const val PXC_HEARTBEAT_INTERVAL_MS = 2000L

        // Ghost-touch filtering for the 800NK digitizer — one finger can appear as two contacts
        // during a press or drag. Without filtering, AA reads the extras as stray taps / pinch.
        /** A contact within this many px of another pointer is the same finger ghosting. */
        private const val GHOST_MERGE_PX = 48
        /** A finger whose UP frame was lost is dropped after this long with no update. */
        private const val POINTER_STALE_MS = 300L
        /** AA only needs two fingers (pinch); extra contacts are noise. */
        private const val MAX_POINTERS = 2

        // Contact re-acquisition — the digitizer drops a finger mid-drag and picks it back up
        // within ~0–14 ms and under 150 px. Committing that UP ends the gesture as a string of taps.
        /** Hold an UP this long to see if the same finger comes back. */
        private const val STITCH_MS = 80L
        /** Re-acquired contact must land near where it left. */
        private const val STITCH_PX = 150

        /** Two contacts this close are the same finger reported twice (real pinch fingers sit further apart). */
        internal fun near(ax: Int, ay: Int, bx: Int, by: Int, tol: Int = GHOST_MERGE_PX): Boolean =
            kotlin.math.abs(ax - bx) <= tol && kotlin.math.abs(ay - by) <= tol
    }

    private val handshake = PxcHandshake(log).also {
        // Heartbeat BOTH :10922 channel sockets — CAR_DATA carries 0x104a0/CHECK_SN and was
        // previously left idle, which is what triggered the ~7s 800NK flap.
        it.onPxcChannelSelected = { sock, label -> startCtrlHeartbeat(sock, label) }
    }
    private val servers = ArrayList<ServerSocket>()
    private var multicastLock: WifiManager.MulticastLock? = null
    private var heartbeatThread: Thread? = null
    /** One heartbeat thread per live :10922 channel socket (CAR_CTRL + CAR_DATA). */
    private val ctrlHeartbeatThreads =
        java.util.Collections.synchronizedList(ArrayList<Thread>())
    @Volatile private var running = false
    @Volatile private var probed = false
    @Volatile private var video: VideoPipeline? = null
    @Volatile private var ownsVideo = false
    @Volatile private var negW = 800
    @Volatile private var negH = 384
    @Volatile private var framesSent = 0
    @Volatile private var lastFrameAt = 0L
    /** SoftAP Yunmo path (MOTOMORINI / X-Cape 1200) when EasyConn never answers. */
    private var yunmo: YunmoLink? = null

    /** Live dash contacts: id → (x, y, lastSeenMs). Stale entries are evicted when UP is lost. */
    private val pointers = LinkedHashMap<Int, Triple<Int, Int, Long>>()
    private var ghostsDropped = 0
    private var stitches = 0

    /** An UP held back briefly (see [STITCH_MS]) and the timer that commits it. */
    private var pendingUpId = -1
    private var pendingUpX = 0
    private var pendingUpY = 0
    private var pendingUpAt = 0L
    private var pendingUpTask: java.util.concurrent.ScheduledFuture<*>? = null
    private var stitchExec: java.util.concurrent.ScheduledExecutorService? = null

    /** Dash pointer id → the id AA already knows, when a dropped contact came back as a new id. */
    private val aaIdOf = HashMap<Int, Int>()

    // Live client sockets the bike has opened back to us, so the watchdog ([AndroidAutoService]) can
    // force a clean reconnect by dropping them (which trips the existing onAllConnectionsClosed path).
    private val activeClients = java.util.Collections.synchronizedList(ArrayList<Socket>())

    // Auto-reconnect: the phone keeps listening on all three ports for the whole session, so if the
    // dash drops the link while Wi-Fi is still up we just re-send the mDNS probe to invite it back —
    // no user Stop/Start. Retained connection params + a live-connection counter drive this.
    @Volatile private var myIp: Inet4Address? = null
    @Volatile private var bikeIp: Inet4Address? = null
    @Volatile private var network: Network? = null
    private val liveConns = AtomicInteger(0)
    @Volatile private var everConnected = false
    @Volatile private var reprobing = false
    @Volatile private var reconnectAttempts = 0

    /**
     * @param gatewayOverride when set (Wi‑Fi Direct P2P path), use this bike GO IP instead of
     *   resolving from [Network] link properties — P2P often has no default route.
     * @param bindIpOverride phone's P2P interface address when there is no [Network] to bind.
     */
    fun start(
        network: Network?,
        gatewayOverride: java.net.Inet4Address? = null,
        bindIpOverride: java.net.Inet4Address? = null,
    ) {
        if (running) {
            // Mode switches (AA↔Map↔Mirror) must never no-op on a half-dead session
            // (logs: "already running" + framesSent=0). Always rebuild PXC cleanly.
            log("already running — forcing PXC restart for clean mode switch")
            stop()
        }
        probed = false
        framesSent = 0
        lastFrameAt = 0L
        everConnected = false
        reconnectAttempts = 0
        liveConns.set(0)
        this.network = network
        dumpEnvironment(network)

        val myIp = bindIpOverride ?: pickBikeInterfaceIp(network)
        if (myIp == null) { log("could not resolve our IPv4 on the bike network; aborting"); return }
        val bikeIp = gatewayOverride ?: resolveGateway(network)
        if (bikeIp == null) {
            log(
                "could not resolve bike gateway IP; aborting" +
                    (if (network != null) " — if Wi‑Fi is up, open MotoPlay / show the pairing QR on the dash"
                    else ""),
            )
            return
        }
        this.myIp = myIp
        this.bikeIp = bikeIp
        log("our IP=${myIp.hostAddress}  bike IP=${bikeIp.hostAddress}")

        running = true
        acquireMulticastLock()

        // VPNs (PCAPdroid, AdGuard, work VPN, …) steal the default route after Wi-Fi join.
        // Re-pin the process and warn early; probe sockets also use Network.socketFactory.
        BikeWifi.rebindProcessToBike(context)
        val vpnSummary = BikeWifi.vpnNetworksSummary(context)
        if (vpnSummary.isNotEmpty()) {
            log("!! VPN interface(s) present: $vpnSummary — probes use Network.socketFactory to stay on bike Wi‑Fi")
        }
        if (BikeWifi.isVpnActive(context)) {
            log("!! active internet VPN — if probes time out: turn VPN off, or disable " +
                "'Block connections without VPN' / enable LAN bypass")
            val bindErr = BikeWifi.testBikeSocketBind(context)
            if (BikeWifi.isVpnBindBlocked(context, bindErr)) {
                log("!! VPN kill-switch is blocking bike Wi-Fi (EPERM on Network.bindSocket). " +
                    "Disable Always-on VPN → 'Block connections without VPN', allow LAN, or turn the VPN off.")
                ConnectionState.set(Phase.ERROR, "VPN kill-switch blocking bike Wi‑Fi")
                stop()
                return
            }
        }

        // 1. Listen on all three ports BEFORE probing, so we're ready for the bike's call-back.
        //    SO_REUSEADDR (set before bind) lets us re-listen immediately after a Stop→Connect: the
        //    bike's just-closed call-back sockets leave these ports in TIME_WAIT for up to a couple
        //    minutes, and without reuse the rebind fails with EADDRINUSE — so no media servers open,
        //    the bike has nowhere to connect back to, and the dash shows an empty screen (no frames).
        var bindConflict = false
        for (port in LISTEN_PORTS) {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(myIp, port), 50)
                servers.add(ss)
                spawnAccept(port, ss)
            } catch (e: Exception) {
                bindConflict = true
                log("bind :$port failed: ${e.message}")
            }
        }
        log("listening on ${myIp.hostAddress} ports ${LISTEN_PORTS.toList()} (${servers.count { !it.isClosed }} open)")

        // If a port is taken, the bike's mirroring link ports are held by another EasyConnect client —
        // almost always the official CFMoto app running in the background (it binds the same 10920-10922
        // and the bike connects back to IT, not us). Probing anyway is pointless: no media server means
        // no frames and a blank dash. Fail fast with an actionable message instead of failing silently.
        if (bindConflict) {
            log("!! link ports are held by another app (usually the official CFMoto/EasyConnect app). " +
                "Close it (force-stop) and reconnect — OpenCfMoto needs ports ${LISTEN_PORTS.toList()}.")
            ConnectionState.set(Phase.ERROR, "close the official CFMoto app, then reconnect")
            stop()
            return
        }

        // 2. Discover EasyConn (NSD → :10930 wake → nearby port scan), then MDNS_RESPOND.
        thread(name = "ec-probe", isDaemon = true) {
            discoverAndProbe(bikeIp, myIp, network)
        }
        startHeartbeatLog()
        stitchExec = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "ec-touch-stitch").apply { isDaemon = true }
        }
    }

    /**
     * Drop the current video source without closing PXC. Used when parking AA so 114 doesn't keep
     * polling a stopped shared pipeline (that looked like a stall and triggered forceReconnect).
     */
    fun detachVideoSource() {
        if (ownsVideo) {
            try { video?.stop(abandonNavigation = false) } catch (_: Exception) {}
        }
        video = null
        ownsVideo = false
        lastFrameAt = System.currentTimeMillis()
        log("[MAP] detached video source (PXC kept)")
    }

    /**
     * Soft-switch the live bike video source to an owned Map / GPX Presentation without tearing
     * down PXC sockets or rejoining Wi‑Fi. Used when the rider starts NAV_TO / free ride while AA
     * (or mirror) was already projecting.
     *
     * @return true if a GPX pipeline is attached; false if the prober isn't running so the caller
     *   should fall back to [joinWifi].
     */
    fun attachOwnedGpxVideo(): Boolean {
        if (!running) {
            log("[MAP] attachOwnedGpxVideo: prober not running")
            return false
        }
        if (!GpxSession.active) {
            log("[MAP] attachOwnedGpxVideo: no map session")
            return false
        }
        detachVideoSource()
        val w = negW.coerceAtLeast(16)
        val h = negH.coerceAtLeast(16)
        log("[MAP] attaching owned GPX video ${w}x${h} (PXC kept)")
        val vp = VideoPipeline(context, w, h, log)
        vp.start()
        if (!vp.isAlive) {
            log("[MAP] GPX VideoPipeline failed to start — retry once")
            try { vp.stop(abandonNavigation = false) } catch (_: Exception) {}
            val retry = VideoPipeline(context, w, h, log)
            retry.start()
            if (!retry.isAlive) {
                log("[MAP] GPX VideoPipeline failed to start")
                try { retry.stop(abandonNavigation = false) } catch (_: Exception) {}
                return false
            }
            video = retry
        } else {
            video = vp
        }
        val live = video!!
        ownsVideo = true
        // Watchdog grace + keyframe for the bike's next pull.
        lastFrameAt = System.currentTimeMillis()
        framesSent = 0
        live.onBikeDataStart()
        // Second IDR after the Presentation has painted.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (video === live && live.isAlive) live.onBikeDataStart()
        }, 200)
        ConnectionState.set(Phase.STREAMING)
        return true
    }

    fun stop() {
        running = false
        probed = false
        everConnected = false
        reprobing = false
        try { yunmo?.stop() } catch (_: Exception) {}
        yunmo = null
        // Only stop the pipeline if we created it; the shared Android Auto pipeline is owned
        // by AndroidAutoService and must outlive a bike disconnect.
        if (ownsVideo) video?.stop()
        video = null; ownsVideo = false
        heartbeatThread?.interrupt(); heartbeatThread = null
        synchronized(pointers) { cancelPendingUp(); pointers.clear(); aaIdOf.clear() }
        stitchExec?.shutdownNow(); stitchExec = null
        synchronized(ctrlHeartbeatThreads) {
            for (t in ctrlHeartbeatThreads) t.interrupt()
            ctrlHeartbeatThreads.clear()
        }
        synchronized(activeClients) {
            for (s in activeClients.toList()) try { s.close() } catch (_: Exception) {}
            activeClients.clear()
        }
        for (s in servers) try { s.close() } catch (_: IOException) {}
        servers.clear()
        multicastLock?.let { try { if (it.isHeld) it.release() } catch (_: Exception) {} }
        multicastLock = null
        log("stopped")
    }

    // ---- Watchdog surface (read/driven by AndroidAutoService's auto-recovery loop) ----

    /** True while the prober is live (started, not stopped). */
    val isRunning: Boolean get() = running

    /** True once at least one frame has been delivered to the dash this session. */
    val isStreaming: Boolean
        get() = running && (framesSent > 0 || (yunmo?.framesSent ?: 0) > 0)

    /** Milliseconds since the last frame was sent to the dash (Long.MAX_VALUE if none yet). */
    fun msSinceLastFrame(): Long {
        val yAt = yunmo?.lastFrameAt ?: 0L
        val at = maxOf(lastFrameAt, yAt)
        return if (at == 0L) Long.MAX_VALUE else System.currentTimeMillis() - at
    }

    /**
     * Force a clean reconnect: drop every live bike socket. Each read loop then ends and, once the
     * last one closes, [onAllConnectionsClosed] re-probes — reusing the proven reconnect path. Used
     * by the watchdog when frames stall while the sockets are (half-)open and won't close on their own.
     */
    fun forceReconnect() {
        val n = activeClients.size
        val hadYunmo = yunmo != null
        log(
            "[watchdog] forcing reconnect — dropping $n live socket(s)" +
                if (hadYunmo) " + Yunmo" else "",
        )
        synchronized(activeClients) {
            for (s in activeClients.toList()) try { s.close() } catch (_: Exception) {}
        }
        // Yunmo SoftAP has no reverse-TCP clients in [activeClients]. Closing EasyConn
        // sockets alone left X-Cape 1200 half-dead (kosalos: "dropping 0", yunmoFrames stuck).
        if (hadYunmo) {
            try { yunmo?.stop() } catch (_: Exception) {}
            yunmo = null
            probed = false
            if (n == 0) onAllConnectionsClosed()
        }
    }

    /**
     * Re-arm reconnection after the retry budget was exhausted ([Phase.ERROR]). Resets the attempt
     * counter and kicks a fresh probe so a bike that comes back into range links up on its own.
     */
    fun rearmFromError() {
        if (!running || liveConns.get() > 0) return
        log("[watchdog] re-arming reconnect after error")
        reconnectAttempts = 0
        onAllConnectionsClosed()
    }

    /**
     * Find where EasyConn is listening, then send MDNS_RESPOND so the bike dials back.
     * Order: NSD `_EasyConn._tcp.` → classic `:10930` on gateway → TCP scan 10915–10935.
     */
    private fun discoverAndProbe(bikeIp: Inet4Address, myIp: Inet4Address, network: Network?) {
        BikeWifi.rebindProcessToBike(context)

        val nsd = EasyConnDiscovery.discoverNsd(context, log)
        if (!running) return
        if (nsd != null) {
            probeTarget(nsd.host, nsd.port, myIp, network, attempts = 3)
            if (probed) return
            // SoftAP: wake sometimes only re-arms mDNS — try NSD once more before falling through.
            log("[DISC] NSD endpoint did not accept probe — re-discovering…")
            BikeWifi.rebindProcessToBike(context)
            val nsd2 = EasyConnDiscovery.discoverNsd(context, log)
            if (!running) return
            if (nsd2 != null) {
                probeTarget(nsd2.host, nsd2.port, myIp, network, attempts = 3)
                if (probed) return
            }
        }

        // Classic wake port on the SoftAP / P2P gateway.
        probeTarget(bikeIp, BIKE_PROBE_PORT, myIp, network, attempts = 5)
        if (probed || !running) return

        // Peer alive but :10930 closed (common on Morini SoftAP) — scan nearby ports.
        val open = EasyConnDiscovery.scanOpenPorts(
            bikeIp, network, myIp, ::openOnBikeNetwork, log,
        )
        if (!running) return
        val ordered = open.sortedBy { if (it == BIKE_PROBE_PORT) 0 else 1 }
        for (port in ordered) {
            if (!running || probed) break
            probeTarget(bikeIp, port, myIp, network, attempts = 2)
        }
        if (probed || !running) return

        if (!everConnected) {
            // X-Cape 1200 / MOTOMORINI SoftAP speaks Yunmo on :8200, not EasyConn :10930.
            if (tryYunmoFallback(bikeIp, network)) return

            // Kove / Thinkerride SoftAP: neither EasyConn nor Yunmo — log open ports for RE.
            if (running) {
                EasyConnDiscovery.scanThinkerridePorts(
                    bikeIp, network, myIp, ::openOnBikeNetwork, log,
                )
            }

            val bindErr = BikeWifi.testBikeSocketBind(context)
            if (BikeWifi.isVpnBindBlocked(context, bindErr)) {
                log("!! VPN kill-switch blocked bike bind after probe failure: ${bindErr?.message}")
                ConnectionState.set(Phase.ERROR, "VPN kill-switch blocking bike Wi‑Fi")
            } else if (BikeWifi.isVpnActive(context)) {
                log("!! probe never reached the bike; an internet VPN is also active " +
                    "(${BikeWifi.vpnNetworksSummary(context)}). If this keeps happening, turn the VPN " +
                    "off or allow LAN — not treating as hard VPN error (bind still works).")
            } else {
                log(
                    "!! SoftAP is up but EasyConn never answered (NSD empty, :$BIKE_PROBE_PORT " +
                        "refused, nearby ports quiet) and Yunmo :${YunmoFrame.DEFAULT_PORT} also failed. " +
                        "Keep Nav QR open on the dash. If this is Kove/Thinkerride, check " +
                        "[DISC] thinkerride-scan in Share Logs.",
                )
                ConnectionState.set(
                    Phase.ERROR,
                    context.getString(R.string.conn_detail_easyconn_offline),
                )
            }
        }
    }

    /** Try MOTOMORINI Yunmo TCP after EasyConn discovery fails. */
    private fun tryYunmoFallback(bikeIp: Inet4Address, network: Network?): Boolean {
        if (!running) return false
        log("[YUNMO] EasyConn quiet — trying SoftAP Yunmo ${bikeIp.hostAddress}:${YunmoFrame.DEFAULT_PORT}")
        val link = YunmoLink(context, log)
        link.onFrameSent = { lastFrameAt = System.currentTimeMillis() }
        // Prefer AA coded size when known (kosalos log: 800×480); else negotiated / default.
        val aa = BikeProfileHolder.aaVideo
        val w = when {
            aa.width >= 64 -> aa.width
            negW >= 64 -> negW
            else -> 800
        }
        val h = when {
            aa.height >= 64 -> aa.height
            negH >= 64 -> negH
            else -> 480
        }
        val ok = link.connectAndStream(bikeIp, network, w, h)
        if (!ok) {
            link.stop()
            return false
        }
        yunmo = link
        probed = true
        everConnected = true
        // Seed lastFrameAt so stall watchdog doesn't fire on Long.MAX_VALUE before frame #1.
        lastFrameAt = System.currentTimeMillis()
        val sw = link.streamWidth.takeIf { it >= 16 } ?: w
        val sh = link.streamHeight.takeIf { it >= 16 } ?: h
        log("[YUNMO] *** linked — streaming AA/H264 over Yunmo (${sw}x${sh}) ***")
        return true
    }

    /** Phone→bike probe. cmd 0x70000010 + JSON; expect 0x70000011 {"status":true}. */
    private fun probeTarget(
        host: Inet4Address,
        port: Int,
        myIp: Inet4Address,
        network: Network?,
        attempts: Int,
    ) {
        var attempt = 0
        while (running && attempt < attempts && !probed) {
            attempt++
            try {
                BikeWifi.rebindProcessToBike(context)
                log("[PROBE] connect #$attempt -> ${host.hostAddress}:$port")
                // CRITICAL: do NOT Socket.bind(local) before Network.bindSocket — Android requires
                // the socket unbound. Prefer the network's SocketFactory (bypasses VPN).
                val sock = openOnBikeNetwork(network, myIp)
                sock.connect(InetSocketAddress(host, port), 3000)
                sock.soTimeout = 5000

                val json = JSONProbe()
                log("[PROBE] -> MDNS_RESPOND (0x70000010) $json")
                PxcFrame(PxcFrame.CMD_MDNS_RESPOND, json.toByteArray(Charsets.UTF_8))
                    .write(sock.getOutputStream())

                val resp = PxcFrame.read(sock.getInputStream())
                if (resp == null) {
                    log("[PROBE] bike closed before responding")
                } else {
                    val body = String(resp.payload, Charsets.UTF_8)
                    log("[PROBE] <- cmd=0x${resp.cmd.toUInt().toString(16)} $body")
                    if (resp.cmd == PxcFrame.CMD_MDNS_RESPOND_ACK && body.contains("true")) {
                        log("[PROBE] *** accepted — bike should now connect back to our ports ***")
                        probed = true
                    } else {
                        log("[PROBE] !! not accepted: $body")
                    }
                }
                try { sock.close() } catch (_: IOException) {}
                if (probed) return
            } catch (e: VpnBypassBlockedException) {
                log("!! ${e.message}")
                if (everConnected) {
                    log("!! ignoring VPN bind blip — bike already linked this session; will retry")
                } else {
                    ConnectionState.set(Phase.ERROR, "VPN kill-switch blocking bike Wi‑Fi")
                    return
                }
            } catch (e: Exception) {
                log("[PROBE] failed: ${e.javaClass.simpleName}: ${e.message}")
            }
            try { Thread.sleep(750L * attempt) } catch (_: InterruptedException) { return }
        }
    }

    /** Thrown when Always-on VPN lockdown refuses [Network.bindSocket] (EPERM). */
    private class VpnBypassBlockedException(message: String) : IOException(message)

    /**
     * Socket pinned to the bike [Network] so traffic bypasses any VPN tunnel.
     * Falls back to process-bound / local bike IPv4 if no Network handle is available.
     * Throws [VpnBypassBlockedException] on EPERM (VPN kill-switch) — do not retry.
     */
    private fun openOnBikeNetwork(network: Network?, myIp: Inet4Address): Socket {
        if (network != null) {
            try {
                val s = network.socketFactory.createSocket()
                log("[PROBE] socket via Network.socketFactory (VPN bypass)")
                return s
            } catch (e: Exception) {
                if (BikeWifi.isVpnBindBlocked(context, e)) {
                    throw VpnBypassBlockedException(
                        "VPN kill-switch blocked Network.socketFactory (EPERM). " +
                            "Disable Always-on VPN → 'Block connections without VPN', allow LAN, or turn VPN off."
                    )
                }
                log("[PROBE] socketFactory failed (${e.javaClass.simpleName}: ${e.message}) — trying bindSocket")
            }
            try {
                val s = Socket()
                network.bindSocket(s)
                log("[PROBE] socket via Network.bindSocket (VPN bypass)")
                return s
            } catch (e: Exception) {
                if (BikeWifi.isVpnBindBlocked(context, e)) {
                    throw VpnBypassBlockedException(
                        "VPN kill-switch blocked Network.bindSocket (EPERM). " +
                            "Disable Always-on VPN → 'Block connections without VPN', allow LAN, or turn VPN off."
                    )
                }
                log("[PROBE] bindSocket FAILED (${e.javaClass.simpleName}: ${e.message}) — " +
                    "falling back to a socket source-bound to the bike interface")
            }
            // Network.bindSocket refused (Wi‑Fi Direct groups expose no bindable Network; a VPN
            // kill-switch was already ruled out above). Do NOT fall back to an UNBOUND Socket(): with
            // home Wi‑Fi still up it holds the phone's DEFAULT route, so the probe to the on-link
            // 192.168.49.x bike gateway egresses the WRONG interface and burns its full 3s connect
            // timeout before failing over (logcat: from /192.168.1.x → /192.168.49.1 after 3000ms).
            // Fall through to the source-bound socket below so traffic routes onto the bike interface
            // by its source IP (the bike's /24 is on-link there) — the same path the no-Network
            // branch uses, restoring fast first-connect over Wi‑Fi Direct with home Wi‑Fi present.
        }
        // Source-bind to our bike-interface IP so the probe egresses that interface even when there
        // is no bindable Network and home Wi‑Fi holds the default route (P2P + bind-refused paths).
        val s = Socket()
        try {
            s.bind(InetSocketAddress(myIp, 0))
            log("[PROBE] socket source-bound to ${myIp.hostAddress} (routes onto the bike interface)")
        } catch (e: Exception) {
            log("[PROBE] source-bind to ${myIp.hostAddress} failed " +
                "(${e.javaClass.simpleName}: ${e.message}) — unbound socket")
        }
        return s
    }

    private fun JSONProbe(): String =
        "{\"phoneType\":\"Android\",\"packageName\":\"$SPOOFED_PACKAGE\"}"

    private fun spawnAccept(port: Int, server: ServerSocket) =
        thread(name = "ec-accept-$port", isDaemon = true) {
            while (running) {
                val client = try { server.accept() } catch (e: IOException) {
                    if (running) log("[:$port] accept ended: ${e.message}"); break
                }
                log("[:$port] <<< bike connected from ${client.remoteSocketAddress}")
                everConnected = true
                reconnectAttempts = 0            // a fresh connection resets the retry budget
                liveConns.incrementAndGet()
                activeClients.add(client)
                thread(name = "ec-conn-$port", isDaemon = true) {
                    try { readLoop(port, client) }
                    finally {
                        activeClients.remove(client)
                        if (liveConns.decrementAndGet() == 0) onAllConnectionsClosed()
                    }
                }
            }
        }

    /**
     * All bike sockets have closed. If we're still running and the link had connected at least once,
     * the dash likely dropped the session (a UI transition, brief Wi-Fi blip, etc.) while the phone
     * kept listening. Re-send the mDNS probe to invite it straight back — no user Stop/Start — with a
     * capped, backed-off retry so a genuinely-gone bike doesn't spin forever.
     */
    private fun onAllConnectionsClosed() {
        if (!running || !everConnected || reprobing) return
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            log("[reconnect] gave up after $reconnectAttempts attempts — tap Connect to retry")
            ConnectionState.set(Phase.ERROR, "lost bike link")
            return
        }
        reprobing = true
        ConnectionState.set(Phase.RECONNECTING, "attempt ${reconnectAttempts + 1}")
        thread(name = "ec-reprobe", isDaemon = true) {
            try {
                while (running && liveConns.get() == 0 && reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
                    reconnectAttempts++
                    // Faster early retries (1s, 1.5s, 2s…) then cap — don't burn the budget on long waits.
                    val backoff = minOf(500L + 500L * reconnectAttempts, 4_000L)
                    log("[reconnect] link lost — re-probing (attempt $reconnectAttempts/$MAX_RECONNECT_ATTEMPTS) in ${backoff}ms")
                    try { Thread.sleep(backoff) } catch (_: InterruptedException) { return@thread }
                    if (!running || liveConns.get() > 0) return@thread
                    val bi = bikeIp; val mi = myIp
                    if (bi == null || mi == null) { log("[reconnect] no cached IPs — abort"); return@thread }
                    probed = false
                    framesSent = 0   // so the first frame after reconnect re-signals STREAMING
                    try { yunmo?.stop() } catch (_: Exception) {}
                    yunmo = null
                    // Fast path for reconnect — classic wake → NSD → Yunmo SoftAP :8200.
                    probeTarget(bi, BIKE_PROBE_PORT, mi, network, attempts = 2)
                    if (!probed && running && liveConns.get() == 0) {
                        val ep = EasyConnDiscovery.discoverNsd(context, log)
                        if (ep != null) probeTarget(ep.host, ep.port, mi, network, attempts = 2)
                    }
                    if (!probed && running && liveConns.get() == 0) {
                        tryYunmoFallback(bi, network)
                    }
                    // Give the dash a moment to connect back before deciding to try again.
                    try { Thread.sleep(2500) } catch (_: InterruptedException) { return@thread }
                }
            } finally {
                reprobing = false
            }
        }
    }

    private fun readLoop(port: Int, socket: Socket) {
        val tag = ":$port"
        socket.soTimeout = 0
        socket.tcpNoDelay = true
        try {
            val input = BufferedInputStream(socket.getInputStream())
            // Framing is by port (consistent across every run):
            //   10922 = PXC control (16-byte CmdBaseHead); 10921/10920 = media (8-byte ReqBase).
            if (port == PORT_PXC_CTRL) {
                log("[$tag] framing=CmdBaseHead (PXC control)")
                while (running) {
                    val frame = try { PxcFrame.read(input) } catch (e: Exception) {
                        log("[$tag] frame error: ${e.message}"); return
                    } ?: run { log("[$tag] bike closed"); return }
                    try { handshake.handle(tag, frame, socket) }
                    catch (e: Exception) { log("[$tag] handler error: $e") }
                }
            } else {
                log("[$tag] framing=ReqBase (media plane) profile=${handshake.profile.name}")
                mediaLoop(tag, input, socket.getOutputStream())
            }
        } catch (e: IOException) {
            log("[$tag] read error: ${e.message}")
        } finally {
            try { socket.close() } catch (_: IOException) {}
        }
    }

    // ---- Media plane: Protocol.ReqBase framing (8-byte LE header + body) ----
    // header: cmdType(s16) | cmdLen(s16) | token(i32); reply uses the same header.
    private fun mediaLoop(tag: String, input: java.io.InputStream, out: OutputStream) {
        val header = ByteArray(8)
        while (running) {
            if (!PxcFrame.readFully(input, header, 8)) { log("[$tag] media closed"); return }
            val b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val cmdType = b.getShort(0).toInt()
            val cmdLen = b.getShort(2).toInt() and 0xFFFF
            val token = b.getInt(4)
            val body = ByteArray(cmdLen)
            if (cmdLen > 0 && !PxcFrame.readFully(input, body, cmdLen)) { log("[$tag] media body short"); return }
            handleMediaReq(tag, cmdType, token, body, out)
        }
    }

    /** Frame reply on the data socket is written RAW (not ReqBase): [size i32 LE][access unit].
     *  Inferred from the partial-decompiled MediaProjectServerDataExecuteThread.reply*Data(). */
    private fun sendFrameRaw(out: OutputStream, frame: ByteArray) {
        val sz = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0, frame.size).array()
        synchronized(out) {
            out.write(sz)
            out.write(frame)
            out.flush()
        }
    }

    private fun sendReqBase(out: OutputStream, cmdType: Int, body: ByteArray?) {
        val len = body?.size ?: 0
        val h = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        h.putShort(0, cmdType.toShort())
        h.putShort(2, len.toShort())
        h.putInt(4, 0)
        synchronized(out) {
            out.write(h.array())
            if (body != null && body.isNotEmpty()) out.write(body)
            out.flush()
        }
    }

    private fun handleMediaReq(tag: String, cmdType: Int, token: Int, body: ByteArray, out: OutputStream) {
        when (cmdType) {
            16 -> { // REQ_RV_CONFIG_CAPTURE
                val cfg = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
                val w = if (body.size >= 2) cfg.getShort(0).toInt() and 0xFFFF else 0
                val h = if (body.size >= 4) cfg.getShort(2).toInt() and 0xFFFF else 0
                val fps = if (body.size >= 8) cfg.getInt(4) else 0
                val wantEncoder = if (body.size >= 12) cfg.getInt(8) else 2
                val supportExtend = if (body.size >= 30) body[29] else 0
                log("[$tag] REQ_CONFIG_CAPTURE w=$w h=$h fps=$fps wantEncoder=$wantEncoder ext=$supportExtend len=${body.size}")
                // Learn this dash's shape so an unknown bike auto-picks the right AA orientation next time.
                if (w > 0 && h > 0) {
                    val ssid = BikeMemory.lastQr(context)?.ssid
                    DashMemory.observe(context, ssid, w, h)
                    val dashPortrait = h > w
                    val aaPortrait = BikeProfileHolder.aaVideo.height > BikeProfileHolder.aaVideo.width
                    if (dashPortrait != aaPortrait) {
                        log("[$tag] dash canvas is ${if (dashPortrait) "portrait" else "landscape"} (${w}x$h) " +
                            "but AA is ${if (aaPortrait) "portrait" else "landscape"} — reconnect once to auto-apply the right orientation")
                    }
                }
                val (rw, rh) = handshake.profile.roundCaptureDimensions(w, h)
                negW = rw
                negH = rh
                // If Android Auto is the source (shared pipeline), size its encoder + letterbox
                // compositor to this bike canvas now — before the bike starts pulling frames (112/114).
                AaVideoBridge.pipeline?.configureBikeCanvas(negW, negH)
                // RLY_RV_CONFIG_CAPTURE (17): encoder(i32) | width&~15(s16) | height&~15(s16) | ext(byte)
                val rly = ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN)
                rly.putInt(0, if (wantEncoder == 0) 2 else wantEncoder)
                rly.putShort(4, negW.toShort())
                rly.putShort(6, negH.toShort())
                rly.put(8, supportExtend)
                log("[$tag] → RLY_CONFIG_CAPTURE(17) encoder=${if (wantEncoder==0) 2 else wantEncoder} w=$negW h=$negH")
                sendReqBase(out, 17, rly.array())
            }
            48 -> { // REQ_GET_VERSION → 49 (two LE ints: version, subVersion=1) per ctrl thread
                log("[$tag] REQ_GET_VERSION → RLY 49")
                val v = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                v.putInt(0, 3); v.putInt(4, 1)
                sendReqBase(out, 49, v.array())
            }
            64 -> { // REQ_HEARTBEAT → 65
                sendReqBase(out, 65, null)
            }
            96 -> { // REQ_CONFIGCAPTUREREXTEND → 97 (JSON). Send state OK.
                log("[$tag] REQ_CONFIGCAPTUREREXTEND len=${body.size} ${String(body, Charsets.UTF_8).take(120)} → RLY 97")
                sendReqBase(out, 97, "{\"state\":0}".toByteArray(Charsets.UTF_8))
            }
            128 -> { // REQ_START_H264 → 129 (then bike expects frames on data socket)
                log("[$tag] *** REQ_START_H264 *** len=${body.size} → RLY 129 (no encoder yet — video stage TODO)")
                sendReqBase(out, 129, null)
            }
            112 -> { // REQ_RV_DATA_START → start encoder, then RLY_RV_DATA_START(113)
                if (video == null) {
                    val shared = AaVideoBridge.pipeline
                    if (shared != null) {
                        // Android Auto is running: pull encoded frames from its (already started)
                        // pipeline instead of creating our own Presentation/mirror source.
                        video = shared
                        ownsVideo = false
                        log("[$tag] REQ_RV_DATA_START(112): using shared Android Auto video pipeline")
                    } else {
                        log("[$tag] REQ_RV_DATA_START(112): starting video ${negW}x${negH}")
                        video = VideoPipeline(context, negW, negH, log).also { it.start() }
                        ownsVideo = true
                    }
                }
                // Ensure the first frame the bike pulls is a keyframe (SPS+PPS+IDR). Critical for the
                // Android Auto path, whose encoder has been running since REQ_CONFIG_CAPTURE — its
                // initial IDR is already gone from the queue, so without this the dash starts mid-GOP
                // on a P-frame and stays black. See VideoPipeline.onBikeDataStart().
                video?.onBikeDataStart()
                log("[$tag] → RLY 113")
                sendReqBase(out, 113, null)
            }
            114 -> { // REQ_RV_DATA_NEXT — bike pulling a frame (data socket); send one access unit raw
                val frame = video?.pollFrame(1500)
                if (frame == null) {
                    log("[$tag] REQ_RV_DATA_NEXT(114): no frame ready")
                } else {
                    sendFrameRaw(out, frame)
                    framesSent++
                    lastFrameAt = System.currentTimeMillis()
                    if (framesSent == 1) ConnectionState.set(Phase.STREAMING)
                    if (framesSent <= 5 || framesSent % 60 == 0)
                        log("[$tag] sent frame #$framesSent (${frame.size}b)")
                }
            }
            32 -> handleTouch(tag, body)
            else -> {
                val preview = BleProtocol.bytesToHex(body.copyOf(minOf(32, body.size)))
                log("[$tag] media cmdType=$cmdType len=${body.size} $preview")
            }
        }
    }

    /**
     * Dash touchscreen event (PXC media cmdType 32, 18-byte body, little-endian):
     *   action u16 @0 (2=DOWN, 3=MOVE, 1=UP) | x u16 @2 | y u16 @4 | pointerId u16 @6 | timestamp u32 @8 | …
     * Coordinates are in the bike canvas we negotiated ([negW]x[negH]). Y is u16 at @4; pointerId is
     * u16 at @6. Ghost contacts and mid-drag UP drops are filtered before forwarding the active pointer
     * via [AaVideoBridge.touchSink] (AaInput tracks the full multi-touch set). Actions normalised to
     * AaInput's 0=DOWN/1=UP/2=MOVE.
     */
    private fun handleTouch(tag: String, body: ByteArray) {
        if (body.size < 8) { log("[$tag] touch frame too short (${body.size}b)"); return }
        val b = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
        val rawAction = b.getShort(0).toInt() and 0xFFFF
        val x = b.getShort(2).toInt() and 0xFFFF
        val y = b.getShort(4).toInt() and 0xFFFF
        val pointerId = b.getShort(6).toInt() and 0xFFFF
        val action = when (rawAction) {
            2 -> 0   // DOWN
            1 -> 1   // UP
            3 -> 2   // MOVE
            else -> { log("[$tag] touch: unknown action=$rawAction x=$x y=$y"); return }
        }

        synchronized(pointers) {
            val now = android.os.SystemClock.elapsedRealtime()

            // Drop fingers whose UP frame was lost so a missed UP can't strand one down forever.
            pointers.entries.removeAll { (id, p) -> id != pointerId && now - p.third > POINTER_STALE_MS }

            when (action) {
                1 -> {
                    if (!pointers.containsKey(pointerId)) return   // UP for a filtered ghost
                    holdUp(tag, pointerId, x, y, now)
                    return
                }
                0 -> {
                    // Did the finger we are holding an UP for just come back? Continue as MOVE.
                    if (pendingUpId >= 0 && now - pendingUpAt <= STITCH_MS &&
                        near(pendingUpX, pendingUpY, x, y, STITCH_PX)
                    ) {
                        val originalId = aaIdFor(pendingUpId)
                        cancelPendingUp()
                        if (pointerId != originalId) aaIdOf[pointerId] = originalId
                        if (stitches++ % 10 == 0) {
                            log("[$tag] touch: contact came back ${now - pendingUpAt}ms later, " +
                                "keeping the drag alive instead of ending it (#$stitches)")
                        }
                        emit(tag, 2, pointerId, x, y, now)
                        return
                    }
                    commitPendingUp(tag)
                    if (isGhost(pointerId, x, y)) {
                        if ((ghostsDropped++ % 20) == 0) {
                            log("[$tag] touch: ignoring ghost contact at ($x,$y) near an existing finger")
                        }
                        return
                    }
                }
                else -> if (isGhost(pointerId, x, y)) return
            }

            emit(tag, action, pointerId, x, y, now)
        }
    }

    /** A contact sitting on top of a different finger is one finger reported twice. */
    private fun isGhost(pointerId: Int, x: Int, y: Int): Boolean =
        pointers.any { (id, p) -> id != pointerId && near(p.first, p.second, x, y) }

    private fun aaIdFor(dashId: Int): Int = aaIdOf[dashId] ?: dashId

    /** Forward one event for the active pointer. Caller holds the [pointers] lock. */
    private fun emit(tag: String, action: Int, dashId: Int, x: Int, y: Int, now: Long) {
        pointers[dashId] = Triple(x, y, now)

        if (pointers.size > MAX_POINTERS) {
            val keep = (listOf(dashId) +
                pointers.entries.sortedByDescending { it.value.third }.map { it.key })
                .distinct().take(MAX_POINTERS).toSet()
            pointers.keys.retainAll(keep)
        }

        val aaId = aaIdFor(dashId)

        if (action == 2) {
            // Keep MOVE debug, but don't flood the buffer / UI under finger drag.
            LogBus.logThrottled(
                "touch-move-$tag",
                "[$tag] TOUCH MOVE bike=($x,$y) ptr=$dashId of ${pointers.size} canvas=${negW}x$negH",
                minIntervalMs = 400L,
            )
        } else {
            log("[$tag] TOUCH ${if (action == 0) "DOWN" else "UP"} " +
                "bike=($x,$y) ptr=$dashId of ${pointers.size} canvas=${negW}x$negH")
        }

        val sink = AaVideoBridge.touchSink
        when {
            sink != null -> sink(action, aaId, x, y)
            GpxSession.active && GpxSession.dispatchTouch(action, x, y) -> {
                if (action != 2) log("[$tag] touch → GPX viewer")
            }
            else -> if (action != 2) log("[$tag] touch dropped (no AA / GPX sink)")
        }
        if (action == 1) { pointers.remove(dashId); aaIdOf.remove(dashId) }
    }

    /** Hold an UP for [STITCH_MS] in case the digitizer re-acquires the same finger. */
    private fun holdUp(tag: String, dashId: Int, x: Int, y: Int, now: Long) {
        cancelPendingUp()
        pendingUpId = dashId; pendingUpX = x; pendingUpY = y; pendingUpAt = now
        pendingUpTask = try {
            stitchExec?.schedule({
                synchronized(pointers) {
                    if (pendingUpId == dashId) {
                        pendingUpId = -1
                        emit(tag, 1, dashId, x, y, android.os.SystemClock.elapsedRealtime())
                    }
                }
            }, STITCH_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            emit(tag, 1, dashId, x, y, now); pendingUpId = -1; null
        }
    }

    /** Commit a held UP immediately. Caller holds the [pointers] lock. */
    private fun commitPendingUp(tag: String) {
        val id = pendingUpId
        if (id < 0) return
        val x = pendingUpX; val y = pendingUpY
        cancelPendingUp()
        emit(tag, 1, id, x, y, android.os.SystemClock.elapsedRealtime())
    }

    private fun cancelPendingUp() {
        pendingUpTask?.cancel(false)
        pendingUpTask = null
        pendingUpId = -1
    }

    private fun resolveGateway(network: Network?): Inet4Address? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val target = network ?: cm.activeNetwork ?: return null
        val lp = cm.getLinkProperties(target) ?: return null

        // 1. Explicit default-route gateway. Present on most phones — the DHCP server on the
        //    bike's Wi-Fi Direct group advertises itself as the router.
        for (r in lp.routes) {
            if (r.isDefaultRoute) {
                val gw = r.gateway
                if (gw is Inet4Address && !gw.isAnyLocalAddress) return gw
            }
        }
        // 2. Wi-Fi Direct fallback. Some phones (seen on Samsung / Android 16) install NO default
        //    route for the P2P group — only the on-link 192.168.49.0/24 subnet with a 0.0.0.0
        //    gateway. But the group owner (the bike) is always the ".1" host of our own /24 by
        //    Android's Wi-Fi Direct convention, so derive it from our address. Additive: only runs
        //    when step 1 found nothing (which would otherwise abort the whole link).
        for (la in lp.linkAddresses) {
            val a = la.address
            if (a is Inet4Address && !a.isLoopbackAddress && !a.isLinkLocalAddress) {
                gatewayForSubnet(a, la.prefixLength)?.let {
                    log("no default route — assuming Wi-Fi Direct group owner ${it.hostAddress}")
                    return it
                }
            }
        }
        // 3. Last resort.
        return lp.dnsServers.filterIsInstance<Inet4Address>().firstOrNull()
    }

    /** The ".1" host of [addr]'s subnet (network address | 1) — the Wi-Fi Direct group-owner IP. */
    private fun gatewayForSubnet(addr: Inet4Address, prefix: Int): Inet4Address? {
        if (prefix !in 1..31) return null
        return try {
            val b = addr.address
            val ip = ((b[0].toInt() and 0xFF) shl 24) or ((b[1].toInt() and 0xFF) shl 16) or
                ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)
            val mask = -1 shl (32 - prefix)
            val gw = (ip and mask) or 1
            if (gw == ip) return null // we already are ".1"; there's no distinct gateway to reach
            val out = byteArrayOf(
                ((gw ushr 24) and 0xFF).toByte(),
                ((gw ushr 16) and 0xFF).toByte(),
                ((gw ushr 8) and 0xFF).toByte(),
                (gw and 0xFF).toByte(),
            )
            Inet4Address.getByAddress(out) as? Inet4Address
        } catch (_: Exception) {
            null
        }
    }

    private fun pickBikeInterfaceIp(network: Network?): Inet4Address? {
        if (network != null) {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.getLinkProperties(network)?.linkAddresses
                ?.map(LinkAddress::getAddress)
                ?.filterIsInstance<Inet4Address>()
                ?.firstOrNull { !it.isLoopbackAddress }
                ?.let { return it }
        }
        for (nic in NetworkInterface.getNetworkInterfaces()) {
            if (!nic.isUp || nic.isLoopback) continue
            for (addr in nic.inetAddresses) {
                if (addr is Inet4Address && !addr.isLoopbackAddress) return addr
            }
        }
        return null
    }

    private fun acquireMulticastLock() {
        val wm = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wm.createMulticastLock("opencfmoto").apply {
            setReferenceCounted(false); acquire()
        }
    }

    /**
     * Keep a PXC :10922 channel socket alive by sending empty CMD_HEARTBEAT (0x70000000) every
     * [PXC_HEARTBEAT_INTERVAL_MS].
     *
     * The 800NK (CRCP / sdk 0.9.23.x) sends no heartbeats of its own. An idle channel socket —
     * including CAR_DATA where CHECK_SN / 0x104a0 land — makes the dash tear down after ~7s.
     * OpenMoto fixed this by heartbeating every :10922 accept; we previously only heartbeated
     * CAR_CTRL, which left CAR_DATA silent and produced the "7s after 0x104a0" flap.
     *
     * One sender thread per socket. Writes go through [PxcFrame.write]'s `synchronized(out)`, so
     * they interleave safely with the read loop's acks. Harmless on bikes that ignore or ack
     * 0x70000001.
     */
    private fun startCtrlHeartbeat(sock: Socket, label: String) {
        val t = thread(name = "ec-pxc-hb-$label", isDaemon = true) {
            log("[hb] PXC $label heartbeat started (0x70000000 every ${PXC_HEARTBEAT_INTERVAL_MS}ms)")
            val out = try { sock.getOutputStream() } catch (e: Exception) {
                log("[hb] $label: no stream: ${e.message}"); return@thread
            }
            var beats = 0
            while (running && !sock.isClosed) {
                try { Thread.sleep(PXC_HEARTBEAT_INTERVAL_MS) } catch (_: InterruptedException) { break }
                if (!running || sock.isClosed) break
                try {
                    PxcFrame(PxcFrame.CMD_HEARTBEAT, ByteArray(0)).write(out)
                    beats++
                    if (beats <= 3 || beats % 15 == 0) {
                        log("[hb] → PXC $label heartbeat #$beats")
                    }
                } catch (e: Exception) {
                    log("[hb] PXC $label heartbeat send failed: ${e.message}"); break
                }
            }
        }
        ctrlHeartbeatThreads.add(t)
    }

    private fun startHeartbeatLog() {
        heartbeatThread = thread(name = "ec-hb", isDaemon = true) {
            var i = 0
            while (running) {
                try { Thread.sleep(5000) } catch (_: InterruptedException) { break }
                i++
                val yFrames = yunmo?.framesSent ?: 0
                log(
                    "hb#$i probed=$probed video=${video != null} framesSent=$framesSent" +
                        (if (yFrames > 0 || yunmo != null) " yunmoFrames=$yFrames" else "") +
                        " openServers=${servers.count { !it.isClosed }}",
                )
            }
        }
    }

    private fun dumpEnvironment(network: Network?) {
        log("---- environment ----")
        log("Build: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} / Android ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (network != null) {
            val lp = cm.getLinkProperties(network)
            log("linkProps iface=${lp?.interfaceName} addrs=${lp?.linkAddresses} routes=${lp?.routes}")
        }
        log("---------------------")
    }
}
