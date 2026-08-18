// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
//
// Mapas offline · Mapsforge — the native Compose catalog + downloader for the Mapsforge offline VECTOR
// `.map` files the RendererKind.MAPSFORGE engine renders. Unlike OfflinePacksScreen (bbox "areas" —
// MapLibre vector regions + BRouter routing data), this drives the SEPARATE Mapsforge `.map` surface
// on the library's [OfflineManager] facade (via `overtakeOffline`): list installed `.map` files
// (name + size + delete), browse the mapsforge.org catalog by continent → country → sub-region, and
// stream-download a chosen regional `.map` with a live progress bar. A "Suggested for you" section at
// the top offers the rider's own region first (country detected best-effort by MapCountryHint). All
// HTTP + parsing + streaming lives in the library; this screen is pure UI + state. Pattern language
// matches OfflinePacksScreen / SetupScreen (Group / chips / rows / ProgressBar); Header is reused from
// SettingsScreen (same package, internal).
package dev.zanderp.opencfmoto.ui.settings

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import dev.overtake.maps.MapsforgeCatalogPage
import dev.overtake.maps.MapsforgeDownloadStatus
import dev.overtake.maps.MapsforgeMap
import dev.zanderp.opencfmoto.MapCountryHint
import dev.zanderp.opencfmoto.MapDownloadService
import dev.zanderp.opencfmoto.R
import dev.zanderp.opencfmoto.overtakeOffline
import dev.zanderp.opencfmoto.ui.components.MonoLabel
import dev.zanderp.opencfmoto.ui.theme.LocalCockpitColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** Above this we flag a download as "large" and nudge toward Wi-Fi (Mapsforge regionals run 300MB–2GB). */
private const val LARGE_BYTES = 400L * 1024 * 1024

/**
 * The native "Mapas offline · Mapsforge" screen. Installed `.map` files (delete), a continent browser
 * over the mapsforge.org catalog (drill in, download with live progress), and a "suggested for you"
 * shortcut to the rider's own region — all through the library's [dev.overtake.maps.OfflineManager].
 */
@Composable
fun MapsforgeMapsScreen(nav: NavController) {
    val c = LocalCockpitColors.current
    val ctx = LocalContext.current
    val offline = remember { overtakeOffline(ctx) }

    var refreshTick by remember { mutableStateOf(0) }

    // Installed `.map` files (name + on-disk size).
    var installed by remember { mutableStateOf<List<File>>(emptyList()) }

    // "Suggested for you" — the rider's region, detected best-effort (may stay empty).
    var suggestions by remember { mutableStateOf<List<MapsforgeMap>>(emptyList()) }

    // Catalog browser: current path + fetched page + load state.
    var path by remember { mutableStateOf("") }
    var page by remember { mutableStateOf<MapsforgeCatalogPage?>(null) }
    var loading by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf(false) }

    // The single app-scoped download's live state. The transfer runs in MapDownloadService / the
    // library worker — NOT in this screen — so it survives navigation; re-collecting here on re-entry
    // re-attaches to a still-running download instead of starting a new one.
    val dl by offline.mapsforgeDownloadState().collectAsState()
    val downloading = dl.isActive

    // Last op status line (a download's terminal result, or a delete result).
    var status by remember { mutableStateOf<String?>(null) }

    var pendingDelete by remember { mutableStateOf<File?>(null) }

    // Re-read installed maps on resume and after every op.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) refreshTick++
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(refreshTick) {
        installed = withContext(Dispatchers.IO) {
            runCatching { offline.installedMapsforgeMaps() }.getOrDefault(emptyList())
        }
    }
    // Suggestion once — country detection + a catalog lookup, both off the main thread.
    LaunchedEffect(Unit) {
        suggestions = withContext(Dispatchers.IO) {
            val hint = MapCountryHint.detect(ctx)
            if (hint == null) {
                emptyList<MapsforgeMap>()
            } else {
                runCatching { offline.suggestMapsforgeMaps(hint.iso, hint.city) }.getOrDefault(emptyList())
            }
        }
    }
    // Browse whenever the path changes (or after an install, to refresh "Installed" badges).
    LaunchedEffect(path, refreshTick) {
        loading = true
        loadError = false
        val result = withContext(Dispatchers.IO) { runCatching { offline.browseMapsforge(path) } }
        result.onSuccess { page = it }.onFailure { loadError = true }
        loading = false
    }

    // Surface the download's terminal result as the status line, and refresh installed on success.
    // Keyed on (status, fileName) so a re-attach after navigation still reflects the outcome.
    LaunchedEffect(dl.status, dl.fileName) {
        when (dl.status) {
            MapsforgeDownloadStatus.SUCCESS -> {
                status = ctx.getString(R.string.ovk_mf_downloaded_ok, prettyName(dl.name))
                refreshTick++
            }
            MapsforgeDownloadStatus.FAILED ->
                status = ctx.getString(R.string.ovk_mf_download_failed, dl.message)
            MapsforgeDownloadStatus.CANCELED ->
                status = ctx.getString(R.string.ovk_mf_download_canceled)
            else -> Unit
        }
    }

    val installedNames = remember(installed) { installed.map { it.name.lowercase(Locale.US) }.toSet() }

    fun startDownload(map: MapsforgeMap) {
        if (downloading) return
        // Hand off to the foreground service; it starts the app-scoped library download (resumable +
        // auto-retrying) and keeps it alive with an ongoing % notification when you leave this screen.
        MapDownloadService.start(ctx, map.url, map.name)
    }

    Column(
        Modifier.fillMaxSize().background(c.ground).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Header(stringResource(R.string.ovk_mf_title)) { nav.popBackStack() }
        Text(stringResource(R.string.ovk_mf_subtitle), color = c.inkFaint, fontSize = 12.sp)

        // In-flight download card (keeps running even if you leave this screen).
        if (downloading) {
            Group(stringResource(R.string.ovk_mf_downloading)) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp)) {
                    Text(prettyName(dl.name), color = c.ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    when {
                        dl.status == MapsforgeDownloadStatus.RETRYING -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(color = c.ignition, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    stringResource(R.string.ovk_mf_download_retrying, dl.attempt, dl.maxAttempts),
                                    color = c.warn,
                                    fontSize = 12.sp,
                                )
                            }
                        }
                        dl.percent >= 0 -> {
                            ProgressBar(dl.percent)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                stringResource(
                                    R.string.ovk_mf_progress,
                                    dl.percent,
                                    fmtSize(dl.bytesRead),
                                    fmtSize(dl.totalBytes),
                                ),
                                color = c.inkDim,
                                fontSize = 12.sp,
                            )
                        }
                        else -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(color = c.ignition, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    stringResource(R.string.ovk_mf_progress_unknown, fmtSize(dl.bytesRead)),
                                    color = c.inkDim,
                                    fontSize = 12.sp,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlineButton(stringResource(R.string.ovk_mf_cancel_download), c.fault) {
                        offline.cancelMapsforgeDownload()
                    }
                }
            }
        }

        // Last op result line (+ a Resume affordance when a download failed with a kept `.part`).
        if (!downloading) {
            status?.let { msg ->
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.groundHi)
                        .border(1.dp, c.line, RoundedCornerShape(10.dp)).padding(12.dp),
                ) { Text(msg, color = c.inkDim, fontSize = 12.sp) }
            }
            if (dl.status == MapsforgeDownloadStatus.FAILED && dl.url.isNotBlank()) {
                OutlineButton(stringResource(R.string.ovk_mf_resume), c.ignition) {
                    MapDownloadService.start(ctx, dl.url, dl.name)
                }
            }
        }

        // 1 · Suggested for you (rider's region) — only when a match was found.
        if (suggestions.isNotEmpty()) {
            Group(stringResource(R.string.ovk_mf_suggested)) {
                Text(
                    stringResource(R.string.ovk_mf_your_region),
                    color = c.inkFaint,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 12.dp).padding(top = 10.dp),
                )
                suggestions.forEach { m ->
                    MapRow(
                        map = m,
                        installed = installedNames.contains(m.name.lowercase(Locale.US)),
                        enabled = !downloading,
                        onDownload = { startDownload(m) },
                    )
                }
            }
        }

        // 2 · Installed maps.
        Group(stringResource(R.string.ovk_mf_installed)) {
            if (installed.isEmpty()) {
                InfoText(stringResource(R.string.ovk_mf_none_installed), first = true)
            } else {
                installed.forEachIndexed { i, f ->
                    InstalledRow(
                        name = prettyName(f.name.removeSuffix(".map")),
                        size = fmtSize(f.length()),
                        enabled = !downloading,
                        first = i == 0,
                    ) { pendingDelete = f }
                }
            }
        }

        // 3 · Browse the catalog by continent.
        Group(stringResource(R.string.ovk_mf_browse)) {
            BreadcrumbRow(
                path = path,
                canGoUp = page?.parent != null || path.isNotEmpty(),
                enabled = !loading,
            ) { path = page?.parent ?: "" }

            when {
                loading -> LoadingRow()
                loadError -> ErrorRow(enabled = true) { refreshTick++ }
                else -> {
                    val p = page
                    if (p == null) {
                        LoadingRow()
                    } else {
                        p.dirs.forEach { d ->
                            DirRow(prettyName(d.name), enabled = !loading) { path = d.path }
                        }
                        if (p.maps.isEmpty() && p.dirs.isNotEmpty()) {
                            InfoText(stringResource(R.string.ovk_mf_no_maps_here))
                        }
                        p.maps.forEach { m ->
                            MapRow(
                                map = m,
                                installed = installedNames.contains(m.name.lowercase(Locale.US)),
                                enabled = !downloading,
                                onDownload = { startDownload(m) },
                            )
                        }
                    }
                }
            }
        }
    }

    // Delete confirm.
    pendingDelete?.let { file ->
        val label = prettyName(file.name.removeSuffix(".map"))
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            containerColor = c.surface1,
            title = { Text(stringResource(R.string.ovk_mf_delete_map), color = c.ink) },
            text = { Text(stringResource(R.string.ovk_mf_delete_confirm, label), color = c.inkDim, fontSize = 13.sp) },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    val ok = offline.deleteMapsforgeMap(file)
                    status = if (ok) {
                        ctx.getString(R.string.ovk_mf_map_deleted, label)
                    } else {
                        ctx.getString(R.string.ovk_mf_delete_failed, label)
                    }
                    refreshTick++
                }) { Text(stringResource(R.string.ovk_hub_delete), color = c.fault) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.ovk_cancel), color = c.inkDim)
                }
            },
        )
    }
}

/** `czech-republic` → `Czech Republic`; `us-west` → `Us West`. Display only. */
private fun prettyName(raw: String): String =
    raw.replace('_', ' ').replace('-', ' ').split(' ').joinToString(" ") { w ->
        w.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
    }

private fun fmtSize(bytes: Long): String {
    if (bytes <= 0L) return ""
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024.0) {
        String.format(Locale.US, "%.1f GB", mb / 1024.0)
    } else if (mb >= 1.0) {
        String.format(Locale.US, "%.0f MB", mb)
    } else {
        String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    }
}

// --- Cockpit-styled building blocks (mirroring OfflinePacksScreen; those are file-private there) ----

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    val c = LocalCockpitColors.current
    MonoLabel(title, color = c.inkFaint)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surface1)
            .border(1.dp, c.line, RoundedCornerShape(12.dp)),
    ) { content() }
}

/** A catalog `.map`: name + size (+large-file warning) on the left; Download / Installed on the right. */
@Composable
private fun MapRow(
    map: MapsforgeMap,
    installed: Boolean,
    enabled: Boolean,
    onDownload: () -> Unit,
) {
    val c = LocalCockpitColors.current
    HorizontalDivider(color = c.line.copy(alpha = 0.5f))
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(prettyName(map.name), color = c.ink, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            val size = fmtSize(map.sizeBytes)
            if (size.isNotEmpty()) Text(size, color = c.inkFaint, fontSize = 11.sp)
            if (map.sizeBytes >= LARGE_BYTES) {
                Text(
                    stringResource(R.string.ovk_mf_large_warn, fmtSize(map.sizeBytes)),
                    color = c.warn,
                    fontSize = 10.sp,
                )
            }
        }
        if (installed) {
            Text(
                stringResource(R.string.ovk_mf_installed_badge),
                color = c.live,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
        } else {
            SmallButton(stringResource(R.string.ovk_mf_download), enabled = enabled, onClick = onDownload)
        }
    }
}

/** A drill-in sub-directory row (continent / country with sub-regions). */
@Composable
private fun DirRow(name: String, enabled: Boolean, onClick: () -> Unit) {
    val c = LocalCockpitColors.current
    HorizontalDivider(color = c.line.copy(alpha = 0.5f))
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, color = c.ink, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text("›", color = c.inkFaint, fontSize = 16.sp)
    }
}

/** An installed `.map`: name + size on the left, a delete action on the right. */
@Composable
private fun InstalledRow(name: String, size: String, enabled: Boolean, first: Boolean, onDelete: () -> Unit) {
    val c = LocalCockpitColors.current
    if (!first) HorizontalDivider(color = c.line.copy(alpha = 0.5f))
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, color = c.ink, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            if (size.isNotEmpty()) Text(size, color = c.inkFaint, fontSize = 11.sp)
        }
        Box(
            Modifier.clip(RoundedCornerShape(8.dp)).border(1.dp, c.line, RoundedCornerShape(8.dp))
                .clickable(enabled = enabled, onClick = onDelete)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(
                stringResource(R.string.ovk_hub_delete),
                color = if (enabled) c.fault else c.inkFaint,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** Breadcrumb of the current catalog path + an "up one level" affordance. */
@Composable
private fun BreadcrumbRow(path: String, canGoUp: Boolean, enabled: Boolean, onUp: () -> Unit) {
    val c = LocalCockpitColors.current
    val label = if (path.isEmpty()) stringResource(R.string.ovk_mf_root) else prettyName(path.trim('/').replace('/', ' '))
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = c.inkDim, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, modifier = Modifier.weight(1f))
        if (canGoUp) {
            SmallButton(stringResource(R.string.ovk_mf_up), enabled = enabled, onClick = onUp)
        }
    }
}

@Composable
private fun LoadingRow() {
    val c = LocalCockpitColors.current
    HorizontalDivider(color = c.line.copy(alpha = 0.5f))
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(color = c.ignition, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(10.dp))
        Text(stringResource(R.string.ovk_mf_loading), color = c.inkFaint, fontSize = 12.sp)
    }
}

@Composable
private fun ErrorRow(enabled: Boolean, onRetry: () -> Unit) {
    val c = LocalCockpitColors.current
    HorizontalDivider(color = c.line.copy(alpha = 0.5f))
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.ovk_mf_load_error), color = c.inkDim, fontSize = 12.sp, modifier = Modifier.weight(1f))
        SmallButton(stringResource(R.string.ovk_mf_retry), enabled = enabled, onClick = onRetry)
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
    Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(c.groundHi)) {
        if (frac > 0f) {
            Box(Modifier.fillMaxWidth(frac).height(8.dp).clip(RoundedCornerShape(4.dp)).background(c.ignition))
        }
    }
}

/** Compact filled pill button (ignition). */
@Composable
private fun SmallButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val c = LocalCockpitColors.current
    Box(
        Modifier.clip(RoundedCornerShape(8.dp))
            .background(if (enabled) c.ignition else c.groundHi)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (enabled) c.onIgnition else c.inkFaint,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** Full-width outlined button (used for Cancel). */
@Composable
private fun OutlineButton(label: String, tint: Color, onClick: () -> Unit) {
    val c = LocalCockpitColors.current
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).border(1.dp, c.line, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick).padding(vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = tint, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
}
