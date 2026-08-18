// SPDX-License-Identifier: AGPL-3.0-or-later
// Settings — grouped, not a wall of options. Home of what left the dashboard: the default mode,
// map provider, auto-connect, the hidden developer mode, and telemetry (off by default).
package dev.zanderp.opencfmoto.ui.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.zanderp.opencfmoto.AboutActivity
import dev.zanderp.opencfmoto.AppSettings
import dev.zanderp.opencfmoto.BuildConfig
import dev.zanderp.opencfmoto.GarageActivity
import dev.zanderp.opencfmoto.MainActivity
import dev.zanderp.opencfmoto.MapLibreVdProbe
import android.net.Uri
import dev.zanderp.opencfmoto.UpdateChecker
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.zanderp.opencfmoto.R
import dev.zanderp.opencfmoto.settings.AppMode
import dev.zanderp.opencfmoto.settings.DashRenderer
import dev.zanderp.opencfmoto.settings.MapProvider
import dev.zanderp.opencfmoto.settings.SettingsStore
import dev.zanderp.opencfmoto.settings.ThemeMode
import dev.zanderp.opencfmoto.ui.CockpitActivity
import dev.zanderp.opencfmoto.ui.Routes
import dev.zanderp.opencfmoto.ui.components.MonoLabel
import dev.zanderp.opencfmoto.ui.theme.LocalCockpitColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(nav: NavController) {
    val c = LocalCockpitColors.current
    val ctx = LocalContext.current
    val store = remember { SettingsStore(ctx.applicationContext) }
    val scope = rememberCoroutineScope()
    val mode by store.defaultMode.collectAsStateWithLifecycle(initialValue = AppMode.CFMOTO)
    val provider by store.mapProvider.collectAsStateWithLifecycle(initialValue = MapProvider.BUILTIN)
    val renderer by store.dashRenderer.collectAsStateWithLifecycle(initialValue = DashRenderer.MAPLIBRE)
    val theme by store.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.AUTO)
    val dev by store.developerMode.collectAsStateWithLifecycle(initialValue = false)
    val auto by store.autoConnect.collectAsStateWithLifecycle(initialValue = false)
    val telemetry by store.telemetryEnabled.collectAsStateWithLifecycle(initialValue = false)
    var checking by remember { mutableStateOf(false) }
    var update by remember { mutableStateOf<UpdateChecker.Release?>(null) }

    Column(
        Modifier.fillMaxSize().background(c.ground).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Header(stringResource(R.string.ovk_settings_title)) { nav.popBackStack() }

        Group(stringResource(R.string.ovk_settings_group_connection)) {
            // First-run onboarding, kept reachable after the gate has been passed once.
            ValueRow(stringResource(R.string.ovk_settings_onboarding), stringResource(R.string.ovk_settings_onboarding_val), first = true) { nav.navigate(Routes.ONBOARDING) }
            ToggleRow(stringResource(R.string.ovk_auto_connect), stringResource(R.string.ovk_settings_autoconnect_sub), auto) {
                // Mirror the DataStore switch into AppSettings — the single gate BOTH auto-connect paths
                // (background CompanionDeviceService + foreground cockpit fallback) actually read.
                scope.launch { store.setAutoConnect(it) }
                AppSettings.setAutoConnect(ctx, it)
            }
            // One-time CompanionDeviceManager association so the paired bike's Wi‑Fi can WAKE the app
            // in the background (see CockpitActivity.enableBackgroundAutoConnect).
            ValueRow(stringResource(R.string.ovk_settings_bg_autoconnect), stringResource(R.string.ovk_settings_bg_autoconnect_val)) {
                (ctx.unwrapActivity() as? CockpitActivity)?.enableBackgroundAutoConnect()
            }
        }
        Group(stringResource(R.string.ovk_settings_group_projection)) {
            ValueRow(stringResource(R.string.ovk_settings_default_mode), modeLabel(mode), first = true) {
                scope.launch { store.setDefaultMode(if (mode == AppMode.CFMOTO) AppMode.ANDROID_AUTO else AppMode.CFMOTO) }
            }
            ValueRow(stringResource(R.string.ovk_settings_map_provider), providerLabel(ctx, provider)) {
                scope.launch { store.setMapProvider(nextProvider(provider)) }
            }
            ValueRow(stringResource(R.string.ovk_settings_dash_renderer), rendererLabel(ctx, renderer)) {
                scope.launch { store.setDashRenderer(nextRenderer(renderer)) }
            }
            ValueRow(stringResource(R.string.ovk_settings_map_routes), stringResource(R.string.ovk_settings_map_routes_val)) { nav.navigate(Routes.MAP_HUB) }
        }
        Group(stringResource(R.string.ovk_settings_group_appearance)) {
            ValueRow(stringResource(R.string.ovk_settings_theme), stringResource(themeLabelRes(theme)), first = true) {
                scope.launch { store.setThemeMode(nextTheme(theme)) }
            }
            ValueRow(stringResource(R.string.ovk_settings_language), currentLanguageLabel(ctx)) { nav.navigate(Routes.LANGUAGE) }
        }
        Group(stringResource(R.string.ovk_settings_group_privacy)) {
            ToggleRow(stringResource(R.string.ovk_settings_telemetry), stringResource(R.string.ovk_settings_telemetry_sub), telemetry, first = true) {
                scope.launch { store.setTelemetryEnabled(it) }
            }
        }
        // The original app's full configuration, kept intact (disabled-not-deleted) so the fork stays
        // upstream-compatible for a PR. It lives here, secondary, out of the way of the daily UI.
        Group(stringResource(R.string.ovk_settings_group_classic)) {
            // Always-visible entry to the legacy MainActivity UI (previously only reachable via the
            // dev-only Diagnostics row). The dev Diagnostics row below is kept as-is.
            ValueRow(stringResource(R.string.ovk_settings_legacy_mode), stringResource(R.string.ovk_settings_legacy_mode_val), first = true) {
                ctx.startActivity(Intent(ctx, MainActivity::class.java))
            }
            ValueRow(stringResource(R.string.ovk_settings_initial_setup), stringResource(R.string.ovk_settings_initial_setup_val)) { nav.navigate(Routes.SETUP) }
            ValueRow(stringResource(R.string.ovk_settings_bike_profiles), stringResource(R.string.ovk_garage)) { GarageActivity.start(ctx) }
            ValueRow(stringResource(R.string.ovk_settings_button_mapping), stringResource(R.string.ovk_tile_controls_desc)) { nav.navigate(Routes.BUTTON_MAPPING) }
            ValueRow(stringResource(R.string.ovk_settings_margins), stringResource(R.string.ovk_settings_margins_val)) { nav.navigate(Routes.SCREEN_MARGINS) }
            ValueRow(stringResource(R.string.ovk_settings_resolution), stringResource(R.string.ovk_settings_resolution_val)) { nav.navigate(Routes.CUSTOM_RESOLUTION) }
        }
        if (dev) {
            Group(stringResource(R.string.ovk_settings_group_developer)) {
                ToggleRow(stringResource(R.string.ovk_settings_developer_mode), stringResource(R.string.ovk_settings_developer_mode_sub), dev, first = true) {
                    scope.launch { store.setDeveloperMode(it) }
                }
                ValueRow("Build", BuildConfig.GIT_HASH) { }
                // Native in-cockpit log viewer (was a kick-out to the classic MainActivity; the
                // legacy app now has its own "Original (classic) mode" row in the Classic group).
                ValueRow(stringResource(R.string.ovk_settings_diagnostics), stringResource(R.string.ovk_settings_diagnostics_val)) {
                    nav.navigate(Routes.LOG)
                }
                // DEV diagnostic: force MapLibre onto the bike VirtualDisplay→H.264 encoder for ~60 s and
                // dump the stream + a live frame-rate log. Tap again to stop early. Zero production impact.
                ValueRow("MapLibre VD probe", "60s → logcat/.h264") {
                    val msg = MapLibreVdProbe.toggle(ctx)
                    Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                }
            }
        }
        Group(stringResource(R.string.ovk_settings_group_about)) {
            VersionRow(devOn = dev) { scope.launch { store.setDeveloperMode(true) } }
            ValueRow(stringResource(R.string.ovk_settings_check_update), if (checking) stringResource(R.string.ovk_searching) else BuildConfig.VERSION_NAME) {
                if (!checking) {
                    checking = true
                    scope.launch {
                        val r = withContext(Dispatchers.IO) {
                            runCatching { UpdateChecker.check(ctx, manual = true) }.getOrNull()
                        }
                        checking = false
                        if (r != null) update = r
                        else Toast.makeText(ctx, ctx.getString(R.string.ovk_settings_up_to_date), Toast.LENGTH_SHORT).show()
                    }
                }
            }
            ValueRow(stringResource(R.string.ovk_settings_license), "AGPL-3.0") { AboutActivity.start(ctx) }
        }
    }

    update?.let { rel ->
        AlertDialog(
            onDismissRequest = { update = null },
            title = { Text(stringResource(R.string.ovk_settings_update_available)) },
            text = { Text("${rel.version}\n\n${rel.notes.take(300)}") },
            confirmButton = {
                TextButton(onClick = {
                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(rel.downloadUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    update = null
                }) { Text(stringResource(R.string.ovk_settings_update)) }
            },
            dismissButton = { TextButton(onClick = { update = null }) { Text(stringResource(R.string.ovk_not_now)) } },
        )
    }
}

@Composable
private fun VersionRow(devOn: Boolean, onUnlock: () -> Unit) {
    var taps by remember { mutableStateOf(0) }
    ValueRow(stringResource(R.string.ovk_settings_version), BuildConfig.VERSION_NAME, first = true) {
        if (!devOn) {
            taps++
            if (taps >= 7) onUnlock()
        }
    }
}

// LocalContext under CockpitActivity is the Activity, but unwrap defensively (a ContextThemeWrapper /
// @Preview host must not crash the background-auto-connect setup row with a hard cast).
private tailrec fun Context.unwrapActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.unwrapActivity()
    else -> null
}

private fun modeLabel(m: AppMode) = if (m == AppMode.CFMOTO) "CFMOTO" else "Android Auto"
// Brand names stay literal; only the MIRROR ("Espejo") label is translated.
private fun providerLabel(ctx: Context, p: MapProvider) = when (p) {
    MapProvider.BUILTIN -> "Overtake"; MapProvider.GOOGLE -> "Google Maps"; MapProvider.WAZE -> "Waze"
    MapProvider.MIRROR -> ctx.getString(R.string.ovk_provider_mirror)
}
private fun nextProvider(p: MapProvider) = when (p) {
    MapProvider.BUILTIN -> MapProvider.GOOGLE; MapProvider.GOOGLE -> MapProvider.WAZE
    MapProvider.WAZE -> MapProvider.MIRROR; MapProvider.MIRROR -> MapProvider.BUILTIN
}
// Dash render engine for the built-in map (Overtake). MapLibre = vector + 3D (default, proven clean
// on the bike VD → H.264 path); osmdroid = classic raster; Mapsforge = offline vector (Canvas,
// screen-off). Owner's cycle order: MapLibre → osmdroid → Mapsforge. Brand names stay literal.
private fun rendererLabel(ctx: Context, r: DashRenderer) = when (r) {
    DashRenderer.MAPLIBRE -> ctx.getString(R.string.ovk_renderer_maplibre)
    DashRenderer.OSMDROID -> ctx.getString(R.string.ovk_renderer_osmdroid)
    DashRenderer.MAPSFORGE -> ctx.getString(R.string.ovk_renderer_mapsforge)
}
private fun nextRenderer(r: DashRenderer) = when (r) {
    DashRenderer.MAPLIBRE -> DashRenderer.OSMDROID
    DashRenderer.OSMDROID -> DashRenderer.MAPSFORGE
    DashRenderer.MAPSFORGE -> DashRenderer.MAPLIBRE
}
@StringRes
private fun themeLabelRes(m: ThemeMode): Int = when (m) {
    ThemeMode.AUTO -> R.string.ovk_theme_auto; ThemeMode.LIGHT -> R.string.ovk_theme_light; ThemeMode.DARK -> R.string.ovk_theme_dark
}
private fun nextTheme(m: ThemeMode) = when (m) {
    ThemeMode.AUTO -> ThemeMode.LIGHT; ThemeMode.LIGHT -> ThemeMode.DARK; ThemeMode.DARK -> ThemeMode.AUTO
}

/**
 * The current in-app language, for the Settings row value: the applied locale's own name (e.g.
 * "Español") or the "Automatic (system)" label when following the system. Read from AppCompatDelegate
 * (the source of truth for the per-app locale); a language change recreates the activity, so this is
 * re-evaluated on the next composition.
 */
private fun currentLanguageLabel(ctx: Context): String {
    val locales = AppCompatDelegate.getApplicationLocales()
    val loc = if (locales.isEmpty) null else locales[0]
    return if (loc == null) ctx.getString(R.string.ovk_language_system)
    else loc.getDisplayLanguage(loc).replaceFirstChar { if (it.isLowerCase()) it.titlecase(loc) else it.toString() }
}

@Composable
internal fun Header(title: String, onBack: () -> Unit) {
    val c = LocalCockpitColors.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(c.surface1).border(1.dp, c.line, RoundedCornerShape(10.dp)).clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) { Text("‹", color = c.inkDim, fontSize = 20.sp) }
        Spacer(Modifier.width(12.dp))
        Text(title, color = c.ink, fontWeight = FontWeight.Bold, fontSize = 22.sp)
    }
}

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    val c = LocalCockpitColors.current
    MonoLabel(title, color = c.inkFaint)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surface1).border(1.dp, c.line, RoundedCornerShape(12.dp)),
    ) { content() }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, first: Boolean = false, onCheckedChange: (Boolean) -> Unit) {
    val c = LocalCockpitColors.current
    if (!first) HorizontalDivider(color = c.line.copy(alpha = 0.5f))
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = c.ink, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Text(subtitle, color = c.inkFaint, fontSize = 11.sp)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, colors = SwitchDefaults.colors(checkedTrackColor = c.live))
    }
}

@Composable
private fun ValueRow(title: String, value: String, first: Boolean = false, onClick: () -> Unit) {
    val c = LocalCockpitColors.current
    if (!first) HorizontalDivider(color = c.line.copy(alpha = 0.5f))
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = c.ink, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(value, color = c.ignition, fontSize = 12.sp)
        Spacer(Modifier.width(6.dp))
        Text("›", color = c.inkFaint, fontSize = 16.sp)
    }
}
