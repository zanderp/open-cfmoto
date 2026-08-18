// SPDX-License-Identifier: AGPL-3.0-or-later
// The distiller that turns the raw active-notification set into the cockpit's nav + call panels.
// [NowPlayingListener] hands us its `activeNotifications` on every connect/post/remove; we scan for
// (a) an ONGOING Maps/Waze turn-by-turn notification and (b) an active call notification, and push
// the distilled snapshots into [NavGuidance] / [CallState]. Purely a reader: we never post, cancel or
// mutate the source notifications — only re-render them our way. Defensive by contract: nothing here
// may throw (a listener callback that crashes tears down the whole notification grant).
package dev.zanderp.opencfmoto.cockpit

import android.app.Notification
import android.app.PendingIntent
import android.service.notification.StatusBarNotification

/**
 * Stateless scanner invoked by the notification listener. All entry points swallow their own
 * exceptions so a malformed notification can never break the media grant the listener anchors.
 */
object CockpitNotifications {

    // Packages whose *ongoing* notification we treat as turn-by-turn guidance.
    private const val PKG_MAPS = "com.google.android.apps.maps"
    private const val PKG_WAZE = "com.waze"

    // Dialer/telecom packages — a fallback identity for call notifications that omit CATEGORY_CALL.
    private val DIALER_PACKAGES = setOf(
        "com.android.server.telecom",
        "com.google.android.dialer",
        "com.android.dialer",
        "com.samsung.android.dialer",
        "com.android.incallui",
    )

    // Action-label matchers (ES + EN), ANCHORED with \b so a "Send" action can't match "end" and a
    // "Change" action can't match "hang" (adversarial review finding 3). "answer/contestar" ⇒ ringing.
    private val ANSWER_RE = Regex("\\b(contestar|responder|answer|aceptar|accept)\\b", RegexOption.IGNORE_CASE)
    private val HANGUP_RE = Regex(
        "\\b(colgar|hang ?up|finalizar|terminar|rechazar|declinar|decline|reject|end call|end)\\b",
        RegexOption.IGNORE_CASE,
    )

    // Ongoing Maps/Waze cards that are NOT turn-by-turn (live-location share, the offline-area
    // download). These carry a title, so they'd masquerade as a maneuver (finding 4). Kept narrow on
    // purpose: bare "offline"/"sin conexión"/"actualizando" also appear in REAL offline nav and
    // reroute cards, so we only match the unambiguous non-nav phrasings (the download itself is also
    // caught by the CATEGORY_PROGRESS reject above).
    private val NAV_EXCLUDE = Regex(
        "compartiendo ubicaci|sharing location|ubicaci.n en tiempo real|live location|" +
            "descargando|downloading|offline area",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Re-scan the active notifications and refresh [NavGuidance] + [CallState]. Both scans are
     * isolated so a failure in one still lets the other run; each also resets its target to
     * "inactive" when nothing matches.
     */
    fun refresh(sbns: Array<StatusBarNotification>) {
        runCatching { scanNav(sbns) }
        runCatching { scanCall(sbns) }
    }

    // --- navigation ------------------------------------------------------------------------------

    /**
     * Publish the most-recent ONGOING Maps/Waze turn-by-turn card as guidance.
     *
     * Filter: package ∈ {maps, waze}, FLAG_ONGOING_EVENT|FLAG_FOREGROUND_SERVICE, not FLAG_AUTO_CANCEL
     * (rejects one-off review/place cards), not CATEGORY_PROGRESS (rejects the offline-area download),
     * and — unless the card explicitly declares CATEGORY_NAVIGATION — not matching [NAV_EXCLUDE]
     * (rejects live-location share). Among survivors the highest postTime wins, so the result is
     * deterministic regardless of the array's order (finding 5).
     */
    private fun scanNav(sbns: Array<StatusBarNotification>) {
        var best: NavState? = null
        var bestAt = Long.MIN_VALUE
        for (sbn in sbns) {
            val source = when (sbn.packageName) {
                PKG_MAPS -> "Google Maps"
                PKG_WAZE -> "Waze"
                else -> continue
            }
            val n = sbn.notification ?: continue
            val flags = n.flags
            val ongoing = (flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE)) != 0
            if (!ongoing) continue
            if ((flags and Notification.FLAG_AUTO_CANCEL) != 0) continue // one-off review/place card
            if (n.category == Notification.CATEGORY_PROGRESS) continue   // offline-area download

            val extras = n.extras ?: continue
            val instruction = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
            val detail = (extras.getCharSequence(Notification.EXTRA_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_SUB_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT))
                ?.toString()?.trim().orEmpty()
            if (instruction.isBlank() && detail.isBlank()) continue

            val isNavCategory = n.category == Notification.CATEGORY_NAVIGATION
            if (!isNavCategory && NAV_EXCLUDE.containsMatchIn("$instruction $detail")) continue

            if (sbn.postTime >= bestAt) {
                bestAt = sbn.postTime
                best = NavState(active = true, instruction = instruction, detail = detail, source = source)
            }
        }
        NavGuidance.update(best ?: NavState(active = false))
    }

    // --- calls -----------------------------------------------------------------------------------

    /**
     * Publish the active call and harvest its answer / hang-up PendingIntents.
     *
     * Identity: `category == CATEGORY_CALL` is trusted (compliant dialers set it only on live calls);
     * the package fallback additionally requires the card to be ONGOING *and* to carry a real call
     * action, so a missed-call / voicemail / call-screening card from a dialer package can't
     * masquerade as a live call (finding 2). Among matches a ringing call wins, else the most-recent
     * (finding 5). `ringing` = an answer-labelled action is present (an active call has none).
     */
    private fun scanCall(sbns: Array<StatusBarNotification>) {
        var best: CallCandidate? = null
        for (sbn in sbns) {
            val n = sbn.notification ?: continue
            val pkg = sbn.packageName ?: ""

            var ringing = false
            var answerIntent: PendingIntent? = null
            var hangupIntent: PendingIntent? = null
            var hasCallAction = false
            for (raw in (n.actions ?: emptyArray())) {
                val title = raw?.title?.toString() ?: continue
                when {
                    ANSWER_RE.containsMatchIn(title) -> {
                        ringing = true
                        hasCallAction = true
                        if (answerIntent == null) answerIntent = raw.actionIntent
                    }
                    HANGUP_RE.containsMatchIn(title) -> {
                        hasCallAction = true
                        if (hangupIntent == null) hangupIntent = raw.actionIntent
                    }
                }
            }

            val ongoing = (n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE)) != 0
            val isCall = n.category == Notification.CATEGORY_CALL ||
                (pkg in DIALER_PACKAGES && ongoing && hasCallAction)
            if (!isCall) continue

            val extras = n.extras
            val caller = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
            val status = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
            best = pickCall(
                best,
                CallCandidate(
                    info = CallInfo(active = true, caller = caller, status = status, ringing = ringing),
                    answer = answerIntent,
                    hangup = hangupIntent,
                    at = sbn.postTime,
                ),
            )
        }
        val b = best
        if (b != null) CallState.update(b.info, b.answer, b.hangup)
        else CallState.update(CallInfo(active = false), null, null)
    }

    /** Ringing beats non-ringing; within the same ring state the most-recent notification wins. */
    private fun pickCall(a: CallCandidate?, b: CallCandidate): CallCandidate {
        if (a == null) return b
        if (b.info.ringing != a.info.ringing) return if (b.info.ringing) b else a
        return if (b.at >= a.at) b else a
    }

    private class CallCandidate(
        val info: CallInfo,
        val answer: PendingIntent?,
        val hangup: PendingIntent?,
        val at: Long,
    )
}
