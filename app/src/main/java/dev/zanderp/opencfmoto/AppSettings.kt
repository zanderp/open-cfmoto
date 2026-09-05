// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Context

/**
 * Small on/off app preferences (kept apart from the video-tuning [VideoPrefs]).
 *
 *  - [autoConnect]  — on launch, if a bike is paired and its Wi-Fi is in range, start connecting
 *    automatically instead of waiting for a tap. Default on; the rider can turn it off.
 *  - [autoRecovery] — the watchdog ([AndroidAutoService]) restarts the bike link if projection
 *    stalls or the dash drops, without a manual Stop/Start. Default on.
 *  - [forceNonTouch] — never advertise a touchscreen to Android Auto (focus/knob UI). Use when
 *    handlebar buttons do nothing because a touch profile was selected for a non-touch dash.
 *  - [forceTouch] — always advertise a touchscreen to Android Auto (touch UI). Use on bikes that
 *    default to focus/knob (e.g. 1000 MT‑X) when the rider wants panel touch. Mutually exclusive
 *    with [forceNonTouch].
 */
object AppSettings {
    private const val PREFS = "opencfmoto_bike"
    private const val KEY_AUTO_CONNECT = "auto_connect"
    private const val KEY_AUTO_RECOVERY = "auto_recovery"
    private const val KEY_LOG_TRIPS = "log_trips"
    private const val KEY_FORCE_NON_TOUCH = "force_non_touch"
    private const val KEY_FORCE_TOUCH = "force_touch"
    private const val KEY_INCLUDE_SECRETS = "include_secrets_in_logs"
    private const val KEY_TRANSPORT = "wifi_transport"
    private const val KEY_ANON_TELEMETRY = "anonymous_telemetry"
    private const val KEY_BT_CLOCK = "bluetooth_clock_sync"
    private const val KEY_CLOCK_LAB_QUERY = "clock_lab_query"
    private const val KEY_CLOCK_LAB_TIMESYNC = "clock_lab_timesync"
    private const val KEY_KEEP_WIFI = "keep_wifi_after_disconnect"
    private const val KEY_BT_TRIGGER_MAC = "bt_trigger_mac"
    private const val KEY_BT_TRIGGER_NAME = "bt_trigger_name"
    private const val KEY_AUTO_VOLUME_ON = "auto_volume_on"
    private const val KEY_AUTO_VOLUME_MODE = "auto_volume_mode"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun autoConnect(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_AUTO_CONNECT, true)
    fun setAutoConnect(ctx: Context, on: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_AUTO_CONNECT, on).apply()

    fun autoRecovery(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_AUTO_RECOVERY, true)
    fun setAutoRecovery(ctx: Context, on: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_AUTO_RECOVERY, on).apply()

    fun logTrips(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_LOG_TRIPS, true)
    fun setLogTrips(ctx: Context, on: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_LOG_TRIPS, on).apply()

    fun forceNonTouch(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_FORCE_NON_TOUCH, false)
    fun setForceNonTouch(ctx: Context, on: Boolean) {
        val e = prefs(ctx).edit().putBoolean(KEY_FORCE_NON_TOUCH, on)
        if (on) e.putBoolean(KEY_FORCE_TOUCH, false)
        e.apply()
        BikeProfileHolder.forceNonTouch = on
        if (on) BikeProfileHolder.forceTouch = false
    }

    fun forceTouch(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_FORCE_TOUCH, false)
    fun setForceTouch(ctx: Context, on: Boolean) {
        val e = prefs(ctx).edit().putBoolean(KEY_FORCE_TOUCH, on)
        if (on) e.putBoolean(KEY_FORCE_NON_TOUCH, false)
        e.apply()
        BikeProfileHolder.forceTouch = on
        if (on) BikeProfileHolder.forceNonTouch = false
    }

    /** When true, Share Logs / LogBus keep Wi‑Fi passwords and serials. Default off. */
    fun includeSecretsInLogs(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_INCLUDE_SECRETS, false)
    fun setIncludeSecretsInLogs(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_INCLUDE_SECRETS, on).apply()
        LogBus.includeSecrets = on
    }

    fun transport(ctx: Context): WifiTransport =
        WifiTransport.byId(prefs(ctx).getString(KEY_TRANSPORT, null))
    fun setTransport(ctx: Context, t: WifiTransport) =
        prefs(ctx).edit().putString(KEY_TRANSPORT, t.id).apply()

    /**
     * Anonymous install ping + crash/error upload (random UUID only). Default **on**;
     * rider can turn off in Setup → Privacy. See PRIVACY.md.
     */
    /** Answer dash clock over BLE (EC-BTP). Off by default — bike must already be paired. */
    fun bluetoothClockSync(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_BT_CLOCK, false)
    fun setBluetoothClockSync(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_BT_CLOCK, on).apply()
        ClockLab.bluetooth = on
    }

    /** Clock lab `0x10450` reply. Default empty ack = Latest 2.0.13. Not in SettingsBackup. */
    fun clockLabQuery(ctx: Context): ClockQueryMode =
        ClockQueryMode.byId(prefs(ctx).getString(KEY_CLOCK_LAB_QUERY, null))
    fun setClockLabQuery(ctx: Context, mode: ClockQueryMode) {
        prefs(ctx).edit().putString(KEY_CLOCK_LAB_QUERY, mode.id).apply()
        ClockLab.query = mode
    }

    /** Clock lab `0x10600` reply. Default echo = Latest 2.0.13. Not in SettingsBackup. */
    fun clockLabTimeSync(ctx: Context): ClockTimeSyncMode =
        ClockTimeSyncMode.byId(prefs(ctx).getString(KEY_CLOCK_LAB_TIMESYNC, null))
    fun setClockLabTimeSync(ctx: Context, mode: ClockTimeSyncMode) {
        prefs(ctx).edit().putString(KEY_CLOCK_LAB_TIMESYNC, mode.id).apply()
        ClockLab.timeSync = mode
    }

    fun applyClockLabPreset(ctx: Context, preset: ClockLabPreset) {
        ClockLab.applyPreset(preset)
        prefs(ctx).edit()
            .putString(KEY_CLOCK_LAB_QUERY, ClockLab.query.id)
            .putString(KEY_CLOCK_LAB_TIMESYNC, ClockLab.timeSync.id)
            .putBoolean(KEY_BT_CLOCK, ClockLab.bluetooth)
            .apply()
    }

    /**
     * Stay associated to the bike SoftAP / Wi-Fi Direct after Stop so some dashes keep the clock.
     * Process is unbound so cellular/maps still work. Off by default.
     */
    fun keepWifiAfterDisconnect(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_KEEP_WIFI, false)
    fun setKeepWifiAfterDisconnect(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_KEEP_WIFI, on).apply()
        ClockLab.keepWifi = on
    }

    fun anonymousTelemetry(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ANON_TELEMETRY, true)
    fun setAnonymousTelemetry(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_ANON_TELEMETRY, on).apply()
        if (!on) AnonymousTelemetry.onDisabled(ctx)
    }

    /** Sync holder flags from prefs (call on process start / before connect). */
    /**
     * Bonded Bluetooth device that starts Connect when it ACL-connects. Null / blank = off.
     * Not exported in [SettingsBackup] (device-specific).
     */
    fun btTriggerMac(ctx: Context): String? =
        prefs(ctx).getString(KEY_BT_TRIGGER_MAC, null)?.takeIf { it.isNotBlank() }

    fun btTriggerName(ctx: Context): String? =
        prefs(ctx).getString(KEY_BT_TRIGGER_NAME, null)?.takeIf { it.isNotBlank() }

    fun setBtTrigger(ctx: Context, mac: String?, name: String?) {
        prefs(ctx).edit()
            .putString(KEY_BT_TRIGGER_MAC, mac?.ifBlank { null })
            .putString(KEY_BT_TRIGGER_NAME, name?.ifBlank { null })
            .apply()
    }

    /** Automatic music-volume adjustment by GPS speed — see [AutoVolumeController]. */
    fun autoVolumeOn(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_AUTO_VOLUME_ON, false)
    fun setAutoVolumeOn(ctx: Context, on: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_AUTO_VOLUME_ON, on).apply()

    fun autoVolumeMode(ctx: Context): Int = prefs(ctx).getInt(KEY_AUTO_VOLUME_MODE, 1) // Default to 1 (Relative)
    fun setAutoVolumeMode(ctx: Context, mode: Int) =
        prefs(ctx).edit().putInt(KEY_AUTO_VOLUME_MODE, mode).apply()

    fun autoVolumeMaxSteps(ctx: Context): Int = prefs(ctx).getInt("auto_volume_max_steps", 15).coerceIn(5, 100)
    fun setAutoVolumeMaxSteps(ctx: Context, max: Int) =
        prefs(ctx).edit().putInt("auto_volume_max_steps", max.coerceIn(5, 100)).apply()

    fun autoVolumePointsAbsolute(ctx: Context): List<Int> {
        val raw = prefs(ctx).getString("auto_volume_points_abs", null)
            ?: "4,5,6,7,8,8,9,9,10,10,11,12,13,14,15"
        val list = raw.split(",").map { it.toIntOrNull() ?: 0 }
        return if (list.size >= 15) list.take(15) else (list + List(15 - list.size) { 15 })
    }

    fun setAutoVolumePointsAbsolute(ctx: Context, points: List<Int>) {
        prefs(ctx).edit().putString("auto_volume_points_abs", points.joinToString(",")).apply()
    }

    fun autoVolumePointsRelative(ctx: Context): List<Int> {
        val raw = prefs(ctx).getString("auto_volume_points_rel", null)
            ?: "0,0,1,1,2,2,3,3,4,4,5,5,6,7,8"
        val list = raw.split(",").map { it.toIntOrNull() ?: 0 }
        return if (list.size >= 15) list.take(15) else (list + List(15 - list.size) { 8 })
    }

    fun setAutoVolumePointsRelative(ctx: Context, points: List<Int>) {
        prefs(ctx).edit().putString("auto_volume_points_rel", points.joinToString(",")).apply()
    }

    fun applyToHolder(ctx: Context) {
        BikeProfileHolder.forceNonTouch = forceNonTouch(ctx)
        BikeProfileHolder.forceTouch = forceTouch(ctx)
        BikeProfileHolder.aaDpiOverride = VideoPrefs.dpiOverride(ctx)
        LogBus.includeSecrets = includeSecretsInLogs(ctx)
        ProfilePrefs.applyToHolder(ctx)
        ButtonMap.ensureDefaultsMigrated(ctx)
        ScreenMargins.load(ctx)
        ClockLab.applyFrom(
            clockLabQuery(ctx),
            clockLabTimeSync(ctx),
            bluetoothClockSync(ctx),
            keepWifiAfterDisconnect(ctx),
        )
    }
}

/** How to join the bike Wi‑Fi. [AUTO] prefers AP when the QR advertises it, else P2P. */
enum class WifiTransport(val id: String, val label: String) {
    AUTO("auto", "Auto (from QR)"),
    AP("ap", "Force Wi‑Fi AP"),
    P2P("p2p", "Force Wi‑Fi Direct"),
    ;

    companion object {
        fun byId(id: String?): WifiTransport =
            entries.firstOrNull { it.id == id } ?: AUTO
    }
}
