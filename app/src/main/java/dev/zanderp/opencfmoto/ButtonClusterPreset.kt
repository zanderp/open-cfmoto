// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Context

/**
 * Recommended handlebar mappings for the common CFMOTO left-switch clusters. Physical hardware
 * differs in what AVRCP events the bike actually sends — especially long-press and double-tap —
 * so one default map is not enough. Riders pick the photo that matches their bars in
 * [ButtonMappingActivity]; [apply] writes the mapping for the **selected bike**.
 *
 * Evidence (field logs 2026-07-21 / 07-22 / 07-24 + 800MT Explore):
 *  - **BACK/SET diamond** — long-press collapses to a single tap; discrete double-tap is absent.
 *    Some units still coalesce ▲/▼ *volume* into a big jump (= ×2) — we keep that path.
 *  - **MODE/ENT** — singles + occasional Select ×2; hold on track keys when the dash sends UP.
 *  - **5-way Explore (Fn / ★ / voice center)** — ◀/▶ + start with full tap / ×2 / hold on all three.
 */
enum class ButtonClusterPreset(
    val id: String,
    val title: String,
    val summary: String,
    /**
     * When true, [MediaButtonBridge] fires singles immediately (no double-tap wait). Use for
     * clusters that cannot express discrete ×2 — waiting only adds lag.
     */
    val instantSingles: Boolean,
    /**
     * When false, two separate ◀/▲ or ▶/▼ taps never upgrade to ×2 — only a coalesced volume jump
     * does. For pods where ×2 is mapped to Back/Home, so quick scrolling can't throw the rider out.
     */
    val navTapDoubles: Boolean = true,
) {
    FIVE_WAY(
        id = "five_way",
        title = "5-way Explore (Fn / ★ / voice)",
        summary = "◀/▶ = knob (move) · ◀◀/▶▶ = D-pad ←/→ (into apps) · ★ = Select · " +
            "★★ = Back · ★ hold = Home. Singles fire immediately (no lag waiting for ×2).",
        // Eager singles: waiting for ×2 made every BT echo look like D-pad and felt "dead".
        instantSingles = true,
    ),
    MODE_ENT(
        id = "mode_ent",
        title = "MODE / ENT cluster",
        summary = "Same AA navigation as 5-way: knob · ×2 = D-pad ←/→ · ★★ = Back. " +
            "Singles are snappy; a second tap in the window still fires ×2.",
        instantSingles = true,
    ),
    BACK_SET(
        id = "back_set",
        title = "BACK / SET diamond (limited)",
        summary = "▲/▼ = knob · SET = Select · ▲ then ▼ quickly = Home (app list) · " +
            "▼ then ▲ quickly = Back. Fast ▲▲ / ▼▼ scrolling stays scrolling; only a hard " +
            "coalesced volume flick still counts as ×2.",
        instantSingles = true,
        navTapDoubles = false,
    );

    /**
     * Mapping this preset writes. Always covers all nine gestures so [ButtonMap.resetAll] + apply
     * can't leave a stale custom row behind from a previous preset.
     */
    fun mapping(): Map<ButtonGesture, ButtonAction> = when (this) {
        // Same AA nav model on both pods that can do discrete ×2.
        FIVE_WAY, MODE_ENT -> ButtonGesture.entries.associateWith { it.default }
        BACK_SET -> mapOf(
            ButtonGesture.NAV_BACK to ButtonAction.KNOB_BACK,
            ButtonGesture.NAV_FWD to ButtonAction.KNOB_FORWARD,
            ButtonGesture.SELECT_PRESS to ButtonAction.SELECT,
            ButtonGesture.NAV_BACK_LONG to ButtonAction.NONE,
            ButtonGesture.NAV_FWD_LONG to ButtonAction.NONE,
            ButtonGesture.SELECT_LONG to ButtonAction.NONE,
            ButtonGesture.SELECT_DOUBLE to ButtonAction.NONE,
            // Volume-coalesced ▲▲ / ▼▼ (jump ≥ 3) still fire these on many CFDL16 logs.
            // Discrete ×2 (if the dash ever sends it) also maps here via eager second-tap.
            ButtonGesture.NAV_BACK_DOUBLE to ButtonAction.BACK,
            ButtonGesture.NAV_FWD_DOUBLE to ButtonAction.HOME,
            // The reliable way to Back / Home on this pod: two opposite taps (focus cancels out).
            ButtonGesture.NAV_BACK_THEN_FWD to ButtonAction.HOME,
            ButtonGesture.NAV_FWD_THEN_BACK to ButtonAction.BACK,
        )
    }

    fun apply(context: Context) {
        // Clear first so gestures not listed fall back to shipped defaults (FIVE_WAY), then
        // overwrite every entry for limited presets (including NONE).
        ButtonMap.resetAll(context)
        for ((gesture, action) in mapping()) {
            ButtonMap.set(context, gesture, action)
        }
        saveActive(context, this)
        // Cluster = which physical pod map to use. Also turn handlebar→AA on so keys aren't
        // swallowed with capture off. Does NOT change touchscreen / Disable touchscreen —
        // touch dashes (800MT, …) keep touch + bars together like before.
        if (!ButtonMode.isControlAa(context)) {
            ButtonMode.set(context, true)
            MediaButtonBridge.instance?.setCaptureActive(true)
        }
    }

    companion object {
        private const val PREF = "button_cluster_preset"
        private const val KEY_ACTIVE = "active"

        fun byId(id: String?): ButtonClusterPreset? = entries.firstOrNull { it.id == id }

        fun active(context: Context): ButtonClusterPreset? =
            byId(BikeScope.getString(prefs(context), context, KEY_ACTIVE, null))

        /** True when singles should fire immediately (×2 still fires on a second tap in-window). */
        fun prefersInstantSingles(context: Context): Boolean {
            active(context)?.let { return it.instantSingles }
            // No preset → snappy defaults (same as 5-way). Waiting only helps rare custom maps.
            return true
        }

        /** False when the active preset only trusts coalesced ×2 on the ◀/▶ (▲/▼) axis. */
        fun allowsNavTapDoubles(context: Context): Boolean =
            active(context)?.navTapDoubles ?: true

        fun saveActive(context: Context, preset: ButtonClusterPreset?) {
            val p = prefs(context)
            if (preset == null) BikeScope.remove(p, context, KEY_ACTIVE)
            else BikeScope.putString(p, context, KEY_ACTIVE, preset.id)
        }

        /**
         * Drop the active cluster tag and restore shipped gesture defaults. Some bikes work best
         * with no preset selected (plain defaults + optional manual tweaks). Does not change
         * touchscreen or handlebar-capture mode.
         */
        fun clear(context: Context) {
            ButtonMap.resetAll(context)
            saveActive(context, null)
        }

        private fun prefs(context: Context) =
            context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    }
}
