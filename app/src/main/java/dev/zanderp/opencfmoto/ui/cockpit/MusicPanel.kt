// SPDX-License-Identifier: AGPL-3.0-or-later
// The music half of the split cockpit dash: album art + track + transport, reading the phone's
// active media session via [NowPlaying]. Three states — no grant, granted-but-idle, playing. Styled
// with the cockpit tokens (theme-aware) to match MapScreen / Cockpit atoms.
package dev.zanderp.opencfmoto.ui.cockpit

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zanderp.opencfmoto.R
import dev.zanderp.opencfmoto.cockpit.NowPlaying
import dev.zanderp.opencfmoto.cockpit.NowPlayingState
import dev.zanderp.opencfmoto.ui.components.MonoLabel
import dev.zanderp.opencfmoto.ui.theme.LocalCockpitColors

/**
 * The now-playing panel. Observes [NowPlaying.state] and drives its lifecycle with a
 * [DisposableEffect] tied to the local context, so it starts observing when it enters composition
 * and cleanly unregisters when it leaves.
 */
@Composable
fun MusicPanel(modifier: Modifier = Modifier) {
    val c = LocalCockpitColors.current
    val ctx = LocalContext.current
    val state by NowPlaying.state.collectAsStateWithLifecycle()

    DisposableEffect(ctx) {
        NowPlaying.start(ctx)
        onDispose { NowPlaying.stop(ctx) }
    }

    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(c.surface1)
            .border(1.dp, c.line, RoundedCornerShape(14.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when {
            !state.hasAccess -> NoAccess(ctx)
            !state.hasSession -> NoSession()
            else -> Playing(state)
        }
    }
}

@Composable
private fun ColumnScope.NoAccess(ctx: Context) {
    val c = LocalCockpitColors.current
    Spacer(Modifier.weight(1f))
    Text("♪", color = c.inkFaint, fontSize = 30.sp)
    Text(
        stringResource(R.string.ovk_music_grant_access_desc),
        color = c.inkDim,
        fontSize = 12.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    PillButton(stringResource(R.string.ovk_music_grant_access)) {
        // Deep-link straight to the Notification access screen so the rider can flip the grant.
        runCatching { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
    }
    Spacer(Modifier.weight(1f))
}

@Composable
private fun ColumnScope.NoSession() {
    val c = LocalCockpitColors.current
    Spacer(Modifier.weight(1f))
    Text("♪", color = c.inkFaint, fontSize = 30.sp)
    MonoLabel(stringResource(R.string.ovk_music_none), color = c.inkDim)
    Spacer(Modifier.weight(1f))
}

@Composable
private fun ColumnScope.Playing(state: NowPlayingState) {
    val c = LocalCockpitColors.current
    // Album art fills the remaining height; falls back to a note-glyph placeholder box.
    Box(
        Modifier
            .fillMaxWidth()
            .weight(1f)
            .clip(RoundedCornerShape(10.dp))
            .background(c.groundHi)
            .border(1.dp, c.line, RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        val art = state.art
        if (art != null) {
            Image(
                bitmap = art.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text("♪", color = c.inkFaint, fontSize = 34.sp)
        }
    }

    Text(
        state.title.ifBlank { "—" },
        color = c.ink,
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    if (state.artist.isNotBlank()) {
        Text(
            state.artist,
            color = c.inkDim,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlyphControl("⏮") { NowPlaying.prev() }
        GlyphControl(if (state.playing) "⏸" else "▶", primary = true) { NowPlaying.playPause() }
        GlyphControl("⏭") { NowPlaying.next() }
    }
}

/** A tappable transport glyph — the play/pause hero is tinted with the ignition accent. */
@Composable
private fun GlyphControl(glyph: String, primary: Boolean = false, onClick: () -> Unit) {
    val c = LocalCockpitColors.current
    val side = if (primary) 40.dp else 34.dp
    val radius = if (primary) 12.dp else 10.dp
    Box(
        Modifier
            .size(side)
            .clip(RoundedCornerShape(radius))
            .background(if (primary) c.ignition else c.surface2)
            .border(1.dp, c.line, RoundedCornerShape(radius))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, color = if (primary) c.onIgnition else c.ink, fontSize = if (primary) 18.sp else 15.sp)
    }
}

/** Compact filled action pill sized for the narrow panel (the full PrimaryButton is too wide here). */
@Composable
private fun PillButton(text: String, onClick: () -> Unit) {
    val c = LocalCockpitColors.current
    Box(
        Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(c.ignition)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = c.onIgnition, fontWeight = FontWeight.Bold, fontSize = 12.sp)
    }
}
