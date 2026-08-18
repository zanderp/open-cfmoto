// SPDX-License-Identifier: AGPL-3.0-or-later
// Controls — drive the dash without touching it: D-pad + rotary + volume + the ▲/▼ mode toggle.
// Input is forwarded to the same sinks the classic ControlsActivity uses (MapInputBridge for the
// built-in map, else AaVideoBridge for Android Auto), so it works in either projection mode.
package dev.zanderp.opencfmoto.ui.controls

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import dev.zanderp.opencfmoto.R
import dev.zanderp.opencfmoto.AaVideoBridge
import dev.zanderp.opencfmoto.HandlebarVolumeMode
import dev.zanderp.opencfmoto.MapInputBridge
import dev.zanderp.opencfmoto.MediaButtonBridge
import dev.zanderp.opencfmoto.aa.AaInput
import dev.zanderp.opencfmoto.ui.components.MonoLabel
import dev.zanderp.opencfmoto.ui.theme.LocalCockpitColors

@Composable
fun ControlsScreen(nav: NavController) {
    val c = LocalCockpitColors.current
    val ctx = LocalContext.current
    val maxVol = remember { MediaButtonBridge.volumeLevels(ctx).second.coerceAtLeast(1) }
    var vol by remember { mutableFloatStateOf(MediaButtonBridge.volumeLevels(ctx).first.toFloat()) }
    var navMode by remember { mutableStateOf(HandlebarVolumeMode.isNavigate(ctx)) }

    fun key(code: Int) {
        val sink = MapInputBridge.keySink ?: AaVideoBridge.keySink
        if (sink == null) Toast.makeText(ctx, ctx.getString(R.string.ovk_controls_connect_first_input), Toast.LENGTH_SHORT).show()
        else sink(code)
    }
    fun scroll(delta: Int) {
        val sink = MapInputBridge.scrollSink ?: AaVideoBridge.scrollSink
        if (sink == null) Toast.makeText(ctx, ctx.getString(R.string.ovk_controls_connect_first), Toast.LENGTH_SHORT).show()
        else sink(delta)
    }

    Column(
        Modifier.fillMaxSize().background(c.ground).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            GlyphBox("‹") { nav.popBackStack() }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(stringResource(R.string.ovk_tile_controls), color = c.ink, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                MonoLabel(stringResource(R.string.ovk_controls_subtitle), color = c.inkDim)
            }
        }

        // D-pad + rotary
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1.3f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DpadRow(KeyBlank(), Key("▲") { key(AaInput.KEY_UP) }, KeyBlank())
                DpadRow(Key("◀") { key(AaInput.KEY_LEFT) }, Key("OK", primary = true) { key(AaInput.KEY_ENTER) }, Key("▶") { key(AaInput.KEY_RIGHT) })
                DpadRow(KeyBlank(), Key("▼") { key(AaInput.KEY_DOWN) }, KeyBlank())
            }
            Knob(Modifier.weight(0.9f), onBack = { scroll(-1) }, onFwd = { scroll(+1) })
        }

        // Volume
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.ovk_volume), color = c.inkDim, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(vol.toInt().toString(), color = c.ink, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        }
        Slider(
            value = vol, onValueChange = { vol = it; MediaButtonBridge.setVolume(ctx, it.toInt()) },
            valueRange = 0f..maxVol.toFloat(),
            colors = SliderDefaults.colors(thumbColor = c.ignition, activeTrackColor = c.ignition),
        )

        // ▲/▼ mode toggle
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp)).background(c.groundHi).border(1.dp, c.line, RoundedCornerShape(11.dp)).padding(3.dp)) {
            SegOption("▲▼ " + stringResource(R.string.ovk_volume), selected = !navMode, Modifier.weight(1f)) {
                HandlebarVolumeMode.set(ctx, false); navMode = false
            }
            SegOption("▲▼ " + stringResource(R.string.ovk_navigate), selected = navMode, Modifier.weight(1f)) {
                HandlebarVolumeMode.set(ctx, true); navMode = true
            }
        }

        // Voice / Home / Back
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BarButton("◉ " + stringResource(R.string.ovk_voice), Modifier.weight(1f)) { key(AaInput.KEY_ASSISTANT) }
            BarButton("⌂ " + stringResource(R.string.ovk_home), Modifier.weight(1f)) { key(AaInput.KEY_HOME) }
            BarButton("↩ " + stringResource(R.string.ovk_back), Modifier.weight(1f)) { key(AaInput.KEY_BACK) }
        }
    }
}

private class KeySpec(val label: String?, val primary: Boolean, val onClick: (() -> Unit)?)
private fun Key(label: String, primary: Boolean = false, onClick: () -> Unit) = KeySpec(label, primary, onClick)
private fun KeyBlank() = KeySpec(null, false, null)

@Composable
private fun DpadRow(a: KeySpec, b: KeySpec, c: KeySpec) {
    val cc = LocalCockpitColors.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (k in listOf(a, b, c)) {
            if (k.label == null) {
                Spacer(Modifier.weight(1f))
            } else {
                Box(
                    Modifier.weight(1f).aspectRatio(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (k.primary) cc.ignition.copy(alpha = 0.14f) else cc.surface1)
                        .border(1.dp, if (k.primary) cc.ignition.copy(alpha = 0.4f) else cc.line, RoundedCornerShape(12.dp))
                        .clickable { k.onClick?.invoke() },
                    contentAlignment = Alignment.Center,
                ) { Text(k.label, color = if (k.primary) cc.ignition else cc.ink, fontWeight = FontWeight.Bold, fontSize = 18.sp) }
            }
        }
    }
}

@Composable
private fun Knob(modifier: Modifier, onBack: () -> Unit, onFwd: () -> Unit) {
    val c = LocalCockpitColors.current
    Box(modifier.aspectRatio(1f).clip(CircleShape).background(c.surface2).border(1.dp, c.line, CircleShape)) {
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxSize().clickable(onClick = onBack), contentAlignment = Alignment.CenterStart) {
                Text("‹", color = c.inkDim, fontSize = 22.sp, modifier = Modifier.padding(start = 10.dp))
            }
            Box(Modifier.weight(1f).fillMaxSize().clickable(onClick = onFwd), contentAlignment = Alignment.CenterEnd) {
                Text("›", color = c.inkDim, fontSize = 22.sp, modifier = Modifier.padding(end = 10.dp))
            }
        }
        MonoLabel("SCROLL", color = c.inkFaint, modifier = Modifier.align(Alignment.Center))
    }
}

@Composable
private fun SegOption(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = LocalCockpitColors.current
    Box(
        modifier.clip(RoundedCornerShape(8.dp)).background(if (selected) c.ignition else Color.Transparent).clickable(onClick = onClick).padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (selected) c.onIgnition else c.inkDim, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
    }
}

@Composable
private fun BarButton(label: String, modifier: Modifier, onClick: () -> Unit) {
    val c = LocalCockpitColors.current
    Box(
        modifier.clip(RoundedCornerShape(10.dp)).background(c.surface1).border(1.dp, c.line, RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = c.ink, fontWeight = FontWeight.SemiBold, fontSize = 12.sp) }
}

@Composable
private fun GlyphBox(glyph: String, onClick: () -> Unit) {
    val c = LocalCockpitColors.current
    Box(
        Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(c.surface1).border(1.dp, c.line, RoundedCornerShape(10.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(glyph, color = c.inkDim, fontSize = 20.sp) }
}
