// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Background capture for a Bluetooth remote ([RemotePad]) — the only way to see its keys while
 * OpenCfMoto isn't in front. Many cheap remotes turn holds / double-taps into VOLUME_* or HOME,
 * which the system swallows before any app or MediaSession sees them; an accessibility key filter
 * runs first. It also means the remote drives Android Auto without handlebar capture
 * ([ButtonMode]) being on, so the bike's own buttons can stay on music.
 *
 * Only keys from an external, non-virtual input device are touched, and only while the
 * [MediaButtonBridge] is up (AA / Map session) — otherwise the remote behaves normally.
 */
class RemoteKeyService : AccessibilityService() {

    /** Key-down time per key code, for tap vs hold on gestures that have a hold variant. */
    private val downAt = mutableMapOf<Int, Long>()
    /** Last key-up per key code, to fold a remote's synthetic hold stream into one press. */
    private val upAt = mutableMapOf<Int, Long>()

    override fun onServiceConnected() {
        LogBus.log("[REMOTE] background key service on")
        MediaButtonBridge.instance?.refreshMode()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        LogBus.log("[REMOTE] background key service off")
        MediaButtonBridge.instance?.refreshMode()
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!RemotePad.isExternalPad(event)) return false
        RemotePad.teachListener?.let { return it(event) }
        if (!RemotePad.enabled(this) || MediaButtonBridge.instance == null) return false

        val code = event.keyCode
        val action = RemotePad.dpadAction(code)
        val gesture = if (action == null) RemotePad.remoteGesture(this, code) else null
        if (action == null && gesture == null) return false
        val hold = gesture?.let(RemotePad::holdGesture)?.takeIf {
            ButtonTimingPrefs.holdsEnabled(this) && ButtonMap.get(this, it) != ButtonAction.NONE
        }

        // Remotes that turn a held track key into VOLUME_* send a fresh tap every ~300 ms rather than
        // key-repeat. Treat that stream as one press: swallow taps that follow the last too closely.
        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN) {
            val last = upAt[code]
            if (event.action == KeyEvent.ACTION_UP) upAt[code] = event.eventTime
            if (last != null && event.eventTime - last < HOLD_STREAM_GAP_MS) return true
        }

        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) {
                // No hold variant mapped → fire on press for snappiness; otherwise wait for release.
                if (hold == null) fire(event, action, gesture) else downAt[code] = event.eventTime
            }
            KeyEvent.ACTION_UP -> if (hold != null) {
                val down = downAt.remove(code) ?: return true
                val long = event.eventTime - down >= ButtonTimingPrefs.longPressMs(this)
                fire(event, null, if (long) hold else gesture)
            }
        }
        return true
    }

    private fun fire(event: KeyEvent, action: ButtonAction?, gesture: ButtonGesture?) {
        val what = action?.label ?: gesture?.label ?: return
        LogBus.log("[REMOTE] ${KeyEvent.keyCodeToString(event.keyCode)} (${event.device?.name}) → $what")
        MediaButtonBridge.remotePressed()
        if (action != null) MediaButtonBridge.injectAction(action)
        else if (gesture != null) MediaButtonBridge.injectGesture(gesture)
    }

    companion object {
        /** SmartRemote repeats every 300–340 ms while held; a deliberate second press is slower. */
        private const val HOLD_STREAM_GAP_MS = 450L

        /** True when the rider has switched the service on in Android's accessibility settings. */
        fun isEnabled(ctx: Context): Boolean {
            val me = ComponentName(ctx, RemoteKeyService::class.java)
            val on = Settings.Secure.getString(
                ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            return on.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}
