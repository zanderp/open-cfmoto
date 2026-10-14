// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

/**
 * Spots a backward tap followed quickly by a forward tap (or the reverse) and reports the
 * matching combo gesture ([ButtonGesture.NAV_BACK_THEN_FWD] / [ButtonGesture.NAV_FWD_THEN_BACK]).
 *
 * Pods like the BACK/SET diamond only send ▲ / ▼ / SET to the phone — no hold, no reliable ×2 —
 * so Back and Home have nowhere to live. Both singles still run as knob steps (they cancel out),
 * then the combo runs its own action. Pure state machine so it can be unit-tested.
 */
class ButtonCombo(private val windowMs: Long = DEFAULT_WINDOW_MS) {
    private var last: ButtonGesture? = null
    private var lastAt = 0L

    /** Feed every committed single tap. Returns the combo gesture to run, or null. */
    fun onSingle(gesture: ButtonGesture, nowMs: Long): ButtonGesture? {
        val prev = last
        val combo = if (prev != null && nowMs - lastAt in 0..windowMs) comboFor(prev, gesture) else null
        if (combo != null) {
            // Consume both taps so ▲▼▲ doesn't fire a second combo off the shared ▼.
            reset()
        } else {
            last = gesture
            lastAt = nowMs
        }
        return combo
    }

    fun reset() {
        last = null
        lastAt = 0L
    }

    companion object {
        /** Tight on purpose: a slow overshoot-and-correct while scrolling must stay two steps. */
        const val DEFAULT_WINDOW_MS = 400L

        fun comboFor(first: ButtonGesture, second: ButtonGesture): ButtonGesture? = when {
            first == ButtonGesture.NAV_BACK && second == ButtonGesture.NAV_FWD -> ButtonGesture.NAV_BACK_THEN_FWD
            first == ButtonGesture.NAV_FWD && second == ButtonGesture.NAV_BACK -> ButtonGesture.NAV_FWD_THEN_BACK
            else -> null
        }
    }
}
