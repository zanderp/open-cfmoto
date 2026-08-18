// SPDX-License-Identifier: AGPL-3.0-or-later
// Shared cockpit UI atoms — the visual language from the approved mockups, as Compose.
package dev.zanderp.opencfmoto.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.zanderp.opencfmoto.Phase
import dev.zanderp.opencfmoto.ui.theme.CockpitColors
import dev.zanderp.opencfmoto.ui.theme.LocalCockpitColors

enum class StatusKind { LIVE, BUSY, FAULT, IDLE }

fun Phase.kind(): StatusKind = when (this) {
    Phase.STREAMING, Phase.MIRRORING, Phase.AA_VIDEO_LIVE -> StatusKind.LIVE
    Phase.ERROR -> StatusKind.FAULT
    Phase.IDLE, Phase.STOPPED -> StatusKind.IDLE
    else -> StatusKind.BUSY
}

/**
 * Maps a status to its semantic color. Not @Composable (called from a couple of draw/derive spots),
 * so it takes the palette explicitly — pass [LocalCockpitColors].current at the call site.
 */
fun StatusKind.color(c: CockpitColors): Color = when (this) {
    StatusKind.LIVE -> c.live
    StatusKind.BUSY -> c.warn
    StatusKind.FAULT -> c.fault
    StatusKind.IDLE -> c.inkFaint
}

@Composable
fun StatusChip(label: String, kind: StatusKind, modifier: Modifier = Modifier) {
    val c = kind.color(LocalCockpitColors.current)
    Row(
        modifier
            .clip(RoundedCornerShape(999.dp))
            .background(c.copy(alpha = 0.12f))
            .border(1.dp, c.copy(alpha = 0.4f), RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(c))
        Text(label.uppercase(), color = c, fontFamily = FontFamily.Monospace, fontSize = 10.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun MonoLabel(text: String, modifier: Modifier = Modifier, color: Color = LocalCockpitColors.current.inkFaint) {
    Text(text.uppercase(), color = color, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = modifier)
}

@Composable
fun Tile(glyph: String, name: String, desc: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalCockpitColors.current
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(c.surface1)
            .border(1.dp, c.line, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(
            Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(c.groundHi)
                .border(1.dp, c.line, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) { Text(glyph, color = c.ignition, fontSize = 14.sp) }
        Text(name, color = c.ink, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Text(desc.uppercase(), color = c.inkFaint, fontFamily = FontFamily.Monospace, fontSize = 9.sp)
    }
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalCockpitColors.current
    Button(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = c.ignition, contentColor = c.onIgnition),
    ) { Text(text, fontWeight = FontWeight.Bold) }
}

/** Circular connection instrument — a ring whose colored sweep + center glyph read the state. */
@Composable
fun ConnectionGauge(kind: StatusKind, centerGlyph: String, modifier: Modifier = Modifier) {
    val colors = LocalCockpitColors.current
    val c = kind.color(colors)
    val sweep = when (kind) {
        StatusKind.LIVE -> 320f
        StatusKind.BUSY -> 210f
        StatusKind.FAULT -> 320f
        StatusKind.IDLE -> 78f
    }
    // BUSY = connecting: spin the colored sweep continuously so it reads as "working" (like a
    // progress indicator) instead of a frozen arc. Other states keep the fixed sweep at -90°.
    val start = if (kind == StatusKind.BUSY) {
        val transition = rememberInfiniteTransition(label = "gaugeSpin")
        transition.animateFloat(
            initialValue = -90f,
            targetValue = 270f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1100, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "gaugeSpinAngle",
        ).value
    } else {
        -90f
    }
    Box(modifier.size(104.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(6.dp)) {
            val w = 9.dp.toPx()
            drawArc(colors.line, 0f, 360f, false, style = androidx.compose.ui.graphics.drawscope.Stroke(w, cap = androidx.compose.ui.graphics.StrokeCap.Round))
            drawArc(c, start, sweep, false, style = androidx.compose.ui.graphics.drawscope.Stroke(w, cap = androidx.compose.ui.graphics.StrokeCap.Round))
        }
        Text(centerGlyph, color = c, fontSize = 26.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalCockpitColors.current
    Button(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, c.line),
        colors = ButtonDefaults.buttonColors(containerColor = c.surface1, contentColor = c.ink),
    ) { Text(text, fontWeight = FontWeight.SemiBold) }
}
