// SPDX-License-Identifier: AGPL-3.0-or-later
// Garage — each paired bike with its mode and status. Tapping a bike sets its projection mode
// (CFMOTO / Android Auto); pairing a new one triggers the same choice.
package dev.zanderp.opencfmoto.ui.garage

import android.content.Intent
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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.navigation.NavController
import dev.zanderp.opencfmoto.R
import dev.zanderp.opencfmoto.BikeMemory
import dev.zanderp.opencfmoto.QrScanActivity
import dev.zanderp.opencfmoto.SavedBike
import dev.zanderp.opencfmoto.ui.components.MonoLabel
import dev.zanderp.opencfmoto.ui.settings.Header
import dev.zanderp.opencfmoto.ui.theme.LocalCockpitColors

@Composable
fun GarageScreen(nav: NavController) {
    val c = LocalCockpitColors.current
    val ctx = LocalContext.current
    val bikes = remember { BikeMemory.devices(ctx) }
    val selected = remember { BikeMemory.lastRaw(ctx) }
    var refresh by remember { mutableStateOf(0) }
    var modeFor by remember { mutableStateOf<SavedBike?>(null) }

    Column(
        Modifier.fillMaxSize().background(c.ground).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Header(stringResource(R.string.ovk_garage)) { nav.popBackStack() }
        Spacer(Modifier.size(2.dp))

        if (bikes.isEmpty()) {
            MonoLabel(stringResource(R.string.ovk_garage_empty), color = c.inkFaint)
        } else {
            bikes.forEach { bike -> BikeRow(bike, current = bike.raw == selected, refreshKey = refresh) { modeFor = bike } }
        }

        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp))
                .border(1.5.dp, c.line, RoundedCornerShape(13.dp))
                .clickable { nav.navigate("scan") }
                .padding(14.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("＋  " + stringResource(R.string.ovk_garage_pair_another), color = c.inkDim, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        }
        MonoLabel(stringResource(R.string.ovk_garage_tap_hint), color = c.inkFaint)
    }

    modeFor?.let { bike ->
        val ssid = bike.qr?.ssid ?: ""
        ModeDialog(
            bikeName = bike.name,
            onPick = { mode ->
                BikeMemory.setBikeMode(ctx, ssid, mode)
                refresh++
                modeFor = null
            },
            onDismiss = { modeFor = null },
        )
    }
}

@Composable
private fun BikeRow(bike: SavedBike, current: Boolean, refreshKey: Int, onClick: () -> Unit) {
    val c = LocalCockpitColors.current
    val ctx = LocalContext.current
    val ssid = remember(bike.raw) { bike.qr?.ssid ?: "" }
    val mode = remember(bike.raw, refreshKey) { BikeMemory.bikeMode(ctx, ssid) }
    val borderColor = if (current) c.ignition.copy(alpha = 0.45f) else c.line
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(c.surface1).border(1.dp, borderColor, RoundedCornerShape(13.dp)).clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(c.ground).border(1.dp, c.line, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) { Text("🏍", fontSize = 20.sp) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(bike.name, color = c.ink, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text(
                (if (ssid.isNotBlank()) "$ssid · " else "") + if (current) stringResource(R.string.ovk_garage_connected_recent) else stringResource(R.string.ovk_garage_saved),
                color = c.inkFaint, fontFamily = FontFamily.Monospace, fontSize = 9.sp,
            )
        }
        when (mode) {
            "ANDROID_AUTO" -> ModeTag("AUTO", aa = true)
            "CFMOTO" -> ModeTag("CFMOTO", aa = false)
            else -> ModeTag(stringResource(R.string.ovk_garage_no_mode), aa = true)
        }
    }
}

@Composable
private fun ModeTag(text: String, aa: Boolean) {
    val c = LocalCockpitColors.current
    val fg = if (aa) c.inkFaint else c.ignition
    val bg = if (aa) c.ground else c.ignition.copy(alpha = 0.12f)
    val bd = if (aa) c.line else c.ignition.copy(alpha = 0.35f)
    Box(
        Modifier.clip(RoundedCornerShape(6.dp)).background(bg).border(1.dp, bd, RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 3.dp),
    ) { Text(text, color = fg, fontFamily = FontFamily.Monospace, fontSize = 9.sp) }
}

@Composable
private fun ModeDialog(bikeName: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val c = LocalCockpitColors.current
    Dialog(onDismissRequest = onDismiss) {
        Surface(color = c.surface1, shape = RoundedCornerShape(18.dp), border = androidx.compose.foundation.BorderStroke(1.dp, c.line)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text(stringResource(R.string.ovk_dlg_mode_title, bikeName), color = c.ink, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(stringResource(R.string.ovk_garage_mode_subtitle), color = c.inkDim, fontSize = 12.5.sp)
                Spacer(Modifier.size(4.dp))
                ChoiceRow("CFMOTO", stringResource(R.string.ovk_dlg_mode_cfmoto_desc), primary = true) { onPick("CFMOTO") }
                ChoiceRow("Android Auto", stringResource(R.string.ovk_dlg_mode_aa_desc), primary = false) { onPick("ANDROID_AUTO") }
            }
        }
    }
}

@Composable
private fun ChoiceRow(title: String, subtitle: String, primary: Boolean, onClick: () -> Unit) {
    val c = LocalCockpitColors.current
    val bd = if (primary) c.ignition.copy(alpha = 0.55f) else c.line
    val bg = if (primary) c.ignition.copy(alpha = 0.12f) else c.groundHi
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(bg).border(1.dp, bd, RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = c.ink, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text(subtitle, color = c.inkDim, fontSize = 11.sp)
        }
        Text("›", color = c.inkFaint, fontSize = 18.sp)
    }
}
