// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
//
// Mapas offline — the CFMOTO-first offline map/route pack manager, natively in Compose. Unlike the rest
// of the Map hub (which bridges the interactive engine back to GpxActivity), this screen drives the
// offline substrate DIRECTLY through the extracted Overtake library's [OfflineManager] facade
// (obtained via `overtakeOffline`): list downloaded areas (+size), download / delete an area, and the
// dashboard raster cache size/clear. Behind that facade sit the shared engines (MapLibre vector tiles,
// the BRouter/Overpass offline routing data, the named-area registry [OfflineAreasStore]) — the SAME
// engine the classic GpxActivity hub now drives. No bridge, no reimplemented engine. Download targets
// are "around me" (device GPS + radius) and a searched place ([NominatimSearch]); both feed the
// identical bbox -> download flow as GpxActivity.
// Pattern language matches SetupScreen / MapHubScreen (Group / chips / rows); Header is reused from
// SettingsScreen (same package, internal). Downloading a GPX-track corridor still lives in GpxActivity
// (it needs a loaded track) and is reachable from the hub.
package dev.zanderp.opencfmoto.ui.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import dev.zanderp.opencfmoto.R
import dev.zanderp.opencfmoto.MapPlace
import dev.zanderp.opencfmoto.overtakeOffline
import dev.overtake.maps.OfflineManager
import dev.overtake.maps.search.NominatimSearch
import dev.overtake.maps.route.offline.OfflineAreasStore
import dev.zanderp.opencfmoto.ui.components.MonoLabel
import dev.zanderp.opencfmoto.ui.theme.LocalCockpitColors
import java.util.Locale

private val RADIUS_OPTIONS = listOf(10, 25, 50)

/**
 * The native "Mapas offline" screen. Lists downloaded areas (name + detail + on-disk size), deletes an
 * area (tiles + routing data + registry), and downloads a new one around the device location or a
 * searched place at a chosen radius/detail — all through the library's [OfflineManager] facade, i.e.
 * the exact engine the classic hub uses.
 */
@Composable
fun OfflinePacksScreen(nav: NavController) {
    val c = LocalCockpitColors.current
    val ctx = LocalContext.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    // Marshal engine callbacks (MapLibre callback thread / route-build worker / place-search worker)
    // onto the UI thread before touching Compose state.
    val ui: (() -> Unit) -> Unit = remember { { block -> mainHandler.post(block) } }
    // The extracted offline-data manager — the shared engine the classic GpxActivity hub also drives.
    val offline = remember { overtakeOffline(ctx) }

    // --- Download options (shared by both download modes). ---
    var highDetail by remember { mutableStateOf(false) }
    var radiusKm by remember { mutableStateOf(25) }

    // --- Live in-flight download state. ---
    var downloading by remember { mutableStateOf(false) }
    var downloadName by remember { mutableStateOf("") }
    var phase by remember { mutableStateOf<OfflineManager.Phase?>(null) }
    var percent by remember { mutableStateOf(0) }
    var bytes by remember { mutableStateOf(0L) }
    var status by remember { mutableStateOf<String?>(null) }

    // --- Downloaded areas + sizes + raster cache; re-read on each resume (and after every op). ---
    var refreshTick by remember { mutableStateOf(0) }
    var areas by remember { mutableStateOf<List<OfflineAreasStore.Area>>(emptyList()) }
    var vectorSizes by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    var rasterBytes by remember { mutableStateOf(0L) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) refreshTick++
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(refreshTick) {
        areas = offline.storedAreas()
        rasterBytes = offline.rasterCacheBytes()
        // Real vector sizes come back async from the MapLibre region DB.
        offline.areaSizes { sizes ->
            ui { vectorSizes = sizes }
        }
    }

    // --- Dialog state. ---
    var searchOpen by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<OfflineAreasStore.Area?>(null) }

    fun startDownload(name: String, bbox: OfflineManager.Bbox) {
        if (downloading) return
        downloading = true
        downloadName = name
        phase = OfflineManager.Phase.TILES
        percent = 0
        bytes = 0
        status = null
        offline.download(
            name,
            bbox,
            highDetail,
            onPhase = { p -> ui { phase = p } },
            onProgress = { pc, b -> ui { percent = pc; bytes = b } },
            onDone = { _, _, msg ->
                ui {
                    downloading = false
                    phase = null
                    status = msg
                    refreshTick++
                }
            },
        )
    }

    // "Around me" needs a location fix; request permission then use the last known location.
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            status = ctx.getString(R.string.ovk_off_perm_denied)
            return@rememberLauncherForActivityResult
        }
        val here = lastKnown(ctx)
        if (here == null) {
            status = ctx.getString(R.string.ovk_off_no_gps)
        } else {
            startDownload(
                ctx.getString(R.string.ovk_off_near_me, radiusKm),
                offline.bboxAround(here.first, here.second, radiusKm),
            )
        }
    }

    fun downloadAroundMe() {
        if (downloading) return
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            return
        }
        val here = lastKnown(ctx)
        if (here == null) {
            status = ctx.getString(R.string.ovk_off_no_gps)
        } else {
            startDownload(
                ctx.getString(R.string.ovk_off_near_me, radiusKm),
                offline.bboxAround(here.first, here.second, radiusKm),
            )
        }
    }

    Column(
        Modifier.fillMaxSize().background(c.ground).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Header(stringResource(R.string.ovk_off_title)) { nav.popBackStack() }

        Text(
            stringResource(R.string.ovk_off_subtitle),
            color = c.inkFaint,
            fontSize = 12.sp,
        )

        // In-flight download card.
        if (downloading) {
            Group(stringResource(R.string.ovk_off_downloading)) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp)) {
                    Text(downloadName, color = c.ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    when (phase) {
                        OfflineManager.Phase.ROUTING -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    color = c.ignition,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    stringResource(R.string.ovk_off_building_routes),
                                    color = c.inkDim,
                                    fontSize = 12.sp,
                                )
                            }
                        }
                        else -> {
                            ProgressBar(percent)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                stringResource(R.string.ovk_off_map_progress, percent, fmtSize(bytes)),
                                color = c.inkDim,
                                fontSize = 12.sp,
                            )
                        }
                    }
                }
            }
        }

        // Last op result line.
        status?.let { msg ->
            if (!downloading) {
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.groundHi)
                        .border(1.dp, c.line, RoundedCornerShape(10.dp)).padding(12.dp),
                ) { Text(msg, color = c.inkDim, fontSize = 12.sp) }
            }
        }

        // 1 · Download a new area.
        Group(stringResource(R.string.ovk_off_download_new)) {
            // Detail (Standard / High) → max zoom.
            LabeledChips(
                stringResource(R.string.ovk_off_detail),
                first = true,
                options = listOf(stringResource(R.string.ovk_hub_quality_standard) to false, stringResource(R.string.ovk_hub_quality_high) to true),
                selected = highDetail,
                enabled = !downloading,
            ) { highDetail = it }
            // Radius, applied to both download targets.
            LabeledChips(
                stringResource(R.string.ovk_off_radius),
                options = RADIUS_OPTIONS.map { "$it km" to it },
                selected = radiusKm,
                enabled = !downloading,
            ) { radiusKm = it }
            ActionRow(
                stringResource(R.string.ovk_off_around_me),
                stringResource(R.string.ovk_off_around_me_sub),
                enabled = !downloading,
            ) { downloadAroundMe() }
            ActionRow(
                stringResource(R.string.ovk_off_search_place),
                stringResource(R.string.ovk_off_search_place_sub),
                enabled = !downloading,
            ) { searchOpen = true }
        }

        // 2 · Downloaded areas.
        Group(stringResource(R.string.ovk_off_downloaded_areas)) {
            if (areas.isEmpty()) {
                InfoText(stringResource(R.string.ovk_hub_no_areas), first = true)
            } else {
                areas.forEachIndexed { i, a ->
                    AreaRow(
                        name = a.name,
                        detail = areaDetail(ctx, a, vectorSizes[a.name] ?: 0L),
                        enabled = !downloading,
                        first = i == 0,
                    ) { pendingDelete = a }
                }
            }
        }

        // 3 · Bike dashboard raster cache (osmdroid). Not an "area", but rider-facing storage.
        Group(stringResource(R.string.ovk_off_dash_cache)) {
            InfoText(
                stringResource(R.string.ovk_off_raster_tiles, fmtSize(rasterBytes).ifEmpty { "0 KB" }),
                first = true,
            )
            ActionRow(
                stringResource(R.string.ovk_off_clear_cache),
                stringResource(R.string.ovk_off_clear_cache_sub),
                enabled = !downloading && rasterBytes > 0,
            ) {
                offline.clearRasterCache()
                status = ctx.getString(R.string.ovk_off_cache_cleared)
                refreshTick++
            }
        }
    }

    // --- Search dialog: geocode a place, then download around the picked result. ---
    if (searchOpen) {
        SearchPlaceDialog(
            onDismiss = { searchOpen = false },
            onPick = { place ->
                searchOpen = false
                startDownload(
                    place.name.take(28),
                    offline.bboxAround(place.lat, place.lon, radiusKm),
                )
            },
            ui = ui,
            near = lastKnown(ctx),
        )
    }

    // --- Delete confirm. ---
    pendingDelete?.let { area ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            containerColor = c.surface1,
            title = { Text(stringResource(R.string.ovk_off_delete_area), color = c.ink) },
            text = {
                Text(
                    stringResource(R.string.ovk_off_delete_confirm, area.name),
                    color = c.inkDim,
                    fontSize = 13.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    offline.delete(area.name) { ok ->
                        ui {
                            status = if (ok) {
                                ctx.getString(R.string.ovk_off_area_deleted, area.name)
                            } else {
                                ctx.getString(R.string.ovk_off_area_deleted_partial, area.name)
                            }
                            refreshTick++
                        }
                    }
                }) { Text(stringResource(R.string.ovk_hub_delete), color = c.fault) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.ovk_cancel), color = c.inkDim) }
            },
        )
    }
}

/** last known device location (GPS then network), or null if no permission / no fix. */
private fun lastKnown(ctx: Context): Pair<Double, Double>? {
    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
        != PackageManager.PERMISSION_GRANTED
    ) return null
    val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
    val loc = runCatching { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull()
        ?: runCatching { lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }.getOrNull()
    return loc?.let { it.latitude to it.longitude }
}

/** zoom detail + tile kind + on-disk size of a stored offline area. */
private fun areaDetail(ctx: Context, a: OfflineAreasStore.Area, vectorBytes: Long): String {
    val quality = if (a.zoomMax >= OfflineAreasStore.AREA_ZOOM_HIGH_MAX) ctx.getString(R.string.ovk_hub_quality_high) else ctx.getString(R.string.ovk_hub_quality_standard)
    val kind = when {
        a.vector && a.raster -> ctx.getString(R.string.ovk_hub_kind_both)
        a.vector -> ctx.getString(R.string.ovk_hub_kind_vector)
        a.raster -> ctx.getString(R.string.ovk_hub_kind_raster)
        else -> "—"
    }
    val size = fmtSize(vectorBytes)
    return if (size.isEmpty()) "$quality · $kind" else "$quality · $kind · $size"
}

private fun fmtSize(bytes: Long): String {
    if (bytes <= 0L) return ""
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1.0) {
        String.format(Locale.US, "%.0f MB", mb)
    } else {
        String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    }
}

// --- Dialogs -------------------------------------------------------------------------------------

/** Type a query → [NominatimSearch] → pick a result to download around it. */
@Composable
private fun SearchPlaceDialog(
    onDismiss: () -> Unit,
    onPick: (MapPlace) -> Unit,
    ui: (() -> Unit) -> Unit,
    near: Pair<Double, Double>?,
) {
    val c = LocalCockpitColors.current
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<MapPlace>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }

    fun doSearch() {
        val q = query.trim()
        if (q.isEmpty() || searching) return
        searching = true
        error = null
        results = emptyList()
        NominatimSearch.searchAsync(
            q,
            nearLat = near?.first,
            nearLon = near?.second,
            onResult = { list -> ui { searching = false; results = list } },
            onError = { e -> ui { searching = false; error = e } },
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface1,
        title = { Text(stringResource(R.string.ovk_off_search_place), color = c.ink) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.ovk_off_search_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = fieldColors(),
                )
                when {
                    searching -> Text(stringResource(R.string.ovk_searching), color = c.inkFaint, fontSize = 12.sp)
                    error != null -> Text(error!!, color = c.fault, fontSize = 12.sp)
                    results.isEmpty() ->
                        Text(stringResource(R.string.ovk_off_type_search), color = c.inkFaint, fontSize = 12.sp)
                    else -> Column(
                        Modifier.fillMaxWidth().heightIn(max = 280.dp).verticalScroll(rememberScrollState()),
                    ) {
                        results.forEachIndexed { i, p ->
                            if (i > 0) HorizontalDivider(color = c.line.copy(alpha = 0.5f))
                            Column(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                    .clickable { onPick(p) }.padding(vertical = 8.dp, horizontal = 4.dp),
                            ) {
                                Text(p.name, color = c.ink, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                if (p.subtitle.isNotBlank()) {
                                    Text(p.subtitle, color = c.inkFaint, fontSize = 11.sp, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { doSearch() }) { Text(stringResource(R.string.ovk_hub_search), color = c.ignition) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ovk_close), color = c.inkDim) } },
    )
}

@Composable
private fun fieldColors(): TextFieldColors {
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

// --- Cockpit-styled building blocks (mirroring SetupScreen / MapHubScreen; those are file-private). --

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    val c = LocalCockpitColors.current
    MonoLabel(title, color = c.inkFaint)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surface1)
            .border(1.dp, c.line, RoundedCornerShape(12.dp)),
    ) { content() }
}

/** Title + a wrapping row of pick-one chips; the selected chip is ignition. */
@Composable
private fun <T> LabeledChips(
    title: String,
    options: List<Pair<String, T>>,
    selected: T,
    enabled: Boolean,
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
                Seg(label, value == selected, enabled) { onSelect(value) }
            }
        }
    }
}

@Composable
private fun Seg(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val c = LocalCockpitColors.current
    Box(
        Modifier.clip(RoundedCornerShape(8.dp))
            .background(if (selected) c.ignition else c.groundHi)
            .border(1.dp, if (selected) c.ignition else c.line, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (selected) c.onIgnition else if (enabled) c.inkDim else c.inkFaint,
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
        )
    }
}

/** Title + subtitle + chevron; runs [onClick] when enabled. */
@Composable
private fun ActionRow(title: String, subtitle: String, enabled: Boolean, onClick: () -> Unit) {
    val c = LocalCockpitColors.current
    HorizontalDivider(color = c.line.copy(alpha = 0.5f))
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = if (enabled) c.ink else c.inkFaint,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
            )
            Text(subtitle, color = c.inkFaint, fontSize = 11.sp)
        }
        Text("›", color = c.inkFaint, fontSize = 16.sp)
    }
}

/** A downloaded area: name + detail on the left, a Borrar action on the right. */
@Composable
private fun AreaRow(
    name: String,
    detail: String,
    enabled: Boolean,
    first: Boolean,
    onDelete: () -> Unit,
) {
    val c = LocalCockpitColors.current
    if (!first) HorizontalDivider(color = c.line.copy(alpha = 0.5f))
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, color = c.ink, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Text(detail, color = c.inkFaint, fontSize = 11.sp)
        }
        Box(
            Modifier.clip(RoundedCornerShape(8.dp)).border(1.dp, c.line, RoundedCornerShape(8.dp))
                .clickable(enabled = enabled, onClick = onDelete)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(stringResource(R.string.ovk_hub_delete), color = if (enabled) c.fault else c.inkFaint, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
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

/** A thin determinate progress track (custom, so no Material3 progress-API version drift). */
@Composable
private fun ProgressBar(percent: Int) {
    val c = LocalCockpitColors.current
    val frac = (percent.coerceIn(0, 100)) / 100f
    Box(
        Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(c.groundHi),
    ) {
        if (frac > 0f) {
            Box(
                Modifier.fillMaxWidth(frac).height(8.dp).clip(RoundedCornerShape(4.dp)).background(c.ignition),
            )
        }
    }
}
