package id.web.izs.sshclient.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FormatColorReset
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.unit.dp
import android.content.ClipData
import id.web.izs.sshclient.core.config.PROFILE_COLORS
import id.web.izs.sshclient.core.config.profileColorArgb
import id.web.izs.sshclient.core.term.KEYBOARD_PRESETS
import id.web.izs.sshclient.core.term.KIND_MENU
import id.web.izs.sshclient.core.term.KIND_SEND
import id.web.izs.sshclient.core.term.KIND_STICKY_ALT
import id.web.izs.sshclient.core.term.KIND_STICKY_CTRL
import id.web.izs.sshclient.core.term.KeyDef
import id.web.izs.sshclient.core.term.KeyLayout
import id.web.izs.sshclient.core.term.KeyStep
import id.web.izs.sshclient.core.term.MAX_KEYS_PER_ROW
import id.web.izs.sshclient.core.term.MAX_MENU_ITEMS
import id.web.izs.sshclient.core.term.MAX_ROWS
import id.web.izs.sshclient.core.term.MAX_STEPS
import id.web.izs.sshclient.core.term.MOD_ALT
import id.web.izs.sshclient.core.term.MOD_CTRL
import id.web.izs.sshclient.core.term.MOD_NONE
import id.web.izs.sshclient.core.term.MenuItemDef
import id.web.izs.sshclient.core.term.MACRO_PRESETS
import id.web.izs.sshclient.core.term.STEP_PRESETS
import id.web.izs.sshclient.core.term.WIDTH_DOUBLE
import id.web.izs.sshclient.core.term.WIDTH_HALF
import id.web.izs.sshclient.core.term.WIDTH_NORMAL
import id.web.izs.sshclient.core.term.WIDTH_WIDE
import id.web.izs.sshclient.core.term.describeStep
import id.web.izs.sshclient.core.term.loadKeyLayout
import id.web.izs.sshclient.core.term.maxLabelForWidth
import id.web.izs.sshclient.core.term.normalizeKeyLayout
import id.web.izs.sshclient.core.term.parseKeyLayout
import id.web.izs.sshclient.core.term.saveKeyLayout
import id.web.izs.sshclient.core.term.stepsDisplay
import id.web.izs.sshclient.ui.AppState
import kotlinx.coroutines.launch

/** Editor position of the key dialog: index == row size means a new key. */
private data class EditTarget(val row: Int, val index: Int)

/**
 * Settings > Terminal > Extra keys: live preview (the same bar composable
 * the terminal renders, so WYSIWYG by construction) above the row/key
 * editor. Everything persists immediately — no Save button.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KeyboardLayoutScreen(
    state: AppState,
    onBack: () -> Unit,
) {
    var layout by remember { mutableStateOf(loadKeyLayout(state.disk.extraKeysJson)) }
    var edit by remember { mutableStateOf<EditTarget?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var pendingPreset by remember { mutableStateOf<KeyLayout?>(null) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    /** Single write path: normalize in memory, store the canonical JSON. */
    fun commit(next: KeyLayout) {
        layout = normalizeKeyLayout(next)
        state.disk.extraKeysJson = saveKeyLayout(next)
        notice = null
    }

    // Preview sits OUTSIDE the padded list, edge-to-edge like the
    // terminal dock: same composable AND same full-bleed width, so the
    // key sizes match the live bar exactly (weight only stretches within
    // the available width — padding would shrink every key).
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            "Extra keys",
            onBack,
            Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
        )
        Text(
            "Preview — exactly how the bar looks in the terminal.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
        )
            // Inert taps (menu keys still open, showing their items).
            ExtraKeysBar(
                layout = layout,
                enabled = true,
                ctrlActive = false,
                altActive = false,
                onSendSteps = {},
                onToggleCtrl = {},
                onToggleAlt = {},
            )
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
        itemsIndexed(layout.rows, key = { i, _ -> "row$i" }) { rowIdx, row ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Row ${rowIdx + 1}", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.weight(1f))
                        IconButton(
                            onClick = {
                                if (row.size < MAX_KEYS_PER_ROW) edit = EditTarget(rowIdx, row.size)
                            },
                            enabled = row.size < MAX_KEYS_PER_ROW,
                        ) { Icon(Icons.Filled.Add, contentDescription = "Add key") }
                        IconButton(
                            onClick = {
                                commit(layout.copy(rows = layout.rows.filterIndexed { i, _ -> i != rowIdx }))
                            },
                            enabled = layout.rows.size > 1,
                        ) { Icon(Icons.Filled.Delete, contentDescription = "Delete row") }
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        row.forEachIndexed { keyIdx, key ->
                            AssistChip(
                                onClick = { edit = EditTarget(rowIdx, keyIdx) },
                                label = { Text(key.label) },
                                trailingIcon = if (key.kind == KIND_MENU) {
                                    { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) }
                                } else null,
                            )
                        }
                    }
                }
            }
        }
        if (layout.rows.size < MAX_ROWS) {
            item {
                OutlinedButton(
                    onClick = {
                        val rows = layout.rows + listOf(
                            listOf(KeyDef("A", KIND_SEND, steps = listOf(KeyStep("a")))),
                        )
                        commit(layout.copy(rows = rows))
                        // Index into the NEW list: layout is still the
                        // pre-commit value here, so rows.size - 1, not
                        // layout.rows.size (that opens the old last row).
                        edit = EditTarget(rows.size - 1, 0)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Add row") }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PresetRow(onPick = { pendingPreset = it })
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    clipboard.setClipEntry(
                                        ClipEntry(ClipData.newPlainText("extra-keys", saveKeyLayout(layout))),
                                    )
                                }
                                notice = "Layout copied: ${layout.rows.size} rows."
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = null)
                            Text("Export")
                        }
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    val pasted = clipboard.getClipEntry()
                                        ?.clipData?.getItemAt(0)?.text?.toString()
                                    val parsed = pasted?.let(::parseKeyLayout)
                                    if (parsed == null) {
                                        notice = if (pasted.isNullOrBlank()) {
                                            "Clipboard is empty."
                                        } else {
                                            "Not a valid layout — nothing imported."
                                        }
                                    } else {
                                        commit(parsed)
                                        notice = "Imported ${parsed.rows.size} rows."
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Filled.ContentPaste, contentDescription = null)
                            Text("Import")
                        }
                    }
                    notice?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        "Layouts are stored only on this device. They are never synced.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        } // end LazyColumn
    } // end root Column

    edit?.let { target ->
        val row = layout.rows.getOrNull(target.row) ?: return@let
        val current = row.getOrNull(target.index)
        // Keyed on position: after a move the dialog shows the key now at
        // this index (remembers reset) instead of stale field values saved
        // onto the wrong key.
        key(target.row, target.index) {
            KeyEditDialog(
            initial = current,
            onSave = { saved ->
                val rows = layout.rows.mapIndexed { i, r ->
                    if (i != target.row) r
                    else if (target.index >= r.size) r + saved
                    else r.mapIndexed { j, k -> if (j == target.index) saved else k }
                }
                commit(layout.copy(rows = rows))
                edit = null
            },
            onDelete = current?.let {
                {
                    val rows = layout.rows.mapIndexed { i, r ->
                        if (i != target.row) r
                        else r.filterIndexed { j, _ -> j != target.index }
                    }.filter { it.isNotEmpty() }
                    commit(layout.copy(rows = rows.ifEmpty { layout.rows }))
                    edit = null
                }
            },
            onMove = { delta ->
                val from = target.index
                val to = from + delta
                if (from < row.size && to in row.indices) {
                    val moved = row.toMutableList().also {
                        val k = it.removeAt(from)
                        it.add(to, k)
                    }
                    val rows = layout.rows.mapIndexed { i, r -> if (i == target.row) moved else r }
                    commit(layout.copy(rows = rows))
                    edit = target.copy(index = to)
                }
            },
            canMoveLeft = target.index > 0,
            canMoveRight = current != null && target.index < row.size - 1,
            onDismiss = { edit = null },
            )
        }
    }

    // Preset landing: append as new rows (keeps mine) or replace all
    // (back to a known-good start). Replace is destructive — error tint.
    pendingPreset?.let { preset ->
        AlertDialog(
            onDismissRequest = { pendingPreset = null },
            title = { Text("Load preset") },
            text = { Text("Add its rows to your layout, or replace everything?") },
            confirmButton = {
                Row {
                    TextButton(
                        onClick = {
                            val room = MAX_ROWS - layout.rows.size
                            if (room <= 0) {
                                notice = "Row limit reached ($MAX_ROWS). Delete a row first."
                            } else {
                                val add = preset.rows.take(room)
                                commit(layout.copy(rows = layout.rows + add))
                                notice = "Added ${add.size} rows."
                            }
                            pendingPreset = null
                        },
                    ) { Text("Add rows") }
                    TextButton(
                        onClick = {
                            commit(preset)
                            notice = "Layout replaced."
                            pendingPreset = null
                        },
                    ) { Text("Replace all", color = MaterialTheme.colorScheme.error) }
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingPreset = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun PresetRow(onPick: (KeyLayout) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Load preset")
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for ((name, preset) in KEYBOARD_PRESETS) {
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        open = false
                        onPick(preset)
                    },
                )
            }
        }
    }
}

@Composable
private fun KindChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}

/**
 * Unconfirmed step entry (text field + modifier + edited row). Hoisted out
 * of [StepsEditor] so the dialog can fold the draft into Save/Add-item:
 * typing a step then hitting Save must not silently drop it.
 */
private class StepDraft {
    var text by mutableStateOf("")
    var mod by mutableStateOf(MOD_NONE)
    var editIdx by mutableStateOf<Int?>(null)
    var editPreset by mutableStateOf(false)
    var picker by mutableStateOf(false)

    fun clear() {
        text = ""
        mod = MOD_NONE
        editIdx = null
        editPreset = false
    }

    fun build(): KeyStep? =
        KeyStep(text, mod, editPreset).takeIf { it.text.isNotEmpty() }

    /** Draft folded in: replaces the edited row, else appends (capped). */
    fun committed(steps: List<KeyStep>): List<KeyStep> {
        val draft = build() ?: return steps
        val idx = editIdx
        return if (idx != null && idx in steps.indices) {
            steps.mapIndexed { j, s -> if (j == idx) draft else s }
        } else {
            (steps + draft).take(MAX_STEPS)
        }
    }
}

/**
 * New/edit key dialog. Save stays disabled until the key is complete:
 * non-blank label always, at least one step for Send, at least one item
 * for Menu (unconfirmed drafts count — they fold in on Save/Add-item).
 * Structural caps are enforced by normalize on commit.
 */
@Composable
private fun KeyEditDialog(
    initial: KeyDef?,
    onSave: (KeyDef) -> Unit,
    onDelete: (() -> Unit)?,
    onMove: (Int) -> Unit,
    canMoveLeft: Boolean,
    canMoveRight: Boolean,
    onDismiss: () -> Unit,
) {
    var label by remember { mutableStateOf(initial?.label ?: "") }
    var kind by remember { mutableStateOf(initial?.kind ?: KIND_SEND) }
    var width by remember { mutableStateOf(initial?.width ?: WIDTH_NORMAL) }
    var color by remember { mutableStateOf(initial?.color ?: "") }
    var steps by remember { mutableStateOf(initial?.steps ?: emptyList()) }
    var items by remember { mutableStateOf(initial?.items ?: emptyList()) }
    var itemLabel by remember { mutableStateOf("") }
    var itemSteps by remember { mutableStateOf(emptyList<KeyStep>()) }
    var editItemIdx by remember { mutableStateOf<Int?>(null) }
    val keyDraft = remember { StepDraft() }
    val itemDraft = remember { StepDraft() }

    fun clearItemFields() {
        itemLabel = ""
        itemSteps = emptyList()
        editItemIdx = null
        itemDraft.clear()
    }

    /** Item list with the unconfirmed item draft folded in (if valid). */
    fun commitItemDraft(): List<MenuItemDef> {
        val finalSteps = itemDraft.committed(itemSteps)
        if (itemLabel.isBlank() || finalSteps.none { it.text.isNotEmpty() }) return items
        val built = MenuItemDef(itemLabel.trim(), steps = finalSteps)
        return (editItemIdx?.let { idx ->
            items.mapIndexed { j, m -> if (j == idx) built else m }
        } ?: (items + built)).take(MAX_MENU_ITEMS)
    }

    val complete = label.isNotBlank() && when (kind) {
        KIND_SEND -> keyDraft.committed(steps).any { it.text.isNotEmpty() }
        KIND_MENU -> commitItemDraft().isNotEmpty()
        else -> true
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "New key" else "Edit key") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Every block keyed: menu rows come and go, and unkeyed
                // slots would hand remembered state to the wrong block.
                item(key = "label") {
                    // Budget follows the width tier; narrowing trims live so
                    // the saved label always fits the button.
                    val maxLabel = maxLabelForWidth(width)
                    OutlinedTextField(
                        value = label,
                        onValueChange = { label = it.take(maxLabel) },
                        label = { Text("Label") },
                        supportingText = { Text("${label.length}/$maxLabel") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item(key = "kind") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        KindChip("Send", kind == KIND_SEND) { kind = KIND_SEND }
                        KindChip("Modifier", kind == KIND_STICKY_CTRL || kind == KIND_STICKY_ALT) {
                            kind = KIND_STICKY_CTRL
                        }
                        KindChip("Menu", kind == KIND_MENU) { kind = KIND_MENU }
                    }
                }
                when (kind) {
                    KIND_SEND -> item(key = "send-steps") {
                        StepsEditor(steps = steps, onChange = { steps = it }, draft = keyDraft)
                    }
                    KIND_STICKY_CTRL, KIND_STICKY_ALT -> item(key = "sticky") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            KindChip("CTRL", kind == KIND_STICKY_CTRL) { kind = KIND_STICKY_CTRL }
                            KindChip("ALT", kind == KIND_STICKY_ALT) { kind = KIND_STICKY_ALT }
                        }
                    }
                    KIND_MENU -> {
                        if (items.isEmpty()) {
                            item(key = "menu-hint") {
                                Text(
                                    "Add at least one item: fill its label and steps, " +
                                        "then Add item.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        items.forEachIndexed { i, item ->
                            item(key = "menuitem-$i") {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(
                                        onClick = {
                                            itemLabel = item.label
                                            itemSteps = item.steps
                                            editItemIdx = i
                                        },
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Column(Modifier.fillMaxWidth()) {
                                            Text(item.label)
                                            Text(
                                                stepsDisplay(item.steps),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                    IconButton(
                                        onClick = {
                                            items = items.filterIndexed { j, _ -> j != i }
                                            val idx = editItemIdx
                                            if (idx == i) clearItemFields()
                                            else if (idx != null && i < idx) editItemIdx = idx - 1
                                        },
                                    ) {
                                        Icon(Icons.Filled.Delete, contentDescription = "Delete item")
                                    }
                                }
                            }
                        }
                        item(key = "item-label") {
                            OutlinedTextField(
                                value = itemLabel,
                                onValueChange = { itemLabel = it },
                                label = { Text("Item label") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        item(key = "item-steps") {
                            StepsEditor(steps = itemSteps, onChange = { itemSteps = it }, draft = itemDraft)
                        }
                        item(key = "item-add") {
                            OutlinedButton(
                                onClick = {
                                    items = commitItemDraft()
                                    clearItemFields()
                                },
                                enabled = itemLabel.isNotBlank() &&
                                    itemDraft.committed(itemSteps).any { it.text.isNotEmpty() },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(if (editItemIdx == null) "Add item" else "Update item") }
                        }
                    }
                }
                item(key = "width") {
                    // Segmented (never wraps): Half 0.5x, Normal 1x, Wide
                    // 1.5x, Double 2x.
                    Text(
                        "Key width",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val widths = listOf(
                        "Half" to WIDTH_HALF,
                        "Normal" to WIDTH_NORMAL,
                        "Wide" to WIDTH_WIDE,
                        "Double" to WIDTH_DOUBLE,
                    )
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        widths.forEachIndexed { i, (name, value) ->
                            SegmentedButton(
                                selected = width == value,
                                onClick = {
                                    width = value
                                    label = label.take(maxLabelForWidth(value))
                                },
                                shape = SegmentedButtonDefaults.itemShape(i, widths.size),
                                // Highlight only: the default check icon
                                // crowds the short labels.
                                icon = {},
                            ) { Text(name) }
                        }
                    }
                }
                item(key = "color") {
                    // Compact inline swatches (same palette storage as profiles):
                    // empty = tint by kind.
                    KeyColorRow(selected = color, onSelect = { color = it })
                }
            }
        },
        confirmButton = {},
        // One button row: move/delete on the left, Cancel/Save on the
        // right. Compact text padding so all five fit narrow screens.
        dismissButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row {
                    IconButton(onClick = { onMove(-1) }, enabled = canMoveLeft) {
                        Icon(Icons.Filled.ChevronLeft, contentDescription = "Move left")
                    }
                    IconButton(onClick = { onMove(1) }, enabled = canMoveRight) {
                        Icon(Icons.Filled.ChevronRight, contentDescription = "Move right")
                    }
                    onDelete?.let {
                        IconButton(onClick = it) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete key")
                        }
                    }
                }
                Row {
                    TextButton(
                        onClick = onDismiss,
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) { Text("Cancel") }
                    TextButton(
                        onClick = {
                            onSave(
                                KeyDef(
                                    label = label.trim(),
                                    kind = kind,
                                    items = if (kind == KIND_MENU) {
                                        commitItemDraft()
                                    } else {
                                        emptyList()
                                    },
                                    steps = if (kind == KIND_SEND) {
                                        keyDraft.committed(steps)
                                    } else {
                                        emptyList()
                                    },
                                    width = width,
                                    color = color,
                                ),
                            )
                        },
                        enabled = complete,
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) { Text("Save") }
                }
            }
        },
    )
}

/**
 * Ordered step list editor, shared by Send keys and Menu items. Every step
 * shows what it sends ([describeStep]); the badge color-codes preset keys
 * vs literal text with the same meaning as the bar button tints. Steps send
 * in order with a short settle delay between them — put ESC first, text
 * next, Enter last for app macros like vim `:q!`.
 *
 * The entry draft lives in [draft] (hoisted): unconfirmed typing folds into
 * Save/Add-item instead of being dropped.
 */
@Composable
private fun StepsEditor(
    steps: List<KeyStep>,
    onChange: (List<KeyStep>) -> Unit,
    draft: StepDraft,
) {
    fun clearStepFields() = draft.clear()

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (steps.isEmpty() && draft.build() == null) {
            Text(
                "Add at least one step: type text or pick a preset key.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        steps.forEachIndexed { i, st ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                StepBadge(preset = st.preset)
                TextButton(
                    onClick = {
                        draft.text = st.text
                        draft.mod = st.mod
                        draft.editIdx = i
                        draft.editPreset = st.preset
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        describeStep(st),
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                IconButton(
                    onClick = {
                        if (i > 0) {
                            onChange(steps.toMutableList().also { l ->
                                val s = l.removeAt(i)
                                l.add(i - 1, s)
                            })
                            if (draft.editIdx == i) draft.editIdx = i - 1
                        }
                    },
                    enabled = i > 0,
                ) { Icon(Icons.Filled.ChevronLeft, contentDescription = "Move left") }
                IconButton(
                    onClick = {
                        if (i < steps.size - 1) {
                            onChange(steps.toMutableList().also { l ->
                                val s = l.removeAt(i)
                                l.add(i + 1, s)
                            })
                            if (draft.editIdx == i) draft.editIdx = i + 1
                        }
                    },
                    enabled = i < steps.size - 1,
                ) { Icon(Icons.Filled.ChevronRight, contentDescription = "Move right") }
                IconButton(
                    onClick = {
                        onChange(steps.filterIndexed { j, _ -> j != i })
                        val idx = draft.editIdx
                        if (idx == i) clearStepFields()
                        else if (idx != null && i < idx) draft.editIdx = idx - 1
                    },
                ) { Icon(Icons.Filled.Delete, contentDescription = "Delete step") }
            }
        }
        // Preview of what Save would send: committed steps include the
        // unconfirmed draft.
        val previewSteps = draft.committed(steps)
        if (previewSteps.isNotEmpty()) {
            Text(
                "Sends: " + stepsDisplay(previewSteps),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedTextField(
            value = draft.text,
            onValueChange = { draft.text = it },
            label = { Text("Step text") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KindChip("None", draft.mod == MOD_NONE) { draft.mod = MOD_NONE }
            KindChip("CTRL", draft.mod == MOD_CTRL) { draft.mod = MOD_CTRL }
            KindChip("ALT", draft.mod == MOD_ALT) { draft.mod = MOD_ALT }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(modifier = Modifier.weight(1f)) {
                OutlinedButton(onClick = { draft.picker = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Preset key")
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                }
                DropdownMenu(expanded = draft.picker, onDismissRequest = { draft.picker = false }) {
                    // A pick splices steps in: macros expand to several steps,
                    // keys to one. Replaces the edited step, else appends.
                    fun splice(add: List<KeyStep>) {
                        onChange(
                            draft.editIdx?.let { idx ->
                                steps.toMutableList().also { l ->
                                    l.removeAt(idx)
                                    l.addAll(idx.coerceAtMost(l.size), add)
                                }.take(MAX_STEPS)
                            } ?: (steps + add).take(MAX_STEPS),
                        )
                        clearStepFields()
                    }
                    Text(
                        "Macros",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                    for ((name, macroSteps) in MACRO_PRESETS) {
                        DropdownMenuItem(
                            text = { Text("$name  (${stepsDisplay(macroSteps)})") },
                            onClick = {
                                draft.picker = false
                                splice(macroSteps)
                            },
                        )
                    }
                    HorizontalDivider()
                    Text(
                        "Keys",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                    for ((name, _) in STEP_PRESETS) {
                        DropdownMenuItem(
                            text = { Text(name) },
                            onClick = {
                                draft.picker = false
                                splice(listOf(KeyStep(name, preset = true)))
                            },
                        )
                    }
                }
            }
            OutlinedButton(
                onClick = {
                    val built = KeyStep(draft.text, draft.mod, draft.editPreset)
                    if (built.text.isNotEmpty()) {
                        onChange(
                            draft.editIdx?.let { idx ->
                                steps.mapIndexed { j, s -> if (j == idx) built else s }
                            } ?: (steps + built).take(MAX_STEPS),
                        )
                        clearStepFields()
                    }
                },
                enabled = draft.text.isNotEmpty() && (draft.editIdx != null || steps.size < MAX_STEPS),
                modifier = Modifier.weight(1f),
            ) { Text(if (draft.editIdx == null) "Add step" else "Update step") }
        }
    }
}

/**
 * Step badge: preset keys read `key` on the tonal fill, literal text reads
 * `text` neutral — the same key-vs-text code as the bar button tints.
 */
@Composable
private fun StepBadge(preset: Boolean) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = if (preset) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
    ) {
        Text(
            if (preset) "Key" else "Text",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
        )
    }
}

/**
 * Compact inline color dots (same palette storage as profiles): blank means
 * uniform default. Fixed 7-per-row grid — Default + 13 presets fill two
 * rows exactly. Inline instead of a dialog — the key dialog is already tall.
 */
@Composable
private fun KeyColorRow(selected: String, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        (listOf("") + PROFILE_COLORS).chunked(7).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (hex in row) {
                    if (hex.isBlank()) {
                        KeyColorDot(
                            fill = null,
                            isSelected = selected.isBlank(),
                            contentDescription = "Default colour",
                            onSelect = { onSelect("") },
                        )
                    } else {
                        val argb = profileColorArgb(hex) ?: continue
                        KeyColorDot(
                            fill = Color(argb),
                            isSelected = selected == hex,
                            contentDescription = "Colour $hex",
                            onSelect = { onSelect(hex) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyColorDot(
    fill: Color?,
    isSelected: Boolean,
    contentDescription: String,
    onSelect: () -> Unit,
) {
    val ring = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(fill ?: MaterialTheme.colorScheme.surfaceVariant)
            .border(2.dp, ring, CircleShape)
            .clickable(onClick = onSelect),
    ) {
        if (fill == null) {
            Icon(
                Icons.Filled.FormatColorReset,
                contentDescription = contentDescription,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
