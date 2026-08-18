// SPDX-License-Identifier: AGPL-3.0-or-later
// Compose port of SetupActivity's settings surface — video / map & buttons / profile & transport /
// connectivity / routing / privacy, in the cockpit design. ADDITIVE: reads/writes the EXACT same
// prefs objects as the classic Activity (VideoPrefs, NightPrefs, ButtonTimingPrefs, AppSettings,
// ProfilePrefs, MapPrefs), so the new UI, the classic SetupActivity and the live dash stay in sync.
// The classic SetupActivity is kept in place. The AA-prerequisites checklist, the Play-Store steps
// and export/import stay in the classic onboarding — this is the settings surface only.
package dev.zanderp.opencfmoto.ui.settings

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import dev.zanderp.opencfmoto.R
import dev.zanderp.opencfmoto.AaVideoBridge
import dev.zanderp.opencfmoto.AnonymousTelemetry
import dev.zanderp.opencfmoto.AppSettings
import dev.zanderp.opencfmoto.ButtonTimingPrefs
import dev.zanderp.opencfmoto.DoubleTapDelay
import dev.zanderp.opencfmoto.LongPressDelay
import dev.zanderp.opencfmoto.MapPrefs
import dev.zanderp.opencfmoto.MapTheme
import dev.zanderp.opencfmoto.NightPrefs
import dev.zanderp.opencfmoto.PowerMode
import dev.zanderp.opencfmoto.ProfileOverride
import dev.zanderp.opencfmoto.ProfilePrefs
import dev.zanderp.opencfmoto.ResolutionMode
import dev.zanderp.opencfmoto.ScreenFit
import dev.zanderp.opencfmoto.VideoPrefs
import dev.zanderp.opencfmoto.VideoQuality
import dev.zanderp.opencfmoto.WifiTransport
import dev.zanderp.opencfmoto.ui.Routes
import dev.zanderp.opencfmoto.ui.components.MonoLabel
import dev.zanderp.opencfmoto.ui.components.PrimaryButton
import dev.zanderp.opencfmoto.ui.theme.LocalCockpitColors

/**
 * Configuración inicial — the classic [dev.zanderp.opencfmoto.SetupActivity] settings, natively in
 * Compose. Every selector reflects the CURRENT stored value and writes on change to the identical
 * prefs object the classic Activity uses, so the dash and the classic app never diverge. Video and
 * profile changes toast "se aplica en la próxima conexión"; map theme and button timing apply live
 * (map theme is also pushed to any running AA session via [AaVideoBridge.nightSink], exactly like the
 * Activity). The two touch booleans ([AppSettings.setForceTouch] / [AppSettings.setForceNonTouch],
 * mutually exclusive) are folded into one 3-way selector covering all their reachable states.
 */
@Composable
fun SetupScreen(nav: NavController) {
    val c = LocalCockpitColors.current
    val ctx = LocalContext.current

    // Mirror the classic onCreate / sibling Compose screens: sync the holder flags from prefs once,
    // before the getters below seed the UI state. Placed first so it runs ahead of the seeds.
    remember { AppSettings.applyToHolder(ctx) }

    // Each control is backed by a state var seeded once from its getter; onChange writes prefs AND
    // updates the state so the highlight moves immediately (same as CustomResolutionScreen).
    var quality by remember { mutableStateOf(VideoPrefs.get(ctx)) }
    var fit by remember { mutableStateOf(VideoPrefs.fit(ctx)) }
    var power by remember { mutableStateOf(VideoPrefs.power(ctx)) }
    var resolution by remember { mutableStateOf(VideoPrefs.resolution(ctx)) }
    var theme by remember { mutableStateOf(NightPrefs.theme(ctx)) }
    var doubleTap by remember { mutableStateOf(ButtonTimingPrefs.doubleTap(ctx)) }
    var holds by remember { mutableStateOf(ButtonTimingPrefs.holdsEnabled(ctx)) }
    var longPress by remember { mutableStateOf(ButtonTimingPrefs.longPress(ctx)) }
    var touch by remember { mutableStateOf(currentTouch(ctx)) }
    var profile by remember { mutableStateOf(ProfilePrefs.get(ctx)) }
    var transport by remember { mutableStateOf(AppSettings.transport(ctx)) }
    var autoConnect by remember { mutableStateOf(AppSettings.autoConnect(ctx)) }
    var autoRecovery by remember { mutableStateOf(AppSettings.autoRecovery(ctx)) }
    var btClock by remember { mutableStateOf(AppSettings.bluetoothClockSync(ctx)) }
    var keepWifi by remember { mutableStateOf(AppSettings.keepWifiAfterDisconnect(ctx)) }
    var logTrips by remember { mutableStateOf(AppSettings.logTrips(ctx)) }
    var orsKey by remember { mutableStateOf(MapPrefs.orsApiKey(ctx)) }
    var secrets by remember { mutableStateOf(AppSettings.includeSecretsInLogs(ctx)) }
    var telemetry by remember { mutableStateOf(AppSettings.anonymousTelemetry(ctx)) }

    fun nextConnectToast() =
        Toast.makeText(ctx, ctx.getString(R.string.ovk_setup_next_connect), Toast.LENGTH_SHORT).show()

    Column(
        Modifier.fillMaxSize().background(c.ground).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Header(stringResource(R.string.ovk_settings_initial_setup)) { nav.popBackStack() }

        Group(stringResource(R.string.ovk_setup_group_video)) {
            Selector(
                stringResource(R.string.ovk_setup_quality),
                listOf(
                    stringResource(R.string.ovk_setup_smooth) to VideoQuality.SMOOTH,
                    stringResource(R.string.ovk_setup_balanced) to VideoQuality.BALANCED,
                    stringResource(R.string.ovk_setup_sharp) to VideoQuality.SHARP,
                ),
                quality,
                first = true,
            ) { VideoPrefs.set(ctx, it); quality = it; nextConnectToast() }
            Selector(
                stringResource(R.string.ovk_setup_screen_fit),
                listOf(
                    stringResource(R.string.ovk_setup_fill) to ScreenFit.FILL,
                    stringResource(R.string.ovk_setup_fit) to ScreenFit.FIT,
                    stringResource(R.string.ovk_setup_stretch) to ScreenFit.STRETCH,
                ),
                fit,
            ) { VideoPrefs.setFit(ctx, it); fit = it; nextConnectToast() }
            Selector(
                stringResource(R.string.ovk_setup_power),
                listOf(
                    "Auto" to PowerMode.AUTO,
                    stringResource(R.string.ovk_setup_smooth) to PowerMode.SMOOTH,
                    stringResource(R.string.ovk_setup_balanced) to PowerMode.BALANCED,
                    stringResource(R.string.ovk_setup_saver) to PowerMode.SAVER,
                ),
                power,
            ) { VideoPrefs.setPower(ctx, it); power = it; nextConnectToast() }
            Selector(
                stringResource(R.string.ovk_setup_resolution),
                listOf(
                    "Auto" to ResolutionMode.AUTO,
                    stringResource(R.string.ovk_setup_landscape_sd) to ResolutionMode.LANDSCAPE_SD,
                    stringResource(R.string.ovk_setup_landscape_hd) to ResolutionMode.LANDSCAPE_HD,
                    stringResource(R.string.ovk_setup_portrait_sd) to ResolutionMode.PORTRAIT_SD,
                    stringResource(R.string.ovk_setup_portrait_hd) to ResolutionMode.PORTRAIT_HD,
                ),
                resolution,
            ) { VideoPrefs.setResolution(ctx, it); resolution = it; nextConnectToast() }
        }

        Group(stringResource(R.string.ovk_setup_group_map_touch)) {
            // Map theme applies live — push it to any running AA session, exactly like the Activity.
            Selector(
                stringResource(R.string.ovk_setup_map_theme),
                listOf(
                    "Auto" to MapTheme.AUTO,
                    stringResource(R.string.ovk_setup_day) to MapTheme.DAY,
                    stringResource(R.string.ovk_setup_night) to MapTheme.NIGHT,
                ),
                theme,
                first = true,
            ) {
                NightPrefs.setTheme(ctx, it)
                AaVideoBridge.nightSink?.invoke(NightPrefs.isNightNow(ctx))
                theme = it
            }
            // Button timing applies live — the next press uses the new window (no toast).
            Selector(
                stringResource(R.string.ovk_setup_double_tap),
                listOf(
                    stringResource(R.string.ovk_setup_fast) to DoubleTapDelay.FAST,
                    "Normal" to DoubleTapDelay.NORMAL,
                    stringResource(R.string.ovk_setup_slow) to DoubleTapDelay.SLOW,
                    stringResource(R.string.ovk_setup_very_slow) to DoubleTapDelay.VERY_SLOW,
                ),
                doubleTap,
            ) { ButtonTimingPrefs.setDoubleTap(ctx, it); doubleTap = it }
            ToggleRow(stringResource(R.string.ovk_setup_hold_detect), stringResource(R.string.ovk_setup_hold_detect_sub), holds) {
                ButtonTimingPrefs.setHoldsEnabled(ctx, it); holds = it
            }
            Selector(
                stringResource(R.string.ovk_setup_hold_duration),
                listOf(
                    stringResource(R.string.ovk_setup_short) to LongPressDelay.SHORT,
                    "Normal" to LongPressDelay.NORMAL,
                    stringResource(R.string.ovk_setup_long) to LongPressDelay.LONG,
                ),
                longPress,
            ) { ButtonTimingPrefs.setLongPress(ctx, it); longPress = it }
            // The two mutually-exclusive touch booleans as one 3-way selector (per bike).
            Selector(
                stringResource(R.string.ovk_setup_touchscreen),
                listOf(
                    "Auto" to TouchOverride.AUTO,
                    stringResource(R.string.ovk_setup_force_touch) to TouchOverride.TOUCH,
                    stringResource(R.string.ovk_setup_no_touch) to TouchOverride.NON_TOUCH,
                ),
                touch,
            ) {
                when (it) {
                    TouchOverride.AUTO -> {
                        AppSettings.setForceTouch(ctx, false)
                        AppSettings.setForceNonTouch(ctx, false)
                    }
                    TouchOverride.TOUCH -> AppSettings.setForceTouch(ctx, true)
                    TouchOverride.NON_TOUCH -> AppSettings.setForceNonTouch(ctx, true)
                }
                touch = it
                nextConnectToast()
            }
        }

        Group(stringResource(R.string.ovk_setup_group_profile_transport)) {
            Selector(
                stringResource(R.string.ovk_setup_bike_profile),
                // Explicit order = the classic Activity's segment order (not the enum declaration
                // order, which puts NK-ADV before 800MT/1000MTX).
                listOf(
                    ProfileOverride.AUTO,
                    ProfileOverride.LEGACY,
                    ProfileOverride.NK800,
                    ProfileOverride.CFDL26_LAND,
                    ProfileOverride.CFDL26_PORT,
                    ProfileOverride.NK_ADV,
                    ProfileOverride.CLC450,
                ).map { it.shortLabel to it },
                profile,
                first = true,
            ) { ProfilePrefs.set(ctx, it); profile = it; nextConnectToast() }
            Selector(
                stringResource(R.string.ovk_setup_wifi_transport),
                listOf(
                    "Auto" to WifiTransport.AUTO,
                    "AP" to WifiTransport.AP,
                    "P2P" to WifiTransport.P2P,
                ),
                transport,
            ) { AppSettings.setTransport(ctx, it); transport = it; nextConnectToast() }
        }

        Group(stringResource(R.string.ovk_setup_group_connectivity)) {
            ToggleRow(stringResource(R.string.ovk_auto_connect), stringResource(R.string.ovk_settings_autoconnect_sub), autoConnect, first = true) {
                AppSettings.setAutoConnect(ctx, it); autoConnect = it
            }
            ToggleRow(stringResource(R.string.ovk_setup_auto_recovery), stringResource(R.string.ovk_setup_auto_recovery_sub), autoRecovery) {
                AppSettings.setAutoRecovery(ctx, it); autoRecovery = it
            }
            ToggleRow(stringResource(R.string.ovk_setup_bt_clock), stringResource(R.string.ovk_setup_bt_clock_sub), btClock) {
                AppSettings.setBluetoothClockSync(ctx, it); btClock = it
            }
            ToggleRow(stringResource(R.string.ovk_setup_keep_wifi), stringResource(R.string.ovk_setup_keep_wifi_sub), keepWifi) {
                AppSettings.setKeepWifiAfterDisconnect(ctx, it); keepWifi = it
            }
            ToggleRow(stringResource(R.string.ovk_setup_log_trips), stringResource(R.string.ovk_setup_log_trips_sub), logTrips) {
                AppSettings.setLogTrips(ctx, it); logTrips = it
            }
        }

        Group(stringResource(R.string.ovk_setup_group_routes)) {
            Column(
                Modifier.fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(stringResource(R.string.ovk_setup_ors_key), color = c.ink, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text(
                    stringResource(R.string.ovk_setup_ors_key_sub),
                    color = c.inkFaint,
                    fontSize = 11.sp,
                )
                OutlinedTextField(
                    value = orsKey,
                    onValueChange = { orsKey = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.ovk_setup_ors_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = c.ignition,
                        unfocusedBorderColor = c.line,
                        focusedTextColor = c.ink,
                        unfocusedTextColor = c.ink,
                        cursorColor = c.ignition,
                        focusedContainerColor = c.groundHi,
                        unfocusedContainerColor = c.groundHi,
                    ),
                )
                PrimaryButton(
                    text = stringResource(R.string.ovk_setup_save_key),
                    onClick = {
                        MapPrefs.setOrsApiKey(ctx, orsKey)
                        orsKey = MapPrefs.orsApiKey(ctx) // reflect the trimmed stored value
                        Toast.makeText(ctx, ctx.getString(R.string.ovk_setup_key_saved), Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Group(stringResource(R.string.ovk_settings_group_privacy)) {
            ToggleRow(stringResource(R.string.ovk_setup_secrets_logs), stringResource(R.string.ovk_setup_secrets_logs_sub), secrets, first = true) {
                AppSettings.setIncludeSecretsInLogs(ctx, it); secrets = it
            }
            ToggleRow(stringResource(R.string.ovk_settings_telemetry), stringResource(R.string.ovk_setup_telemetry_sub), telemetry) {
                AppSettings.setAnonymousTelemetry(ctx, it)
                if (it) AnonymousTelemetry.onAppStart(ctx)
                telemetry = it
            }
        }

        Group(stringResource(R.string.ovk_setup_group_advanced)) {
            LinkRow(stringResource(R.string.ovk_settings_margins), stringResource(R.string.ovk_settings_margins_val), first = true) { nav.navigate(Routes.SCREEN_MARGINS) }
            LinkRow(stringResource(R.string.ovk_settings_resolution), stringResource(R.string.ovk_settings_resolution_val)) { nav.navigate(Routes.CUSTOM_RESOLUTION) }
        }
    }
}

/** The reachable states of the two mutually-exclusive touch-override booleans in [AppSettings]. */
private enum class TouchOverride { AUTO, TOUCH, NON_TOUCH }

private fun currentTouch(ctx: Context): TouchOverride = when {
    AppSettings.forceTouch(ctx) -> TouchOverride.TOUCH
    AppSettings.forceNonTouch(ctx) -> TouchOverride.NON_TOUCH
    else -> TouchOverride.AUTO
}

/** Section label + rounded surface card, matching SettingsScreen's grouped look. */
@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    val c = LocalCockpitColors.current
    MonoLabel(title, color = c.inkFaint)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surface1)
            .border(1.dp, c.line, RoundedCornerShape(12.dp)),
    ) { content() }
}

/**
 * A pick-one control: a title with a wrapping row of segment chips (the cockpit's segmented-control
 * language from CustomResolutionScreen, wrapped so 5–7 options fit). The selected chip is painted in
 * the ignition color; [onSelect] fires with the chosen value.
 */
@Composable
private fun <T> Selector(
    title: String,
    options: List<Pair<String, T>>,
    selected: T,
    first: Boolean = false,
    onSelect: (T) -> Unit,
) {
    val c = LocalCockpitColors.current
    if (!first) HorizontalDivider(color = c.line.copy(alpha = 0.5f))
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, color = c.ink, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            options.forEach { (label, value) ->
                Seg(label, value == selected) { onSelect(value) }
            }
        }
    }
}

@Composable
private fun Seg(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = LocalCockpitColors.current
    Box(
        Modifier.clip(RoundedCornerShape(8.dp))
            .background(if (selected) c.ignition else c.groundHi)
            .border(1.dp, if (selected) c.ignition else c.line, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (selected) c.onIgnition else c.inkDim,
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    first: Boolean = false,
    onCheckedChange: (Boolean) -> Unit,
) {
    val c = LocalCockpitColors.current
    if (!first) HorizontalDivider(color = c.line.copy(alpha = 0.5f))
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = c.ink, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Text(subtitle, color = c.inkFaint, fontSize = 11.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedTrackColor = c.live),
        )
    }
}

@Composable
private fun LinkRow(title: String, value: String, first: Boolean = false, onClick: () -> Unit) {
    val c = LocalCockpitColors.current
    if (!first) HorizontalDivider(color = c.line.copy(alpha = 0.5f))
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = c.ink, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(value, color = c.ignition, fontSize = 12.sp)
        Spacer(Modifier.width(6.dp))
        Text("›", color = c.inkFaint, fontSize = 16.sp)
    }
}
