// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
// First-run "Get started" flow for the cockpit, native in Compose. It requests the runtime
// permissions the classic SetupActivity onboarding covers and surfaces the two optional steps
// (seamless auto-resume via the overlay grant, Android Auto for Google/Waze mode) WITHOUT gating on
// them — this is CFMOTO-first, so only the runtime permissions matter and even those are skippable.
// Finishing (or skipping) sets SettingsStore.onboardingDone so the start-up gate never re-shows it.
package dev.zanderp.opencfmoto.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import dev.zanderp.opencfmoto.R
import dev.zanderp.opencfmoto.SetupHelper
import dev.zanderp.opencfmoto.settings.SettingsStore
import dev.zanderp.opencfmoto.ui.Routes
import dev.zanderp.opencfmoto.ui.components.GhostButton
import dev.zanderp.opencfmoto.ui.components.MonoLabel
import dev.zanderp.opencfmoto.ui.components.PrimaryButton
import dev.zanderp.opencfmoto.ui.theme.LocalCockpitColors
import kotlinx.coroutines.launch

@Composable
fun OnboardingScreen(nav: NavController) {
    val c = LocalCockpitColors.current
    val ctx = LocalContext.current
    val store = remember { SettingsStore(ctx.applicationContext) }
    val scope = rememberCoroutineScope()

    // SetupHelper exposes plain checks (not Flows), so drive re-reads off a counter that ticks on the
    // permission-dialog result AND on every ON_RESUME (returning from the overlay / Play Store screens
    // re-ticks the steps, exactly like the classic SetupActivity.onResume()).
    var refresh by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) refresh++ }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    val permsGranted = remember(refresh) { SetupHelper.permissionsGranted(ctx) }
    val canAutoResume = remember(refresh) { SetupHelper.canAutoResume(ctx) }
    val aaInstalled = remember(refresh) { SetupHelper.isAndroidAutoInstalled(ctx) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { refresh++ }

    // Mark done from every exit path (Start, Skip, or a back-press that has nothing to pop) so the
    // rider can never be trapped in the gate, then reset the stack to a single Dashboard.
    fun finishOnboarding() {
        scope.launch { store.setOnboardingDone(true) }
        nav.navigate(Routes.DASHBOARD) {
            popUpTo(nav.graph.startDestinationId) { inclusive = true }
            launchSingleTop = true
        }
    }

    Column(
        Modifier.fillMaxSize().background(c.ground).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // On first run this is the start destination, so a back-press has nothing to pop → treat it as
        // "skip". When reached later from Settings, it pops back there normally.
        Header(stringResource(R.string.ovk_onboarding_title)) {
            if (!nav.popBackStack()) finishOnboarding()
        }
        Text(stringResource(R.string.ovk_onboarding_intro), color = c.inkDim, fontSize = 13.sp)

        Group(stringResource(R.string.ovk_onboarding_permissions)) {
            StepRow(
                done = permsGranted,
                title = stringResource(R.string.ovk_onboarding_permissions),
                subtitle = stringResource(R.string.ovk_onboarding_permissions_sub),
                optional = false,
                actionLabel = stringResource(R.string.ovk_onboarding_grant),
                doneLabel = stringResource(R.string.ovk_onboarding_granted),
                pendingLabel = stringResource(R.string.ovk_onboarding_pending),
                first = true,
            ) { permLauncher.launch(SetupHelper.requiredPermissions().toTypedArray()) }

            StepRow(
                done = canAutoResume,
                title = stringResource(R.string.ovk_onboarding_autoresume),
                subtitle = stringResource(R.string.ovk_onboarding_autoresume_sub),
                optional = true,
                actionLabel = stringResource(R.string.ovk_onboarding_enable),
                doneLabel = stringResource(R.string.ovk_onboarding_enabled),
                pendingLabel = stringResource(R.string.ovk_onboarding_optional),
            ) { openOverlaySettings(ctx) }

            StepRow(
                done = aaInstalled,
                title = stringResource(R.string.ovk_onboarding_android_auto),
                subtitle = stringResource(R.string.ovk_onboarding_android_auto_sub),
                optional = true,
                actionLabel = stringResource(R.string.ovk_onboarding_install),
                doneLabel = stringResource(R.string.ovk_onboarding_installed),
                pendingLabel = stringResource(R.string.ovk_onboarding_optional),
            ) { openAndroidAutoInstall(ctx) }
        }

        Spacer(Modifier.height(4.dp))
        PrimaryButton(
            text = stringResource(R.string.ovk_onboarding_finish),
            onClick = { finishOnboarding() },
            modifier = Modifier.fillMaxWidth(),
        )
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.ovk_onboarding_skip),
                color = c.inkDim,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { finishOnboarding() }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

/** Deep-link to this app's "Display over other apps" screen (overlay grant = seamless auto-resume). */
private fun openOverlaySettings(ctx: android.content.Context) {
    try {
        ctx.startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.fromParts("package", ctx.packageName, null)),
        )
    } catch (_: Exception) {
        runCatching { ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)) }
    }
}

/** Open the Play Store on Android Auto (market → web fallback), matching classic SetupActivity. */
private fun openAndroidAutoInstall(ctx: android.content.Context) {
    val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${SetupHelper.GEARHEAD_PACKAGE}"))
    try {
        ctx.startActivity(market)
    } catch (_: Exception) {
        runCatching {
            ctx.startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=${SetupHelper.GEARHEAD_PACKAGE}"),
                ),
            )
        }
    }
}

/** Section label + rounded surface card, matching SettingsScreen's grouped look. */
@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    val c = LocalCockpitColors.current
    MonoLabel(title, color = c.inkFaint)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surface1)
            .border(1.dp, c.line, RoundedCornerShape(12.dp)),
    ) { content() }
}

/**
 * One onboarding step: a live status dot (✓ green when satisfied, • faint when pending), the title
 * with an optional "OPTIONAL" tag, a one-line explanation, and a trailing control that is a ghost
 * action button while pending or a plain status label once satisfied.
 */
@Composable
private fun StepRow(
    done: Boolean,
    title: String,
    subtitle: String,
    optional: Boolean,
    actionLabel: String,
    doneLabel: String,
    pendingLabel: String,
    first: Boolean = false,
    onAction: () -> Unit,
) {
    val c = LocalCockpitColors.current
    if (!first) HorizontalDivider(color = c.line.copy(alpha = 0.5f))
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(20.dp).clip(RoundedCornerShape(6.dp))
                .background(if (done) c.live.copy(alpha = 0.16f) else c.groundHi)
                .border(1.dp, if (done) c.live.copy(alpha = 0.5f) else c.line, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(if (done) "✓" else "•", color = if (done) c.live else c.inkFaint, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = c.ink, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                if (optional) {
                    Spacer(Modifier.width(6.dp))
                    Text(pendingLabel.uppercase(), color = c.inkFaint, fontFamily = FontFamily.Monospace, fontSize = 8.sp)
                }
            }
            Text(subtitle, color = c.inkFaint, fontSize = 11.sp)
        }
        Spacer(Modifier.width(10.dp))
        if (done) {
            Text(doneLabel, color = c.live, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
        } else {
            GhostButton(text = actionLabel, onClick = onAction)
        }
    }
}
