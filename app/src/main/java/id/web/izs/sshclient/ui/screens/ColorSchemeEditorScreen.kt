package id.web.izs.sshclient.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import id.web.izs.sshclient.core.config.RawConfigStore
import id.web.izs.sshclient.core.config.TerminalColorScheme
import id.web.izs.sshclient.core.config.argbToHex
import id.web.izs.sshclient.core.config.deleteCustomByName
import id.web.izs.sshclient.core.config.normalizeSchemeColor
import id.web.izs.sshclient.core.config.schemeColorArgb
import id.web.izs.sshclient.core.config.schemeReadabilityIssues
import id.web.izs.sshclient.core.config.upsertCustom
import id.web.izs.sshclient.ui.AppState
import kotlinx.coroutines.launch

/**
 * Current-scheme editor (desktop colorSchemeSettingsForMode parity): edits
 * the CURRENT global scheme inline (name + 22 dots), live preview,
 * readability warnings. Save writes the object as the global scheme AND
 * upserts it into customs by name — so rename + Save creates a new custom
 * entry (desktop saveScheme), same name replaces. No separate "new" flow.
 *
 * The picker is desktop-like (ngx-colors parity): tap a dot, tap a preset
 * swatch — done in 2 clicks — plus a manual hex field. No sliders.
 */

private data class Slot(val key: String, val label: String, val hint: String)

// Desktop color-picker titles + hints (colorSchemeSettingsForMode pug):
// FG/BG/CU/CA/SB/SF then ANSI 0-15.
private val SLOT_ORDER: List<Slot> = listOf(
    Slot("foreground", "FG", "Foreground"),
    Slot("background", "BG", "Background"),
    Slot("cursor", "CU", "Cursor colour"),
    Slot("cursorAccent", "CA", "Block cursor foreground"),
    Slot("selection", "SB", "Selection background"),
    Slot("selectionForeground", "SF", "Selection foreground"),
) + (0..15).map { Slot("color$it", "$it", "ANSI colour $it") }

/**
 * Desktop picker level 1: 4 x 5 family grid (material 500s + black).
 * Level 2 ([shadeSteps]): 9 lightness steps of the tapped family.
 */
private val FAMILY_BASES = listOf(
    "#f44336", "#e91e63", "#9c27b0", "#673ab7",
    "#3f51b5", "#2196f3", "#03a9f4", "#00bcd4",
    "#009688", "#4caf50", "#8bc34a", "#cddc39",
    "#ffeb3b", "#ffc107", "#ff9800", "#ff5722",
    "#795548", "#9e9e9e", "#607d8b", "#000000",
)

/**
 * 9 steps of one family: 4 shades (toward black), the base, 4 tints
 * (toward white). Pure RGB math (no Android APIs) so it is unit-tested.
 */
fun shadeSteps(argb: Int): List<Int> {
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF
    fun mix(a: Int, c: Int, t: Float): Int = (a + ((c - a) * t)).toInt().coerceIn(0, 255)
    fun rgb(fr: Int, fg: Int, fb: Int): Int = (0xFF shl 24) or (fr shl 16) or (fg shl 8) or fb
    return (0..8).map { i ->
        when {
            i < 4 -> {
                val t = (i + 1) / 5f
                rgb(mix(0, r, t), mix(0, g, t), mix(0, b, t))
            }
            i == 4 -> rgb(r, g, b)
            else -> {
                val t = (i - 4) / 4f
                rgb(mix(r, 255, t), mix(g, 255, t), mix(b, 255, t))
            }
        }
    }
}

private fun slotDefault(key: String, base: TerminalColorScheme): String = when (key) {
    "foreground" -> base.foreground
    "background" -> base.background
    "cursor" -> base.cursor
    "selection" -> base.selection ?: "#88888888"
    "selectionForeground" -> base.selectionForeground ?: ""
    "cursorAccent" -> base.cursorAccent ?: ""
    else -> base.colors.getOrNull(key.removePrefix("color").toIntOrNull() ?: -1) ?: ""
}

private fun draftToScheme(name: String, slots: Map<String, String>): TerminalColorScheme? {
    val colors = (0..15).map { normalizeSchemeColor(slots["color$it"]) ?: return null }
    return TerminalColorScheme(
        name = name.trim(),
        foreground = normalizeSchemeColor(slots["foreground"]) ?: return null,
        background = normalizeSchemeColor(slots["background"]) ?: return null,
        cursor = normalizeSchemeColor(slots["cursor"]) ?: return null,
        colors = colors,
        selection = normalizeSchemeColor(slots["selection"]),
        selectionForeground = normalizeSchemeColor(slots["selectionForeground"]),
        cursorAccent = normalizeSchemeColor(slots["cursorAccent"]),
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ColorSchemeEditorScreen(
    state: AppState,
    initial: TerminalColorScheme,
    showDelete: Boolean,
    onBack: () -> Unit,
    /**
     * Device-only mode (Settings source = this device): Save applies the
     * device pref instantly AND upserts the shared custom pool (vault-aware,
     * may ask unlock) so renamed schemes still accumulate in the list.
     * No Delete in device mode. Synced mode keeps desktop saveScheme/upsert.
     */
    deviceMode: Boolean = false,
    onDeviceSave: (TerminalColorScheme) -> Unit = {},
) {
    var name by remember(initial) { mutableStateOf(initial.name) }
    val slots = remember(initial) {
        mutableStateMapOf<String, String>().also { m ->
            SLOT_ORDER.forEach { m[it.key] = slotDefault(it.key, initial) }
        }
    }
    var editingSlot by remember { mutableStateOf<Slot?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var showUnlock by remember { mutableStateOf(false) }
    var pendingRetry by remember { mutableStateOf<(() -> Unit)?>(null) }
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val draft = remember(name, slots.toMap()) { draftToScheme(name, slots) }
    val issues = remember(draft) { draft?.let { schemeReadabilityIssues(it) } ?: emptyList() }

    fun runWrite(write: (LinkedHashMap<String, Any?>) -> Unit, andBack: Boolean) {
        msg = null
        scope.launch {
            busy = true
            try {
                val loaded = state.repo.updateTerminalSection { raw -> write(raw) }
                if (andBack) state.adopt(loaded) { onBack() } else state.adopt(loaded)
            } catch (e: IllegalStateException) {
                if ((e.message ?: "").contains("locked", ignoreCase = true)) {
                    pendingRetry = { runWrite(write, andBack) }
                    showUnlock = true
                } else {
                    msg = "Couldn't save: ${e.message}"
                }
            } catch (e: Exception) {
                msg = "Couldn't save: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    fun onSave() {
        val scheme = draft ?: run { msg = "Fix the highlighted colours first."; return }
        if (scheme.name.isEmpty()) { msg = "Enter a scheme name."; return }
        if (deviceMode) {
            // Instant first: the device uses it now (plain pref, no vault).
            onDeviceSave(scheme)
            // Shared pool second: upsert so the renamed scheme ALSO appears
            // in the list (vault-aware — may ask unlock; the device pref
            // above already applied regardless).
            runWrite({ raw ->
                RawConfigStore.setCustomColorSchemes(
                    raw,
                    upsertCustom(RawConfigStore.customColorSchemesRaw(raw), scheme),
                )
            }, andBack = true)
            return
        }
        // Desktop saveScheme: global = edited object, customs upsert by name.
        runWrite({ raw ->
            RawConfigStore.setTerminalColorScheme(raw, scheme)
            RawConfigStore.setCustomColorSchemes(
                raw,
                upsertCustom(RawConfigStore.customColorSchemesRaw(raw), scheme),
            )
        }, andBack = true)
    }

    fun onDelete() {
        val target = draft?.name?.takeIf { it.isNotEmpty() } ?: initial.name
        // Desktop deleteScheme: drop the custom entry by name; the global
        // keeps its copy, so the terminal does not change.
        runWrite({ raw ->
            RawConfigStore.setCustomColorSchemes(
                raw,
                deleteCustomByName(RawConfigStore.customColorSchemesRaw(raw), target),
            )
        }, andBack = true)
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenHeader("Edit colour scheme", onBack)
        Text(
            if (deviceMode) "Edits the scheme on this device. Saving also adds it to your custom schemes."
            else "Edits the current scheme. Rename and save to keep the old version " +
                "as a separate custom entry. Using the same name replaces it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Scheme name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        draft?.let { SchemeFullPreview(it, modifier = Modifier.fillMaxWidth()) }
        if (issues.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Readability warning:",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    issues.forEach {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        Text("Colours (22)", style = MaterialTheme.typography.titleMedium)
        // Dense labeled swatches like desktop's color-picker row
        // (FG/BG/CU… + 0-15), not big buttons.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SLOT_ORDER.forEach { slot ->
                val hex = slots[slot.key] ?: ""
                val valid = normalizeSchemeColor(hex) != null || slot.key in
                    setOf("selectionForeground", "cursorAccent")
                val fill = schemeColorArgb(hex)?.let { Color(it) }
                SlotDot(
                    slot = slot,
                    fill = fill,
                    valid = valid,
                    onTap = { editingSlot = slot },
                )
            }
        }
        Text(
            "FG is text, BG is background, CU is cursor, CA is cursor accent, " +
                "SB is selection, SF is selection text, and 0 to 15 are ANSI colors. " +
                "Empty CA or SF uses the default. Tap a colour to change it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = ::onSave, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text("Save")
        }
        if (showDelete && !deviceMode) {
            OutlinedButton(
                onClick = { showDeleteConfirm = true },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        }
        msg?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (busy) CircularProgressIndicator()
    }

    editingSlot?.let { slot ->
        ModalBottomSheet(
            onDismissRequest = { editingSlot = null },
            sheetState = sheetState,
        ) {
            key(slot.key) {
                PresetPicker(
                    label = "${slot.label}: ${slot.hint}",
                    currentHex = slots[slot.key] ?: "",
                    onPick = { slots[slot.key] = it },
                    onPresetPick = { slots[slot.key] = it; editingSlot = null },
                )
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete scheme?") },
            text = { Text("Removes the custom entry. The current global keeps its copy, so the terminal does not change.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    onDelete()
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") } },
        )
    }

    if (showUnlock) {
        VaultUnlockDialog(
            state = state,
            onUnlocked = {
                showUnlock = false
                pendingRetry?.invoke()
                pendingRetry = null
            },
            onNoConfig = { showUnlock = false },
            onDismiss = {
                showUnlock = false
                pendingRetry = null
                // The device pref (device mode) already applied; only the
                // shared-list entry is missing — say so plainly.
                msg = "Saved on this device. Custom list entry skipped (vault locked)."
            },
        )
    }
}

/**
 * One labeled swatch. Tap = pick; LONG-PRESS = tooltip with the full name
 * (the mobile equivalent of desktop hover tooltips — M3 TooltipBox is the
 * canonical pattern, no custom popup needed).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun SlotDot(
    slot: Slot,
    fill: Color?,
    valid: Boolean,
    onTap: () -> Unit,
) {
    val tooltipState = rememberTooltipState()
    val scope = rememberCoroutineScope()
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
            androidx.compose.material3.TooltipAnchorPosition.Above,
        ),
        tooltip = { PlainTooltip { Text("${slot.label}: ${slot.hint}") } },
        state = tooltipState,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.combinedClickable(
                onClick = onTap,
                onLongClick = { scope.launch { tooltipState.show() } },
            ),
        ) {
            Box(
                Modifier.size(32.dp).clip(RoundedCornerShape(8.dp))
                    .background(fill ?: MaterialTheme.colorScheme.surfaceVariant),
            )
            Text(
                slot.label,
                style = MaterialTheme.typography.labelSmall,
                color = if (valid) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * Desktop picker parity: level 1 is a 4 x 5 family grid; tapping a family
 * shows its 9 lightness steps (tap = pick + close). Manual hex always
 * available. No sliders.
 */
@Composable
private fun PresetPicker(
    label: String,
    currentHex: String,
    onPick: (String) -> Unit,
    onPresetPick: (String) -> Unit,
) {
    var hex by remember(currentHex) { mutableStateOf(currentHex) }
    var hexError by remember { mutableStateOf(false) }
    // Level-2 family (null = grid). Reset per dot via the key() at the call site.
    var family by remember { mutableStateOf<Int?>(null) }

    fun pick(argb: Int) {
        val out = argbToHex(argb)
        hex = out
        hexError = false
        onPresetPick(out)
    }

    Column(
        Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (family != null) {
                Text(
                    "\u2039",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.clickable { family = null }.padding(end = 4.dp),
                )
            }
            val preview = schemeColorArgb(hex)?.let { Color(it) }
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(8.dp))
                    .background(preview ?: MaterialTheme.colorScheme.surfaceVariant),
            )
            Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        }
        if (family == null) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                FAMILY_BASES.forEach { base ->
                    val argb = schemeColorArgb(base) ?: return@forEach
                    Box(
                        Modifier.size(40.dp).clip(RoundedCornerShape(8.dp))
                            .background(Color(argb))
                            .clickable { family = argb },
                    )
                }
            }
        } else {
            val steps = remember(family) { shadeSteps(family ?: 0xFF000000.toInt()) }
            val current = normalizeSchemeColor(hex)?.let { schemeColorArgb(it) }
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                steps.forEach { step ->
                    Box(
                        Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(8.dp))
                            .background(Color(step))
                            .clickable { pick(step) },
                    ) {
                        if (step == current) {
                            Box(
                                Modifier.size(12.dp).clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary)
                                    .align(Alignment.Center),
                            )
                        }
                    }
                }
            }
        }
        OutlinedTextField(
            value = hex,
            onValueChange = { t ->
                hex = t
                val norm = normalizeSchemeColor(t)
                hexError = norm == null
                if (norm != null) onPick(norm)
            },
            label = { Text("Hex colour (#rrggbb or #aarrggbb)") },
            singleLine = true,
            isError = hexError,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
