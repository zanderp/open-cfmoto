// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import dev.overtake.maps.MapsforgeDownloadState
import dev.overtake.maps.MapsforgeDownloadStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Foreground service (type `dataSync`) that keeps a Mapsforge `.map` download alive across navigation
 * and process pressure. The DOWNLOAD ITSELF lives in the library (an app-scoped worker on
 * [dev.overtake.maps.OfflineManager], resumable + auto-retrying); this service exists only to (1) give
 * that 300 MB+ transfer a foreground guarantee so Android won't kill the process, and (2) surface an
 * ongoing notification with live %/bytes and a Cancel action.
 *
 * It observes the library's `mapsforgeDownloadState()` StateFlow: while the download is
 * [MapsforgeDownloadState.isActive] it updates the notification; on any terminal state (success /
 * failed / canceled) it drops the notification and stops itself. Because the state is app-scoped, the
 * Mapsforge screen re-attaches to the SAME running download simply by observing that flow — this
 * service never owns the progress, only the foreground lifecycle + notification.
 */
class MapDownloadService : Service() {

    private val offline by lazy { overtakeOffline(this) }
    private var scope: CoroutineScope? = null
    private var observing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            // The notification's Cancel action: cancel the worker; the observer will see the terminal
            // state and tear the service down. (We are already foreground here, so no startForeground.)
            runCatching { offline.cancelMapsforgeDownload() }
            return START_NOT_STICKY
        }

        val url = intent?.getStringExtra(EXTRA_URL)
        val name = intent?.getStringExtra(EXTRA_NAME)
        // startForegroundService() REQUIRES a startForeground() within a few seconds — do it FIRST,
        // before the (slower) library start / observe, so we never trip the ANR-style timeout.
        startForegroundInitial(name)
        if (!url.isNullOrBlank() && !name.isNullOrBlank()) {
            // Start (or re-attach to) the single app-scoped download; a false return means a DIFFERENT
            // one is already running — we simply reflect whatever is active.
            runCatching { offline.startMapsforgeDownload(url, name) }
        }
        ensureObserving()
        // Not sticky: if the OS kills us we don't want a blank auto-restart. The `.part` stays on disk,
        // so the rider resumes by re-opening the screen (the library picks up where it left off).
        return START_NOT_STICKY
    }

    private fun ensureObserving() {
        if (observing) return
        observing = true
        val s = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
        scope = s
        s.launch {
            offline.mapsforgeDownloadState().collect { st ->
                if (st.isActive) {
                    notificationManager().notify(NOTIF_ID, buildNotification(st))
                } else {
                    finishForeground()
                }
            }
        }
    }

    private fun finishForeground() {
        observing = false
        scope?.cancel()
        scope = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // --- notification ----------------------------------------------------------------------------

    private fun startForegroundInitial(name: String?) {
        ensureChannel()
        val n = baseBuilder()
            .setContentText(name?.let { prettify(it) } ?: getString(R.string.ovk_mf_notif_preparing))
            .setProgress(0, 0, true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun buildNotification(st: MapsforgeDownloadState): Notification {
        val b = baseBuilder()
        val name = if (st.name.isNotBlank()) prettify(st.name) else getString(R.string.ovk_mf_notif_title)
        b.setContentTitle(name)
        when {
            st.status == MapsforgeDownloadStatus.RETRYING -> {
                b.setContentText(getString(R.string.ovk_mf_download_retrying, st.attempt, st.maxAttempts))
                b.setProgress(0, 0, true)
            }
            st.percent >= 0 -> {
                b.setContentText(
                    getString(R.string.ovk_mf_progress, st.percent, human(st.bytesRead), human(st.totalBytes)),
                )
                b.setProgress(100, st.percent, false)
            }
            else -> {
                b.setContentText(getString(R.string.ovk_mf_progress_unknown, human(st.bytesRead)))
                b.setProgress(0, 0, true)
            }
        }
        val cancelIntent = Intent(this, MapDownloadService::class.java).apply { action = ACTION_CANCEL }
        val cancelPi = PendingIntent.getService(
            this, 1, cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        b.addAction(
            Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                getString(R.string.ovk_mf_cancel_download),
                cancelPi,
            ).build(),
        )
        return b.build()
    }

    /** Common notification scaffold (channel, icon, ongoing, tap-to-open, progress category). */
    private fun baseBuilder(): Notification.Builder {
        val open = packageManager.getLaunchIntentForPackage(packageName)
        val contentPi = open?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.ovk_mf_notif_title))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .also { if (contentPi != null) it.setContentIntent(contentPi) }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = notificationManager()
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.ovk_mf_notif_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { setShowBadge(false) },
            )
        }
    }

    private fun notificationManager(): NotificationManager =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /** `czech-republic` -> `Czech Republic`. Display only (mirrors the screen's prettyName). */
    private fun prettify(raw: String): String =
        raw.replace('_', ' ').replace('-', ' ').split(' ').joinToString(" ") { w ->
            w.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
        }

    private fun human(bytes: Long): String {
        if (bytes <= 0L) return "—"
        val mb = bytes / (1024.0 * 1024.0)
        return when {
            mb >= 1024.0 -> String.format(Locale.US, "%.1f GB", mb / 1024.0)
            mb >= 1.0 -> String.format(Locale.US, "%.0f MB", mb)
            else -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
        }
    }

    companion object {
        private const val NOTIF_ID = 4210
        private const val CHANNEL_ID = "opencfmoto_mapdownload"
        private const val ACTION_CANCEL = "dev.zanderp.opencfmoto.MAP_DOWNLOAD_CANCEL"
        private const val EXTRA_URL = "url"
        private const val EXTRA_NAME = "name"

        /** Start (or re-attach to) the app-scoped Mapsforge download for [url] under display [name]. */
        fun start(ctx: Context, url: String, name: String) {
            val i = Intent(ctx, MapDownloadService::class.java).apply {
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_NAME, name)
            }
            // minSdk 29 → always a foreground-service start (the service calls startForeground promptly).
            ctx.startForegroundService(i)
        }
    }
}
