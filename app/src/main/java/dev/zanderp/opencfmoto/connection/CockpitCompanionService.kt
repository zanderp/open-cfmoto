// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
//
// "Wake me when the bike is near." Once the rider has associated the bike's Wi‑Fi SSID with the system
// CompanionDeviceManager (see [AutoConnectManager.associate] + [AutoConnectManager.startObserving]),
// the platform BINDS this service and calls [onDeviceAppeared] when the paired dash comes into range —
// even with the app closed. We then connect in the CFMOTO built-in-map (Overtake) mode via the same
// proven path the app uses, never Android Auto (AA 17.4+ blocks background auto-start of the head unit).
//
// Contract (a malformed CompanionDeviceService SILENTLY never fires): the manifest must register it with
// android:permission="android.permission.BIND_COMPANION_DEVICE_SERVICE", android:exported="true", and the
// intent-filter action "android.companion.CompanionDeviceService". See AndroidManifest.xml.
package dev.zanderp.opencfmoto.connection

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.annotation.RequiresApi
import dev.zanderp.opencfmoto.ConnectionState
import dev.zanderp.opencfmoto.LogBus
import dev.zanderp.opencfmoto.Phase
import dev.zanderp.opencfmoto.R
import dev.zanderp.opencfmoto.ui.CockpitActivity

/**
 * System-bound CompanionDeviceService that receives bike-presence callbacks and kicks off a background
 * CFMOTO own-map connect. [CompanionDeviceService] is API 31+, so the whole class is gated there; on
 * older phones the manifest component simply never loads (the platform never binds it). minSdk is 29.
 */
@RequiresApi(31)
@Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
class CockpitCompanionService : CompanionDeviceService() {

    // API 33+ presence callbacks (AssociationInfo).
    override fun onDeviceAppeared(associationInfo: AssociationInfo) =
        onBikeAppeared("id=${associationInfo.id}")

    override fun onDeviceDisappeared(associationInfo: AssociationInfo) =
        onBikeGone("id=${associationInfo.id}")

    // API 31–32 callbacks (String MAC), deprecated in 33 but kept so pre-Android-13 phones wake too.
    // On 33+ the platform dispatches the AssociationInfo variants above; the debounce in
    // [CfmotoConnect] guards the (theoretical) case of both firing.
    override fun onDeviceAppeared(address: String) = onBikeAppeared("addr=$address")

    override fun onDeviceDisappeared(address: String) = onBikeGone("addr=$address")

    private fun onBikeAppeared(who: String) {
        LogBus.log("[CDS] bike appeared ($who) — background CFMOTO auto-connect")
        // Promote to a connectedDevice foreground service FIRST so the connect + headless map
        // projection survive with the app closed / screen off. Allowed from a CDM presence callback
        // (REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND); best-effort if the OEM refuses.
        promoteToForeground()
        val started = try {
            CfmotoConnect.startCfmotoMapBackground(applicationContext)
        } catch (e: Exception) {
            LogBus.log("[CDS] background connect threw: $e"); false
        }
        if (!started) {
            LogBus.log("[CDS] nothing started (feature off / no bike / already live) — dropping foreground")
            stopForegroundCompat()
        }
    }

    private fun onBikeGone(who: String) {
        // Do NOT force-disconnect (the prober's own watchdog handles real link drops). While the dash
        // is actively projecting, KEEP the foreground umbrella so a brief presence miss can't let the
        // OS kill a live ride. Only when nothing is projecting do we release it.
        val p = ConnectionState.phase
        if (p == Phase.STREAMING || p == Phase.MIRRORING) {
            LogBus.log("[CDS] bike disappeared ($who) but still projecting (${p.logLabel}) — keeping foreground")
            return
        }
        LogBus.log("[CDS] bike disappeared ($who) — not projecting, releasing foreground")
        stopForegroundCompat()
    }

    override fun onDestroy() {
        stopForegroundCompat()
        super.onDestroy()
    }

    @SuppressLint("InlinedApi")
    private fun promoteToForeground() {
        try {
            ensureChannel()
            val tap = PendingIntent.getActivity(
                this,
                0,
                Intent(this, CockpitActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle(getString(R.string.ovk_autoconnect_notif_title))
                .setContentText(getString(R.string.ovk_autoconnect_notif_text))
                .setContentIntent(tap)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build()
            // 3-arg startForeground (with type) exists since API 29; the type is REQUIRED on 34+.
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } catch (e: Exception) {
            // FGS-from-background can still be refused on some OEMs — proceed with the connect anyway
            // (works when the app was recently foreground); flagged as a bike-gated risk.
            LogBus.log("[CDS] startForeground refused (continuing best-effort): $e")
        }
    }

    private fun stopForegroundCompat() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.ovk_autoconnect_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = getString(R.string.ovk_autoconnect_channel_desc) },
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "opencfmoto_autoconnect"
        private const val NOTIF_ID = 7
    }
}
