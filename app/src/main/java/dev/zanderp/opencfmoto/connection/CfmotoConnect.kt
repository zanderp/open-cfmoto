// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.connection

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import dev.zanderp.opencfmoto.AaVideoBridge
import dev.zanderp.opencfmoto.AndroidAutoService
import dev.zanderp.opencfmoto.AppSettings
import dev.zanderp.opencfmoto.BikeLink
import dev.zanderp.opencfmoto.BikeMemory
import dev.zanderp.opencfmoto.BikeProfileHolder
import dev.zanderp.opencfmoto.BikeProfiles
import dev.zanderp.opencfmoto.BikeWifi
import dev.zanderp.opencfmoto.BikeWifiP2p
import dev.zanderp.opencfmoto.CrashGuard
import dev.zanderp.opencfmoto.DashClockBle
import dev.zanderp.opencfmoto.DashMemory
import dev.zanderp.opencfmoto.EasyConnDiscovery
import dev.zanderp.opencfmoto.EasyConnProber
import dev.zanderp.opencfmoto.GpxSession
import dev.zanderp.opencfmoto.LogBus
import dev.zanderp.opencfmoto.MapInputBridge
import dev.zanderp.opencfmoto.MatchAspectMode
import dev.zanderp.opencfmoto.Phase
import dev.zanderp.opencfmoto.PhoneHotspotAssist
import dev.zanderp.opencfmoto.PhoneHotspotScan
import dev.zanderp.opencfmoto.ProfileOverride
import dev.zanderp.opencfmoto.ProjectionHolder
import dev.zanderp.opencfmoto.ProjectionService
import dev.zanderp.opencfmoto.QrData
import dev.zanderp.opencfmoto.R
import dev.zanderp.opencfmoto.VideoPrefs
import dev.zanderp.opencfmoto.WifiGate
import dev.zanderp.opencfmoto.WifiTransport
import dev.zanderp.opencfmoto.ConnectionState

/**
 * App-scoped owner of the CFMOTO connect + project trigger (bike Wi‑Fi join, PXC prober start,
 * profile selection, mode-switch teardown). Extracted VERBATIM from [dev.zanderp.opencfmoto.MainActivity]
 * so the new Compose cockpit can connect and project the built-in map to the dash WITHOUT showing the
 * classic MainActivity UI — while MainActivity and the Android Auto path keep calling this SAME code.
 *
 * The connect machinery it drives ([EasyConnProber] via [BikeLink], [BikeWifi], the video pipeline in
 * the foreground service) is already process-global and survives an Activity finishing, so this object
 * only needs an [Activity] handle for the two operations that genuinely require one:
 * [WifiGate.ensureEnabledOrPrompt] and the runtime location-permission prompt.
 *
 * Parameterization of the moved code (see the original MainActivity methods):
 *  - `this` (the Activity, used as Context/Activity) → the [activity] parameter; `applicationContext`
 *    stays `activity.applicationContext` exactly where the originals used it.
 *  - `log(...)` (was `LogBus.log`) → [LogBus.log].
 *  - `(BikeLink.prober ?: prober)` and the MainActivity `prober` field → [proberFor], which reuses the
 *    process-global [BikeLink.prober] and creates + assigns it if absent, mirroring MainActivity.onCreate.
 */
object CfmotoConnect {

    /**
     * The process-global bike PXC client. Reuse [BikeLink.prober] if it already exists (e.g. the AA
     * receiver kept running while an Activity was recreated); otherwise create it and publish it to
     * [BikeLink] — mirrors how MainActivity.onCreate constructs/assigns the prober. Replaces the old
     * `(BikeLink.prober ?: prober)` expression (the MainActivity `prober` field is gone here).
     */
    private fun proberFor(context: Context): EasyConnProber =
        BikeLink.prober ?: EasyConnProber(context.applicationContext, LogBus::log).also { BikeLink.prober = it }

    private fun ensureLocationPermission(activity: Activity) {
        // Some OEMs require fine location to associate via WifiNetworkSpecifier.
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                activity, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1,
            )
        }
    }

    /** Pick the bike profile from the QR up front — it drives the Android Auto resolution/orientation,
     *  which must be set before AA starts. CLIENT_INFO refines it later during the PXC handshake. */
    fun applyProfile(context: Context, qr: QrData) {
        AppSettings.applyToHolder(context)
        BikeProfileHolder.active = BikeProfiles.selectByQr(qr, context)
        DashMemory.setLastDashTouch(context, BikeProfileHolder.advertisesScreenTouch)
        val userOverride = VideoPrefs.resolutionOverride(context, BikeProfileHolder.active)
        // In AUTO mode, if a previous session revealed this dash is a different orientation than the
        // profile assumes, flip AA to match. Learned from the dash's REQ_CONFIG_CAPTURE (see DashMemory).
        val autoGeo = if (userOverride == null) DashMemory.specFor(context, qr.ssid, BikeProfileHolder.active) else null
        BikeProfileHolder.aaVideoOverride = userOverride ?: autoGeo
        val spec = BikeProfileHolder.aaVideo
        BikeProfileHolder.aaContentMargins = VideoPrefs.aaMarginsFor(context, spec, qr.ssid)
        val aspectMode = VideoPrefs.matchAspectMode(context)
        val aspectNote = BikeProfileHolder.aaContentMargins.let { m ->
            when {
                m.any -> " [match-aspect ${aspectMode.name}: margins ${m.marginW}x${m.marginH}]"
                aspectMode == MatchAspectMode.OFF -> " [match-aspect OFF]"
                VideoPrefs.detectedPanelSize(context) == null -> " [match-aspect: waiting for panel size]"
                else -> " [match-aspect ${aspectMode.name}: margins 0]"
            }
        }
        val note = when {
            userOverride != null -> " (override: ${VideoPrefs.resolution(context).label})"
            autoGeo != null -> " (auto-orientation from last connect)"
            else -> ""
        }
        val touchNote = when {
            BikeProfileHolder.forceNonTouch -> " [Disable touchscreen ON — focus/knob AA]"
            BikeProfileHolder.forceTouch -> " [Force touchscreen ON — touch AA]"
            else -> ""
        }
        val ov = BikeProfileHolder.profileOverride
        val ovNote = if (ov != ProfileOverride.AUTO) " [profile override: ${ov.shortLabel}]" else ""
        LogBus.log("→ bike profile (QR ssid=${qr.ssid} modelId=${qr.modelId}): ${BikeProfileHolder.active.name} " +
            "→ AA ${spec.width}x${spec.height} @${spec.dpi}dpi$note$touchNote$ovNote$aspectNote")
    }

    /**
     * Stop the current dash projection so the next mode can open a clean PXC session.
     * Keeps bike Wi‑Fi (fast re-probe). Optionally preserves an armed Map session / mirror token.
     *
     * [localProber] carries the MainActivity `prober` field so its stale-orphan cleanup is preserved
     * for the classic path (pass the field); the cockpit path passes null (it has no separate field —
     * the only prober is [BikeLink.prober], already stopped above).
     */
    fun tearDownForModeSwitch(
        context: Context,
        clearMap: Boolean,
        clearMirror: Boolean,
        localProber: EasyConnProber? = null,
    ) {
        LogBus.log("→ mode switch: stop previous projection (keep Wi‑Fi)")
        try { AaVideoBridge.onSteadyVideo = null } catch (_: Exception) {}
        AaVideoBridge.pipeline = null
        AaVideoBridge.touchSink = null
        AaVideoBridge.keySink = null
        AaVideoBridge.scrollSink = null
        AaVideoBridge.previewTouchSink = null
        AaVideoBridge.nightSink = null
        try { AndroidAutoService.stop(context) } catch (e: Exception) { LogBus.log("AA stop: $e") }
        try { BikeLink.prober?.stop() } catch (e: Exception) { LogBus.log("prober stop: $e") }
        try {
            if (localProber != null && BikeLink.prober !== localProber) localProber.stop()
        } catch (_: Exception) {}
        if (clearMirror) {
            ProjectionHolder.projection?.let { try { it.stop() } catch (_: Exception) {} }
            ProjectionHolder.projection = null
            try { ProjectionService.stop(context) } catch (_: Exception) {}
        }
        if (clearMap) {
            try { GpxSession.clear() } catch (_: Exception) {}
            MapInputBridge.clear()
        }
        BikeLink.beginHandoff(context)
    }

    /**
     * Join the bike Wi-Fi and start the PXC prober.
     *
     * When [gateOnAaSteady] is true (Android Auto path), the prober start is handed to [BikeLink],
     * which waits until AA video is also steady — so this Wi-Fi join can run in parallel with AA
     * boot. When false (mirror path), the prober starts as soon as Wi-Fi is bound.
     *
     * Uses applicationContext + the process-global prober (not this activity) so the hand-off
     * completes even if the activity is destroyed/recreated after it was armed.
     */
    fun joinWifi(context: Context, qr: QrData, gateOnAaSteady: Boolean, activity: Activity? = null) {
        // Wi‑Fi must be on. Foreground (activity present) shows a modal; the background service path
        // (activity == null) posts a notification instead — the ONE genuine Activity dependency here.
        val wifiReady = if (activity != null) WifiGate.ensureEnabledOrPrompt(activity)
        else WifiGate.ensureEnabledOrNotify(context)
        if (!wifiReady) return
        ConnectionState.set(Phase.JOINING_WIFI)
        val transport = AppSettings.transport(context)
        // Phone-hosts-hotspot (Zontes action=128 / no SoftAP pwd): dash joins the phone.
        if (qr.supportsPhoneHotspot && qr.pwd.isEmpty()) {
            joinPhoneHotspot(context, qr, gateOnAaSteady, activity)
            return
        }
        // AUTO: P2P when the QR is P2P-only (incl. non-DIRECT SSIDs — join by MAC), or DIRECT-*.
        // SoftAP fallback remains in joinWifiP2p.onFailed for SoftAP-capable units.
        // Never force P2P when the QR is SoftAP-only (action bit3 clear) — Setup→P2P on those
        // bikes only burns 25s then falls back (Benelli TRK / bj* SSIDs in the field).
        val useP2p = when (transport) {
            WifiTransport.P2P -> qr.supportsP2p || !qr.supportsAp
            WifiTransport.AP -> false
            WifiTransport.AUTO ->
                (qr.supportsP2p && !qr.supportsAp) ||
                    (qr.ssid.startsWith("DIRECT-", ignoreCase = true) &&
                        (qr.supportsP2p || !qr.supportsAp))
        }
        // Per-bike memory refines AUTO only (an explicit P2P/AP setting is the rider's call): once a
        // transport has produced a live link for THIS bike, use it and skip the dead path. Some dashes
        // advertise DIRECT-* + SoftAP, so AUTO tries P2P first and burns the whole timeout on every
        // connect even when P2P never forms a group on this phone, while SoftAP connects in seconds.
        val remembered = if (transport == WifiTransport.AUTO) BikeMemory.winningTransport(context, qr.ssid) else null
        val effUseP2p = when {
            remembered == "AP" && qr.supportsAp -> false
            remembered == "P2P" && qr.supportsP2p -> true
            else -> useP2p
        }
        if (remembered != null && effUseP2p != useP2p) {
            LogBus.log("→ transport memory: '$remembered' connected before for '${qr.ssid}' — using it, skipping the dead path")
        }
        if (transport == WifiTransport.P2P && !useP2p) {
            LogBus.log(
                "→ Setup Wi‑Fi transport is P2P but QR is SoftAP-only " +
                    "(action=${qr.action} ssid=${qr.ssid}) — joining SoftAP instead",
            )
        }
        if (effUseP2p) {
            val isDirect = qr.ssid.startsWith("DIRECT-", ignoreCase = true)
            // P2P-only QRs with a SoftAP-style SSID but no MAC burn ~40s on MAC ERROR
            // (Voge-5G / ZT5G / etc.). Prefer SoftAP immediately when we have a password.
            if (!isDirect && qr.mac.isNullOrBlank() && qr.pwd.isNotEmpty()) {
                LogBus.log(
                    "→ QR says P2P-only; SSID '${qr.ssid}' has no MAC — " +
                        "joining SoftAP first (skip Wi‑Fi Direct by MAC)",
                )
            } else {
                if (!isDirect) {
                    LogBus.log(
                        "→ QR says P2P-only; SSID '${qr.ssid}' is not DIRECT-* — " +
                            "trying Wi‑Fi Direct by MAC (then SoftAP fallback)",
                    )
                }
                joinWifiP2p(context, qr, gateOnAaSteady, activity)
                return
            }
        }
        BikeWifi.reuseOrJoin(
            context = context.applicationContext,
            ssid = qr.ssid,
            psk = qr.pwd,
            onAvailable = { network ->
                WifiGate.cancelNotification(context.applicationContext)
                // SoftAP got Wi-Fi up for this bike → remember it so AUTO skips P2P next time.
                BikeMemory.setWinningTransport(context.applicationContext, qr.ssid, "AP")
                if (gateOnAaSteady) {
                    LogBus.log("→ bike Wi-Fi bound (waiting for AA video to go steady)")
                    BikeLink.markWifiReady(network)
                } else {
                    // Map / Mirror: no AA gating — rebuild PXC immediately on the bound network.
                    ConnectionState.set(Phase.PXC_CONNECTING)
                    LogBus.log("→ Wi-Fi bound; starting EasyConn PXC flow …")
                    try {
                        proberFor(context).start(network ?: BikeWifi.currentNetwork)
                    } catch (e: Exception) {
                        LogBus.log("prober start failed: $e")
                    }
                }
            },
            onLost = { LogBus.log("bike network lost") },
            log = LogBus::log,
        )
    }

    /**
     * Dash is the Wi‑Fi client (Carbit `action=128`). Android cannot create a hotspot with the
     * dash SSID/password for us — show assist dialog, open tether settings, then poll for EasyConn.
     */
    private fun joinPhoneHotspot(context: Context, qr: QrData, gateOnAaSteady: Boolean, activity: Activity?) {
        // Phone-as-hotspot needs the interactive assist dialog + tether settings → can't run headless.
        // The background auto-connect service simply can't handle these bikes; the rider connects from
        // the app instead (foreground). Everything else (SoftAP / P2P) works in the background.
        if (activity == null) {
            LogBus.log("→ phone-hotspot bike can't auto-connect in the background — open the app to connect")
            ConnectionState.set(Phase.ERROR, context.getString(R.string.main_phone_hotspot_status))
            return
        }
        LogBus.log(
            "→ phone-hotspot mode (action=${qr.action} mac=${qr.mac}) — " +
                "assist dialog (app cannot create AP; set dash SSID/pwd in system hotspot)",
        )
        ConnectionState.set(Phase.JOINING_WIFI, activity.getString(R.string.main_phone_hotspot_status))
        PhoneHotspotAssist.showSetupDialog(
            activity = activity,
            qr = qr,
            onContinue = { startPhoneHotspotScan(activity, gateOnAaSteady) },
            onCancel = {
                ConnectionState.set(Phase.ERROR, activity.getString(R.string.main_phone_hotspot_cancelled))
            },
        )
    }

    /** Poll for tether iface (~45s) then NSD / subnet EasyConn scan. */
    private fun startPhoneHotspotScan(activity: Activity, gateOnAaSteady: Boolean) {
        val creds = PhoneHotspotAssist.loadCreds(activity)
        if (creds.ssid.isNotEmpty()) {
            Toast.makeText(
                activity,
                activity.getString(R.string.main_phone_hotspot_waiting_named, creds.ssid),
                Toast.LENGTH_LONG,
            ).show()
        } else {
            Toast.makeText(activity, R.string.main_phone_hotspot_hint, Toast.LENGTH_LONG).show()
        }
        ConnectionState.set(Phase.JOINING_WIFI, activity.getString(R.string.main_phone_hotspot_status))
        Thread({
            try {
                var subnets = emptyList<PhoneHotspotScan.Subnet>()
                val pollRounds = 30 // ~45s at 1.5s
                for (i in 0 until pollRounds) {
                    if (ConnectionState.phase == Phase.STOPPED) return@Thread
                    subnets = PhoneHotspotScan.tetheringSubnets()
                    if (subnets.isNotEmpty()) break
                    if (i == 0 || i % 5 == 0) {
                        LogBus.log(
                            "[HOTSPOT] waiting for tether interface… " +
                                "(${i + 1}/$pollRounds) set Android hotspot" +
                                creds.ssid.takeIf { it.isNotEmpty() }?.let { " SSID='$it'" }.orEmpty(),
                        )
                    }
                    try {
                        Thread.sleep(1_500)
                    } catch (_: InterruptedException) {
                        return@Thread
                    }
                }
                if (subnets.isEmpty()) {
                    LogBus.log(
                        "[HOTSPOT] no tether interface after wait — open hotspot settings " +
                            "and set the dash SSID/password, then Connect again",
                    )
                    activity.runOnUiThread {
                        ConnectionState.set(Phase.ERROR, activity.getString(R.string.main_phone_hotspot_no_iface))
                        com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
                            .setTitle(R.string.main_phone_hotspot_dialog_title)
                            .setMessage(R.string.main_phone_hotspot_no_iface)
                            .setPositiveButton(R.string.main_phone_hotspot_open_settings) { _, _ ->
                                PhoneHotspotAssist.openHotspotSettings(activity)
                            }
                            .setNegativeButton(android.R.string.ok, null)
                            .show()
                    }
                    return@Thread
                }
                val subnet = subnets.first()
                LogBus.log(
                    "[HOTSPOT] using ${subnet.interfaceName} " +
                        "${subnet.localAddress.hostAddress}/${subnet.prefixLength}",
                )
                // Prefer NSD first (works when multicast reaches the tether iface).
                val nsd = EasyConnDiscovery.discoverNsd(activity.applicationContext, LogBus::log)
                val peer = nsd?.host
                    ?: PhoneHotspotScan.findEasyConnPeer(subnet, LogBus::log)
                if (peer == null) {
                    activity.runOnUiThread {
                        ConnectionState.set(Phase.ERROR, activity.getString(R.string.main_phone_hotspot_no_peer))
                        Toast.makeText(
                            activity,
                            activity.getString(R.string.main_phone_hotspot_no_peer),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    return@Thread
                }
                val bindIp = subnet.localAddress
                activity.runOnUiThread {
                    WifiGate.cancelNotification(activity.applicationContext)
                    if (gateOnAaSteady) {
                        LogBus.log(
                            "→ hotspot peer ${peer.hostAddress} (waiting for AA); " +
                                "bind=${bindIp.hostAddress}",
                        )
                        BikeLink.markP2pReady(bindIp, peer)
                    } else {
                        ConnectionState.set(Phase.PXC_CONNECTING)
                        LogBus.log("→ hotspot peer ${peer.hostAddress}; starting EasyConn PXC …")
                        try {
                            proberFor(activity).start(
                                network = null,
                                gatewayOverride = peer,
                                bindIpOverride = bindIp,
                            )
                        } catch (e: Exception) {
                            LogBus.log("prober start failed: $e")
                        }
                    }
                }
            } catch (e: Exception) {
                LogBus.log("[HOTSPOT] failed: $e")
                activity.runOnUiThread {
                    ConnectionState.set(Phase.ERROR, "hotspot scan failed")
                }
            }
        }, "phone-hotspot-scan").apply { isDaemon = true }.start()
    }

    /** Wi‑Fi Direct Group Owner path (CL‑C450 / some DIRECT- SSIDs / Zontes MAC join). */
    private fun joinWifiP2p(context: Context, qr: QrData, gateOnAaSteady: Boolean, activity: Activity?) {
        val wifiReady = if (activity != null) WifiGate.ensureEnabledOrPrompt(activity)
        else WifiGate.ensureEnabledOrNotify(context)
        if (!wifiReady) return
        LogBus.log("→ joining via Wi‑Fi Direct (P2P)")
        // How long to wait for a P2P group before failing over to SoftAP:
        //  - "P2P" remembered  → proven P2P device on this phone; give it the full window.
        //  - SoftAP advertised → a working fallback exists, so bail fast (6s) when P2P is hopeless
        //    (some phones ERROR on every join attempt in <1s but the group never forms).
        //  - otherwise (P2P-only) → full window; there is nothing to fall back to.
        val known = BikeMemory.winningTransport(context, qr.ssid)
        val p2pTimeout = when {
            known == "P2P" -> BikeWifiP2p.CONNECT_TIMEOUT_MS
            qr.supportsAp && qr.pwd.isNotEmpty() -> 6_000L
            else -> BikeWifiP2p.CONNECT_TIMEOUT_MS
        }
        BikeWifiP2p.connect(
            context = context.applicationContext,
            qr = qr,
            timeoutMs = p2pTimeout,
            onConnected = { bindIp, gatewayIp ->
                WifiGate.cancelNotification(context.applicationContext)
                // P2P actually formed a group for this bike → remember it (some DIRECT-* bikes need P2P).
                BikeMemory.setWinningTransport(context.applicationContext, qr.ssid, "P2P")
                if (gateOnAaSteady) {
                    LogBus.log("→ P2P bound (waiting for AA video); bike=${gatewayIp.hostAddress}")
                    BikeLink.markP2pReady(bindIp, gatewayIp)
                } else {
                    ConnectionState.set(Phase.PXC_CONNECTING)
                    LogBus.log("→ P2P bound; starting EasyConn PXC flow …")
                    try {
                        proberFor(context).start(
                            network = null,
                            gatewayOverride = gatewayIp,
                            bindIpOverride = bindIp,
                        )
                    } catch (e: Exception) {
                        LogBus.log("prober start failed: $e")
                    }
                }
            },
            onFailed = { reason ->
                if (qr.pwd.isEmpty() || qr.ssid.startsWith("PHONE-HOTSPOT", ignoreCase = true)) {
                    LogBus.log("P2P join failed: $reason — no SoftAP credentials to fall back to")
                    ConnectionState.set(Phase.ERROR, "Wi‑Fi Direct failed")
                    return@connect
                }
                LogBus.log("P2P join failed: $reason — falling back to AP join")
                if (!WifiGate.ensureEnabledOrNotify(context.applicationContext)) return@connect
                BikeWifi.join(
                    context = context.applicationContext,
                    ssid = qr.ssid,
                    psk = qr.pwd,
                    onAvailable = { network ->
                        WifiGate.cancelNotification(context.applicationContext)
                        // SoftAP fallback connected → remember AP so AUTO goes straight here next time.
                        BikeMemory.setWinningTransport(context.applicationContext, qr.ssid, "AP")
                        if (gateOnAaSteady) BikeLink.markWifiReady(network)
                        else {
                            ConnectionState.set(Phase.PXC_CONNECTING)
                            try {
                                proberFor(context).start(network)
                            } catch (e: Exception) {
                                LogBus.log("prober start failed: $e")
                            }
                        }
                    },
                    onLost = { LogBus.log("bike network lost") },
                    log = LogBus::log,
                )
            },
            log = LogBus::log,
        )
    }

    /**
     * Cockpit "Conectar" for the CFMOTO / built-in-map mode: CONNECT + project our own map to the dash
     * WITHOUT the classic MainActivity UI. This is the CFMOTO/wantBike branch of MainActivity's
     * `beginGpxProjection()`, moved verbatim. The cockpit must have armed the session first
     * (`GpxSession.prepareFreeRide()`), so [GpxSession.active] is already set here.
     */
    fun startCfmotoMap(activity: Activity) {
        if (!GpxSession.active) {
            // Cockpit is expected to call GpxSession.prepareFreeRide() first; if not, do nothing harmful.
            LogBus.log("→ Map: nothing prepared — open Map / GPX first")
            return
        }
        ensureLocationPermission(activity)
        val saved = BikeMemory.lastQr(activity)
        if (saved == null) {
            LogBus.log("→ Map: no saved bike — scan the dash QR first")
            ConnectionState.set(Phase.ERROR, activity.getString(R.string.ovk_conn_no_bike))
            return
        }
        LogBus.log(
            "→ Map: ${GpxSession.mode} '${GpxSession.trackName}' → " +
                "'${BikeMemory.lastBikeName(activity)}' (hard switch)",
        )
        // Stop AA / mirror / old sockets, keep Wi‑Fi + GpxSession, then fresh PXC.
        tearDownForModeSwitch(activity, clearMap = false, clearMirror = true)
        applyProfile(activity, saved)
        ConnectionState.set(Phase.MIRRORING, BikeMemory.lastBikeName(activity) ?: saved.ssid)
        joinWifi(activity, saved, gateOnAaSteady = false, activity = activity)
    }

    // ---- Auto-connect (background CompanionDeviceService + foreground cockpit fallback) ----

    /** Debounce so the background service and the foreground cockpit can't both fire a connect at once. */
    @Volatile private var lastAutoConnectClaimAt = 0L
    private const val AUTO_CONNECT_DEBOUNCE_MS = 8_000L

    /**
     * Shared gate for BOTH auto-connect paths: the feature is on ([AppSettings.autoConnect], the single
     * source of truth the visible switch mirrors) and nothing is already connecting/live. Side-effect
     * free — the caller still does the cheap range/Wi‑Fi checks before committing.
     */
    private fun autoConnectAllowed(context: Context, tag: String): Boolean {
        if (!AppSettings.autoConnect(context)) { LogBus.log("[$tag] auto-connect disabled — skip"); return false }
        val p = ConnectionState.phase
        if (p.busy || p == Phase.STREAMING || p == Phase.MIRRORING) {
            LogBus.log("[$tag] already ${p.logLabel} — skip"); return false
        }
        return true
    }

    /** Single-flight claim: true only for the first caller inside the debounce window. */
    private fun claimAutoConnect(): Boolean {
        val now = System.currentTimeMillis()
        synchronized(this) {
            if (now - lastAutoConnectClaimAt < AUTO_CONNECT_DEBOUNCE_MS) return false
            lastAutoConnectClaimAt = now
        }
        return true
    }

    /**
     * Background auto-connect entry, driven by [CockpitCompanionService.onDeviceAppeared] — NO Activity.
     * Connects in the CFMOTO built-in-map (Overtake) mode via the same proven path the cockpit uses
     * ([joinWifi] → PXC → the headless [dev.zanderp.opencfmoto.VideoPipeline] map projection), NEVER
     * Android Auto (AA 17.4+ blocks background head-unit start). Returns true if a connect was started.
     *
     * Chose option (a): the connect machinery is app/service-scoped, so the only genuinely-Activity
     * operations are skippable here — the Wi‑Fi-enable prompt falls back to a notification
     * ([WifiGate.ensureEnabledOrNotify]) and the runtime location prompt is replaced by a check + log
     * (a service can't prompt; the grant is expected from prior foreground use).
     */
    fun startCfmotoMapBackground(context: Context): Boolean {
        val app = context.applicationContext
        val saved = BikeMemory.lastQr(app) ?: run { LogBus.log("[auto-bg] no saved bike — skip"); return false }
        if (!autoConnectAllowed(app, "auto-bg")) return false
        if (!WifiGate.isWifiEnabled(app)) {
            WifiGate.ensureEnabledOrNotify(app)
            LogBus.log("[auto-bg] phone Wi-Fi is off — posted a notification"); return false
        }
        if (!claimAutoConnect()) { LogBus.log("[auto-bg] another auto-connect just fired — skip"); return false }
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            // No fine-location → some OEMs refuse the SoftAP WifiNetworkSpecifier join. Best-effort;
            // the rider grants it once from the app. Logged so a failed background join is explainable.
            LogBus.log("[auto-bg] no fine-location grant — SoftAP join may fail; open the app once to grant")
        }
        // Arm the free-ride map session exactly like the cockpit does before startCfmotoMap.
        if (!GpxSession.active) GpxSession.prepareFreeRide()
        LogBus.log("→ [auto-bg] bike appeared — connecting '${BikeMemory.lastBikeName(app)}' (CFMOTO map)")
        tearDownForModeSwitch(app, clearMap = false, clearMirror = true)
        applyProfile(app, saved)
        ConnectionState.set(Phase.MIRRORING, BikeMemory.lastBikeName(app) ?: saved.ssid)
        joinWifi(app, saved, gateOnAaSteady = false, activity = null)
        return true
    }

    /**
     * Foreground cockpit fallback: auto-connect the built-in map when [dev.zanderp.opencfmoto.ui.CockpitActivity]
     * resumes and the bike is in range — the cockpit analogue of [dev.zanderp.opencfmoto.MainActivity.maybeAutoConnect]
     * (which only ran in MainActivity and via Android Auto). Mirrors its guards (auto-connect setting,
     * in-range, not-already-busy, once via the shared single-flight) but routes to CFMOTO own-map, and
     * shares [claimAutoConnect] with the background path so the two never double-fire. Own-map only —
     * the caller restricts this to the built-in map provider, never auto-starting Android Auto.
     */
    fun maybeAutoConnectCfmotoMap(activity: Activity): Boolean {
        val saved = BikeMemory.lastQr(activity) ?: return false
        if (!autoConnectAllowed(activity, "auto-fg")) return false
        if (!WifiGate.isWifiEnabled(activity)) {
            LogBus.log("[auto-fg] phone Wi-Fi is off — tap Connect to turn it on"); return false
        }
        if (BikeWifi.isSsidInRange(activity, saved.ssid) == false) {
            LogBus.log("[auto-fg] '${BikeMemory.lastBikeName(activity)}' not in range — will retry on resume")
            return false
        }
        if (!claimAutoConnect()) { LogBus.log("[auto-fg] another auto-connect just fired — skip"); return false }
        if (!GpxSession.active) GpxSession.prepareFreeRide()
        LogBus.log("→ [auto-fg] auto-connecting '${BikeMemory.lastBikeName(activity)}' (CFMOTO map)")
        startCfmotoMap(activity)
        return true
    }

    /**
     * Cockpit "Espejo": after screen-capture consent, cast the PHONE screen to the dash via
     * MediaProjection — the mirror counterpart of [startCfmotoMap]. Ported from
     * MainActivity.startMirrorLink (`this` → [activity]; `log(...)` → [LogBus.log];
     * `getString(...)` → `activity.getString(...)`), REUSING the already-extracted
     * [tearDownForModeSwitch] / [applyProfile] / [joinWifi] helpers so the classic MainActivity mirror
     * and the cockpit run the SAME connect code.
     *
     * The MediaProjection token must already be armed by the caller (CockpitActivity's projectionLauncher,
     * exactly like MainActivity's) — this only re-links as mirror (keeping that token) and drops the app
     * to the background so single-app capture stays visible. The classic no-saved-bike branch launches a
     * QR scan (MainActivity-scoped `scanLauncher`); here we mirror [startCfmotoMap]'s no-bike handling
     * (ERROR state) instead — the cockpit dashboard routes to scan on its own.
     */
    fun startMirrorLink(activity: Activity) {
        val saved = BikeMemory.lastQr(activity)
        if (saved != null) {
            LogBus.log("→ Mirror: reusing saved bike '${BikeMemory.lastBikeName(activity)}'")
            // Drop AA / Map / old PXC; keep the MediaProjection token we just armed.
            tearDownForModeSwitch(activity, clearMap = true, clearMirror = false)
            applyProfile(activity, saved)
            ConnectionState.set(Phase.MIRRORING, BikeMemory.lastBikeName(activity) ?: saved.ssid)
            joinWifi(activity, saved, gateOnAaSteady = false, activity = activity)
            // Single-app MediaProjection only emits while the shared app is visible. Leaving this
            // Activity on top → capture visible=false → black dash / framesSent=0.
            activity.moveTaskToBack(true)
            Toast.makeText(
                activity,
                activity.getString(R.string.main_mirror_leave_shared_app),
                Toast.LENGTH_LONG,
            ).show()
        } else {
            LogBus.log("→ Mirror: no saved bike — scan the dash QR first")
            ConnectionState.set(Phase.ERROR, activity.getString(R.string.ovk_conn_no_bike))
        }
    }

    /**
     * Cockpit "Conectar" for the Android Auto mode: CONNECT + trigger the Android Auto → bike
     * projection (Google Maps on the dash via AA) WITHOUT the classic MainActivity UI. This is
     * MainActivity's `startAaFlow(qr)`, moved VERBATIM so MainActivity's classic AA path AND the cockpit
     * run the SAME code — the AA counterpart of [startCfmotoMap].
     *
     * Parameterization of the moved code:
     *  - `this` (Activity) → the [activity] parameter; `log(...)` → [LogBus.log];
     *    `getString(...)` → `activity.getString(...)`.
     *  - the three `logView.postDelayed{}` retry timers → one main-looper [Handler] (`900`/`4500`/`18000`ms
     *    unchanged). The callbacks fire against process-global state and are meant to outlive an Activity
     *    recreation (launching Google AA can destroy/recreate the caller) — see [BikeLink]; a main-looper
     *    Handler survives it, where a View's handler would not.
     *  - `AaSelfMode.trigger(this, log = ::log)` → `AaSelfMode.trigger(activity, log = LogBus::log)`
     *    (MainActivity's `::log` was already [LogBus.log]).
     */
    fun startAaConnect(activity: Activity, qr: QrData) {
        try {
            if (!WifiGate.ensureEnabledOrPrompt(activity)) return
            // AA Connect needs Nearby + Bluetooth so HUR (START_WIRELESS_PROJECTION) can run when
            // WirelessStartupActivity is a no-op (common on AA 16.4+/17.4+). Mirror/Map unchanged.
            if (!ensureNearbyBtForAaConnect(activity)) return
            // Map / Mirror / stale PXC must die first — half-switches leave framesSent=0.
            tearDownForModeSwitch(activity, clearMap = true, clearMirror = true)
            applyProfile(activity, qr)
            val bikeName = BikeMemory.lastBikeName(activity) ?: qr.ssid
            ConnectionState.set(Phase.STARTING_AA, bikeName)
            LogBus.log("→ starting Android Auto receiver (loopback self-mode). Ensure Android Auto is installed & set up.")

            // Parallel startup: the two slow steps (AA reaching steady video, and the user accepting the
            // bike Wi-Fi dialog) now overlap. [BikeLink] gates the actual bike probe until BOTH complete,
            // so the bike is never contacted before AA has frames to serve. These callbacks fire against
            // process-global state (applicationContext + BikeLink.prober), NOT this activity: launching
            // Google AA can destroy/recreate the caller Activity mid-startup and the hand-off must finish.
            BikeLink.beginHandoff(activity)
            AaVideoBridge.onSteadyVideo = {
                AaVideoBridge.onSteadyVideo = null
                ConnectionState.set(Phase.AA_VIDEO_LIVE)
                LogBus.log("→ Android Auto video is live")
                AndroidAutoService.upgradeForegroundForMicrophone()
                BikeLink.markAaVideoSteady()
            }
            AndroidAutoService.start(activity)
            // Trigger Google AA to project from the FOREGROUND activity (background-activity-launch
            // safe on Android 12+/15), after giving the service's :5288 server time to bind.
            // AA 16.4+/17.4+ often need the broadcast fallbacks; retry once if video never arrives
            // (common when the first WirelessStartupReceiver is ignored).
            val handler = Handler(Looper.getMainLooper())
            handler.postDelayed({
                try {
                    dev.zanderp.opencfmoto.aa.AaSelfMode.trigger(activity, log = LogBus::log)
                } catch (e: Exception) {
                    LogBus.log("AA self-mode trigger failed: $e")
                }
            }, 900)
            // Additive retry for AA 16.4+/17.4+ that ignore the first broadcast — skipped if
            // a session is already live (re-firing START_WIRELESS_PROJECTION kills AAP).
            handler.postDelayed({
                if (aaAlreadyLiveOrTerminal()) {
                    if (AaVideoBridge.aaSessionLive || AaVideoBridge.aaDecoding) {
                        LogBus.log("[AA] skip re-trigger — AA already connected/decoding")
                    }
                    return@postDelayed
                }
                try {
                    LogBus.log("[AA] no video yet — re-triggering self-mode (AA 16.4+/17.4+ fallbacks)")
                    dev.zanderp.opencfmoto.aa.AaSelfMode.trigger(activity, log = LogBus::log)
                } catch (e: Exception) {
                    LogBus.log("AA self-mode re-trigger failed: $e")
                }
            }, 4_500)
            // Give up the silent black/QR reconnect loop — surface a clear error after several tries.
            handler.postDelayed({
                val p = ConnectionState.phase
                if (p == Phase.IDLE || p == Phase.STOPPED || p == Phase.ERROR ||
                    p == Phase.STREAMING || p == Phase.MIRRORING
                ) return@postDelayed
                if (AaVideoBridge.aaSessionLive || AaVideoBridge.aaDecoding) return@postDelayed
                // Still no AA after retries — stop thrashing (bike SoftAP may already be up).
                LogBus.log("[AA] Android Auto never attached — stopping silent retry")
                ConnectionState.set(Phase.ERROR, activity.getString(R.string.conn_detail_aa_not_started))
            }, 18_000)
            // Kick off the Wi-Fi join right away, in parallel with AA boot.
            joinWifi(activity, qr, gateOnAaSteady = true, activity = activity)
        } catch (e: Exception) {
            LogBus.log("Connect failed (app stays open): $e")
            CrashGuard.persistSession(activity)
            ConnectionState.set(Phase.ERROR, activity.getString(R.string.conn_detail_connect_failed))
        }
    }

    /**
     * Terminal/steady guard for the 4.5s AA re-trigger. Reads only process-global state
     * (ConnectionState.phase + AaVideoBridge flags), so it needs no Activity — moved verbatim.
     */
    private fun aaAlreadyLiveOrTerminal(): Boolean {
        val p = ConnectionState.phase
        return AaVideoBridge.aaSessionLive || AaVideoBridge.aaDecoding ||
            p == Phase.AA_VIDEO_LIVE || p == Phase.PXC_CONNECTING || p == Phase.STREAMING ||
            p == Phase.IDLE || p == Phase.STOPPED || p == Phase.ERROR ||
            p == Phase.MIRRORING
    }

    /** Request code for the Nearby/Bluetooth grant AA Connect needs (moved from MainActivity). */
    private const val REQ_BT_FOR_AA = 4

    /**
     * Block AA Connect until Nearby (API 31+) is granted and Bluetooth is on — otherwise HUR
     * never runs and Activity/Receiver alone often leave SoftAP up with no AA on the phone.
     *
     * Moved verbatim from MainActivity (`this` → [activity]) so the classic AA path and the cockpit gate
     * identically. Returns false (caller aborts) when it must prompt for a grant / ask to turn BT on —
     * the rider re-taps Connect after granting, exactly as before (there is no permission-result
     * callback; MainActivity never registered one for [REQ_BT_FOR_AA]).
     */
    fun ensureNearbyBtForAaConnect(activity: Activity): Boolean {
        if (dev.zanderp.opencfmoto.aa.AaSelfMode.canAttemptHur(activity)) return true
        val needNearby = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        if (needNearby) {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN,
                ),
                REQ_BT_FOR_AA,
            )
            LogBus.log("→ Nearby/Bluetooth required for AA Connect — waiting for grant + BT on")
        } else {
            LogBus.log("→ Bluetooth is off — turn it on, then tap Connect again")
        }
        ConnectionState.set(Phase.ERROR, activity.getString(R.string.conn_detail_need_bluetooth))
        showNearbyBtForAaDialog(activity, needNearby)
        return false
    }

    private fun showNearbyBtForAaDialog(activity: Activity, needNearby: Boolean) {
        if (activity.isFinishing || activity.isDestroyed) return
        try {
            val msg = if (needNearby) {
                activity.getString(R.string.main_aa_need_nearby_message)
            } else {
                activity.getString(R.string.main_aa_need_bt_on_message)
            }
            com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.main_aa_need_nearby_title)
                .setMessage(msg)
                .setPositiveButton(R.string.main_aa_need_nearby_settings) { _, _ ->
                    try {
                        if (needNearby) {
                            activity.startActivity(
                                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                    .setData(Uri.fromParts("package", activity.packageName, null)),
                            )
                        } else {
                            activity.startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))
                        }
                    } catch (_: Exception) {
                        try {
                            activity.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
                        } catch (_: Exception) {
                        }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } catch (e: Exception) {
            LogBus.log("Nearby/BT dialog failed: $e")
        }
    }

    /**
     * Cockpit "Desconectar" for the CFMOTO / map projection: mirrors the map-relevant teardown of
     * MainActivity's `stopEverything()`. `AndroidAutoService.stop` releases the SCREEN_BRIGHT/CPU/WiFi
     * wake locks + kills the foreground service UNCONDITIONALLY (its onDestroy) — necessary because
     * `setGpxScreenWake(false)` refuses to release while `GpxSession.active`, so we must also
     * `GpxSession.clear()`. `releaseSession` (not `leave`) honors the keep-Wi‑Fi Setup toggle and tears
     * down any Wi‑Fi Direct (P2P) group. No MediaProjection teardown (mirror-only, stays in MainActivity).
     */
    fun stop(context: Context) {
        LogBus.log("→ stopping bike projection (cockpit)")
        try { AaVideoBridge.onSteadyVideo = null } catch (_: Exception) {}
        try { AndroidAutoService.stop(context) } catch (e: Exception) { LogBus.log("AA stop: $e") }
        try { dev.zanderp.opencfmoto.aa.AaSelfMode.stop(context, LogBus::log) } catch (e: Exception) { LogBus.log("gearhead kill: $e") }
        try { BikeLink.prober?.stop() } catch (e: Exception) { LogBus.log("prober stop: $e") }
        try { GpxSession.clear() } catch (_: Exception) {}
        try { DashClockBle.stop() } catch (_: Exception) {}
        try { BikeWifi.releaseSession(context, LogBus::log) } catch (e: Exception) { LogBus.log("wifi leave: $e") }
        ConnectionState.set(Phase.STOPPED, "")
    }
}
