// SPDX-License-Identifier: AGPL-3.0-or-later
// Compose port of CustomResolutionActivity — the match-panel-aspect page, in the cockpit design.
// ADDITIVE: reads/writes the exact same per-bike "opencfmoto_bike" prefs via [VideoPrefs], so the
// classic Activity and the live projection stay in sync. The classic Activity is kept in place.
package dev.zanderp.opencfmoto.ui.settings

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import dev.zanderp.opencfmoto.R
import dev.zanderp.opencfmoto.AaMargins
import dev.zanderp.opencfmoto.BikeProfileHolder
import dev.zanderp.opencfmoto.MatchAspectMode
import dev.zanderp.opencfmoto.VideoPrefs
import dev.zanderp.opencfmoto.ui.components.MonoLabel
import dev.zanderp.opencfmoto.ui.components.PrimaryButton
import dev.zanderp.opencfmoto.ui.theme.LocalCockpitColors

private const val MIN_SIDE = 16
private const val MAX_SIDE = 8192

/**
 * Resolución del tablero — makes Android Auto reflow to the dash panel's real aspect via AAP margins.
 * Auto uses the learned/profile panel size, Off restores letterbox/crop, Manual takes a typed W×H.
 * Writes the same per-bike prefs as the classic Activity through [VideoPrefs.setMatchAspect]; the
 * info line reproduces the Activity's computation ([AaMargins.forAspect] + the detected panel).
 */
@Composable
fun CustomResolutionScreen(nav: NavController) {
    val c = LocalCockpitColors.current
    val ctx = LocalContext.current
    val initial = remember {
        val m = VideoPrefs.matchAspectMode(ctx)
        val (w, h) = VideoPrefs.aspectTarget(ctx)
        Triple(m, w, h)
    }
    val fallbackW = initial.second
    val fallbackH = initial.third
    var mode by remember { mutableStateOf(initial.first) }
    var wText by remember { mutableStateOf(fallbackW.toString()) }
    var hText by remember { mutableStateOf(fallbackH.toString()) }

    // Detected panel + coded AA size are fixed for this screen (they change only on reconnect), so
    // read them once — the note only reflows with the mode / typed size, exactly like the Activity.
    val detected = remember { VideoPrefs.detectedPanelSize(ctx) }
    val coded = remember { VideoPrefs.resolution(ctx).spec ?: BikeProfileHolder.aaVideo }

    val wVal = wText.trim().toIntOrNull()
    val hVal = hText.trim().toIntOrNull()
    val manualValid = mode != MatchAspectMode.MANUAL ||
        (wVal != null && hVal != null && wVal in MIN_SIDE..MAX_SIDE && hVal in MIN_SIDE..MAX_SIDE)

    // Same branch + numbers as CustomResolutionActivity.refreshMatchNote(), phrased for the cockpit.
    val detectedNote = if (detected != null) {
        stringResource(R.string.ovk_res_detected_panel, detected.first, detected.second)
    } else {
        stringResource(R.string.ovk_res_no_panel)
    }
    val panel: Pair<Int, Int>? = when (mode) {
        MatchAspectMode.OFF -> null
        MatchAspectMode.AUTO -> detected
        MatchAspectMode.MANUAL -> (wVal ?: fallbackW) to (hVal ?: fallbackH)
    }
    val marginNote = if (panel == null) {
        if (mode == MatchAspectMode.OFF) {
            stringResource(R.string.ovk_res_aspect_off)
        } else {
            stringResource(R.string.ovk_res_no_margins)
        }
    } else {
        val m = AaMargins.forAspect(coded, panel.first, panel.second)
        val uw = coded.width - m.marginW
        val uh = coded.height - m.marginH
        if (!m.any) {
            stringResource(R.string.ovk_res_already_matches, coded.width, coded.height)
        } else {
            stringResource(
                R.string.ovk_res_margins_detail,
                coded.width, coded.height, m.marginW, m.marginH, uw, uh,
                "%.3f".format(uw.toDouble() / uh),
            )
        }
    }

    Column(
        Modifier.fillMaxSize().background(c.ground).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Header(stringResource(R.string.ovk_settings_resolution)) { nav.popBackStack() }
        MonoLabel(stringResource(R.string.ovk_res_subtitle), color = c.inkFaint)

        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp)).background(c.groundHi)
                .border(1.dp, c.line, RoundedCornerShape(11.dp)).padding(3.dp),
        ) {
            SegOption("Auto", mode == MatchAspectMode.AUTO, Modifier.weight(1f)) { mode = MatchAspectMode.AUTO }
            SegOption(stringResource(R.string.ovk_res_mode_off), mode == MatchAspectMode.OFF, Modifier.weight(1f)) { mode = MatchAspectMode.OFF }
            SegOption("Manual", mode == MatchAspectMode.MANUAL, Modifier.weight(1f)) { mode = MatchAspectMode.MANUAL }
        }

        if (mode == MatchAspectMode.MANUAL) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NumField(stringResource(R.string.ovk_res_width), wText, Modifier.weight(1f)) { wText = it }
                NumField(stringResource(R.string.ovk_res_height), hText, Modifier.weight(1f)) { hText = it }
            }
            if (!manualValid) {
                Text(stringResource(R.string.ovk_res_size_range, MIN_SIDE, MAX_SIDE), color = c.fault, fontSize = 11.sp)
            }
        }

        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surface1)
                .border(1.dp, c.line, RoundedCornerShape(12.dp)).padding(12.dp),
        ) {
            Text("$detectedNote\n$marginNote", color = c.inkDim, fontSize = 12.sp, lineHeight = 17.sp)
        }

        PrimaryButton(
            text = stringResource(R.string.ovk_save),
            onClick = {
                // setMatchAspect coerces W/H to 16..8192, matching the classic save().
                VideoPrefs.setMatchAspect(ctx, mode, wVal ?: fallbackW, hVal ?: fallbackH)
                Toast.makeText(ctx, ctx.getString(R.string.ovk_res_saved), Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SegOption(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = LocalCockpitColors.current
    Box(
        modifier.clip(RoundedCornerShape(8.dp)).background(if (selected) c.ignition else Color.Transparent)
            .clickable(onClick = onClick).padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (selected) c.onIgnition else c.inkDim, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
    }
}

@Composable
private fun NumField(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    val c = LocalCockpitColors.current
    OutlinedTextField(
        value = value,
        onValueChange = { s -> onChange(s.filter { it.isDigit() }.take(4)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = c.ignition,
            unfocusedBorderColor = c.line,
            focusedTextColor = c.ink,
            unfocusedTextColor = c.ink,
            cursorColor = c.ignition,
            focusedLabelColor = c.ignition,
            unfocusedLabelColor = c.inkFaint,
            focusedContainerColor = c.groundHi,
            unfocusedContainerColor = c.groundHi,
        ),
    )
}
