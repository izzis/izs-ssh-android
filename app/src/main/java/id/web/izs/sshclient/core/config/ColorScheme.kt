package id.web.izs.sshclient.core.config

/**
 * Terminal color scheme — parity with Tabby Desktop
 * (`tabby-core/src/api/theme.ts` TerminalColorScheme):
 * name, foreground, background, cursor, colors[16] (0-7 normal, 8-15
 * bright), optional selection / selectionForeground / cursorAccent.
 *
 * Scope (agreed): terminal content ONLY. The profile list and app chrome
 * keep the IzsDarkColors Material3 theme; per-profile identity tint stays
 * the separate `color` key ([ProfileColor]). `lightColorScheme` /
 * `colorSchemeMode` are ignored (dark-only app) — see ARCHITECTURE.md.
 *
 * Storage: full objects inline in YAML, exactly like desktop —
 * `terminal.colorScheme` (global), `terminal.customColorSchemes` (user
 * schemes), per-profile `terminalColorScheme` (null/absent = follow
 * global). Pure JVM for unit tests: hex parsing returns ARGB Ints, the
 * Compose UI wraps them in Colors.
 */

/** Desktop built-in default (`tabby-terminal/src/colorSchemes.ts`). */
val TABBY_DEFAULT_SCHEME = TerminalColorScheme(
    name = "Tabby Default",
    foreground = "#cacaca",
    background = "#171717",
    cursor = "#bbbbbb",
    colors = listOf(
        "#000000", "#ff615a", "#b1e969", "#ebd99c",
        "#5da9f6", "#e86aff", "#82fff7", "#dedacf",
        "#313131", "#f58c80", "#ddf88f", "#eee5b2",
        "#a5c7ff", "#ddaaff", "#b7fff9", "#ffffff",
    ),
)

/**
 * Snapshot of the hardcoded emulator palette before schemes existed
 * (FG/BG/STD defaults). The runtime fallback when `terminal.colorScheme`
 * is absent from YAML — so existing configs render pixel-identical to
 * before, zero visual change on update.
 */
val IZS_DEFAULT_SCHEME = TerminalColorScheme(
    name = "Izs Default",
    foreground = "#e8e8e8",
    background = "#1d1e23",
    cursor = "#e8e8e8",
    colors = listOf(
        "#000000", "#cd0000", "#00cd00", "#cdcd00",
        "#d0bcff", "#cd00cd", "#00cdcd", "#e5e5e5",
        "#7f7f7f", "#ff0000", "#00ff00", "#ffff00",
        "#5c5cff", "#ff00ff", "#00ffff", "#ffffff",
    ),
)

/**
 * Light companion of [IZS_DEFAULT_SCHEME]: same 16 ANSI colors, light
 * background + dark foreground/cursor. Used ONLY when no scheme is set
 * anywhere (see [isFallbackScheme]) and the app theme is light — so the
 * live terminal matches the light preview. Any explicit scheme
 * (profile/global/device) always wins untouched.
 */
val IZS_DEFAULT_LIGHT_SCHEME = TerminalColorScheme(
    name = "Izs Default Light",
    foreground = "#1d1e23",
    background = "#ffffff",
    cursor = "#1d1e23",
    colors = listOf(
        "#000000", "#cd0000", "#00cd00", "#cdcd00",
        "#d0bcff", "#cd00cd", "#00cdcd", "#e5e5e5",
        "#7f7f7f", "#ff0000", "#00ff00", "#ffff00",
        "#5c5cff", "#ff00ff", "#00ffff", "#ffffff",
    ),
)

data class TerminalColorScheme(
    val name: String,
    val foreground: String,
    val background: String,
    val cursor: String,
    val colors: List<String>,
    val selection: String? = null,
    val selectionForeground: String? = null,
    val cursorAccent: String? = null,
)

private val SCHEME_HEX = Regex("^#([0-9a-f]{3}|[0-9a-f]{6}|[0-9a-f]{8})$")

/**
 * Canonical form for scheme storage: lowercase hex (`#rgb` expanded).
 * Blank or non-hex -> null, so garbage never reaches the file.
 * `#aarrggbb` is kept (desktop default selection `#88888888` uses alpha).
 */
fun normalizeSchemeColor(raw: String?): String? {
    val hex = raw?.trim()?.lowercase() ?: return null
    if (!SCHEME_HEX.matches(hex)) return null
    val digits = hex.substring(1)
    return if (digits.length == 3) "#" + digits.map { "$it$it" }.joinToString("") else hex
}

/**
 * ARGB Int for a canonical color (`#rrggbb` opaque, `#aarrggbb` as-is).
 * Null for anything [normalizeSchemeColor] rejects.
 */
fun schemeColorArgb(raw: String?): Int? {
    val hex = normalizeSchemeColor(raw) ?: return null
    val digits = hex.substring(1)
    return when (digits.length) {
        6 -> (0xFF000000.toInt() or digits.toInt(16))
        8 -> digits.toUInt(16).toInt()
        else -> null
    }
}

/**
 * Inverse of [schemeColorArgb]: `#rrggbb` when opaque, `#aarrggbb` with
 * alpha otherwise. (A naive `"#%08x".format(argb).substring(0, 7)` keeps
 * the ALPHA digits and drops real color digits — red became yellow.)
 */
fun argbToHex(argb: Int): String {
    val a = (argb ushr 24) and 0xFF
    return if (a == 255) "#%06x".format(argb and 0x00FFFFFF)
    else "#%08x".format(argb)
}

/**
 * Parse a desktop-shape scheme map (SnakeYAML raw or community-converted).
 * Null when the shape is unusable: blank name, bad fg/bg/cursor, or
 * anything but exactly 16 valid palette entries. Optionals stay null when
 * absent/invalid (desktop falls back per-field at apply time).
 */
@Suppress("UNCHECKED_CAST") // dynamic YAML maps: keys are strings by construction
fun parseTerminalColorScheme(raw: Any?): TerminalColorScheme? {
    val m = raw as? Map<String, Any?> ?: return null
    val name = m["name"]?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val fg = normalizeSchemeColor(m["foreground"]?.toString()) ?: return null
    val bg = normalizeSchemeColor(m["background"]?.toString()) ?: return null
    val cursor = normalizeSchemeColor(m["cursor"]?.toString()) ?: return null
    val colors = (m["colors"] as? List<*>)?.map { normalizeSchemeColor(it?.toString()) }
    if (colors == null || colors.size != 16 || colors.any { it == null }) return null
    return TerminalColorScheme(
        name = name,
        foreground = fg,
        background = bg,
        cursor = cursor,
        colors = colors.filterNotNull(),
        selection = normalizeSchemeColor(m["selection"]?.toString()),
        selectionForeground = normalizeSchemeColor(m["selectionForeground"]?.toString()),
        cursorAccent = normalizeSchemeColor(m["cursorAccent"]?.toString()),
    )
}

/**
 * Desktop key order for YAML writes (name, foreground, background, cursor,
 * colors, then present optionals). Null optionals are omitted, never
 * written as `~`.
 */
fun TerminalColorScheme.toRawMap(): LinkedHashMap<String, Any?> = linkedMapOf<String, Any?>(
    "name" to name,
    "foreground" to foreground,
    "background" to background,
    "cursor" to cursor,
    "colors" to colors.toList(),
).also { m ->
    selection?.let { m["selection"] = it }
    selectionForeground?.let { m["selectionForeground"] = it }
    cursorAccent?.let { m["cursorAccent"] = it }
}

private fun relativeLuminance(argb: Int): Double {
    fun lin(c: Double): Double = if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
    val r = lin(((argb shr 16) and 0xFF) / 255.0)
    val g = lin(((argb shr 8) and 0xFF) / 255.0)
    val b = lin((argb and 0xFF) / 255.0)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b
}

/** WCAG contrast ratio of two ARGB colors (1.0 = identical). */
fun contrastRatio(a: Int, b: Int): Double {
    val la = relativeLuminance(a)
    val lb = relativeLuminance(b)
    return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05)
}

/**
 * Human-readable readability problems (empty = readable). Same gates the
 * curation script used, so the custom editor warns about exactly what the
 * built-in set was filtered for:
 * - body text contrast < 3.5 (the Atom/C64 class of failure);
 * - a non-black palette slot ~ background (bright variants must stay
 *   visible — `color0` plain black is exempt, it is invisible on most dark
 *   backgrounds by universal xterm convention).
 *
 * Warning only, never a block — your scheme, your call.
 */
fun schemeReadabilityIssues(s: TerminalColorScheme): List<String> {
    val out = mutableListOf<String>()
    val fg = schemeColorArgb(s.foreground) ?: return listOf("Invalid foreground color")
    val bg = schemeColorArgb(s.background) ?: return listOf("Invalid background color")
    val body = contrastRatio(fg, bg)
    if (body < 3.5) out += "Low text contrast (%.2f)".format(body)
    val palette = s.colors.mapNotNull { schemeColorArgb(it) }
    for (i in 1..15) {
        if (i >= palette.size) break
        if (contrastRatio(palette[i], bg) < 1.15) out += "color$i is unreadable on the background"
    }
    return out
}

/**
 * Desktop resolution order (xtermFrontend.configure + _getActiveColorScheme,
 * minus the light-mode branch — dark-only app): per-profile override first,
 * then the global `terminal.colorScheme`, then the pre-schemes hardcoded
 * look ([IZS_DEFAULT_SCHEME]) so absent keys change nothing.
 */
fun effectiveTerminalScheme(
    profile: TerminalColorScheme?,
    global: TerminalColorScheme?,
): TerminalColorScheme = profile ?: global ?: IZS_DEFAULT_SCHEME

/**
 * Source priority (Settings > Color scheme, unit-tested, tabSource parity).
 * SYNCED (default) = the synced YAML wins (profile override > global);
 * LOCAL = this device's own scheme wins and the synced global is ignored
 * (instant ConfigDisk write, no vault decrypt — the fast path).
 * Per-profile overrides still apply in both modes (already in RAM, free).
 */
enum class SchemeSource { SYNCED, LOCAL }

/** Lenient parse: anything but "local" is SYNCED. */
fun parseSchemeSource(raw: String?): SchemeSource =
    if (raw?.trim()?.lowercase() == "local") SchemeSource.LOCAL else SchemeSource.SYNCED

/**
 * Full resolution with source priority (unit-tested). [local] is the
 * device scheme (null = unset = Izs Default).
 */
fun resolveActiveScheme(
    profile: TerminalColorScheme?,
    global: TerminalColorScheme?,
    source: SchemeSource,
    local: TerminalColorScheme?,
): TerminalColorScheme = profile
    ?: if (source == SchemeSource.LOCAL) (local ?: IZS_DEFAULT_SCHEME)
    else (global ?: IZS_DEFAULT_SCHEME)

/**
 * True when [resolveActiveScheme] would return the Izs fallback — i.e.
 * nothing is set anywhere in the chain (no profile override, no global
 * in synced mode, no device scheme in local mode). Only then may the
 * caller substitute the theme-aware default; explicit schemes always win.
 */
fun isFallbackScheme(
    profile: TerminalColorScheme?,
    global: TerminalColorScheme?,
    source: SchemeSource,
    local: TerminalColorScheme?,
): Boolean = profile == null &&
    (if (source == SchemeSource.LOCAL) local == null else global == null)

/**
 * Device-pref serialization (ConfigDisk holds strings; YAML keeps desktop
 * objects). Compact JSON, same field names as [toRawMap].
 */
fun TerminalColorScheme.toJsonString(): String {
    // kotlinx.serialization (pure JVM): org.json is an Android stub that
    // throws "not mocked" in unit tests.
    val o = linkedMapOf<String, kotlinx.serialization.json.JsonElement>(
        "name" to kotlinx.serialization.json.JsonPrimitive(name),
        "foreground" to kotlinx.serialization.json.JsonPrimitive(foreground),
        "background" to kotlinx.serialization.json.JsonPrimitive(background),
        "cursor" to kotlinx.serialization.json.JsonPrimitive(cursor),
        "colors" to kotlinx.serialization.json.JsonArray(colors.map {
            kotlinx.serialization.json.JsonPrimitive(it)
        }),
    )
    selection?.let { o["selection"] = kotlinx.serialization.json.JsonPrimitive(it) }
    selectionForeground?.let {
        o["selectionForeground"] = kotlinx.serialization.json.JsonPrimitive(it)
    }
    cursorAccent?.let { o["cursorAccent"] = kotlinx.serialization.json.JsonPrimitive(it) }
    return kotlinx.serialization.json.JsonObject(o).toString()
}

/** Inverse of [toJsonString]. Null on blank/garbage (fail-closed to Izs). */
fun parseSchemeJson(raw: String?): TerminalColorScheme? {
    if (raw.isNullOrBlank()) return null
    return try {
        val el = kotlinx.serialization.json.Json.parseToJsonElement(raw)
        parseTerminalColorScheme(el.toSchemeMap())
    } catch (_: Exception) {
        null
    }
}

private fun kotlinx.serialization.json.JsonElement.toSchemeMap(): Any? = when (this) {
    is kotlinx.serialization.json.JsonObject ->
        LinkedHashMap<String, Any?>().also { m ->
            forEach { (k, v) -> m[k] = v.toSchemeMap() }
        }
    is kotlinx.serialization.json.JsonArray -> map { it.toSchemeMap() }
    is kotlinx.serialization.json.JsonPrimitive ->
        if (isString) content
        else content.toLongOrNull() ?: content.toDoubleOrNull()
            ?: when (content) {
                "true" -> true
                "false" -> false
                "null" -> null
                else -> content
            }
}

/**
 * Desktop saveScheme parity: upsert the edited scheme into customs BY NAME
 * (rename + Save = new entry; same name = replace). Pure for unit tests.
 */
fun upsertCustom(
    customs: List<TerminalColorScheme>,
    scheme: TerminalColorScheme,
): List<TerminalColorScheme> = customs.filter { it.name != scheme.name } + scheme

/** Desktop deleteScheme parity: drop the custom entry by name. */
fun deleteCustomByName(
    customs: List<TerminalColorScheme>,
    name: String,
): List<TerminalColorScheme> = customs.filter { it.name != name }

/** Shared JSONObject -> raw-map bridge (asset loader + device pref). */
internal fun org.json.JSONObject.toSchemeMap(): Map<String, Any?> {
    val out = LinkedHashMap<String, Any?>()
    val it = keys()
    while (it.hasNext()) {
        val k = it.next()
        out[k] = when (val v = get(k)) {
            is org.json.JSONObject -> v.toSchemeMap()
            is org.json.JSONArray -> List(v.length()) { i -> v.get(i).toString() }
            org.json.JSONObject.NULL -> null
            else -> v.toString()
        }
    }
    return out
}
