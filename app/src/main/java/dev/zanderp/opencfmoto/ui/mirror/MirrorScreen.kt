// SPDX-License-Identifier: AGPL-3.0-or-later
// Mirror — cast the PHONE screen TO the bike dash (phone → dash) via MediaProjection, reusing the proven
// classic MainActivity mirror flow without showing the classic UI. The consent + connect run in the host
// CockpitActivity (startPhoneMirror → projectionLauncher → CfmotoConnect.startMirrorLink); this screen is
// just the explainer + trigger. (It used to be the REVERSE — a SurfaceView showing the dash video on the
// phone — which also showed a misleading "connect the bike" hint even while connected.)
package dev.zanderp.opencfmoto.ui.mirror

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.zanderp.opencfmoto.R
import dev.zanderp.opencfmoto.ConnectionState
import dev.zanderp.opencfmoto.Phase
import dev.zanderp.opencfmoto.ui.CockpitActivity
import dev.zanderp.opencfmoto.ui.Routes
import dev.zanderp.opencfmoto.ui.components.PrimaryButton
import dev.zanderp.opencfmoto.ui.theme.LocalCockpitColors

// LocalContext under CockpitActivity's setContent is the Activity today, but unwrap defensively so a
// future ContextThemeWrapper / @Preview / ComposeView host can't crash the cast (same as DashboardScreen).
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun MirrorScreen(nav: NavController) {
    val c = LocalCockpitColors.current
    val ctx = LocalContext.current
    val snap by ConnectionState.flow.collectAsStateWithLifecycle()
    // Status is driven by the connection phase (NOT AaVideoBridge.pipeline, which is the reverse path).
    val projecting = snap.phase == Phase.MIRRORING || snap.phase == Phase.RECONNECTING

    Box(Modifier.fillMaxSize().background(c.ground)) {
        // Hero: what this screen does + the single primary action (cast phone → dash).
        Column(
            Modifier.fillMaxSize().padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.ovk_provider_mirror), color = c.ink, fontWeight = FontWeight.Bold, fontSize = 24.sp)
            Spacer(Modifier.size(6.dp))
            Text(
                stringResource(R.string.ovk_mirror_subtitle),
                color = c.inkDim,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.size(18.dp))
            Text(
                stringResource(R.string.ovk_mirror_body),
                color = c.inkFaint,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.size(24.dp))
            PrimaryButton(
                text = "▶ " + stringResource(R.string.ovk_mirror_project_button),
                onClick = { (ctx.findActivity() as? CockpitActivity)?.startPhoneMirror() },
                modifier = Modifier.fillMaxWidth(),
            )
            if (projecting) {
                Spacer(Modifier.size(16.dp))
                Row(
                    Modifier
                        .clip(RoundedCornerShape(11.dp))
                        .background(c.live.copy(alpha = 0.12f))
                        .border(1.dp, c.live.copy(alpha = 0.3f), RoundedCornerShape(11.dp))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("●", color = c.live, fontSize = 11.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.ovk_mirror_projecting), color = c.ink, fontSize = 13.sp)
                }
            }
        }

        // Top bar — close (✕). Title lives in the hero above.
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            GlyphBox("✕") { nav.popBackStack() }
        }

        // Bottom bar
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BarButton("✥ " + stringResource(R.string.ovk_tile_controls), c.inkFaint, Modifier.weight(1f)) { nav.navigate(Routes.CONTROLS) }
            BarButton("■ " + stringResource(R.string.ovk_close), c.fault, Modifier.weight(1f)) { nav.popBackStack() }
        }
    }
}

@Composable
private fun BarButton(label: String, accent: androidx.compose.ui.graphics.Color, modifier: Modifier, onClick: () -> Unit) {
    val c = LocalCockpitColors.current
    Box(
        modifier.clip(RoundedCornerShape(11.dp)).background(c.surface1.copy(alpha = 0.92f)).border(1.dp, c.line, RoundedCornerShape(11.dp)).clickable(onClick = onClick).padding(vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (accent == c.inkFaint) c.ink else accent, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, fontFamily = FontFamily.Default) }
}

@Composable
private fun GlyphBox(glyph: String, onClick: () -> Unit) {
    val c = LocalCockpitColors.current
    Box(
        Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(c.surface1).border(1.dp, c.line, RoundedCornerShape(10.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(glyph, color = c.inkDim, fontSize = 15.sp) }
}
