package id.web.izs.sshclient.core.term

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Editable on-screen extra-keys bar (the docked rows below the terminal grid).
 *
 * Device-local only: stored as JSON in [id.web.izs.sshclient.data.local.ConfigDisk],
 * never in profile YAML and never synced. A key is one of four actions:
 * - SEND: an ordered list of steps, sent one packet at a time with a short
 *   settle delay between them (macros like vim `:q!` need each part to
 *   register in order: ESC, then text, then Enter).
 * - STICKY_CTRL / STICKY_ALT: toggle the sticky modifier for typed text.
 * - MENU: open a popup of sub-items, each sending its own steps on tap.
 *
 * Each step is either a named preset key (ESC, arrows, F-keys…) or free
 * text with an optional CTRL/ALT modifier — the editor color-codes the two
 * (key vs text) and previews the bytes every step sends.
 *
 * The model is intentionally flat (kind/mod/width as validated strings, no
 * polymorphic serialization): foreign payloads degrade gracefully via
 * [normalizeKeyLayout] instead of failing the parse.
 */
@Serializable
data class KeyStep(
    val text: String = "",
    val mod: String = MOD_NONE,
    val preset: Boolean = false,
)

@Serializable
data class MenuItemDef(
    val label: String = "",
    val steps: List<KeyStep> = emptyList(),
)

@Serializable
data class KeyDef(
    val label: String = "",
    val kind: String = KIND_SEND,
    val items: List<MenuItemDef> = emptyList(),
    val steps: List<KeyStep> = emptyList(),
    val width: String = WIDTH_NORMAL,
    /** Explicit button color (`#rrggbb`, blank = tint by kind). */
    val color: String = "",
)

@Serializable
data class KeyLayout(
    val v: Int = LAYOUT_VERSION,
    val rows: List<List<KeyDef>> = emptyList(),
) {
    companion object {
        const val LAYOUT_VERSION = 1
    }
}

const val KIND_SEND = "send"
const val KIND_STICKY_CTRL = "sticky_ctrl"
const val KIND_STICKY_ALT = "sticky_alt"
const val KIND_MENU = "menu"

/** Per-step modifier: None, or hold CTRL/ALT for the step text. */
const val MOD_NONE = ""
const val MOD_CTRL = "ctrl"
const val MOD_ALT = "alt"

/** Key width tiers: half slot, 1x slot, 1.5x, 2x. */
const val WIDTH_HALF = "half"
const val WIDTH_NORMAL = "normal"
const val WIDTH_WIDE = "wide"
const val WIDTH_DOUBLE = "double"

/** Factory default for the macro step delay (Settings > Terminal). */
const val DEFAULT_MACRO_STEP_DELAY_MS = 120L
const val MAX_MACRO_STEP_DELAY_MS = 2000L

/** Editor caps: the bar must stay thumb-usable on a small phone. */
const val MAX_ROWS = 5
const val MAX_KEYS_PER_ROW = 8
const val MAX_MENU_ITEMS = 12
const val MAX_STEPS = 8
const val MAX_LABEL = 8
const val MAX_STEP_TEXT = 64
const val MAX_MENU_LABEL = 16

private val layoutJson = Json { ignoreUnknownKeys = true }

/** Row weight for the bar: half slot, normal 1 slot, wide 1.5, double 2. */
fun KeyDef.weight(): Float = when (width) {
    WIDTH_HALF -> 0.5f
    WIDTH_WIDE -> 1.5f
    WIDTH_DOUBLE -> 2f
    else -> 1f
}

/** Label budget scales with button width (8 chars per slot). */
fun maxLabelForWidth(width: String): Int = (MAX_LABEL * when (width) {
    WIDTH_HALF -> 0.5f
    WIDTH_WIDE -> 1.5f
    WIDTH_DOUBLE -> 2f
    else -> 1f
}).toInt()

fun isKnownKind(kind: String): Boolean =
    kind == KIND_SEND || kind == KIND_STICKY_CTRL ||
        kind == KIND_STICKY_ALT || kind == KIND_MENU

fun isKnownWidth(width: String): Boolean =
    width == WIDTH_HALF || width == WIDTH_NORMAL ||
        width == WIDTH_WIDE || width == WIDTH_DOUBLE

/**
 * Hold-to-repeat presets (Termux PRIMARY_REPETITIVE_KEYS parity):
 * single navigation/editing keys that auto-repeat while held. The rule is
 * structural — factory or user-added keys qualify alike — so custom macros
 * can never sneak in: exactly one step, a named preset, and a name in this
 * set. A text step labeled "UP" or a multi-step macro is not repetitive.
 */
val REPETITIVE_PRESETS: Set<String> = setOf(
    "UP", "DOWN", "LEFT", "RIGHT", "BKSP", "DEL", "PGUP", "PGDN",
)

fun isRepetitiveKey(key: KeyDef): Boolean =
    key.kind == KIND_SEND && key.steps.size == 1 &&
        key.steps[0].preset && key.steps[0].text in REPETITIVE_PRESETS

/** Repeat cadence (Termux DEFAULT_LONG_PRESS_REPEAT_DELAY parity): one send
 *  per interval after the platform long-press timeout fires. */
const val REPEAT_TICK_MS = 80L

private fun normalizeStep(step: KeyStep): KeyStep? {
    val text = step.text.take(MAX_STEP_TEXT)
    if (text.isEmpty()) return null
    val mod = if (step.mod == MOD_CTRL || step.mod == MOD_ALT) step.mod else MOD_NONE
    return KeyStep(text, mod, step.preset)
}

private fun normalizeSteps(steps: List<KeyStep>): List<KeyStep>? =
    steps.mapNotNull(::normalizeStep).take(MAX_STEPS).ifEmpty { null }

private fun normalizeMenuItem(item: MenuItemDef): MenuItemDef? {
    val label = item.label.trim().take(MAX_MENU_LABEL)
    if (label.isEmpty()) return null
    val steps = normalizeSteps(item.steps) ?: return null
    return MenuItemDef(label, steps)
}

private fun normalizeKey(key: KeyDef): KeyDef? {
    val kind = if (isKnownKind(key.kind)) key.kind else KIND_SEND
    val width = if (isKnownWidth(key.width)) key.width else WIDTH_NORMAL
    val label = key.label.trim().take(maxLabelForWidth(width))
    if (label.isEmpty()) return null
    val color = id.web.izs.sshclient.core.config.normalizeProfileColor(key.color) ?: ""
    return when (kind) {
        KIND_STICKY_CTRL, KIND_STICKY_ALT ->
            KeyDef(label, kind, width = width, color = color)
        KIND_MENU -> {
            val items = key.items.mapNotNull(::normalizeMenuItem).take(MAX_MENU_ITEMS)
            if (items.isEmpty()) return null
            KeyDef(label, kind, items, width = width, color = color)
        }
        else -> {
            val steps = normalizeSteps(key.steps) ?: return null
            KeyDef(label, kind, steps = steps, width = width, color = color)
        }
    }
}

/**
 * Clamp counts, drop blank/unknown keys, fall back to [DEFAULT_LAYOUT] when
 * nothing usable remains. Never throws.
 */
fun normalizeKeyLayout(layout: KeyLayout): KeyLayout {
    val rows = layout.rows.take(MAX_ROWS).map { row ->
        row.mapNotNull(::normalizeKey).take(MAX_KEYS_PER_ROW)
    }.filter { it.isNotEmpty() }
    return if (rows.isEmpty()) DEFAULT_LAYOUT else KeyLayout(KeyLayout.LAYOUT_VERSION, rows)
}

/**
 * Parse stored JSON into a usable layout. Null, blank, or corrupt input
 * falls back to [DEFAULT_LAYOUT] — a bad pref must never break the terminal.
 */
fun loadKeyLayout(raw: String?): KeyLayout {
    if (raw.isNullOrBlank()) return DEFAULT_LAYOUT
    return try {
        normalizeKeyLayout(layoutJson.decodeFromString(KeyLayout.serializer(), raw))
    } catch (_: Exception) {
        DEFAULT_LAYOUT
    }
}

/**
 * Strict parse for pasted import payloads: null on any failure so the
 * editor can report the error instead of silently loading the default.
 */
fun parseKeyLayout(raw: String): KeyLayout? {
    if (raw.isBlank()) return null
    return try {
        normalizeKeyLayout(layoutJson.decodeFromString(KeyLayout.serializer(), raw.trim()))
    } catch (_: Exception) {
        null
    }
}

/** Normalize then encode; the stored form is always canonical. */
fun saveKeyLayout(layout: KeyLayout): String =
    layoutJson.encodeToString(KeyLayout.serializer(), normalizeKeyLayout(layout))

// ASCII-safe escape constants (never raw control bytes in source).
private const val E = "\u001B"
private const val T = "\t"
private const val DEL_CH = "\u007F"

/** Named preset keys offered by the editor, in display order. */
val STEP_PRESETS: List<Pair<String, String>> = listOf(
    "ESC" to E,
    "TAB" to T,
    "ENTER" to "\r",
    "BKSP" to DEL_CH,
    "UP" to "$E[A",
    "DOWN" to "$E[B",
    "LEFT" to "$E[D",
    "RIGHT" to "$E[C",
    "HOME" to "$E[H",
    "END" to "$E[F",
    "PGUP" to "$E[5~",
    "PGDN" to "$E[6~",
    "INS" to "$E[2~",
    "DEL" to "$E[3~",
    "F1" to "${E}OP",
    "F2" to "${E}OQ",
    "F3" to "${E}OR",
    "F4" to "${E}OS",
    "F5" to "$E[15~",
    "F6" to "$E[17~",
    "F7" to "$E[18~",
    "F8" to "$E[19~",
    "F9" to "$E[20~",
    "F10" to "$E[21~",
    "F11" to "$E[23~",
    "F12" to "$E[24~",
)

/**
 * Macro templates: one pick expands into ordered steps (each step sends as
 * its own packet with a settle delay). Single keys live in [STEP_PRESETS];
 * sequences like vim `:q!` live here — never as one blob.
 */
val MACRO_PRESETS: List<Pair<String, List<KeyStep>>> = listOf(
    "VIM :q!" to listOf(
        KeyStep("ESC", preset = true),
        KeyStep(":q!"),
        KeyStep("ENTER", preset = true),
    ),
)

private val STEP_PRESET_MAP: Map<String, String> by lazy { STEP_PRESETS.toMap() }

/**
 * Bytes a step sends: preset name, CTRL single letter (C0 control),
 * ALT text (ESC-prefixed), or literal text. Pure function.
 */
fun stepBytes(step: KeyStep): String {
    if (step.preset) return STEP_PRESET_MAP[step.text] ?: step.text
    return when (step.mod) {
        MOD_CTRL -> {
            val u = step.text.singleOrNull()?.uppercaseChar()
            if (u != null && u in 'A'..'Z') ((u - 'A' + 1).toChar()).toString()
            else step.text
        }
        MOD_ALT -> {
            val t = step.text.singleOrNull()?.lowercase() ?: step.text
            E + t
        }
        else -> step.text
    }
}

/**
 * One-step preview for the editor: the preset name, `Ctrl+X` / `Alt+x`,
 * or the literal text. Combined with [stepsDisplay].
 */
fun describeStep(step: KeyStep): String = when {
    step.preset -> step.text
    step.mod == MOD_CTRL -> "Ctrl+" + step.text
    step.mod == MOD_ALT -> "Alt+" + step.text
    else -> step.text
}

/** Whole-payload preview: each step's [describeStep], joined by spaces. */
fun stepsDisplay(steps: List<KeyStep>): String =
    steps.joinToString(" ") { describeStep(it) }.ifEmpty { "(empty)" }

/** Step builders: [pk] a preset key, [tx] literal text. */
private fun pk(name: String) = KeyStep(name, preset = true)
private fun tx(text: String) = KeyStep(text)
private fun sendKey(label: String, vararg steps: KeyStep) =
    KeyDef(label, KIND_SEND, steps = steps.toList())

/** Factory default: Termux-style 2x7 grid, all keys equal width. */
val DEFAULT_LAYOUT = KeyLayout(
    KeyLayout.LAYOUT_VERSION,
    listOf(
        listOf(
            sendKey("ESC", pk("ESC")),
            sendKey("/", tx("/")),
            sendKey("-", tx("-")),
            sendKey("HOME", pk("HOME")),
            sendKey("↑", pk("UP")),
            sendKey("END", pk("END")),
            sendKey("PGUP", pk("PGUP")),
        ),
        listOf(
            sendKey("TAB", pk("TAB")),
            KeyDef("CTRL", KIND_STICKY_CTRL),
            KeyDef("ALT", KIND_STICKY_ALT),
            sendKey("←", pk("LEFT")),
            sendKey("↓", pk("DOWN")),
            sendKey("→", pk("RIGHT")),
            sendKey("PGDN", pk("PGDN")),
        ),
    ),
)

/** Preset: arrows + navigation cluster. */
val PRESET_ARROWS = KeyLayout(
    KeyLayout.LAYOUT_VERSION,
    listOf(
        listOf(
            sendKey("ESC", pk("ESC")),
            sendKey("HOME", pk("HOME")),
            sendKey("↑", pk("UP")),
            sendKey("END", pk("END")),
            sendKey("PGUP", pk("PGUP")),
        ),
        listOf(
            sendKey("TAB", pk("TAB")),
            sendKey("←", pk("LEFT")),
            sendKey("↓", pk("DOWN")),
            sendKey("→", pk("RIGHT")),
            sendKey("PGDN", pk("PGDN")),
        ),
    ),
)

/** Preset: F1-F12 (F1-F4 as SS3, F5-F12 as CSI number tilde). */
val PRESET_FN_KEYS = KeyLayout(
    KeyLayout.LAYOUT_VERSION,
    listOf(
        listOf(
            sendKey("F1", pk("F1")),
            sendKey("F2", pk("F2")),
            sendKey("F3", pk("F3")),
            sendKey("F4", pk("F4")),
            sendKey("F5", pk("F5")),
            sendKey("F6", pk("F6")),
        ),
        listOf(
            sendKey("F7", pk("F7")),
            sendKey("F8", pk("F8")),
            sendKey("F9", pk("F9")),
            sendKey("F10", pk("F10")),
            sendKey("F11", pk("F11")),
            sendKey("F12", pk("F12")),
        ),
    ),
)

/** Presets offered by the editor, in display order. */
val KEYBOARD_PRESETS: List<Pair<String, KeyLayout>> = listOf(
    "Default" to DEFAULT_LAYOUT,
    "Arrows" to PRESET_ARROWS,
    "F1-F12" to PRESET_FN_KEYS,
)
