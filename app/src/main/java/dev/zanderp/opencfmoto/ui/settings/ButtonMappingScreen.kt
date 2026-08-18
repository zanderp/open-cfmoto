// SPDX-License-Identifier: AGPL-3.0-or-later
// Compose port of ButtonMappingActivity's mapping surface — remap what each handlebar gesture does
// and keep the three navigate-to destinations, in the cockpit design. ADDITIVE: reads/writes the
// EXACT same prefs objects as the classic Activity (ButtonMap over "button_map" per-bike, SavedPlaces
// over "saved_places" global, ButtonClusterPreset over "button_cluster_preset"), so the new UI, the
// classic ButtonMappingActivity, the handlebar controls and the live MediaButtonBridge stay in sync.
// MediaButtonBridge reads the map live on each press, so changes apply with no reconnect. The classic
// ButtonMappingActivity is kept in place. The handlebar "teach" dialog and the overlay-permission card
// (hardware detection / onboarding) stay in the classic screen — this is the mapping surface only.
package dev.zanderp.opencfmoto.ui.settings

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.window.Dialog
import androidx.navigation.NavController
import dev.zanderp.opencfmoto.R
import dev.zanderp.opencfmoto.ButtonAction
import dev.zanderp.opencfmoto.ButtonClusterPreset
import dev.zanderp.opencfmoto.ButtonGesture
import dev.zanderp.opencfmoto.ButtonMap
import dev.zanderp.opencfmoto.NavLauncher
import dev.zanderp.opencfmoto.SavedPlaces
import dev.zanderp.opencfmoto.ui.components.GhostButton
import dev.zanderp.opencfmoto.ui.components.MonoLabel
import dev.zanderp.opencfmoto.ui.components.PrimaryButton
import dev.zanderp.opencfmoto.ui.theme.LocalCockpitColors

/**
 * Mapeo de botones — the classic [dev.zanderp.opencfmoto.ButtonMappingActivity] mapping surface,
 * natively in Compose. Each of the nine [ButtonGesture]s shows its current [ButtonAction] and opens a
 * picker of every action; choosing writes through [ButtonMap.set] (stores `ButtonAction.id`, per bike)
 * and updates the row live — [dev.zanderp.opencfmoto.MediaButtonBridge] re-reads the map on the next
 * press, so no reconnect is needed. The three saved destinations are backed by [SavedPlaces] (global
 * `name0..2` / `query0..2`) and persist on every edit, matching the classic Activity which commits its
 * free-text fields whenever the screen goes away. A "Preajuste" selector applies a whole-cluster
 * [ButtonClusterPreset] (or clears it), and "Restablecer" returns the selected bike's gestures to the
 * shipped defaults via [ButtonMap.resetAll] (the preset tag is left as-is, exactly like the Activity).
 */
@Composable
fun ButtonMappingScreen(nav: NavController) {
    val c = LocalCockpitColors.current
    val ctx = LocalContext.current

    // gesture -> current action, seeded once from the store (get() also migrates shipped defaults);
    // edits write through ButtonMap and update the map so the row repaints immediately.
    val gestureActions = remember {
        mutableStateMapOf<ButtonGesture, ButtonAction>().apply {
            ButtonGesture.entries.forEach { put(it, ButtonMap.get(ctx, it)) }
        }
    }
    // Saved places, seeded once; text fields hold the raw input while SavedPlaces.set trims on write.
    val placeNames = remember {
        mutableStateListOf<String>().apply { for (s in 0 until SavedPlaces.COUNT) add(SavedPlaces.name(ctx, s)) }
    }
    val placeQueries = remember {
        mutableStateListOf<String>().apply { for (s in 0 until SavedPlaces.COUNT) add(SavedPlaces.query(ctx, s)) }
    }

    var activePreset by remember { mutableStateOf(ButtonClusterPreset.active(ctx)) }
    var allDefault by remember { mutableStateOf(ButtonMap.isAllDefault(ctx)) }
    var picking by remember { mutableStateOf<ButtonGesture?>(null) }

    fun reseedGestures() {
        ButtonGesture.entries.forEach { gestureActions[it] = ButtonMap.get(ctx, it) }
        allDefault = ButtonMap.isAllDefault(ctx)
    }

    // How a saved slot reads (its own name once set) — derived from the LIVE field state so nav-action
    // rows relabel the instant a destination name is typed. Mirrors SavedPlaces.actionLabel.
    fun savedPlaceLabel(slot: Int): String {
        val query = placeQueries[slot].trim()
        if (query.isBlank()) return ctx.getString(R.string.ovk_btn_nav_to_dest_unset, slot + 1)
        val name = placeNames[slot].trim()
        return ctx.getString(R.string.ovk_btn_nav_to, name.ifBlank { query })
    }

    // The human label for an action, using the live place names for the nav slots.
    fun actionLabel(action: ButtonAction): String = when (action) {
        ButtonAction.NAV_1 -> savedPlaceLabel(0)
        ButtonAction.NAV_2 -> savedPlaceLabel(1)
        ButtonAction.NAV_3 -> savedPlaceLabel(2)
        else -> action.label
    }

    val navMapped = gestureActions.values.any {
        it == ButtonAction.NAV_1 || it == ButtonAction.NAV_2 || it == ButtonAction.NAV_3
    }

    Column(
        Modifier.fillMaxSize().background(c.ground).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Header(stringResource(R.string.ovk_settings_button_mapping)) { nav.popBackStack() }

        Group(stringResource(R.string.ovk_btn_group_preset)) {
            // Pick-one whole-cluster map (or none). Applying also turns handlebar→AA capture on and
            // re-seeds every gesture, exactly like ButtonClusterPreset.apply / clear in the Activity.
            Selector(
                title = stringResource(R.string.ovk_btn_cluster),
                options = listOf<Pair<String, ButtonClusterPreset?>>(stringResource(R.string.ovk_btn_none) to null) +
                    ButtonClusterPreset.entries.map { it.title to it },
                selected = activePreset,
                first = true,
            ) { preset ->
                if (preset == null) {
                    ButtonClusterPreset.clear(ctx)
                    Toast.makeText(ctx, ctx.getString(R.string.ovk_btn_preset_removed), Toast.LENGTH_SHORT).show()
                } else {
                    preset.apply(ctx)
                    Toast.makeText(ctx, ctx.getString(R.string.ovk_btn_preset_applied, preset.title), Toast.LENGTH_SHORT).show()
                }
                activePreset = ButtonClusterPreset.active(ctx)
                reseedGestures()
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 10.dp)) {
                val activeFmt = stringResource(R.string.ovk_btn_active)
                Text(
                    activePreset?.let { activeFmt.format(it.title) }
                        ?: stringResource(R.string.ovk_btn_no_preset),
                    color = c.inkDim,
                    fontSize = 11.sp,
                )
                activePreset?.let {
                    Text(it.summary, color = c.inkFaint, fontSize = 10.5.sp, modifier = Modifier.padding(top = 3.dp))
                }
            }
        }

        Group(stringResource(R.string.ovk_btn_group_gestures)) {
            ButtonGesture.entries.forEachIndexed { i, gesture ->
                GestureRow(
                    label = gesture.label,
                    hint = gesture.hint,
                    action = actionLabel(gestureActions[gesture] ?: gesture.default),
                    first = i == 0,
                ) { picking = gesture }
            }
        }

        Group(stringResource(R.string.ovk_btn_group_saved_dest)) {
            if (navMapped && !NavLauncher.canLaunchFromBackground(ctx)) {
                Text(
                    stringResource(R.string.ovk_btn_overlay_hint),
                    color = c.warn,
                    fontSize = 11.sp,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 10.dp),
                )
            }
            for (slot in 0 until SavedPlaces.COUNT) {
                PlaceRow(
                    index = slot,
                    name = placeNames[slot],
                    query = placeQueries[slot],
                    first = slot == 0,
                    onName = { v ->
                        placeNames[slot] = v
                        SavedPlaces.set(ctx, slot, v, placeQueries[slot])
                    },
                    onQuery = { v ->
                        placeQueries[slot] = v
                        SavedPlaces.set(ctx, slot, placeNames[slot], v)
                    },
                )
            }
        }

        PrimaryButton(
            text = stringResource(R.string.ovk_btn_reset_defaults),
            onClick = {
                ButtonMap.resetAll(ctx)
                reseedGestures()
                Toast.makeText(ctx, ctx.getString(R.string.ovk_btn_gestures_reset), Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            if (allDefault) stringResource(R.string.ovk_btn_reset_hint_default)
            else stringResource(R.string.ovk_btn_reset_hint_changed),
            color = c.inkFaint,
            fontSize = 11.sp,
        )
    }

    picking?.let { gesture ->
        ActionPickerDialog(
            title = gesture.label,
            current = gestureActions[gesture] ?: gesture.default,
            labelOf = { actionLabel(it) },
            onPick = { action ->
                ButtonMap.set(ctx, gesture, action)
                gestureActions[gesture] = action
                allDefault = ButtonMap.isAllDefault(ctx)
                picking = null
            },
            onDismiss = { picking = null },
        )
    }
}

// ─────────────────────────── rows ───────────────────────────

@Composable
private fun GestureRow(label: String, hint: String, action: String, first: Boolean, onClick: () -> Unit) {
    val c = LocalCockpitColors.current
    if (!first) HorizontalDivider(color = c.line.copy(alpha = 0.5f))
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 10.dp)) {
            Text(label, color = c.ink, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Text(hint, color = c.inkFaint, fontSize = 10.5.sp, modifier = Modifier.padding(top = 1.dp))
            Text(action, color = c.ignition, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
        }
        Text("›", color = c.inkFaint, fontSize = 16.sp)
    }
}

@Composable
private fun PlaceRow(
    index: Int,
    name: String,
    query: String,
    first: Boolean,
    onName: (String) -> Unit,
    onQuery: (String) -> Unit,
) {
    val c = LocalCockpitColors.current
    if (!first) HorizontalDivider(color = c.line.copy(alpha = 0.5f))
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.ovk_btn_destination, index + 1), color = c.ink, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        OutlinedTextField(
            value = name,
            onValueChange = onName,
            singleLine = true,
            placeholder = { Text(stringResource(R.string.ovk_btn_name_placeholder)) },
            modifier = Modifier.fillMaxWidth(),
            colors = placeFieldColors(),
        )
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            singleLine = true,
            placeholder = { Text(stringResource(R.string.ovk_address_or_place)) },
            modifier = Modifier.fillMaxWidth(),
            colors = placeFieldColors(),
        )
    }
}

@Composable
private fun placeFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = LocalCockpitColors.current.ignition,
    unfocusedBorderColor = LocalCockpitColors.current.line,
    focusedTextColor = LocalCockpitColors.current.ink,
    unfocusedTextColor = LocalCockpitColors.current.ink,
    cursorColor = LocalCockpitColors.current.ignition,
    focusedContainerColor = LocalCockpitColors.current.groundHi,
    unfocusedContainerColor = LocalCockpitColors.current.groundHi,
)

// ─────────────────────────── action picker ───────────────────────────

@Composable
private fun ActionPickerDialog(
    title: String,
    current: ButtonAction,
    labelOf: (ButtonAction) -> String,
    onPick: (ButtonAction) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = LocalCockpitColors.current
    Dialog(onDismissRequest = onDismiss) {
        Surface(color = c.surface1, shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, c.line)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(title, color = c.ink, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Column(
                    Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ButtonAction.entries.forEach { action ->
                        val selected = action == current
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                .background(if (selected) c.ignition.copy(alpha = 0.14f) else c.groundHi)
                                .border(
                                    1.dp,
                                    if (selected) c.ignition.copy(alpha = 0.55f) else c.line,
                                    RoundedCornerShape(10.dp),
                                )
                                .clickable { onPick(action) }
                                .padding(horizontal = 12.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                labelOf(action),
                                color = if (selected) c.ink else c.inkDim,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                fontSize = 13.sp,
                                modifier = Modifier.weight(1f),
                            )
                            if (selected) Text("✓", color = c.ignition, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        }
                    }
                }
                GhostButton(stringResource(R.string.ovk_cancel), onDismiss, Modifier.fillMaxWidth())
            }
        }
    }
}

// ─────────────────────────── cockpit chrome (re-implemented from SetupScreen's private patterns) ───────────────────────────

/** Section label + rounded surface card, matching the settings screens' grouped look. */
@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    val c = LocalCockpitColors.current
    MonoLabel(title, color = c.inkFaint)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surface1)
            .border(1.dp, c.line, RoundedCornerShape(12.dp)),
    ) { content() }
}

/** A pick-one control: a title with a wrapping row of segment chips (the cockpit's segmented look). */
@Composable
private fun <T> Selector(
    title: String,
    options: List<Pair<String, T>>,
    selected: T,
    first: Boolean = false,
    onSelect: (T) -> Unit,
) {
    val c = LocalCockpitColors.current
    if (!first) HorizontalDivider(color = c.line.copy(alpha = 0.5f))
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, color = c.ink, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            options.forEach { (label, value) ->
                Seg(label, value == selected) { onSelect(value) }
            }
        }
    }
}

@Composable
private fun Seg(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = LocalCockpitColors.current
    Box(
        Modifier.clip(RoundedCornerShape(8.dp))
            .background(if (selected) c.ignition else c.groundHi)
            .border(1.dp, if (selected) c.ignition else c.line, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (selected) c.onIgnition else c.inkDim,
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
        )
    }
}
