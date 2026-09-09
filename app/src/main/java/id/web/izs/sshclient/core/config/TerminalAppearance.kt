package id.web.izs.sshclient.core.config

/**
 * Terminal appearance keys with desktop parity (`terminal.font`,
 * `terminal.cursor`, `terminal.cursorBlink`).
 *
 * Desktop (`tabby-terminal/src/config.ts`) defaults: font per-OS
 * (Menlo/Consolas/Liberation Mono), cursor `block`, cursorBlink `true`.
 * Like the desktop, the phone bundles one fallback font (Source Code Pro —
 * the same file as `tabby-terminal/src/fonts/`, registered there as the
 * `monospace-fallback` last resort) and otherwise renders the system
 * monospace. An unknown desktop font name renders as system monospace
 * here; the YAML value itself is never rewritten.
 */
enum class TerminalFont {
    SYSTEM,
    SOURCE_CODE_PRO,
}

/** Display name written to `terminal.font` for [TerminalFont.SOURCE_CODE_PRO]. */
const val SOURCE_CODE_PRO_YAML_NAME = "Source Code Pro"

/**
 * Resolves a `terminal.font` YAML value to a phone font. Null/absent (the
 * SYSTEM choice) means "no key" — the desktop default applies there.
 * Matching is case-insensitive; anything unrecognized is SYSTEM.
 */
fun resolveTerminalFont(yamlName: String?): TerminalFont =
    if (yamlName?.trim().equals(SOURCE_CODE_PRO_YAML_NAME, ignoreCase = true)) {
        TerminalFont.SOURCE_CODE_PRO
    } else {
        TerminalFont.SYSTEM
    }

/** YAML name for [font]: null for SYSTEM (absent key = desktop default). */
fun terminalFontYamlName(font: TerminalFont): String? =
    if (font == TerminalFont.SOURCE_CODE_PRO) SOURCE_CODE_PRO_YAML_NAME else null

/** Terminal cursor shape (`terminal.cursor`, desktop `block|beam|underline`). */
enum class TerminalCursor {
    BLOCK,
    BEAM,
    UNDERLINE,
}

/**
 * Parses a `terminal.cursor` YAML value. Unknown garbage falls back to
 * BLOCK (desktop renders block for anything it cannot map either).
 */
fun parseTerminalCursor(raw: String?): TerminalCursor = when (raw?.trim()?.lowercase()) {
    "beam" -> TerminalCursor.BEAM
    "underline" -> TerminalCursor.UNDERLINE
    else -> TerminalCursor.BLOCK
}

/** Lowercase YAML name for [cursor] (desktop spelling). */
fun terminalCursorYamlName(cursor: TerminalCursor): String = when (cursor) {
    TerminalCursor.BLOCK -> "block"
    TerminalCursor.BEAM -> "beam"
    TerminalCursor.UNDERLINE -> "underline"
}
