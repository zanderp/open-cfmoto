// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.view.KeyEvent
import androidx.core.app.NotificationManagerCompat

/**
 * Remote-only mode: with the screen off, a Bluetooth remote's media keys go straight to whichever
 * app owns media buttons — so [MediaButtonBridge] has to own them, and must then hand every
 * non-remote key (Cardo / headset / AVRCP) on to the music app. Finding that app's session needs
 * notification access, which is all this listener exists for; it never reads a notification.
 */
class MediaKeyForwarder : NotificationListenerService() {

    override fun onListenerConnected() {
        LogBus.log("[REMOTE] music pass-through access on")
        // Finding media sessions only needs the access *granted*, not a live listener — so stop
        // Android delivering us any notifications at all. Access stays granted.
        requestUnbind()
        // The listener binds before the settings toggle lands in Settings.Secure, so [hasAccess]
        // still reads false right now — re-check once it has.
        Handler(Looper.getMainLooper()).postDelayed({ MediaButtonBridge.instance?.refreshMode() }, 1_000)
    }

    override fun onListenerDisconnected() {
        // Fires right after our own requestUnbind(); a real revoke is caught by hasAccess().
        MediaButtonBridge.instance?.refreshMode()
    }

    companion object {
        fun hasAccess(ctx: Context): Boolean =
            ctx.packageName in NotificationManagerCompat.getEnabledListenerPackages(ctx)

        /** Send [event] to the playing (else most recent) other media app. False if there is none. */
        fun forward(ctx: Context, event: KeyEvent): Boolean {
            return try {
                val target = musicApp(ctx) ?: return false
                target.dispatchMediaButtonEvent(event)
            } catch (e: Exception) {
                LogBus.log("[REMOTE] music pass-through failed: $e")
                false
            }
        }

        /**
         * The music app's real state, for our own session to report. Headsets pick PLAY vs PAUSE
         * from the state of the media-button owner — us — and a Cardo re-sends PAUSE every 2 s
         * while we still say PLAYING. Null when there is no music app.
         */
        fun musicState(ctx: Context): Int? = try {
            musicApp(ctx)?.playbackState?.state
        } catch (_: Exception) {
            null
        }

        private fun musicApp(ctx: Context): MediaController? {
            val msm = ctx.getSystemService(MediaSessionManager::class.java)
            val sessions = msm.getActiveSessions(ComponentName(ctx, MediaKeyForwarder::class.java))
                .filter { it.packageName != ctx.packageName }
            return sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                ?: sessions.firstOrNull()
        }
    }
}
