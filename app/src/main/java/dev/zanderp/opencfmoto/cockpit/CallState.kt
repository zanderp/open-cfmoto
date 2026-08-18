// SPDX-License-Identifier: AGPL-3.0-or-later
// Call mirror — we do NOT own the telephony stack. The dialer/telecom posts a CATEGORY_CALL
// notification carrying the caller and the Answer/Hang-up PendingIntents; we read it via
// [NowPlayingListener] and re-render our own in-cockpit call card, firing those same intents so the
// rider can answer/hang up without leaving the dash. Slice 2 of the split-dash feature.
package dev.zanderp.opencfmoto.cockpit

import android.app.PendingIntent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Immutable snapshot of the current call for the UI to render. */
data class CallInfo(
    val active: Boolean = false,
    val caller: String = "",
    val status: String = "",
    val ringing: Boolean = false,
)

/**
 * Process-global holder of the active call, in the spirit of [NowPlaying].
 *
 * [CockpitNotifications] pushes the observed [CallInfo] plus the two [PendingIntent]s harvested from
 * the call notification's actions (answer / hang-up). The UI observes [state] and calls [answer] /
 * [hangup], which simply fire the corresponding intent — the dialer does the actual work. When no
 * call notification is present the state clears and both intents are dropped.
 */
object CallState {

    private val _state = MutableStateFlow(CallInfo())
    val state: StateFlow<CallInfo> = _state.asStateFlow()

    // Harvested from the call notification's actions; mutated only from the listener's scan.
    @Volatile private var answerIntent: PendingIntent? = null
    @Volatile private var hangupIntent: PendingIntent? = null

    /** Pushed by [CockpitNotifications] after each scan. Intents are refreshed before the state flips. */
    internal fun update(info: CallInfo, answer: PendingIntent?, hangup: PendingIntent?) {
        answerIntent = answer
        hangupIntent = hangup
        _state.value = info
    }

    /** Fire the dialer's "answer" PendingIntent, if we captured one. Never throws. */
    fun answer() {
        runCatching { answerIntent?.send() }
    }

    /** Fire the dialer's "hang-up/decline" PendingIntent, if we captured one. Never throws. */
    fun hangup() {
        runCatching { hangupIntent?.send() }
    }
}
