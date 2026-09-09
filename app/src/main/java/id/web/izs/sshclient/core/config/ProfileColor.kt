package id.web.izs.sshclient.core.config

/**
 * Profile identity color (`color` key, profile level — sibling of `name`,
 * NOT the terminal color scheme, which lives beside it as
 * `terminalColorScheme` and is edited in the Colours tab +
 * Settings > Color scheme).
 *
 * Desktop uses the identity color for the profile-tree icon tint and the
 * tab colorbar (`tabHeader.component.pug`); mobile mirrors it with a
 * stripe in the profile list. Pure JVM for unit tests: parsing returns an
 * ARGB Int, UI wraps it in a Compose Color.
 */

/** Preset swatches offered by the General tab picker (hex `#rrggbb`). */
val PROFILE_COLORS = listOf(
    "#f44336",
    "#e91e63",
    "#9c27b0",
    "#673ab7",
    "#3f51b5",
    "#2196f3",
    "#00bcd4",
    "#009688",
    "#4caf50",
    "#cddc39",
    "#ffeb3b",
    "#ff9800",
    "#795548",
)

private val HEX_COLOR = Regex("^#([0-9a-f]{3}|[0-9a-f]{6}|[0-9a-f]{8})$")

/**
 * Canonical form for YAML storage: lowercase `#rrggbb` (`#rgb` expanded,
 * `#aarrggbb` kept). Blank or non-hex (CSS names like `red`, typos) ->
 * null, so invalid values never reach the file.
 */
fun normalizeProfileColor(raw: String?): String? {
    val hex = raw?.trim()?.lowercase() ?: return null
    if (!HEX_COLOR.matches(hex)) return null
    val digits = hex.substring(1)
    return when (digits.length) {
        3 -> "#" + digits.map { "$it$it" }.joinToString("")
        else -> hex
    }
}

/**
 * ARGB Int for a canonical color (`#rrggbb` opaque, `#aarrggbb` as-is).
 * Returns null for anything [normalizeProfileColor] rejects.
 */
fun profileColorArgb(raw: String?): Int? {
    val hex = normalizeProfileColor(raw) ?: return null
    val digits = hex.substring(1)
    return when (digits.length) {
        6 -> (0xFF000000.toInt() or digits.toInt(16))
        8 -> digits.toUInt(16).toInt()
        else -> null
    }
}
