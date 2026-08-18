// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.zanderp.opencfmoto.DashRemote
import dev.zanderp.opencfmoto.MapTheme
import dev.zanderp.opencfmoto.NightPrefs
import dev.zanderp.opencfmoto.R
import dev.zanderp.opencfmoto.ui.theme.LocalCockpitColors

/**
 * Persistent day / night / auto toggle that lives ON the map, so the rider flips the map's light/dark
 * look without leaving the screen. A single tap cycles AUTO → DAY → NIGHT via [NightPrefs.cycle] — the
 * SAME store the Settings → "Mapa y rutas" option writes, so the on-map button and the settings option
 * stay in sync (both read/write one pref). [onThemeChanged] fires with the resolved night boolean so the
 * caller can re-style its live MapLibre renderer immediately (`MapRenderer.applyTheme(night)`); AUTO
 * resolves to the current day/night via [NightPrefs.isNightNow].
 *
 * [compact] shows the glyph only (for tight top bars like the cockpit); otherwise glyph + label.
 */
@Composable
fun MapThemeToggle(
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onThemeChanged: (night: Boolean) -> Unit,
) {
    val ctx = LocalContext.current
    val c = LocalCockpitColors.current
    var theme by remember { mutableStateOf(NightPrefs.theme(ctx)) }
    val glyph = when (theme) {
        MapTheme.AUTO -> "◐"
        MapTheme.DAY -> "☀"
        MapTheme.NIGHT -> "☾"
    }
    val labelRes = when (theme) {
        MapTheme.AUTO -> R.string.ovk_map_theme_auto
        MapTheme.DAY -> R.string.ovk_map_theme_day
        MapTheme.NIGHT -> R.string.ovk_map_theme_night
    }
    Row(
        modifier
            .clip(RoundedCornerShape(11.dp))
            .background(c.ground.copy(alpha = 0.85f))
            .border(1.dp, c.line, RoundedCornerShape(11.dp))
            .clickable {
                theme = NightPrefs.cycle(ctx)
                val night = NightPrefs.isNightNow(ctx)
                onThemeChanged(night)          // re-style THIS screen's live renderer
                DashRemote.applyTheme(night)   // and flip the bike dash live, if one is projecting
            }
            .padding(horizontal = if (compact) 10.dp else 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(glyph, color = c.ignition, fontSize = 15.sp)
        if (!compact) {
            Text(
                stringResource(labelRes),
                color = c.ink,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
            )
        }
    }
}
