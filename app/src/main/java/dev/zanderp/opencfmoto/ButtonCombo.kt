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
class ButtonCombo {
    private var last: ButtonGesture? = null
    private var lastAt = 0L

    /**
     * Feed every committed single tap. Returns the combo gesture to run, or null. [windowMs] is
     * read per press so Setup → Double-tap delay applies live (slow dashes need a wider window).
     */
    fun onSingle(gesture: ButtonGesture, nowMs: Long, windowMs: Long = MIN_WINDOW_MS): ButtonGesture? {
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
        /**
         * Floor for the pair window. Kept tight-ish so a slow overshoot-and-correct while
         * scrolling stays two steps; riders on slow dashes widen it via Double-tap delay.
         */
        const val MIN_WINDOW_MS = 400L

        /** Pair window for a given Double-tap delay setting (never below [MIN_WINDOW_MS]). */
        fun windowFor(doubleTapMs: Long): Long = maxOf(MIN_WINDOW_MS, doubleTapMs)

        fun comboFor(first: ButtonGesture, second: ButtonGesture): ButtonGesture? = when {
            first == ButtonGesture.NAV_BACK && second == ButtonGesture.NAV_FWD -> ButtonGesture.NAV_BACK_THEN_FWD
            first == ButtonGesture.NAV_FWD && second == ButtonGesture.NAV_BACK -> ButtonGesture.NAV_FWD_THEN_BACK
            else -> null
        }
    }
}
