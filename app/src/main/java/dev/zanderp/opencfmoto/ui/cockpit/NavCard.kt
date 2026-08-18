// SPDX-License-Identifier: AGPL-3.0-or-later
// The navigation half of the split cockpit dash: a compact, glassy card that floats over the top of
// the map with the next maneuver read from Maps/Waze via [NavGuidance]. Rendered by CockpitScreen
// only while NavGuidance.state.active. Styled with the cockpit tokens (theme-aware) to match MusicPanel.
package dev.zanderp.opencfmoto.ui.cockpit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.zanderp.opencfmoto.R
import dev.zanderp.opencfmoto.cockpit.NavState
import dev.zanderp.opencfmoto.ui.components.MonoLabel
import dev.zanderp.opencfmoto.ui.theme.LocalCockpitColors

/**
 * The turn-by-turn card. A maneuver glyph on the left, the instruction (bold) and its detail (dim)
 * on the right, plus a small source tag (GOOGLE MAPS / WAZE). Sits glassy over the map — a
 * translucent Ground fill so the road underneath still reads through the edges.
 */
@Composable
fun NavCard(state: NavState, modifier: Modifier = Modifier) {
    val c = LocalCockpitColors.current
    val onRouteFallback = stringResource(R.string.ovk_nav_on_route)
    Row(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(c.ground.copy(alpha = 0.82f))
            .border(1.dp, c.line, RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Maneuver glyph — a filled ignition chip, the strongest "your next move" signal on the dash.
        Box(
            Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(c.ignition),
            contentAlignment = Alignment.Center,
        ) {
            Text("➤", color = c.onIgnition, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        }

        Column(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                state.instruction.ifBlank { onRouteFallback },
                color = c.ink,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (state.detail.isNotBlank()) {
                Text(
                    state.detail,
                    color = c.inkDim,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (state.source.isNotBlank()) {
                MonoLabel(state.source, color = c.inkFaint)
            }
        }
    }
}
