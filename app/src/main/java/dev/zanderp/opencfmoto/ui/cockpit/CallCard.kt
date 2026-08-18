// SPDX-License-Identifier: AGPL-3.0-or-later
// The call half of the split cockpit dash: takes over the right pane (in place of MusicPanel) while
// a call is live, showing the caller and Answer/Hang-up controls wired to the dialer's own
// PendingIntents via [CallState]. Styled with the cockpit tokens (theme-aware) to match MusicPanel.
package dev.zanderp.opencfmoto.ui.cockpit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.zanderp.opencfmoto.R
import dev.zanderp.opencfmoto.cockpit.CallInfo
import dev.zanderp.opencfmoto.cockpit.CallState
import dev.zanderp.opencfmoto.ui.theme.LocalCockpitColors

/**
 * The call panel. Mirrors MusicPanel's outer frame so the swap is seamless. Ringing → two buttons
 * (Contestar / Colgar); in-call → just Colgar. The buttons fire [CallState.answer] / [CallState.hangup],
 * which relay the dialer's captured PendingIntents.
 */
@Composable
fun CallCard(info: CallInfo, modifier: Modifier = Modifier) {
    val c = LocalCockpitColors.current
    val callerFallback = stringResource(R.string.ovk_call_title)
    val incomingLabel = stringResource(R.string.ovk_call_incoming)
    val inCallLabel = stringResource(R.string.ovk_call_active)
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(c.surface1)
            .border(1.dp, c.line, RoundedCornerShape(14.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("☎", color = if (info.ringing) c.live else c.ignition, fontSize = 34.sp)
        Text(
            info.caller.ifBlank { callerFallback },
            color = c.ink,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            info.status.ifBlank { if (info.ringing) incomingLabel else inCallLabel },
            color = c.inkDim,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        if (info.ringing) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Answer: dark ink on bright green reads far better than white would.
                CallButton(stringResource(R.string.ovk_call_answer), c.live, c.onIgnition, Modifier.weight(1f)) { CallState.answer() }
                CallButton(stringResource(R.string.ovk_call_hangup), c.fault, c.ink, Modifier.weight(1f)) { CallState.hangup() }
            }
        } else {
            CallButton(stringResource(R.string.ovk_call_hangup), c.fault, c.ink, Modifier.fillMaxWidth()) { CallState.hangup() }
        }
    }
}

/** A filled semantic action button; [content] is the on-fill text color chosen for contrast. */
@Composable
private fun CallButton(
    label: String,
    fill: Color,
    content: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(fill)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = content, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}
