// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.LocationManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import java.util.Locale

/** A best-effort guess of where the rider is: an ISO-3166 alpha-2 country + an optional city/region. */
data class CountryHint(val iso: String, val city: String?)

/**
 * Best-effort detection of the rider's country, to seed the "Suggested for you / Your region" shortcut
 * on the Mapsforge offline-maps screen. Deliberately CHEAP and SAFE:
 *  - never adds a mandatory permission (GPS is used only if location is ALREADY granted; we never prompt),
 *  - never crashes (everything is `runCatching`; a total failure just returns null → no suggestion),
 *  - never blocks the UI meaningfully (the caller runs [detect] off the main thread — the Geocoder step
 *    does blocking I/O).
 *
 * First hit wins, in this order:
 *  1. Last known GPS fix reverse-geocoded via [Geocoder] → country (+ city/admin-area) — ONLY if
 *     location permission is already held.
 *  2. [TelephonyManager.getSimCountryIso] / [TelephonyManager.getNetworkCountryIso].
 *  3. The active [Locale] country ([Context.getResources] configuration) — always available offline.
 */
object MapCountryHint {

    fun detect(ctx: Context): CountryHint? {
        gpsCountry(ctx)?.let { return it }
        simCountry(ctx)?.let { return CountryHint(it, null) }
        localeCountry(ctx)?.let { return CountryHint(it, null) }
        return null
    }

    /** Reverse-geocode the last known fix, but only when location permission is already granted. */
    @Suppress("DEPRECATION") // sync getFromLocation is the only cross-version overload; we're off-main.
    private fun gpsCountry(ctx: Context): CountryHint? = runCatching {
        val granted =
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        if (!granted) return null
        if (!Geocoder.isPresent()) return null

        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val loc = runCatching { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull()
            ?: runCatching { lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }.getOrNull()
            ?: return null

        val addresses = Geocoder(ctx.applicationContext, Locale.ENGLISH)
            .getFromLocation(loc.latitude, loc.longitude, 1)
        val a = addresses?.firstOrNull() ?: return null
        val iso = a.countryCode?.trim()?.uppercase(Locale.US)
        if (iso.isNullOrBlank() || iso.length != 2) return null
        val city = (a.locality ?: a.subAdminArea ?: a.adminArea)?.trim()?.ifBlank { null }
        CountryHint(iso, city)
    }.getOrNull()

    /** SIM country first (where the account lives), then the network country (where the phone is). */
    private fun simCountry(ctx: Context): String? = runCatching {
        val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return null
        val iso = tm.simCountryIso.ifBlank { tm.networkCountryIso }.trim().uppercase(Locale.US)
        iso.takeIf { it.length == 2 }
    }.getOrNull()

    /** The active configuration locale's country — present even fully offline. */
    private fun localeCountry(ctx: Context): String? = runCatching {
        val locale = ctx.resources.configuration.locales.get(0) ?: Locale.getDefault()
        locale.country.trim().uppercase(Locale.US).takeIf { it.length == 2 }
    }.getOrNull()
}
