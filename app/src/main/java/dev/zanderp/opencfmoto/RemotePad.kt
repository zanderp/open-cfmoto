// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Context
import android.view.InputDevice
import android.view.KeyEvent

/**
 * Pair a Bluetooth gamepad / ring / keypad and treat its keys as handlebar gestures.
 * AVRCP remotes already reach [MediaButtonBridge] via MediaSession. This path is HID
 * (D-pad / BUTTON_A / media keys) captured from a focused activity, or from anywhere once
 * [RemoteKeyService] is switched on. Remote presses drive AA whatever [ButtonMode] says.
 */
object RemotePad {
    private const val PREFS = "opencfmoto_remote_pad"
    private const val KEY_ON = "remote_pad_on"
    private const val KEY_MAP = "remote_pad_map"

    @Volatile var teachListener: ((KeyEvent) -> Boolean)? = null

    fun enabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_ON, true)

    fun setEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_ON, on).apply()
    }

    /** Consume a HID key if it should stand in for a handlebar press. */
    fun consume(ctx: Context, event: KeyEvent): Boolean {
        teachListener?.let { if (it(event)) return true }
        if (!enabled(ctx)) return false
        if (!isExternalPad(event)) return false
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return false
        if (event.repeatCount > 0 && event.action == KeyEvent.ACTION_DOWN) return true
        val action = dpadAction(event.keyCode)
        val gesture = if (action == null) gestureFor(ctx, event.keyCode) else null
        if (action == null && gesture == null) return false
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            if (action != null) MediaButtonBridge.injectAction(action)
            else if (gesture != null) MediaButtonBridge.injectGesture(gesture)
        }
        return true
    }

    fun gestureFor(ctx: Context, keyCode: Int): ButtonGesture? {
        val custom = loadMap(ctx)[keyCode]
        if (custom != null) return custom
        return defaultGesture(keyCode)
    }

    /**
     * A taught gesture for a media key that reached us via MediaSession (phone locked / app in the
     * background). Only real HID remotes match — the bike's AVRCP keys arrive on a virtual device,
     * so they keep their normal handlebar meaning. Untaught keys return null and also fall through.
     */
    fun taughtMediaGesture(ctx: Context, event: KeyEvent): ButtonGesture? {
        if (!enabled(ctx) || !isExternalPad(event)) return null
        return loadMap(ctx)[event.keyCode]
    }

    /** Remote-only mode: every remote media key is ours — taught, else [remoteGesture] defaults. */
    fun remoteMediaGesture(ctx: Context, event: KeyEvent): ButtonGesture? {
        if (!enabled(ctx) || !isExternalPad(event)) return null
        return remoteGesture(ctx, event.keyCode)
    }

    fun teach(ctx: Context, keyCode: Int, gesture: ButtonGesture) {
        val map = loadMap(ctx).toMutableMap()
        map[keyCode] = gesture
        saveMap(ctx, map)
    }

    fun clearMap(ctx: Context) {
        prefs(ctx).edit().remove(KEY_MAP).apply()
    }

    fun dpadAction(keyCode: Int): ButtonAction? = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> ButtonAction.DPAD_UP
        KeyEvent.KEYCODE_DPAD_DOWN -> ButtonAction.DPAD_DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> ButtonAction.DPAD_LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> ButtonAction.DPAD_RIGHT
        else -> null
    }

    /**
     * What a remote key does when [RemoteKeyService] sees it first: taught, then pad defaults, then
     * plain media keys as the bike's track/select keys (MediaSession never sees them in that case).
     */
    fun remoteGesture(ctx: Context, keyCode: Int): ButtonGesture? =
        gestureFor(ctx, keyCode) ?: when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_NEXT -> ButtonGesture.NAV_FWD
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> ButtonGesture.NAV_BACK
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK -> ButtonGesture.SELECT_PRESS
            else -> null
        }

    /** The hold variant of a tap gesture, for remotes that send a real key-down → key-up. */
    fun holdGesture(gesture: ButtonGesture): ButtonGesture? = when (gesture) {
        ButtonGesture.NAV_BACK -> ButtonGesture.NAV_BACK_LONG
        ButtonGesture.NAV_FWD -> ButtonGesture.NAV_FWD_LONG
        ButtonGesture.SELECT_PRESS -> ButtonGesture.SELECT_LONG
        else -> null
    }

    fun defaultGesture(keyCode: Int): ButtonGesture? = when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_Y,
        KeyEvent.KEYCODE_BUTTON_L1,
        KeyEvent.KEYCODE_BUTTON_THUMBL -> ButtonGesture.NAV_BACK
        KeyEvent.KEYCODE_BUTTON_R1,
        KeyEvent.KEYCODE_BUTTON_THUMBR -> ButtonGesture.NAV_FWD
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_BUTTON_A,
        KeyEvent.KEYCODE_BUTTON_START,
        KeyEvent.KEYCODE_ENTER -> ButtonGesture.SELECT_PRESS
        KeyEvent.KEYCODE_BUTTON_B,
        KeyEvent.KEYCODE_BACK -> ButtonGesture.SELECT_DOUBLE
        KeyEvent.KEYCODE_BUTTON_X,
        KeyEvent.KEYCODE_HOME -> ButtonGesture.SELECT_LONG
        else -> null
    }

    fun isExternalPad(event: KeyEvent): Boolean {
        val dev = event.device ?: return false
        // Not the phone's own volume / power keys — those are built in, not external.
        if (dev.isVirtual || !dev.isExternal) return false
        val src = event.source
        return src and InputDevice.SOURCE_GAMEPAD != 0 ||
            src and InputDevice.SOURCE_DPAD != 0 ||
            src and InputDevice.SOURCE_JOYSTICK != 0 ||
            (src and InputDevice.SOURCE_KEYBOARD != 0 && !dev.isVirtual)
    }

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun loadMap(ctx: Context): Map<Int, ButtonGesture> {
        val raw = prefs(ctx).getString(KEY_MAP, null) ?: return emptyMap()
        val out = mutableMapOf<Int, ButtonGesture>()
        raw.split(';').forEach { part ->
            val bits = part.split('=')
            if (bits.size != 2) return@forEach
            val code = bits[0].toIntOrNull() ?: return@forEach
            val g = ButtonGesture.entries.firstOrNull { it.id == bits[1] } ?: return@forEach
            out[code] = g
        }
        return out
    }

    private fun saveMap(ctx: Context, map: Map<Int, ButtonGesture>) {
        val raw = map.entries.joinToString(";") { "${it.key}=${it.value.id}" }
        prefs(ctx).edit().putString(KEY_MAP, raw).apply()
    }
}
