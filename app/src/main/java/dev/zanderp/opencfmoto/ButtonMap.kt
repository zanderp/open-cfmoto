// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
// Handlebar gesture → action mapping, ported from the ionutradu252/open-cfmoto fork.
package dev.zanderp.opencfmoto

import android.content.Context

/**
 * Something a handlebar gesture can be made to do. Everything here is reachable with a live Android
 * Auto session and no touchscreen.
 */
enum class ButtonAction(val id: String, val label: String) {
    NONE("none", "Do nothing"),
    KNOB_FORWARD("knobFwd", "Knob forward (next item)"),
    KNOB_BACK("knobBack", "Knob back (previous item)"),
    SELECT("select", "Select / OK"),
    BACK("back", "Back"),
    HOME("home", "Home (app list)"),
    ASSISTANT("assistant", "Assistant (voice)"),
    DPAD_UP("up", "D-pad up"),
    DPAD_DOWN("down", "D-pad down"),
    DPAD_LEFT("left", "D-pad left"),
    DPAD_RIGHT("right", "D-pad right"),
    NAV_1("nav1", "Navigate to saved place 1"),
    NAV_2("nav2", "Navigate to saved place 2"),
    NAV_3("nav3", "Navigate to saved place 3");

    /** Nav actions read better as the place's own name once one is saved. */
    fun displayLabel(context: Context): String = when (this) {
        NAV_1 -> SavedPlaces.actionLabel(context, 0)
        NAV_2 -> SavedPlaces.actionLabel(context, 1)
        NAV_3 -> SavedPlaces.actionLabel(context, 2)
        else -> label
    }

    companion object {
        fun byId(id: String?): ButtonAction? = entries.firstOrNull { it.id == id }
    }
}

/**
 * The **semantic** gestures a CFMoto handlebar can produce, regardless of which physical buttons a
 * given bike uses. Different dashes send navigation on different buttons, but they all collapse to a
 * "previous / next / select" trio, so we map by meaning and let [MediaButtonBridge] route whatever
 * the bike sends into the matching gesture. Physical clusters still differ in whether hold / ×2
 * events exist — riders pick a [ButtonClusterPreset] (BACK/SET vs MODE/ENT vs 5-way Explore) when
 * the shipped defaults don't match their pod:
 *   • **Backward / Forward** — every cluster collapses to these two directions: ◀ or ▲ = backward,
 *     ▶ or ▼ = forward (5-way track keys vs volume-style pods).
 *   • **Backward/Forward ×2** — double-tap on that axis → D-pad ← / →.
 *   • **Select / Select ×2** — OK / ★ / start: tap = Enter, ×2 = Back. The only third button that
 *     works the same on all bikes.
 *   • **Holds** — optional; many pods never send a real key-up (especially ▲/▼ volume).
 *
 * [label] is the semantic name (with the physical buttons that trigger it); [hint] explains it.
 */
enum class ButtonGesture(
    val id: String,
    val label: String,
    val hint: String,
    val default: ButtonAction,
) {
    // Universal 3-control model: single = knob, ×2 = D-pad left/right, Select = OK / ×2 Back.
    NAV_BACK("navBack", "Backward  ◀ / ▲", "◀ left, or the ▲ volume press on non-touch dashes", ButtonAction.KNOB_BACK),
    NAV_FWD("navFwd", "Forward  ▶ / ▼", "▶ right, or the ▼ volume press on non-touch dashes", ButtonAction.KNOB_FORWARD),
    SELECT_PRESS("selectPress", "Select  Enter / ★", "a quick tap of the OK / ★ start button", ButtonAction.SELECT),
    NAV_BACK_LONG("navBackLong", "Backward (hold)  ◀", "press and hold ◀ (track-key dashes only)", ButtonAction.NONE),
    NAV_FWD_LONG("navFwdLong", "Forward (hold)  ▶", "press and hold ▶ (track-key dashes only)", ButtonAction.NONE),
    SELECT_LONG("selectLong", "Select (hold)  Enter / ★", "press and hold the OK / ★ button", ButtonAction.HOME),
    NAV_BACK_DOUBLE("navBackDouble", "Backward ×2", "double-tap backward within a short window", ButtonAction.DPAD_LEFT),
    NAV_FWD_DOUBLE("navFwdDouble", "Forward ×2", "double-tap forward within a short window", ButtonAction.DPAD_RIGHT),
    SELECT_DOUBLE("selectDouble", "Select ×2", "double-tap the OK / ★ button", ButtonAction.BACK),
    // Opposite-direction pairs: the two knob steps cancel out, so focus ends where it started and
    // only the mapped action is felt. Lets 3-button pods (BACK/SET diamond) reach Back / Home.
    NAV_BACK_THEN_FWD("navBackThenFwd", "Backward → Forward  ▲▼", "tap backward, then forward right after", ButtonAction.NONE),
    NAV_FWD_THEN_BACK("navFwdThenBack", "Forward → Backward  ▼▲", "tap forward, then backward right after", ButtonAction.NONE),
}

/**
 * What each handlebar gesture does. Unset gestures fall back to [ButtonGesture.default], so
 * "reset to defaults" is just clearing the store.
 */
object ButtonMap {
    private const val PREF = "button_map"
    private const val KEY_DEFAULTS_VER = "defaults_ver"
    /** Bumped when shipping new [ButtonGesture.default] values — migrates riders still on old defaults. */
    private const val DEFAULTS_VER = 8

    fun get(context: Context, gesture: ButtonGesture): ButtonAction {
        ensureDefaultsMigrated(context)
        return ButtonAction.byId(BikeScope.getString(prefs(context), context, gesture.id, null))
            ?: gesture.default
    }

    fun set(context: Context, gesture: ButtonGesture, action: ButtonAction) {
        ensureDefaultsMigrated(context)
        BikeScope.putString(prefs(context), context, gesture.id, action.id)
    }

    /** Reset the **selected bike's** mapping to defaults (other bikes keep theirs). */
    fun resetAll(context: Context) {
        ensureDefaultsMigrated(context)
        val p = prefs(context)
        for (g in ButtonGesture.entries) BikeScope.remove(p, context, g.id)
    }

    /** True when every gesture is still on its default — used to enable/disable the reset button. */
    fun isAllDefault(context: Context): Boolean =
        ButtonGesture.entries.all { get(context, it) == it.default }

    /**
     * One-shot upgrade of shipped defaults. Clears stored values that still match a *previous*
     * default so the new ones apply; intentional custom mappings stay.
     */
    fun ensureDefaultsMigrated(context: Context) {
        val p = prefs(context)
        if (p.getInt(KEY_DEFAULTS_VER, 1) >= DEFAULTS_VER) return
        // v2 migrations (idempotent if already applied)
        clearIfStored(context, ButtonGesture.NAV_BACK_DOUBLE, ButtonAction.HOME)
        clearIfStored(context, ButtonGesture.NAV_FWD_DOUBLE, ButtonAction.BACK)
        clearIfStored(context, ButtonGesture.SELECT_DOUBLE, ButtonAction.ASSISTANT)
        val back = ButtonAction.byId(BikeScope.getString(p, context, ButtonGesture.NAV_BACK.id, null))
        val fwd = ButtonAction.byId(BikeScope.getString(p, context, ButtonGesture.NAV_FWD.id, null))
        if (back == ButtonAction.KNOB_FORWARD && fwd == ButtonAction.KNOB_BACK) {
            BikeScope.remove(p, context, ButtonGesture.NAV_BACK.id)
            BikeScope.remove(p, context, ButtonGesture.NAV_FWD.id)
        }
        // v3: Select hold → Home (was Assistant)
        clearIfStored(context, ButtonGesture.SELECT_LONG, ButtonAction.ASSISTANT)
        // v4: Forward hold → D-pad down (was Back; Select ×2 already covers Back)
        clearIfStored(context, ButtonGesture.NAV_FWD_LONG, ButtonAction.BACK)
        // v5: Backward hold → D-pad up (was Assistant)
        clearIfStored(context, ButtonGesture.NAV_BACK_LONG, ButtonAction.ASSISTANT)
        // v6: AA knob UI — singles must be knob (not D-pad ↑↓, which does nothing on these dashes).
        // Undo the bad “tap = D-pad up/down” map; holds that were ↑↓ become Do nothing.
        clearIfStored(context, ButtonGesture.NAV_BACK, ButtonAction.DPAD_UP)
        clearIfStored(context, ButtonGesture.NAV_FWD, ButtonAction.DPAD_DOWN)
        clearIfStored(context, ButtonGesture.NAV_BACK_LONG, ButtonAction.DPAD_UP)
        clearIfStored(context, ButtonGesture.NAV_FWD_LONG, ButtonAction.DPAD_DOWN)
        // v7 briefly shipped ×2 → ↑/↓; undo so defaults stay ←/→.
        clearIfStored(context, ButtonGesture.NAV_BACK_DOUBLE, ButtonAction.DPAD_UP)
        clearIfStored(context, ButtonGesture.NAV_FWD_DOUBLE, ButtonAction.DPAD_DOWN)
        p.edit().putInt(KEY_DEFAULTS_VER, DEFAULTS_VER).apply()
    }

    private fun clearIfStored(context: Context, gesture: ButtonGesture, oldDefault: ButtonAction) {
        val p = prefs(context)
        val stored = BikeScope.getString(p, context, gesture.id, null) ?: return
        if (stored == oldDefault.id) BikeScope.remove(p, context, gesture.id)
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
}
