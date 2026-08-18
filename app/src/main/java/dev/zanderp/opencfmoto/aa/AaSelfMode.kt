// OpenCfMoto glue (technique ported from headunit-revived AGPLv3 AapService.startSelfMode).
// Triggers Google Android Auto's loopback "self-mode": asks gearhead to project to 127.0.0.1:PORT
// with NO VPN. Best launched from a foreground Activity to satisfy Android's background-activity-
// launch restrictions (Android 12+/15).
//
// Path (escalating — wait for :5288 accept / session between steps):
//  1) WirelessStartupActivity (1.0.x — works on older gearhead)
//  2) WirelessStartupReceiver (1.0.x / AA 16.4+ when activity is not exported)
//  3) WifiBluetoothReceiver + START_WIRELESS_PROJECTION (AA 17.4+ / HUR) — ONLY with a real BT MAC
// Never use a dummy MAC: that causes Pixel "Wi‑Fi OK / AA never starts" loops after 2.0.6.
// Callers must NOT re-fire this while a session is already live — that kills AAP.
package dev.zanderp.opencfmoto.aa

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import android.os.Parcelable
import java.util.concurrent.atomic.AtomicInteger

object AaSelfMode {
    private const val GEARHEAD_PKG = "com.google.android.projection.gearhead"
    /** Wait after each self-mode poke before escalating if AA never attaches. */
    private const val ESCALATE_WAIT_MS = 1_200L

    private val mainHandler = Handler(Looper.getMainLooper())
    /** Bumps when a new trigger starts so stale escalate callbacks exit. */
    private val triggerGeneration = AtomicInteger(0)

    /**
     * Tear down self-mode: bump the generation so pending escalate pokes exit, and KILL the Google AA
     * gearhead we launched. It does NOT die when we stop our AAP receiver — it lingers (holding the
     * projection / retrying) until killed. Needs KILL_BACKGROUND_PROCESSES (declared in the manifest).
     */
    fun stop(context: Context, log: (String) -> Unit = {}) {
        triggerGeneration.incrementAndGet() // stale escalate callbacks exit early
        try {
            val am = context.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            am.killBackgroundProcesses(GEARHEAD_PKG)
            log("[AA] self-mode stopped — gearhead background killed")
        } catch (e: Exception) {
            log("[AA] gearhead kill failed: $e")
        }
    }

    fun trigger(context: Context, port: Int = AaReceiver.PORT, log: (String) -> Unit) {
        // Additive safety: never poke gearhead again if AAP is already up.
        if (sessionLive()) {
            log("[AA] self-mode skip — session already live/decoding")
            return
        }

        val app = context.applicationContext
        val gen = triggerGeneration.incrementAndGet()
        logGearheadVersion(app, log)

        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networkToUse: Parcelable? = (cm.activeNetwork as? Parcelable) ?: createFakeNetwork(0)
        val fakeWifiInfo = createFakeWifiInfo()

        // 1) Activity (older gearhead). Do NOT return on success — AA 16.4+/17.4+ often
        // startActivity without throwing and then ignore the intent (HUR never ran before).
        var activityStarted = false
        try {
            val intent = Intent().apply {
                setClassName(
                    GEARHEAD_PKG,
                    "com.google.android.apps.auto.wireless.setup.service.impl.WirelessStartupActivity",
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra("PARAM_HOST_ADDRESS", "127.0.0.1")
                putExtra("PARAM_SERVICE_PORT", port)
                networkToUse?.let { putExtra("PARAM_SERVICE_WIFI_NETWORK", it) }
                fakeWifiInfo?.let { putExtra("wifi_info", it) }
            }
            log("[AA] launching Android Auto WirelessStartupActivity → 127.0.0.1:$port")
            context.startActivity(intent)
            activityStarted = true
        } catch (e: Exception) {
            log("[AA] Activity trigger failed (${e.message}); trying broadcast fallback")
        }

        if (!activityStarted) {
            // Activity denied — escalate Receiver immediately, then HUR after wait.
            fireWirelessStartupReceiver(app, port, networkToUse, fakeWifiInfo, log)
            scheduleIfStillNeeded(gen, ESCALATE_WAIT_MS) {
                if (sessionLive()) {
                    log("[AA] self-mode escalate stop — session live after Receiver")
                    return@scheduleIfStillNeeded
                }
                fireHurProjection(app, log)
            }
            return
        }

        // Activity launched without throw — wait for accept, then Receiver, then HUR.
        scheduleIfStillNeeded(gen, ESCALATE_WAIT_MS) {
            if (sessionLive()) {
                log("[AA] self-mode escalate stop — session live after Activity")
                return@scheduleIfStillNeeded
            }
            log("[AA] no session after Activity — escalating to WirelessStartupReceiver")
            fireWirelessStartupReceiver(app, port, networkToUse, fakeWifiInfo, log)
            scheduleIfStillNeeded(gen, ESCALATE_WAIT_MS) {
                if (sessionLive()) {
                    log("[AA] self-mode escalate stop — session live after Receiver")
                    return@scheduleIfStillNeeded
                }
                log("[AA] no session after Receiver — escalating to START_WIRELESS_PROJECTION")
                fireHurProjection(app, log)
            }
        }
    }

    private fun sessionLive(): Boolean =
        dev.zanderp.opencfmoto.AaVideoBridge.aaSessionLive ||
            dev.zanderp.opencfmoto.AaVideoBridge.aaDecoding

    private fun scheduleIfStillNeeded(gen: Int, delayMs: Long, block: () -> Unit) {
        mainHandler.postDelayed({
            if (gen != triggerGeneration.get()) return@postDelayed
            if (sessionLive()) return@postDelayed
            block()
        }, delayMs)
    }

    private fun fireWirelessStartupReceiver(
        app: Context,
        port: Int,
        networkToUse: Parcelable?,
        fakeWifiInfo: Parcelable?,
        log: (String) -> Unit,
    ) {
        try {
            val receiverIntent = Intent().apply {
                setClassName(
                    GEARHEAD_PKG,
                    "com.google.android.apps.auto.wireless.setup.receiver.WirelessStartupReceiver",
                )
                action = "com.google.android.apps.auto.wireless.setup.receiver.wirelessstartup.START"
                putExtra("ip_address", "127.0.0.1")
                putExtra("projection_port", port)
                networkToUse?.let { putExtra("PARAM_SERVICE_WIFI_NETWORK", it) }
                fakeWifiInfo?.let { putExtra("wifi_info", it) }
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            }
            app.sendBroadcast(receiverIntent)
            log("[AA] broadcast fallback sent")
        } catch (e: Exception) {
            log("[AA] broadcast fallback failed: ${e.message}")
        }
    }

    private fun fireHurProjection(app: Context, log: (String) -> Unit) {
        // AA 17.4+ / HUR — only with a real BT MAC (never dummy 00:11:22:33:44:55).
        val mac = pickProjectionMac(app, log) ?: run {
            log("[AA] skip START_WIRELESS_PROJECTION — no real BT MAC (grant Nearby / turn BT on)")
            return
        }
        try {
            val btReceiverIntent = Intent("com.google.android.projection.gearhead.START_WIRELESS_PROJECTION").apply {
                setClassName(
                    GEARHEAD_PKG,
                    "com.google.android.apps.auto.wireless.bluetooth.WifiBluetoothReceiver",
                )
                putExtra("DEVICE_ADDRESS", mac)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            }
            app.sendBroadcast(btReceiverIntent)
            log("[AA] broadcast fallback 2 (START_WIRELESS_PROJECTION mac=$mac) sent")
        } catch (e: Exception) {
            log("[AA] broadcast fallback 2 failed: ${e.message} — is Android Auto installed & set up?")
        }
    }

    fun logGearheadVersion(context: Context, log: (String) -> Unit) {
        try {
            val pi = context.packageManager.getPackageInfo(GEARHEAD_PKG, 0)
            @Suppress("DEPRECATION")
            val ver = pi.versionName ?: "?"
            val code = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else {
                @Suppress("DEPRECATION")
                pi.versionCode.toLong()
            }
            log("[AA] gearhead versionName=$ver versionCode=$code")
        } catch (e: Exception) {
            log("[AA] gearhead not installed or unreadable: ${e.message}")
        }
    }

    /**
     * True when Connect/AA can attempt HUR (Nearby granted on API 31+, BT adapter on).
     * Older APIs only need BT enabled.
     */
    fun canAttemptHur(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return try {
            val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = mgr?.adapter
            adapter != null && adapter.isEnabled
        } catch (_: Exception) {
            false
        }
    }

    /** Real bonded/connected MAC, or null if we must not send HUR (no CONNECT / BT off / none). */
    private fun pickProjectionMac(context: Context, log: (String) -> Unit): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            log("[AA] no BLUETOOTH_CONNECT — HUR skipped (1.0-style Activity/Receiver only)")
            return null
        }
        return try {
            val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = mgr?.adapter
            if (adapter == null || !adapter.isEnabled) {
                log("[AA] Bluetooth off/unavailable — HUR skipped")
                return null
            }

            val a2dp = adapter.getProfileConnectionState(BluetoothProfile.A2DP)
            val hfp = adapter.getProfileConnectionState(BluetoothProfile.HEADSET)
            val audioConnected =
                a2dp == BluetoothProfile.STATE_CONNECTED || hfp == BluetoothProfile.STATE_CONNECTED

            val bonded = try {
                adapter.bondedDevices?.toList().orEmpty()
            } catch (_: SecurityException) {
                emptyList()
            }

            val connected = bonded.filter { it.isConnectedCompat() }
            val bikeConnected = connected.firstOrNull { it.isBikeLookingName() }
            val anyConnected = bikeConnected ?: connected.firstOrNull()

            // Prefer a live connection; else bike-looking bond; never a random first bond when
            // audio is connected but we couldn't resolve which device (use first bond only then).
            val selected = anyConnected
                ?: bonded.firstOrNull { it.isBikeLookingName() }
                ?: if (audioConnected) bonded.firstOrNull() else null
                ?: bonded.firstOrNull()

            log(
                "[AA] SelfMode BT: bonded=${bonded.size} " +
                    "connectedMac=${anyConnected?.address} selected=${selected?.address} " +
                    "name=${selected?.name}",
            )
            selected?.address
        } catch (e: Exception) {
            log("[AA] BT MAC lookup failed: ${e.message}")
            null
        }
    }

    private fun BluetoothDevice.isConnectedCompat(): Boolean = try {
        val m = javaClass.getMethod("isConnected")
        (m.invoke(this) as? Boolean) == true
    } catch (_: Exception) {
        false
    }

    private fun BluetoothDevice.isBikeLookingName(): Boolean {
        val n = name ?: return false
        return n.contains("CFMOTO", ignoreCase = true) ||
            n.contains("CFDL", ignoreCase = true) ||
            n.contains("Bike", ignoreCase = true) ||
            n.contains("Moto", ignoreCase = true) ||
            n.contains("VOGE", ignoreCase = true) ||
            n.contains("QJ", ignoreCase = true) ||
            n.contains("Zontes", ignoreCase = true) ||
            n.contains("Benelli", ignoreCase = true) ||
            n.contains("Morini", ignoreCase = true) ||
            n.contains("Alltrhike", ignoreCase = true) ||
            n.contains("Kove", ignoreCase = true)
    }

    private fun createFakeNetwork(netId: Int): Parcelable? {
        val parcel = Parcel.obtain()
        return try {
            parcel.writeInt(netId)
            parcel.setDataPosition(0)
            val creator = Class.forName("android.net.Network").getField("CREATOR").get(null) as Parcelable.Creator<*>
            creator.createFromParcel(parcel) as Parcelable
        } catch (_: Exception) {
            null
        } finally {
            parcel.recycle()
        }
    }

    private fun createFakeWifiInfo(): Parcelable? {
        return try {
            val wifiInfoClass = Class.forName("android.net.wifi.WifiInfo")
            val wifiInfo = wifiInfoClass.getDeclaredConstructor().apply { isAccessible = true }
                .newInstance() as Parcelable
            try {
                wifiInfoClass.getDeclaredField("mSSID").apply { isAccessible = true }
                    .set(wifiInfo, "\"Headunit-Fake-Wifi\"")
            } catch (_: Exception) {
            }
            wifiInfo
        } catch (_: Exception) {
            null
        }
    }
}
