// SPDX-License-Identifier: AGPL-3.0-or-later
// Cockpit design system — the tokens from the approved mockups, as a THEME-AWARE Compose system.
// The whole cockpit UI reads its colors from LocalCockpitColors (a CompositionLocal) instead of the
// old hardcoded top-level tokens, so a single switch at the top (CockpitTheme, driven by the Tema
// setting) repaints every screen light or dark. Material3's ColorScheme is derived from the same
// palette so M3 widgets (Slider, OutlinedTextField, AlertDialog, Switch, Surface…) theme too.
package dev.zanderp.opencfmoto.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zanderp.opencfmoto.settings.SettingsStore
import dev.zanderp.opencfmoto.settings.ThemeMode

/**
 * Every cockpit color token in one bag. Screens read [LocalCockpitColors].current and pull the token
 * they need (`c.ground`, `c.ink`, …), so the same composable renders correctly in light or dark
 * without any per-screen branching. Material3's ColorScheme is derived from the same instance.
 */
data class CockpitColors(
    val ground: Color,
    val groundHi: Color,
    val surface1: Color,
    val surface2: Color,
    val line: Color,
    val ink: Color,
    val inkDim: Color,
    val inkFaint: Color,
    val ignition: Color,
    val ignitionHi: Color,
    val onIgnition: Color,
    val live: Color,
    val warn: Color,
    val fault: Color,
)

/** Cockpit-night palette (from the approved 450NK Cockpit mockups). */
val DarkCockpit = CockpitColors(
    ground = Color(0xFF0B0E13),
    groundHi = Color(0xFF0F141B),
    surface1 = Color(0xFF161C24),
    surface2 = Color(0xFF1E252F),
    line = Color(0xFF29323D),
    ink = Color(0xFFEAEEF3),
    inkDim = Color(0xFF9AA6B4),
    inkFaint = Color(0xFF66717F),
    ignition = Color(0xFFFF6A2C),
    ignitionHi = Color(0xFFFF8A4C),
    onIgnition = Color(0xFF160A03),
    live = Color(0xFF35D07F),
    warn = Color(0xFFF3B33D),
    fault = Color(0xFFFF5B52),
)

/** Cockpit-day palette — cool-tinted neutrals; the semantics are darkened for contrast on light. */
val LightCockpit = CockpitColors(
    ground = Color(0xFFF3F5F8),
    groundHi = Color(0xFFECEFF4),
    surface1 = Color(0xFFFFFFFF),
    surface2 = Color(0xFFF5F7FA),
    line = Color(0xFFDCE2EA),
    ink = Color(0xFF141A21),
    inkDim = Color(0xFF566270),
    inkFaint = Color(0xFF8B96A3),
    ignition = Color(0xFFE85D1F),
    ignitionHi = Color(0xFFFF7A3C),
    onIgnition = Color(0xFFFFFFFF),
    live = Color(0xFF1D9E5E),
    warn = Color(0xFFB9790A),
    fault = Color(0xFFD23A31),
)

/** The active cockpit palette for the current composition. Defaults to dark for pre-theme callers. */
val LocalCockpitColors = staticCompositionLocalOf { DarkCockpit }

/** Map the cockpit palette onto a Material3 ColorScheme so M3 components theme with the cockpit. */
private fun CockpitColors.toMaterialScheme(dark: Boolean) = if (dark) {
    darkColorScheme(
        primary = ignition,
        onPrimary = onIgnition,
        secondary = ignitionHi,
        onSecondary = onIgnition,
        background = ground,
        onBackground = ink,
        surface = surface1,
        onSurface = ink,
        surfaceVariant = surface2,
        onSurfaceVariant = inkDim,
        outline = line,
        outlineVariant = line,
        error = fault,
        onError = onIgnition,
    )
} else {
    lightColorScheme(
        primary = ignition,
        onPrimary = onIgnition,
        secondary = ignitionHi,
        onSecondary = onIgnition,
        background = ground,
        onBackground = ink,
        surface = surface1,
        onSurface = ink,
        surfaceVariant = surface2,
        onSurfaceVariant = inkDim,
        outline = line,
        outlineVariant = line,
        error = fault,
        onError = onIgnition,
    )
}

/**
 * Wraps [content] in the resolved cockpit palette. Reads the Tema setting from [SettingsStore] and
 * resolves AUTO against the system light/dark. Signature is unchanged (setting read internally), so
 * CockpitActivity's `CockpitTheme { … }` call is untouched.
 */
@Composable
fun CockpitTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val store = remember(ctx) { SettingsStore(ctx.applicationContext) }
    val mode by store.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.AUTO)
    val dark = when (mode) {
        ThemeMode.AUTO -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val scheme = if (dark) DarkCockpit else LightCockpit
    CompositionLocalProvider(LocalCockpitColors provides scheme) {
        MaterialTheme(colorScheme = scheme.toMaterialScheme(dark), content = content)
    }
}
