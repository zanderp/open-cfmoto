// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
// In-app language picker for the cockpit. Uses the AndroidX per-app locales API
// (AppCompatDelegate.setApplicationLocales) — the cockpit activities are AppCompatActivity and
// minSdk is 29, so this one call covers API 29-32 (via the appcompat backport + the manifest's
// AppLocalesMetadataHolderService) and API 33+ (delegated to the framework LocaleManager), and it
// self-persists on both. Selecting a language recreates the activity, which is why the current
// choice is re-read from AppCompatDelegate on every composition.
package dev.zanderp.opencfmoto.ui.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
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
import androidx.core.os.LocaleListCompat
import androidx.navigation.NavController
import dev.zanderp.opencfmoto.R
import dev.zanderp.opencfmoto.ui.components.MonoLabel
import dev.zanderp.opencfmoto.ui.theme.LocalCockpitColors
import java.util.Locale

/**
 * The 14 locales the app ships translations for (see res/xml/locales_config.xml and the manifest's
 * android:localeConfig). Kept in the same order as the config. "" (empty) = follow the system, and is
 * presented first as "Automatic (system)".
 */
private val LOCALE_TAGS = listOf(
    "en", "de", "it", "fr", "es", "ca", "pt", "pl", "cs", "ro", "nl", "hu", "tr", "ko",
)

/** Language of the applied per-app locale ("" when following the system). */
private fun appliedLanguage(): String {
    val locales = AppCompatDelegate.getApplicationLocales()
    return if (locales.isEmpty) "" else locales[0]?.language ?: ""
}

/** A locale's own name for itself, capitalized in its own script (e.g. "Español", "Français", "한국어"). */
private fun nativeName(tag: String): String {
    val loc = Locale.forLanguageTag(tag)
    return loc.getDisplayLanguage(loc).replaceFirstChar { if (it.isLowerCase()) it.titlecase(loc) else it.toString() }
}

private fun applyLocale(tag: String) {
    val locales = if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag)
    // Self-persisting + recreates the (AppCompat) activities so the whole cockpit repaints in the
    // chosen language. Must be called on the main thread (it is — this is a click handler).
    AppCompatDelegate.setApplicationLocales(locales)
}

@Composable
fun LanguageScreen(nav: NavController) {
    val c = LocalCockpitColors.current
    val ctx = LocalContext.current

    // Re-seeded on every composition (a selection recreates the activity, giving a fresh one). The
    // local state makes the checkmark move instantly on tap, ahead of that recreation.
    var selected by remember { mutableStateOf(appliedLanguage()) }

    Column(
        Modifier.fillMaxSize().background(c.ground).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Header(stringResource(R.string.ovk_settings_language)) { nav.popBackStack() }
        Text(stringResource(R.string.ovk_language_caption), color = c.inkFaint, fontSize = 12.sp)

        Group(stringResource(R.string.ovk_settings_language)) {
            LangRow(
                label = stringResource(R.string.ovk_language_system),
                selected = selected.isEmpty(),
                first = true,
            ) { selected = ""; applyLocale("") }
            LOCALE_TAGS.forEach { tag ->
                val lang = Locale.forLanguageTag(tag).language
                LangRow(label = nativeName(tag), selected = selected == lang) {
                    selected = lang
                    applyLocale(tag)
                }
            }
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

/** One language option: native name on the left, a check (in the ignition color) when it's active. */
@Composable
private fun LangRow(label: String, selected: Boolean, first: Boolean = false, onClick: () -> Unit) {
    val c = LocalCockpitColors.current
    if (!first) HorizontalDivider(color = c.line.copy(alpha = 0.5f))
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = if (selected) c.ignition else c.ink,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
            fontSize = 14.sp,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Spacer(Modifier.width(6.dp))
            Text("✓", color = c.ignition, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
    }
}
