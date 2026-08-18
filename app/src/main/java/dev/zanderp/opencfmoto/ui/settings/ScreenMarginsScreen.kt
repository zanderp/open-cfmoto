// SPDX-License-Identifier: AGPL-3.0-or-later
// Compose port of ScreenMarginsActivity — the per-edge dash inset page, in the cockpit design.
// ADDITIVE: reads/writes the exact same "screen_margins" prefs via [ScreenMargins], so the classic
// Activity and the live dash stay in sync. The classic ScreenMarginsActivity is kept in place.
package dev.zanderp.opencfmoto.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.navigation.NavController
import dev.zanderp.opencfmoto.R
import dev.zanderp.opencfmoto.AppSettings
import dev.zanderp.opencfmoto.ScreenMargins
import dev.zanderp.opencfmoto.ui.components.GhostButton
import dev.zanderp.opencfmoto.ui.components.MonoLabel
import dev.zanderp.opencfmoto.ui.theme.LocalCockpitColors
import kotlin.math.roundToInt

/**
 * Márgenes del tablero — four independent edge insets (0..[ScreenMargins.MAX]) held back from the
 * dash. Same store + refresh hook as the classic Activity: [ScreenMargins.set] / [ScreenMargins.reset]
 * both persist to "screen_margins" and call `AaVideoBridge.pipeline?.refreshScreenMargins()` for us.
 * Until customised, the getters return the active profile's default margins.
 */
@Composable
fun ScreenMarginsScreen(nav: NavController) {
    val c = LocalCockpitColors.current
    val ctx = LocalContext.current
    // Mirror the classic Activity's onCreate: sync the active profile into the holder, then load the
    // saved margins. The getters below then reflect the profile default when not yet customised.
    val initial = remember {
        AppSettings.applyToHolder(ctx)
        ScreenMargins.load(ctx)
        intArrayOf(ScreenMargins.top, ScreenMargins.bottom, ScreenMargins.left, ScreenMargins.right)
    }
    var top by remember { mutableStateOf(initial[0]) }
    var bottom by remember { mutableStateOf(initial[1]) }
    var left by remember { mutableStateOf(initial[2]) }
    var right by remember { mutableStateOf(initial[3]) }

    // Persist all four edges at once (the classic writes every seekbar together). ScreenMargins.set
    // marks customised=true, writes the prefs and refreshes the live pipeline for us.
    fun persist() = ScreenMargins.set(ctx, top, bottom, left, right)

    Column(
        Modifier.fillMaxSize().background(c.ground).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Header(stringResource(R.string.ovk_settings_margins)) { nav.popBackStack() }
        MonoLabel(stringResource(R.string.ovk_margins_subtitle), color = c.inkFaint)

        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surface1)
                .border(1.dp, c.line, RoundedCornerShape(12.dp)).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            MarginSlider(stringResource(R.string.ovk_margin_top), top, onChange = { top = it }, onCommit = ::persist)
            MarginSlider(stringResource(R.string.ovk_margin_bottom), bottom, onChange = { bottom = it }, onCommit = ::persist)
            MarginSlider(stringResource(R.string.ovk_margin_left), left, onChange = { left = it }, onCommit = ::persist)
            MarginSlider(stringResource(R.string.ovk_margin_right), right, onChange = { right = it }, onCommit = ::persist)
        }

        GhostButton(
            text = stringResource(R.string.ovk_reset),
            onClick = {
                // Back to the active profile's defaults (also refreshes the live pipeline).
                ScreenMargins.reset(ctx)
                top = ScreenMargins.top
                bottom = ScreenMargins.bottom
                left = ScreenMargins.left
                right = ScreenMargins.right
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Text(
            stringResource(R.string.ovk_margins_apply_note),
            color = c.inkFaint,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun MarginSlider(label: String, value: Int, onChange: (Int) -> Unit, onCommit: () -> Unit) {
    val c = LocalCockpitColors.current
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = c.inkDim, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(
                "$value px",
                color = c.ink,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt()) },
            onValueChangeFinished = onCommit,
            valueRange = 0f..ScreenMargins.MAX.toFloat(),
            colors = SliderDefaults.colors(thumbColor = c.ignition, activeTrackColor = c.ignition),
        )
    }
}
