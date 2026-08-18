// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.zanderp.opencfmoto.cockpit

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * The [NotificationListenerService] behind the cockpit's read-only mirrors.
 *
 * Reading other apps' active media sessions (Spotify, YouTube Music, the system player…) via
 * [android.media.session.MediaSessionManager.getActiveSessions] is gated on the caller being an
 * **enabled notification listener**. Android has no dedicated "media session read" permission; the
 * platform reuses the notification-listener grant as the trust anchor.
 *
 * So this class is registered in the manifest and granted by the user in
 * *Settings → Notification access*. Once granted, [NowPlaying] can pass this component to
 * `getActiveSessions(...)` and observe playback.
 *
 * Slice 2 also taps the notification **stream** the same grant exposes: on connect and on every
 * posted/removed notification we hand the current active set to [CockpitNotifications], which distils
 * it into the nav-guidance ([NavGuidance]) and call ([CallState]) panels. The media-session path
 * above is unchanged; this class still owns no state of its own.
 */
class NowPlayingListener : NotificationListenerService() {

    // `activeNotifications` throws if accessed before the listener is connected, so every read is
    // guarded — a throwing callback would tear down the whole notification-access binding.
    private fun pushActive() {
        runCatching { CockpitNotifications.refresh(activeNotifications ?: emptyArray()) }
    }

    override fun onListenerConnected() = pushActive()

    override fun onNotificationPosted(sbn: StatusBarNotification?) = pushActive()

    override fun onNotificationRemoved(sbn: StatusBarNotification?) = pushActive()
}
