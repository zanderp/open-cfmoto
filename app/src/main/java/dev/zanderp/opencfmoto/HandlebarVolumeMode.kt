// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Context

/**
 * What the bike's ▲/▼ volume rocker does while projecting:
 *   true  (default) = NAVIGATE — [MediaButtonBridge] pins the music stream and reads the rocker's
 *                     direction as dash navigation (previous/next) on whichever surface is projected
 *                     — Android Auto OR our own map (Overtake). Volume is then set elsewhere (phone
 *                     buttons / the Controls slider).
 *   false           = VOLUME — the rocker changes the phone's volume normally; nothing is pinned and
 *                     no navigation is fired. Use this if you'd rather control volume from the bike.
 *
 * Split from [ButtonMode] (which governs the track/select keys) because a rider may want the track
 * keys on AA navigation while ▲/▼ still work as plain volume. Per-bike via [BikeScope].
 */
object HandlebarVolumeMode {
    private const val PREF = "handlebar_volume_mode"
    private const val KEY = "navigate"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** True (default) = ▲/▼ navigate the dash (pins volume). False = ▲/▼ are plain phone volume. */
    fun isNavigate(context: Context): Boolean =
        BikeScope.getBoolean(prefs(context), context, KEY, true)

    fun set(context: Context, navigate: Boolean) {
        BikeScope.putBoolean(prefs(context), context, KEY, navigate)
        // Apply live if the bridge is running.
        MediaButtonBridge.instance?.refreshVolumePresencePolicy()
    }
}
