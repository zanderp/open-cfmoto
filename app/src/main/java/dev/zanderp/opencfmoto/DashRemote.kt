// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

/**
 * Bridge so the phone can drive the search of the dash that's currently projected to the bike
 * HUD (both run in the same app process). Like Google Maps' "type on your phone" for the car:
 * the rider taps Search on the bike screen, types with the phone keyboard, results show on the dash.
 *
 * The active [GpxDashUi] registers a query handler while bound; [MainActivity] registers the
 * keyboard/focus handler so a bike tap can raise the phone IME.
 */
object DashRemote {
    @Volatile private var handler: ((String) -> Unit)? = null
    @Volatile private var navHandler: ((MapPlace) -> Unit)? = null
    @Volatile private var typeOnPhone: (() -> Unit)? = null
    @Volatile private var themeHandler: ((Boolean) -> Unit)? = null
    @Volatile private var endHandler: (() -> Unit)? = null
    @Volatile private var panelHandler: ((Boolean) -> Unit)? = null

    /**
     * The Map|Panel mode the phone last chose (true = Panel, i.e. the map + now-playing split). A dash
     * reads this on bind so it starts in the right mode even when the rider flipped the toggle BEFORE
     * this dash began projecting.
     */
    @Volatile var panelMode: Boolean = false
        private set

    /** True when a dash is bound and can receive a search (e.g. projected to the bike). */
    val isAvailable: Boolean get() = handler != null

    fun setHandler(h: ((String) -> Unit)?) {
        handler = h
    }

    fun setNavHandler(h: ((MapPlace) -> Unit)?) {
        navHandler = h
    }

    fun setTypeOnPhoneHandler(h: (() -> Unit)?) {
        typeOnPhone = h
    }

    fun setThemeHandler(h: ((Boolean) -> Unit)?) {
        themeHandler = h
    }

    fun setEndHandler(h: (() -> Unit)?) {
        endHandler = h
    }

    fun setPanelHandler(h: ((Boolean) -> Unit)?) {
        panelHandler = h
    }

    /** Send a search query to the active dash. Returns false if no dash is listening. */
    fun submit(query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return false
        val h = handler ?: return false
        h(q)
        return true
    }

    /**
     * Re-target the active (already-projected) dash to a destination — routes + turn-by-turn,
     * with NO PXC reconnect. Returns false if no dash is listening.
     */
    fun navigateTo(place: MapPlace): Boolean {
        val h = navHandler ?: return false
        h(place)
        return true
    }

    /** Ask the phone UI to focus the destination field and show the keyboard. */
    fun requestTypeOnPhone(): Boolean {
        val h = typeOnPhone ?: return false
        h()
        return true
    }

    /**
     * End the ride/route on the active (already-projected) dash: it drops the route, stops
     * turn-by-turn voice, and returns to a clean free-ride map — with NO PXC reconnect. Called when
     * the rider ends the trip on the phone so the bike dash doesn't keep showing a stale route (and
     * keep speaking guidance). Returns false if no dash is listening.
     */
    fun endNavigation(): Boolean {
        val h = endHandler ?: return false
        h()
        return true
    }

    /**
     * Choose the dash's Map|Panel mode (Panel = the map + now-playing music strip). Remembers it in
     * [panelMode] for a dash that binds later, and flips the LIVE (already-projected) dash in place —
     * no PXC reconnect. Returns false if no dash is listening (the choice still applies on next bind).
     */
    fun applyPanelMode(panel: Boolean): Boolean {
        panelMode = panel
        val h = panelHandler ?: return false
        h(panel)
        return true
    }

    /**
     * Flip the active (already-projected) dash's map to day/night IN PLACE — no PXC reconnect — so the
     * on-map day/night/auto toggle on the phone also flips the bike screen live. Returns false if no
     * dash is listening (nothing projecting); the shared [NightPrefs] write still applies on next start.
     */
    fun applyTheme(night: Boolean): Boolean {
        val h = themeHandler ?: return false
        h(night)
        return true
    }
}
