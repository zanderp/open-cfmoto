// SPDX-License-Identifier: AGPL-3.0-or-later
// The redesigned dashboard. Reads live ConnectionState; the Connect flow runs the new UX (choose the
// bike's mode once, ask auto-connect consent once) and then hands off to the proven path for that
// mode (CFMOTO → the built-in map; Android Auto → the classic hub). No mode switch clutters the dash.
package dev.zanderp.opencfmoto.ui.dashboard

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.zanderp.opencfmoto.R
import dev.zanderp.opencfmoto.AppSettings
import dev.zanderp.opencfmoto.BikeMemory
import dev.zanderp.opencfmoto.ConnectionState
import dev.zanderp.opencfmoto.ControlsActivity
import dev.zanderp.opencfmoto.GpxSession
import dev.zanderp.opencfmoto.connection.CfmotoConnect
import dev.zanderp.opencfmoto.HudViewActivity
import dev.zanderp.opencfmoto.QrScanActivity
import dev.zanderp.opencfmoto.TripsListActivity
import dev.zanderp.opencfmoto.settings.MapProvider
import dev.zanderp.opencfmoto.settings.SettingsStore
import dev.zanderp.opencfmoto.ui.Routes
import dev.zanderp.opencfmoto.ui.components.AutoConnectDialog
import dev.zanderp.opencfmoto.ui.components.MODE_ANDROID_AUTO
import dev.zanderp.opencfmoto.ui.components.ModeChoiceDialog
import dev.zanderp.opencfmoto.ui.components.ConnectionGauge
import dev.zanderp.opencfmoto.ui.components.MonoLabel
import dev.zanderp.opencfmoto.ui.components.PrimaryButton
import dev.zanderp.opencfmoto.ui.components.StatusKind
import dev.zanderp.opencfmoto.ui.components.Tile
import dev.zanderp.opencfmoto.ui.components.kind
import dev.zanderp.opencfmoto.ui.theme.LocalCockpitColors
import kotlinx.coroutines.launch

// LocalContext inside CockpitActivity's setContent is the Activity today, but unwrap defensively so a
// future ContextThemeWrapper / @Preview / ComposeView host can't crash Conectar with a hard cast.
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun DashboardScreen(nav: NavController) {
    val c = LocalCockpitColors.current
    val ctx = LocalContext.current
    val store = remember { SettingsStore(ctx.applicationContext) }
    val scope = rememberCoroutineScope()
    val snap by ConnectionState.flow.collectAsStateWithLifecycle()
    val autoOn by store.autoConnect.collectAsStateWithLifecycle(initialValue = false)
    val prompted by store.autoConnectPrompted.collectAsStateWithLifecycle(initialValue = false)
    val provider by store.mapProvider.collectAsStateWithLifecycle(initialValue = MapProvider.BUILTIN)
    val kind = snap.phase.kind()
    val statusText = stringResource(snap.phase.labelRes)
    val bikeName = remember { BikeMemory.lastBikeName(ctx) ?: ctx.getString(R.string.ovk_no_bike_paired) }
    val hasBike = remember { BikeMemory.lastQr(ctx) != null }

    var showMode by remember { mutableStateOf(false) }
    var showConsent by remember { mutableStateOf(false) }

    // Foreground fallback: when the cockpit resumes and the bike is in range, auto-connect the built-in
    // map. Own-map ONLY (never auto-start Android Auto — AA 17.4+ blocks it), so it's skipped when the
    // provider is Google/Waze (AA) or Mirror. Gated on AppSettings.autoConnect inside the call, and
    // shares CfmotoConnect's single-flight with the background CompanionDeviceService (no double-fire).
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (provider == MapProvider.BUILTIN) {
            ctx.findActivity()?.let { CfmotoConnect.maybeAutoConnectCfmotoMap(it) }
        }
    }

    fun execute(mode: String?) {
        if (mode == MODE_ANDROID_AUTO) {
            // Android Auto: CONNECT + trigger the AA projection (Google Maps → dash) IN-PLACE via the
            // proven path, so the rider stays in Overtake (the cockpit already reflects
            // ConnectionState.flow: STARTING_AA → AA_VIDEO_LIVE) and the classic MainActivity UI never
            // shows. If no bike QR is saved yet, route to scan first. Mirrors the CFMOTO branch below.
            val activity = ctx.findActivity() ?: return
            val qr = BikeMemory.lastQr(ctx) ?: run { nav.navigate(Routes.SCAN); return }
            CfmotoConnect.startAaConnect(activity, qr)
            return
        }
        // CFMOTO: CONNECT + project the built-in free-ride map to the dash IN-PLACE. The rider stays in
        // the cockpit (which already reflects ConnectionState.flow) — the classic MainActivity UI never
        // shows. prepareFreeRide arms the session; CfmotoConnect.startCfmotoMap runs the proven connect
        // path (join bike Wi-Fi + project own content, no Android Auto). Fixes "Conectar opens the map menu".
        GpxSession.prepareFreeRide()
        val activity = ctx.findActivity() ?: return
        CfmotoConnect.startCfmotoMap(activity)
    }
    fun afterMode(mode: String) {
        if (!prompted) showConsent = true else execute(mode)
    }
    // The MAP PROVIDER is the single source of truth for how we project to the dash: Google → Android
    // Auto (Google Maps on the dash); Overtake/Waze → our own map, no AA. (Replaces the old per-bike
    // mode dialog, which could start AA even while the rider had the Overtake map selected.)
    fun connectMode(): String? = if (provider == MapProvider.GOOGLE || provider == MapProvider.WAZE) MODE_ANDROID_AUTO else null
    fun onConnect() {
        val ssid = BikeMemory.lastQr(ctx)?.ssid
        if (ssid == null) { nav.navigate(Routes.SCAN); return }
        if (!prompted) { showConsent = true; return }
        execute(connectMode())
    }

    Column(
        Modifier.fillMaxSize().background(c.ground).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text(stringResource(R.string.ovk_dashboard_title), color = c.ink, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                MonoLabel("OpenCfMoto · Overtake")
            }
            GlyphButton("⚙") { nav.navigate(Routes.SETTINGS) }
        }

        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surface1).border(1.dp, c.line, RoundedCornerShape(16.dp)).padding(vertical = 20.dp, horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ConnectionGauge(kind, gaugeGlyph(kind))
            Text(statusText, color = c.ink, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            MonoLabel(bikeName + if (hasBike) "  ·  " + stringResource(R.string.ovk_mode_per_bike) else "", color = c.inkDim)
        }

        // LIVE → Desconectar; BUSY (connecting) → Cancelar — both abort via CfmotoConnect.stop, so the
        // rider can cancel an in-progress connect from the cockpit (no return to the classic UI). Only
        // an idle/faulted gauge offers Conectar.
        val stoppable = kind == StatusKind.LIVE || kind == StatusKind.BUSY
        PrimaryButton(
            text = when (kind) {
                StatusKind.LIVE -> stringResource(R.string.ovk_disconnect)
                StatusKind.BUSY -> stringResource(R.string.ovk_cancel)
                else -> stringResource(R.string.ovk_connect)
            },
            onClick = { if (stoppable) CfmotoConnect.stop(ctx) else onConnect() },
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp)).background(c.live.copy(alpha = 0.12f)).border(1.dp, c.live.copy(alpha = 0.3f), RoundedCornerShape(11.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.ovk_auto_connect), color = c.ink, fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            Switch(checked = autoOn, onCheckedChange = { scope.launch { store.setAutoConnect(it) }; AppSettings.setAutoConnect(ctx, it) }, colors = SwitchDefaults.colors(checkedTrackColor = c.live))
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Tile("⌖", stringResource(R.string.ovk_tile_scan), stringResource(R.string.ovk_tile_scan_desc), { nav.navigate(Routes.SCAN) }, Modifier.weight(1f))
            Tile("◧", stringResource(R.string.ovk_mode_map), "Overtake", { nav.navigate(Routes.COCKPIT) }, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Tile("⊞", stringResource(R.string.ovk_provider_mirror), stringResource(R.string.ovk_tile_mirror_desc), { nav.navigate(Routes.MIRROR) }, Modifier.weight(1f))
            Tile("✥", stringResource(R.string.ovk_tile_controls), stringResource(R.string.ovk_tile_controls_desc), { nav.navigate(Routes.CONTROLS) }, Modifier.weight(1f))
        }

        GlyphRow("⌂", stringResource(R.string.ovk_garage), stringResource(R.string.ovk_garage_desc)) { nav.navigate(Routes.GARAGE) }
        GlyphRow("◴", stringResource(R.string.ovk_trips), stringResource(R.string.ovk_trips_desc)) { TripsListActivity.start(ctx) }
    }

    if (showMode) {
        ModeChoiceDialog(
            bikeName = bikeName,
            onPick = { mode ->
                val ssid = BikeMemory.lastQr(ctx)?.ssid ?: ""
                BikeMemory.setBikeMode(ctx, ssid, mode)
                showMode = false
                afterMode(mode)
            },
            onDismiss = { showMode = false },
        )
    }
    if (showConsent) {
        AutoConnectDialog(
            onAllow = {
                scope.launch { store.setAutoConnect(true) }
                AppSettings.setAutoConnect(ctx, true)
                showConsent = false
                execute(connectMode())
            },
            onDeny = {
                scope.launch { store.setAutoConnect(false) }
                AppSettings.setAutoConnect(ctx, false)
                showConsent = false
                execute(connectMode())
            },
        )
    }
}

private fun gaugeGlyph(kind: StatusKind): String = when (kind) {
    StatusKind.LIVE -> "⚡"
    StatusKind.BUSY -> "⟳"
    StatusKind.FAULT -> "!"
    StatusKind.IDLE -> "○"
}

@Composable
private fun GlyphButton(glyph: String, onClick: () -> Unit) {
    val c = LocalCockpitColors.current
    Box(
        Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(c.surface1).border(1.dp, c.line, RoundedCornerShape(10.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(glyph, color = c.inkDim, fontSize = 16.sp) }
}

@Composable
private fun GlyphRow(glyph: String, title: String, subtitle: String, onClick: () -> Unit) {
    val c = LocalCockpitColors.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.groundHi).border(1.dp, c.line, RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(c.surface1).border(1.dp, c.line, RoundedCornerShape(9.dp)),
            contentAlignment = Alignment.Center,
        ) { Text(glyph, color = c.ignition, fontSize = 15.sp) }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = c.ink, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            MonoLabel(subtitle, color = c.inkDim)
        }
        Text("›", color = c.inkDim, fontSize = 18.sp)
    }
}
