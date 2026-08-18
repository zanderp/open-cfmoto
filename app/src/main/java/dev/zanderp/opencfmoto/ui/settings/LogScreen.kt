// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
//
// Native, in-cockpit view of the process-wide session log (LogBus). Replaces the developer
// "Diagnostics" row's old kick-out to the classic MainActivity: the rider stays inside the Compose
// cockpit to read logs, Share them (same FileProvider export as MainActivity.shareLog), Copy them, or
// Clear them. Live-updating (wraps the LogBus listener without displacing it).
// and tails the newest line until the reader scrolls up.
package dev.zanderp.opencfmoto.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.navigation.NavController
import dev.zanderp.opencfmoto.BuildConfig
import dev.zanderp.opencfmoto.CrashGuard
import dev.zanderp.opencfmoto.LogBus
import dev.zanderp.opencfmoto.R
import dev.zanderp.opencfmoto.ui.components.MonoLabel
import dev.zanderp.opencfmoto.ui.theme.LocalCockpitColors
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** Only the tail is rendered on-screen; the full buffer is always what Share/Copy export. */
private const val RENDER_CAP_BYTES = 128 * 1024

/** How close (px) to the bottom still counts as "pinned to the newest line". */
private const val BOTTOM_SLACK_PX = 8

/** A snapshot prepared for the on-screen Text: the (possibly capped) body plus its size accounting. */
private data class LogView(val body: String, val totalKb: Int, val shownKb: Int, val truncated: Boolean)

/** Cap the rendered text to the last [RENDER_CAP_BYTES], starting at a line boundary so the first
 *  visible line isn't chopped mid-way. The uncapped buffer stays the source for Share/Copy. */
private fun buildView(full: String): LogView {
    val totalKb = full.length / 1024
    if (full.length <= RENDER_CAP_BYTES) return LogView(full, totalKb, totalKb, false)
    val tail = full.substring(full.length - RENDER_CAP_BYTES)
    val nl = tail.indexOf('\n')
    val body = if (nl in 0 until tail.length - 1) tail.substring(nl + 1) else tail
    return LogView(body, totalKb, RENDER_CAP_BYTES / 1024, true)
}

/**
 * Native session-log viewer ([Routes.LOG]). Read-only, selectable, monospace; tails the log live.
 */
@Composable
fun LogScreen(nav: NavController) {
    val c = LocalCockpitColors.current
    val ctx = LocalContext.current
    val scrollState = rememberScrollState()

    // Follow the newest line by default; a user scroll-up parks it, a return to the bottom re-arms it.
    var autoScroll by remember { mutableStateOf(true) }
    var view by remember { mutableStateOf(buildView(LogBus.snapshot())) }

    // The log-producing threads (foreground service, AA receiver, video pipeline) call LogBus.log; we
    // only flip a flag from there and re-snapshot on a UI-side poll, so a chatty burst never blocks or
    // re-renders per line — it coalesces into one refresh per tick.
    val dirty = remember { AtomicBoolean(false) }
    DisposableEffect(Unit) {
        val prior = LogBus.listener
        val self: (String) -> Unit = { line ->
            try { prior?.invoke(line) } catch (_: Exception) {}
            dirty.set(true)
        }
        LogBus.listener = self
        onDispose {
            // Relinquish only if still ours, so we never clobber a listener installed after us.
            if (LogBus.listener === self) LogBus.listener = prior
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            if (dirty.getAndSet(false)) view = buildView(LogBus.snapshot())
            delay(200)
        }
    }

    // Tail: pin to the bottom on first layout, on content growth (maxValue change), and when the reader
    // re-arms follow — but never yank the view while a drag is in progress.
    LaunchedEffect(scrollState.maxValue, autoScroll) {
        if (autoScroll && !scrollState.isScrollInProgress) scrollState.scrollTo(scrollState.maxValue)
    }
    val atBottom by remember {
        derivedStateOf { scrollState.value >= scrollState.maxValue - BOTTOM_SLACK_PX }
    }
    // On the settle of a user drag, decide whether to keep following: yes if they left it at the tail.
    LaunchedEffect(scrollState.isScrollInProgress) {
        if (!scrollState.isScrollInProgress) autoScroll = atBottom
    }

    Column(
        Modifier.fillMaxSize().background(c.ground).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Header(stringResource(R.string.ovk_log_title)) { nav.popBackStack() }

        // Build stamp, always visible even if the banner has scrolled out of the capped tail.
        MonoLabel(
            "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${BuildConfig.GIT_HASH}",
            color = c.inkFaint,
        )

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BarButton(stringResource(R.string.ovk_log_share), c.ignition, Modifier.weight(1f)) {
                shareLogs(ctx)
            }
            BarButton(stringResource(R.string.ovk_log_copy), c.inkDim, Modifier.weight(1f)) {
                copyLogs(ctx)
            }
            BarButton(stringResource(R.string.ovk_log_clear), c.fault, Modifier.weight(1f)) {
                // Same semantics as the classic Clear Logs button: drop memory + durable session,
                // then re-stamp the banner so the next capture is unambiguous.
                LogBus.clear()
                CrashGuard.clearSession(ctx)
                LogBus.logSessionBanner()
                view = buildView(LogBus.snapshot())
                autoScroll = true
            }
        }

        if (view.truncated) {
            MonoLabel(
                stringResource(R.string.ovk_log_truncated, view.shownKb, view.totalKb),
                color = c.warn,
            )
        }

        Box(
            Modifier.fillMaxWidth().weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(c.groundHi)
                .border(1.dp, c.line, RoundedCornerShape(12.dp)),
        ) {
            SelectionContainer(Modifier.fillMaxSize()) {
                Text(
                    view.body,
                    color = c.inkDim,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    modifier = Modifier.fillMaxWidth().verticalScroll(scrollState).padding(12.dp),
                )
            }
            // Parked away from the tail → offer a one-tap return that also re-arms live follow.
            if (!autoScroll) {
                Box(
                    Modifier.align(Alignment.BottomEnd).padding(12.dp)
                        .clip(RoundedCornerShape(50))
                        .background(c.ignition)
                        .clickable { autoScroll = true }
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                ) {
                    Text(
                        stringResource(R.string.ovk_log_follow),
                        color = c.onIgnition,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun BarButton(label: String, accent: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = LocalCockpitColors.current
    Box(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(c.surface1)
            .border(1.dp, accent.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = accent, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    }
}

/**
 * Export the full session log via the classic Share Logs path: persist the live buffer, write it to
 * the FileProvider-shared cache dir, and fire a chooser. Reuses MainActivity.shareLog's authority
 * (`$packageName.fileprovider`) and `cache/logs` location — declared in res/xml/file_paths.xml.
 */
private fun shareLogs(ctx: Context) {
    try {
        CrashGuard.persistSession(ctx)
        val dir = File(ctx.cacheDir, "logs").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "opencfmoto-$stamp.log")
        file.writeText(LogBus.snapshot())
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "opencfmoto log $stamp")
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(
            Intent.createChooser(send, ctx.getString(R.string.ovk_log_share_chooser)),
        )
    } catch (e: Exception) {
        LogBus.log("[log] share failed: $e")
        Toast.makeText(ctx, ctx.getString(R.string.ovk_log_share_failed), Toast.LENGTH_LONG).show()
    }
}

/** Copy the full session log to the clipboard. */
private fun copyLogs(ctx: Context) {
    try {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("opencfmoto log", LogBus.snapshot()))
        // Android 13+ shows its own copy confirmation; avoid a duplicate toast there.
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            Toast.makeText(ctx, ctx.getString(R.string.ovk_log_copied), Toast.LENGTH_SHORT).show()
        }
    } catch (e: Exception) {
        LogBus.log("[log] copy failed: $e")
    }
}
