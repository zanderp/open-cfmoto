// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
//
// Mapa — the CFMOTO-first "Map hub", natively in Compose. This MIGRATES the tractable, prefs/state
// surfaces of the classic on-phone map hub ([dev.zanderp.opencfmoto.GpxActivity] + GpxDashUi): saved
// places (home / parked / favorites via MapPlaces), routing + map settings (MapPrefs) and the offline
// area list (OfflineAreasStore). It DELIBERATELY BRIDGES the interactive, stateful, bike-gated engine
// back to the proven GpxActivity: Nominatim/Photon search, the routing/dash projection, and the
// offline DOWNLOAD engine + area management. Those are called via GpxActivity.start / .startSearch
// rather than rewritten. ADDITIVE: reads/writes the EXACT same prefs objects the classic hub uses
// (MapPrefs, NightPrefs, MapPlaces, OfflineAreasStore), so this screen, GpxActivity and the live dash
// never diverge. GpxActivity stays in place. Pattern language matches SetupScreen (Group / Selector /
// Seg / ToggleRow / LinkRow, the OutlinedTextField style); Header is reused from SettingsScreen (same
// package, internal).
package dev.zanderp.opencfmoto.ui.settings

import android.content.Context
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import dev.zanderp.opencfmoto.R
import dev.zanderp.opencfmoto.AaVideoBridge
import dev.zanderp.opencfmoto.GpxActivity
import dev.zanderp.opencfmoto.MapChrome
import dev.zanderp.opencfmoto.MapPlace
import dev.zanderp.opencfmoto.MapPlaces
import dev.zanderp.opencfmoto.MapPrefs
import dev.zanderp.opencfmoto.MapTheme
import dev.zanderp.opencfmoto.MapUnits
import dev.zanderp.opencfmoto.NightPrefs
import dev.overtake.maps.route.offline.OfflineAreasStore
import dev.zanderp.opencfmoto.RouteMode
import dev.zanderp.opencfmoto.ui.Routes
import dev.zanderp.opencfmoto.ui.components.MonoLabel
import dev.zanderp.opencfmoto.ui.theme.LocalCockpitColors
import java.util.Locale

/** Which modal is open over the hub. */
private enum class HubDialog { HOME, PARKED, SEARCH }

/**
 * The Map hub. Every routing/guide/map selector reflects the CURRENT stored value and writes on change
 * to the identical prefs object the classic [GpxActivity] uses, so the dash and the classic app never
 * diverge. Saved places and offline areas are read live from [MapPlaces] / [OfflineAreasStore] (keyed
 * to a resume tick, so a bridged round-trip through GpxActivity refreshes the lists on return). Map
 * theme applies live — it's pushed to any running AA session via [AaVideoBridge.nightSink], exactly
 * like SetupScreen. The interactive engine (search, routing, dash projection, offline download) is
 * bridged to GpxActivity, not reimplemented here.
 */
@Composable
fun MapHubScreen(nav: NavController) {
    val c = LocalCockpitColors.current
    val ctx = LocalContext.current

    // --- Prefs: seed each control once from its getter; write on change (immediate). ---
    var routeMode by remember { mutableStateOf(MapPrefs.routeMode(ctx)) }
    var avoidTolls by remember { mutableStateOf(MapPrefs.avoidTolls(ctx)) }
    var avoidHighways by remember { mutableStateOf(MapPrefs.avoidHighways(ctx)) }
    var units by remember { mutableStateOf(MapPrefs.units(ctx)) }
    var circuitKm by remember { mutableStateOf(MapPrefs.funCircuitKm(ctx)) }
    var voice by remember { mutableStateOf(MapPrefs.voicePrompts(ctx)) }
    var nextStop by remember { mutableStateOf(MapPrefs.showNextStop(ctx)) }
    var autoFinish by remember { mutableStateOf(MapPrefs.autoFinishOnArrive(ctx)) }
    var mapTheme by remember { mutableStateOf(NightPrefs.theme(ctx)) }
    var chrome by remember { mutableStateOf(MapPrefs.chrome(ctx)) }
    var buildings3d by remember { mutableStateOf(MapPrefs.buildings3d(ctx)) }

    // --- Places / offline: source of truth stays in the stores; re-read on each resume so a bridged
    // trip to GpxActivity (which may set Home, park, add favorites, download areas) refreshes here. ---
    var refreshTick by remember { mutableStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) refreshTick++
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    val home = remember(refreshTick) { MapPlaces.home(ctx) }
    val parked = remember(refreshTick) { MapPlaces.parked(ctx) }
    val favCount = remember(refreshTick) { MapPlaces.favorites(ctx).size }
    val offlineAreas = remember(refreshTick) { OfflineAreasStore.list(ctx) }

    var dialog by remember { mutableStateOf<HubDialog?>(null) }

    Column(
        Modifier.fillMaxSize().background(c.ground).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Header(stringResource(R.string.ovk_mode_map)) { nav.popBackStack() }

        // 1 · Saved places — display + rename/clear are migrated (pure MapPlaces state); acquiring a
        // NEW location (needs a GPS fix or geocoding) is bridged to GpxActivity's proven flow.
        Group(stringResource(R.string.ovk_hub_group_places)) {
            ValueRow(
                stringResource(R.string.ovk_hub_home),
                home?.name ?: stringResource(R.string.ovk_hub_not_set),
                first = true,
                valueColor = if (home == null) c.inkFaint else c.ignition,
            ) { dialog = HubDialog.HOME }
            ValueRow(
                stringResource(R.string.ovk_hub_parked),
                parked?.name ?: stringResource(R.string.ovk_hub_not_set),
                valueColor = if (parked == null) c.inkFaint else c.ignition,
            ) { dialog = HubDialog.PARKED }
            InfoValueRow(stringResource(R.string.ovk_hub_favorites), if (favCount == 1) stringResource(R.string.ovk_hub_saved_one) else stringResource(R.string.ovk_hub_saved_many, favCount))
            LinkRow(stringResource(R.string.ovk_hub_manage_on_map), stringResource(R.string.ovk_mode_map)) { GpxActivity.start(ctx) }
        }

        // 2 · Routing knobs — all read/write MapPrefs.
        Group(stringResource(R.string.ovk_hub_group_route)) {
            Selector(
                stringResource(R.string.ovk_hub_route_mode),
                listOf(stringResource(R.string.ovk_setup_fast) to RouteMode.FAST, stringResource(R.string.ovk_hub_fun) to RouteMode.FUN),
                routeMode,
                first = true,
            ) { MapPrefs.setRouteMode(ctx, it); routeMode = it }
            ToggleRow(stringResource(R.string.ovk_hub_avoid_tolls), stringResource(R.string.ovk_hub_avoid_tolls_sub), avoidTolls) {
                MapPrefs.setAvoidTolls(ctx, it); avoidTolls = it
            }
            ToggleRow(stringResource(R.string.ovk_hub_avoid_highways), stringResource(R.string.ovk_hub_avoid_highways_sub), avoidHighways) {
                MapPrefs.setAvoidHighways(ctx, it); avoidHighways = it
            }
            Selector(
                stringResource(R.string.ovk_hub_units),
                listOf("km" to MapUnits.METRIC, "mi" to MapUnits.IMPERIAL),
                units,
            ) { MapPrefs.setUnits(ctx, it); units = it }
            StepperRow(
                stringResource(R.string.ovk_hub_circuit_km),
                stringResource(R.string.ovk_hub_circuit_km_sub),
                "$circuitKm km",
                onMinus = {
                    MapPrefs.setFunCircuitKm(ctx, circuitKm - 10)
                    circuitKm = MapPrefs.funCircuitKm(ctx)
                },
                onPlus = {
                    MapPrefs.setFunCircuitKm(ctx, circuitKm + 10)
                    circuitKm = MapPrefs.funCircuitKm(ctx)
                },
            )
        }

        // 3 · Turn-by-turn guidance + map look. Map theme reuses NightPrefs (like SetupScreen) and is
        // pushed live to any running AA session; chrome / buildings are MapPrefs.
        Group(stringResource(R.string.ovk_hub_group_guidance)) {
            ToggleRow(stringResource(R.string.ovk_voice), stringResource(R.string.ovk_hub_voice_sub), voice, first = true) {
                MapPrefs.setVoicePrompts(ctx, it); voice = it
            }
            ToggleRow(stringResource(R.string.ovk_hub_next_stop), stringResource(R.string.ovk_hub_next_stop_sub), nextStop) {
                MapPrefs.setShowNextStop(ctx, it); nextStop = it
            }
            ToggleRow(stringResource(R.string.ovk_hub_auto_finish), stringResource(R.string.ovk_hub_auto_finish_sub), autoFinish) {
                MapPrefs.setAutoFinishOnArrive(ctx, it); autoFinish = it
            }
            Selector(
                stringResource(R.string.ovk_setup_map_theme),
                listOf("Auto" to MapTheme.AUTO, stringResource(R.string.ovk_setup_day) to MapTheme.DAY, stringResource(R.string.ovk_setup_night) to MapTheme.NIGHT),
                mapTheme,
            ) {
                NightPrefs.setTheme(ctx, it)
                AaVideoBridge.nightSink?.invoke(NightPrefs.isNightNow(ctx))
                mapTheme = it
            }
            Selector(
                stringResource(R.string.ovk_hub_map_style),
                listOf(
                    stringResource(R.string.ovk_theme_dark) to MapChrome.DARK,
                    stringResource(R.string.ovk_hub_style_black) to MapChrome.BLACK,
                    stringResource(R.string.ovk_hub_style_slate) to MapChrome.SLATE,
                    stringResource(R.string.ovk_hub_style_forest) to MapChrome.FOREST,
                ),
                chrome,
            ) { MapPrefs.setChrome(ctx, it); chrome = it }
            ToggleRow(stringResource(R.string.ovk_hub_buildings3d), stringResource(R.string.ovk_hub_buildings3d_sub), buildings3d) {
                MapPrefs.setBuildings3d(ctx, it); buildings3d = it
            }
        }

        // 4 · Offline areas — the list preview is read from OfflineAreasStore; download / manage / delete
        // is now a NATIVE cockpit screen (OfflinePacksScreen) that drives the same offline engine.
        Group(stringResource(R.string.ovk_hub_group_offline)) {
            if (offlineAreas.isEmpty()) {
                InfoText(stringResource(R.string.ovk_hub_no_areas), first = true)
            } else {
                offlineAreas.forEachIndexed { i, a ->
                    AreaRow(a.name, areaDetail(ctx, a), first = i == 0)
                }
            }
            LinkRow(stringResource(R.string.ovk_hub_manage_areas), "Offline") { nav.navigate(Routes.OFFLINE_PACKS) }
            LinkRow(stringResource(R.string.ovk_hub_mapsforge_maps), "Mapsforge") { nav.navigate(Routes.MAPSFORGE_MAPS) }
        }

        // 5 · Actions — both bridge to the proven interactive engine on GpxActivity.
        Group(stringResource(R.string.ovk_hub_group_actions)) {
            LinkRow(stringResource(R.string.ovk_hub_search_dest), stringResource(R.string.ovk_mode_map), first = true) { dialog = HubDialog.SEARCH }
            LinkRow(stringResource(R.string.ovk_hub_open_full_map), stringResource(R.string.ovk_dashboard_title)) { GpxActivity.start(ctx) }
        }
    }

    when (dialog) {
        HubDialog.HOME -> PlaceEditDialog(
            title = stringResource(R.string.ovk_hub_home),
            current = home,
            emptyHint = stringResource(R.string.ovk_hub_home_empty),
            onDismiss = { dialog = null },
            onSave = { newName ->
                home?.let { MapPlaces.setHome(ctx, it.copy(name = newName)) }
                refreshTick++
                dialog = null
            },
            onClear = { MapPlaces.clearHome(ctx); refreshTick++; dialog = null },
            onBridge = { dialog = null; GpxActivity.start(ctx) },
        )
        HubDialog.PARKED -> PlaceEditDialog(
            title = stringResource(R.string.ovk_hub_parked),
            current = parked,
            emptyHint = stringResource(R.string.ovk_hub_parked_empty),
            onDismiss = { dialog = null },
            onSave = { newName ->
                parked?.let { MapPlaces.setParked(ctx, it.copy(name = newName)) }
                refreshTick++
                dialog = null
            },
            onClear = { MapPlaces.clearParked(ctx); refreshTick++; dialog = null },
            onBridge = { dialog = null; GpxActivity.start(ctx) },
        )
        HubDialog.SEARCH -> SearchDialog(
            onDismiss = { dialog = null },
            onSearch = { q ->
                dialog = null
                if (q.isNotBlank()) GpxActivity.startSearch(ctx, q.trim()) else GpxActivity.start(ctx)
            },
        )
        null -> {}
    }
}

/** zoom detail + tile kind of a stored offline area (size lives in GpxActivity's async manager). */
private fun areaDetail(ctx: Context, a: OfflineAreasStore.Area): String {
    val quality = if (a.zoomMax >= OfflineAreasStore.AREA_ZOOM_HIGH_MAX) ctx.getString(R.string.ovk_hub_quality_high) else ctx.getString(R.string.ovk_hub_quality_standard)
    val kind = when {
        a.vector && a.raster -> ctx.getString(R.string.ovk_hub_kind_both)
        a.vector -> ctx.getString(R.string.ovk_hub_kind_vector)
        a.raster -> ctx.getString(R.string.ovk_hub_kind_raster)
        else -> "—"
    }
    return "$quality · $kind"
}

// --- Dialogs -------------------------------------------------------------------------------------

/**
 * Rename (keeps coordinates) / clear a saved place, or bridge to GpxActivity to fix a NEW location
 * (which needs a GPS fix or geocoding — kept on the proven backend). Persists via [MapPlaces].
 */
@Composable
private fun PlaceEditDialog(
    title: String,
    current: MapPlace?,
    emptyHint: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
    onBridge: () -> Unit,
) {
    val c = LocalCockpitColors.current
    var name by remember(current) { mutableStateOf(current?.name.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface1,
        title = { Text(title, color = c.ink) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (current != null) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.ovk_hub_name)) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = hubFieldColors(),
                    )
                    Text(
                        stringResource(R.string.ovk_hub_coordinates, String.format(Locale.US, "%.5f, %.5f", current.lat, current.lon)),
                        color = c.inkFaint,
                        fontSize = 11.sp,
                    )
                    HorizontalDivider(color = c.line.copy(alpha = 0.5f))
                    DialogActionRow(stringResource(R.string.ovk_hub_set_another), c.ignition, onBridge)
                    DialogActionRow(stringResource(R.string.ovk_hub_delete), c.fault, onClear)
                } else {
                    Text(emptyHint, color = c.inkDim, fontSize = 12.sp)
                    DialogActionRow(stringResource(R.string.ovk_hub_open_to_set), c.ignition, onBridge)
                }
            }
        },
        confirmButton = {
            if (current != null) {
                TextButton(onClick = { onSave(name.trim().ifBlank { current.name }) }) {
                    Text(stringResource(R.string.ovk_save), color = c.ignition)
                }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.ovk_close), color = c.ignition) }
            }
        },
        dismissButton = {
            if (current != null) TextButton(onClick = onDismiss) { Text(stringResource(R.string.ovk_close), color = c.inkDim) }
        },
    )
}

/** Type a query and hand it to GpxActivity's search (via its EXTRA_SEARCH entry point). */
@Composable
private fun SearchDialog(onDismiss: () -> Unit, onSearch: (String) -> Unit) {
    val c = LocalCockpitColors.current
    var q by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface1,
        title = { Text(stringResource(R.string.ovk_hub_search_dest), color = c.ink) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.ovk_hub_search_hint), color = c.inkFaint, fontSize = 11.sp)
                OutlinedTextField(
                    value = q,
                    onValueChange = { q = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.ovk_hub_search_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = hubFieldColors(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSearch(q) }) { Text(stringResource(R.string.ovk_hub_search), color = c.ignition) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ovk_cancel), color = c.inkDim) } },
    )
}

/** The SetupScreen OutlinedTextField palette, re-used by the hub's dialogs. */
@Composable
private fun hubFieldColors(): TextFieldColors {
    val c = LocalCockpitColors.current
    return OutlinedTextFieldDefaults.colors(
        focusedBorderColor = c.ignition,
        unfocusedBorderColor = c.line,
        focusedTextColor = c.ink,
        unfocusedTextColor = c.ink,
        cursorColor = c.ignition,
        focusedContainerColor = c.groundHi,
        unfocusedContainerColor = c.groundHi,
    )
}

@Composable
private fun DialogActionRow(label: String, color: Color, onClick: () -> Unit) {
    val c = LocalCockpitColors.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = color, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text("›", color = c.inkFaint, fontSize = 16.sp)
    }
}

// --- Local re-implementations of the SetupScreen cockpit patterns (they're file-private there) -----

/** Section label + rounded surface card, matching SettingsScreen / SetupScreen's grouped look. */
@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    val c = LocalCockpitColors.current
    MonoLabel(title, color = c.inkFaint)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surface1)
            .border(1.dp, c.line, RoundedCornerShape(12.dp)),
    ) { content() }
}

/** Pick-one control: a title with a wrapping row of segment chips; the selected chip is ignition. */
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

/** Row that navigates/bridges away: title + a hint value + chevron. */
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

/** Clickable title + value + chevron (opens a dialog). */
@Composable
private fun ValueRow(
    title: String,
    value: String,
    first: Boolean = false,
    valueColor: Color? = null,
    onClick: () -> Unit,
) {
    val c = LocalCockpitColors.current
    if (!first) HorizontalDivider(color = c.line.copy(alpha = 0.5f))
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = c.ink, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(value, color = valueColor ?: c.ignition, fontSize = 12.sp)
        Spacer(Modifier.width(6.dp))
        Text("›", color = c.inkFaint, fontSize = 16.sp)
    }
}

/** Non-clickable title + value (read-only, e.g. the favorites count). */
@Composable
private fun InfoValueRow(title: String, value: String, first: Boolean = false) {
    val c = LocalCockpitColors.current
    if (!first) HorizontalDivider(color = c.line.copy(alpha = 0.5f))
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = c.ink, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(value, color = c.inkDim, fontSize = 12.sp)
    }
}

/** A downloaded offline area: name + zoom/kind detail. */
@Composable
private fun AreaRow(name: String, detail: String, first: Boolean) {
    val c = LocalCockpitColors.current
    if (!first) HorizontalDivider(color = c.line.copy(alpha = 0.5f))
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp)) {
        Text(name, color = c.ink, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        Text(detail, color = c.inkFaint, fontSize = 11.sp)
    }
}

/** A quiet informational line inside a group card. */
@Composable
private fun InfoText(text: String, first: Boolean = false) {
    val c = LocalCockpitColors.current
    if (!first) HorizontalDivider(color = c.line.copy(alpha = 0.5f))
    Text(
        text,
        color = c.inkFaint,
        fontSize = 12.sp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 11.dp),
    )
}

/** Title/subtitle on the left, − value + on the right, for a clamped numeric pref. */
@Composable
private fun StepperRow(
    title: String,
    subtitle: String,
    value: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
) {
    val c = LocalCockpitColors.current
    HorizontalDivider(color = c.line.copy(alpha = 0.5f))
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = c.ink, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Text(subtitle, color = c.inkFaint, fontSize = 11.sp)
        }
        StepBtn("−", onMinus)
        Spacer(Modifier.width(10.dp))
        Text(value, color = c.ink, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        Spacer(Modifier.width(10.dp))
        StepBtn("+", onPlus)
    }
}

@Composable
private fun StepBtn(glyph: String, onClick: () -> Unit) {
    val c = LocalCockpitColors.current
    Box(
        Modifier.size(34.dp).clip(RoundedCornerShape(8.dp)).background(c.groundHi)
            .border(1.dp, c.line, RoundedCornerShape(8.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(glyph, color = c.ink, fontWeight = FontWeight.Bold, fontSize = 18.sp) }
}
