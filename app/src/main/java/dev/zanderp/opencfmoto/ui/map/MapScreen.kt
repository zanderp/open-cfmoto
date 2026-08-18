// SPDX-License-Identifier: AGPL-3.0-or-later
// Map — the CFMOTO-first map screen. A live osmdroid MapView (the built-in "Propio" map — the AA-free
// star of CFMOTO mode) with an EXPLICIT provider selector: Propio (own map on the dash), Google (via
// Android Auto), Waze (via mirror). The provider choice is, in practice, the projection-mode choice.
// The heavy hub — search, routes, favorites, offline, and the actual dash projection — lives in the
// proven GpxActivity one tap away; this screen is the entry + the provider choice. See the plan.
package dev.zanderp.opencfmoto.ui.map

import android.content.Context
import android.content.Intent
import android.widget.FrameLayout
import android.widget.Toast
import androidx.annotation.StringRes
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.zanderp.opencfmoto.R
import dev.zanderp.opencfmoto.GpxActivity
import dev.overtake.maps.RendererKind
import dev.overtake.maps.contract.MapRenderer
import dev.zanderp.opencfmoto.NavLauncher
import dev.zanderp.opencfmoto.overtakeOffline
import dev.zanderp.opencfmoto.overtakeRenderer
import dev.zanderp.opencfmoto.NightPrefs
import dev.zanderp.opencfmoto.ui.components.MapThemeToggle
import dev.zanderp.opencfmoto.settings.DashRenderer
import dev.zanderp.opencfmoto.settings.MapProvider
import dev.zanderp.opencfmoto.settings.SettingsStore
import dev.zanderp.opencfmoto.ui.Routes
import dev.zanderp.opencfmoto.ui.components.MonoLabel
import dev.zanderp.opencfmoto.ui.components.PrimaryButton
import dev.zanderp.opencfmoto.ui.theme.LocalCockpitColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun MapScreen(nav: NavController) {
    val c = LocalCockpitColors.current
    val ctx = LocalContext.current
    val store = remember { SettingsStore(ctx.applicationContext) }
    val provider by store.mapProvider.collectAsStateWithLifecycle(initialValue = MapProvider.BUILTIN)
    val renderer by store.dashRenderer.collectAsStateWithLifecycle(initialValue = DashRenderer.MAPLIBRE)
    val scope = rememberCoroutineScope()

    // Strict Mapsforge gate: when the built-in dash renderer is Mapsforge but no offline `.map` is
    // installed, the dash has nothing to draw — show a "download a map" card instead of a map (no
    // osmdroid fallback). Default true so the gate never flashes before the (off-main) check returns;
    // re-checked on resume so it clears right after the rider downloads one.
    var mfTick by remember { mutableStateOf(0) }
    var hasMapsforge by remember { mutableStateOf(true) }
    LaunchedEffect(mfTick) {
        hasMapsforge = withContext(Dispatchers.IO) {
            runCatching { overtakeOffline(ctx).hasMapsforgeMaps() }.getOrDefault(true)
        }
    }
    val needsMapsforgeMap =
        provider == MapProvider.BUILTIN && renderer == DashRenderer.MAPSFORGE && !hasMapsforge

    // The map now renders the SELECTED engine (SettingsStore.dashRenderer) — MapLibre (Liberty vector
    // + 3D), osmdroid raster, or Mapsforge — the exact renderer the dash uses, so this preview is
    // WYSIWYG. The old embed hardcoded osmdroid Mapnik and ignored the choice (the bug). Recreated when
    // the choice changes. NOTE: the library forces MapLibre whenever the host Context is an Activity
    // (this Compose LocalContext is one), so on the phone every choice renders MapLibre — the premium
    // look the owner wants — which is the documented phone-preview behavior of the renderer.
    val kind = remember(renderer) {
        when (renderer) {
            DashRenderer.OSMDROID -> RendererKind.OSMDROID
            DashRenderer.MAPSFORGE -> RendererKind.MAPSFORGE
            DashRenderer.MAPLIBRE -> RendererKind.MAPLIBRE
        }
    }
    // Fresh host + renderer per engine choice (and per gate flip) so a switch tears the old one down.
    val host = remember(kind, needsMapsforgeMap) { FrameLayout(ctx) }
    val mapRenderer: MapRenderer = remember(kind, needsMapsforgeMap) { overtakeRenderer(ctx, kind) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(kind, needsMapsforgeMap) {
        if (!needsMapsforgeMap) {
            mapRenderer.attach(ctx, host)
            mapRenderer.setBuildings3d(true) // the premium 3D look on MapLibre (no-op on raster)
            mapRenderer.onCreate(null)
            mapRenderer.onResume()
            // Apply the rider's day/night choice on attach (the renderer starts day-neutral). Same
            // NightPrefs the on-map toggle + Settings write, so all three agree.
            mapRenderer.applyTheme(NightPrefs.isNightNow(ctx))
        }
        val obs = LifecycleEventObserver { _, e ->
            if (needsMapsforgeMap) {
                if (e == Lifecycle.Event.ON_RESUME) mfTick++ // re-check so the gate clears after a download
                return@LifecycleEventObserver
            }
            when (e) {
                Lifecycle.Event.ON_RESUME -> {
                    mapRenderer.onResume()
                    mapRenderer.applyTheme(NightPrefs.isNightNow(ctx)) // reflect a theme change made in Settings
                    mfTick++
                }
                Lifecycle.Event.ON_PAUSE -> mapRenderer.onPause()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(obs)
            if (!needsMapsforgeMap) {
                runCatching { mapRenderer.onPause() }
                runCatching { mapRenderer.onStop() }
                runCatching { mapRenderer.onDestroy() }
            }
        }
    }
    // MapLibre's style loads async and setCenter no-ops until the map is ready, so nudge the default
    // center (Bogotá area) a few times right after attach until it takes — avoids a world-view flash.
    LaunchedEffect(kind, needsMapsforgeMap) {
        if (needsMapsforgeMap) return@LaunchedEffect
        repeat(14) {
            mapRenderer.setCenter(4.6486, -74.2479, 14.5)
            delay(90)
        }
    }

    Column(
        Modifier.fillMaxSize().background(c.ground).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Top bar
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            GlyphBox("‹") { nav.popBackStack() }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.ovk_mode_map), color = c.ink, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                MonoLabel(stringResource(providerSubtitleRes(provider)), color = c.inkDim)
            }
            GlyphBox("⌕") { GpxActivity.start(ctx) } // full hub: search / routes / favorites
        }

        // Live map + provider selector overlay. When the Mapsforge renderer has no offline map, the
        // map is replaced by a clear "download a map" card (strict — no osmdroid stand-in).
        Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(1.dp, c.line, RoundedCornerShape(14.dp))) {
            if (needsMapsforgeMap) {
                MapsforgeNeedsMapCard(
                    modifier = Modifier.fillMaxSize(),
                    onDownload = { nav.navigate(Routes.MAPSFORGE_MAPS) },
                )
            } else {
                // key(kind) so switching engines disposes the old AndroidView subtree and hosts the new.
                key(kind) {
                    AndroidView(factory = { host }, modifier = Modifier.fillMaxSize())
                }
            }
            ProviderSelector(
                selected = provider,
                onSelect = { scope.launch { store.setMapProvider(it) } },
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(10.dp),
            )
            // Persistent day/night/auto toggle, on the map itself (not just in Settings).
            if (!needsMapsforgeMap) {
                MapThemeToggle(
                    modifier = Modifier.align(Alignment.TopEnd).padding(10.dp),
                    onThemeChanged = { night -> mapRenderer.applyTheme(night) },
                )
            }
        }

        // How the chosen provider reaches the dash (teaches the AA-vs-CFMOTO constraint)
        MonoLabel(stringResource(providerReachRes(provider)), color = c.inkDim)

        // Adaptive primary action
        PrimaryButton(
            text = stringResource(providerActionRes(provider)),
            onClick = {
                when (provider) {
                    MapProvider.GOOGLE -> openApp(ctx, "com.google.android.apps.maps", "Google Maps")
                    MapProvider.WAZE -> NavLauncher.openWaze(ctx) {}
                    else -> GpxActivity.start(ctx) // BUILTIN / MIRROR → own map hub (has project-to-dash)
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@StringRes
private fun providerSubtitleRes(p: MapProvider): Int = when (p) {
    MapProvider.BUILTIN -> R.string.ovk_map_sub_builtin
    MapProvider.GOOGLE -> R.string.ovk_map_sub_google
    MapProvider.WAZE -> R.string.ovk_map_sub_waze
    MapProvider.MIRROR -> R.string.ovk_map_sub_mirror
}

@StringRes
private fun providerReachRes(p: MapProvider): Int = when (p) {
    MapProvider.BUILTIN -> R.string.ovk_map_reach_builtin
    MapProvider.GOOGLE -> R.string.ovk_map_reach_google
    MapProvider.WAZE -> R.string.ovk_map_reach_waze
    MapProvider.MIRROR -> R.string.ovk_map_reach_mirror
}

@StringRes
private fun providerActionRes(p: MapProvider): Int = when (p) {
    MapProvider.GOOGLE -> R.string.ovk_map_action_google
    MapProvider.WAZE -> R.string.ovk_map_action_waze
    else -> R.string.ovk_map_action_builtin
}

private fun openApp(ctx: Context, pkg: String, label: String) {
    val i = ctx.packageManager.getLaunchIntentForPackage(pkg)
    if (i != null) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    } else {
        Toast.makeText(ctx, ctx.getString(R.string.ovk_app_not_installed, label), Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun ProviderSelector(selected: MapProvider, onSelect: (MapProvider) -> Unit, modifier: Modifier) {
    val c = LocalCockpitColors.current
    Row(
        modifier.clip(RoundedCornerShape(11.dp)).background(c.ground.copy(alpha = 0.85f)).border(1.dp, c.line, RoundedCornerShape(11.dp)).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        ProviderOption("Overtake", stringResource(R.string.ovk_map_opt_dash), selected == MapProvider.BUILTIN, Modifier.weight(1f)) { onSelect(MapProvider.BUILTIN) }
        ProviderOption("Google", stringResource(R.string.ovk_map_opt_aa), selected == MapProvider.GOOGLE, Modifier.weight(1f)) { onSelect(MapProvider.GOOGLE) }
        ProviderOption("Waze", stringResource(R.string.ovk_map_opt_aa), selected == MapProvider.WAZE, Modifier.weight(1f)) { onSelect(MapProvider.WAZE) }
    }
}

@Composable
private fun ProviderOption(label: String, sub: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = LocalCockpitColors.current
    Column(
        modifier.clip(RoundedCornerShape(8.dp)).background(if (selected) c.ignition else Color.Transparent).clickable(onClick = onClick).padding(vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, color = if (selected) c.onIgnition else c.ink, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
        Text(sub, color = if (selected) c.onIgnition.copy(alpha = 0.8f) else c.inkDim, fontSize = 9.sp)
    }
}

/** Strict Mapsforge gate: shown in place of the map when the Mapsforge renderer has no `.map`. */
@Composable
private fun MapsforgeNeedsMapCard(modifier: Modifier, onDownload: () -> Unit) {
    val c = LocalCockpitColors.current
    Column(
        modifier.background(c.ground).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.ovk_mf_needs_map_title),
            color = c.ink,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            textAlign = TextAlign.Center,
        )
        Text(
            stringResource(R.string.ovk_mf_needs_map_body),
            color = c.inkDim,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )
        PrimaryButton(
            text = stringResource(R.string.ovk_mf_needs_map_cta),
            onClick = onDownload,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun GlyphBox(glyph: String, onClick: () -> Unit) {
    val c = LocalCockpitColors.current
    Box(
        Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(c.surface1).border(1.dp, c.line, RoundedCornerShape(10.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(glyph, color = c.inkDim, fontSize = 20.sp) }
}
