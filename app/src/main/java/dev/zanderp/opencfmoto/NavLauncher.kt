// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
// Ported from the ionutradu252/open-cfmoto fork.
package dev.zanderp.opencfmoto

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * Start Google Maps turn-by-turn to a destination. With Android Auto projecting, the route appears
 * on the dash — no interaction with a touchless dash needed.
 *
 * Shared by the "Navigate to…" box and by the handlebar [ButtonAction.NAV_1]/`2`/`3` actions. The
 * handlebar case starts an Activity while the app is in the background, which Android blocks by
 * default since 10 — see [canLaunchFromBackground].
 *
 * Prefer the maps/dir HTTPS link first: [google.navigation] often parks the full route UI on AA only
 * ("showing on car display"). maps/dir keeps the phone Maps screen populated and still hands
 * guidance to Android Auto when Maps is the AA nav app.
 */
object NavLauncher {

    private const val MAPS_PKG = "com.google.android.apps.maps"

    /**
     * Whether a nav action fired from the handlebars can actually start Maps. A foreground service
     * does NOT exempt us from the background-activity-launch block, so "Display over other apps"
     * (SYSTEM_ALERT_WINDOW) is a real requirement for the handlebar nav buttons. The in-app
     * "Navigate to…" box doesn't need it: we're on screen.
     */
    fun canLaunchFromBackground(context: Context): Boolean = Settings.canDrawOverlays(context)

    /** @return true if we handed the destination to a nav app. */
    fun navigate(context: Context, destination: String, log: (String) -> Unit): Boolean {
        val dest = destination.trim()
        if (dest.isEmpty()) {
            log("[NAV] no destination saved for that button — set one in Button mapping")
            return false
        }
        if (!canLaunchFromBackground(context)) {
            log("[NAV] warning: \"Display over other apps\" is off — Android may block this launch")
        }
        val enc = Uri.encode(dest)
        val intents = listOf(
            // Phone + AA: directions UI on the phone, navigate action also feeds Android Auto.
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse(
                    "https://www.google.com/maps/dir/?api=1&destination=$enc" +
                        "&travelmode=driving&dir_action=navigate",
                ),
            ).setPackage(MAPS_PKG),
            Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$enc&mode=d"))
                .setPackage(MAPS_PKG),
            Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$enc")).setPackage(MAPS_PKG),
            Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$enc")),
        )
        return startFirst(context, intents, dest, log)
    }

    /**
     * Navigate to lat/lng. Query is bare "lat,lon" (Maps ignores "lat,lon (Label)" and opens empty).
     * Label is only used in the geo: fallback as the pin title.
     */
    fun navigateLatLon(
        context: Context,
        lat: Double,
        lon: Double,
        label: String? = null,
        log: (String) -> Unit,
    ): Boolean {
        val coord = String.format(java.util.Locale.US, "%.6f,%.6f", lat, lon)
        val pin = if (!label.isNullOrBlank()) {
            Uri.encode("$coord (${label.trim()})")
        } else {
            Uri.encode(coord)
        }
        val intents = listOf(
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse(
                    "https://www.google.com/maps/dir/?api=1&destination=$coord" +
                        "&travelmode=driving&dir_action=navigate",
                ),
            ).setPackage(MAPS_PKG),
            Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$coord&mode=d"))
                .setPackage(MAPS_PKG),
            Intent(Intent.ACTION_VIEW, Uri.parse("geo:$coord?q=$pin")).setPackage(MAPS_PKG),
            Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$coord")),
        )
        return startFirst(context, intents, label?.takeIf { it.isNotBlank() } ?: coord, log)
    }

    private fun startFirst(
        context: Context,
        intents: List<Intent>,
        label: String,
        log: (String) -> Unit,
    ): Boolean {
        for (intent in intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                log("[NAV] → navigating to \"$label\" — phone Maps + dash (AA)")
                return true
            } catch (_: Exception) { /* try next */ }
        }
        log("[NAV] navigation failed — is Google Maps installed?")
        return false
    }

    /**
     * Start Waze turn-by-turn to a destination. Waze reaches the dash via the mirror (single-app
     * MediaProjection) — it has no Android Auto self-projection like the built-in map. Deep-link docs:
     * https://developers.google.com/waze/deeplinks . We ask for the app explicitly (com.waze) so the
     * https link doesn't bounce to a browser; the waze:// scheme is the fallback.
     * @return true if we handed the destination to Waze.
     */
    fun navigateWaze(context: Context, destination: String, log: (String) -> Unit): Boolean {
        val dest = destination.trim()
        if (dest.isEmpty()) {
            log("[NAV] no destination for Waze")
            return false
        }
        val enc = Uri.encode(dest)
        val intents = listOf(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://waze.com/ul?q=$enc&navigate=yes"))
                .setPackage(WAZE_PKG),
            Intent(Intent.ACTION_VIEW, Uri.parse("waze://?q=$enc&navigate=yes")),
        )
        return startFirst(context, intents, dest, log)
    }

    /** Just open Waze (no destination) — used when the provider is Waze but no route is set yet. */
    fun openWaze(context: Context, log: (String) -> Unit): Boolean {
        val intents = listOf(
            context.packageManager.getLaunchIntentForPackage(WAZE_PKG),
            Intent(Intent.ACTION_VIEW, Uri.parse("waze://")),
        ).filterNotNull()
        return startFirst(context, intents, "Waze", log)
    }

    private const val WAZE_PKG = "com.waze"

    /** Send the user to the system page where "Display over other apps" can be granted. */
    fun overlayPermissionIntent(context: Context) =
        Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        )
}
