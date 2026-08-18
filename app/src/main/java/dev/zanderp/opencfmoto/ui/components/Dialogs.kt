// SPDX-License-Identifier: AGPL-3.0-or-later
// The two decision popups from the mockups: choose a bike's projection mode (once, at pairing) and
// consent to background auto-connect (once). Both write to the store; both are dismissible.
package dev.zanderp.opencfmoto.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import dev.zanderp.opencfmoto.R
import dev.zanderp.opencfmoto.ui.theme.LocalCockpitColors

/** Values match BikeMemory.setBikeMode / AppMode. */
const val MODE_CFMOTO = "CFMOTO"
const val MODE_ANDROID_AUTO = "ANDROID_AUTO"

@Composable
fun ModeChoiceDialog(bikeName: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val c = LocalCockpitColors.current
    DialogCard(onDismiss) {
        Text(stringResource(R.string.ovk_dlg_mode_title, bikeName), color = c.ink, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(stringResource(R.string.ovk_dlg_mode_subtitle), color = c.inkDim, fontSize = 12.5.sp)
        Spacer(Modifier.size(4.dp))
        ChoiceRow("CFMOTO", stringResource(R.string.ovk_dlg_mode_cfmoto_desc), primary = true) { onPick(MODE_CFMOTO) }
        ChoiceRow("Android Auto", stringResource(R.string.ovk_dlg_mode_aa_desc), primary = false) { onPick(MODE_ANDROID_AUTO) }
    }
}

@Composable
fun AutoConnectDialog(onAllow: () -> Unit, onDeny: () -> Unit) {
    val c = LocalCockpitColors.current
    DialogCard(onDeny) {
        Box(
            Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(c.ignition.copy(alpha = 0.12f)).border(1.dp, c.ignition.copy(alpha = 0.4f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) { Text("⚡", color = c.ignition, fontSize = 20.sp) }
        Text(stringResource(R.string.ovk_dlg_autoconnect_title), color = c.ink, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(
            stringResource(R.string.ovk_dlg_autoconnect_body),
            color = c.inkDim, fontSize = 12.5.sp,
        )
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            GhostButton(stringResource(R.string.ovk_not_now), onDeny, Modifier.weight(1f))
            PrimaryButton(stringResource(R.string.ovk_allow), onAllow, Modifier.weight(1f))
        }
    }
}

@Composable
private fun DialogCard(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val c = LocalCockpitColors.current
    Dialog(onDismissRequest = onDismiss) {
        Surface(color = c.surface1, shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, c.line)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) { content() }
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
