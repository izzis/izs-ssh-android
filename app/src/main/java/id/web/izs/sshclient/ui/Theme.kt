package id.web.izs.sshclient.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import id.web.izs.sshclient.data.local.ConfigDisk

/**
 * Default app-chrome palette (see [AppPalettes]): dark, but not pitch
 * black. Baseline Material dark uses near-black surfaces (#121212 family);
 * these lifted grays keep cards, dialogs and inputs visibly separated
 * without going light mode. Accent colors stay on baseline.
 */
val IzsDarkColors = darkColorScheme(
    background = Color(0xFF1D1E23),
    onBackground = Color(0xFFE3E4E9),
    surface = Color(0xFF24262C),
    onSurface = Color(0xFFE3E4E9),
    surfaceVariant = Color(0xFF33353D),
    onSurfaceVariant = Color(0xFFC3C6CF),
    surfaceContainerLowest = Color(0xFF18191D),
    surfaceContainerLow = Color(0xFF232529),
    surfaceContainer = Color(0xFF282A31),
    surfaceContainerHigh = Color(0xFF2E3038),
    surfaceContainerHighest = Color(0xFF34363F),
)

/**
 * Light mirror of [IzsDarkColors] (Settings > Appearance > App theme):
 * same slot structure, baseline light ramps. Screens use
 * `MaterialTheme.colorScheme` (not hardcoded colors), so they follow
 * automatically. Deliberately untouched: the terminal stage (follows the
 * active color scheme, never the app theme) and the black docked
 * extra-keys bar (Termux-like contrast).
 */
val IzsLightColors = lightColorScheme(
    background = Color(0xFFF3F1EA),
    onBackground = Color(0xFF1D1E23),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1D1E23),
    surfaceVariant = Color(0xFFE4E1D8),
    onSurfaceVariant = Color(0xFF4A4D55),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF6F4EE),
    surfaceContainer = Color(0xFFEFEDE6),
    surfaceContainerHigh = Color(0xFFE8E5DC),
    surfaceContainerHighest = Color(0xFFE1DED4),
)

/**
 * App-chrome color palette (Settings > Appearance > App colors): accent
 * slots over the shared Izs surfaces. Deliberately SEPARATE from the
 * terminal color schemes (`terminal.colorScheme` YAML) — those paint
 * terminal content only and never touch app chrome, and these never
 * touch the terminal. Device-only, never synced.
 */
data class AppPalette(
    /** Stable pref key. */
    val id: String,
    /** Display name. */
    val name: String,
    val dark: ColorScheme,
    val light: ColorScheme,
)

private fun paletteDark(
    primary: Color,
    onPrimary: Color,
    primaryContainer: Color,
    onPrimaryContainer: Color,
    secondary: Color,
    secondaryContainer: Color,
    tertiary: Color,
    tertiaryContainer: Color,
): ColorScheme = darkColorScheme(
    background = Color(0xFF1D1E23),
    onBackground = Color(0xFFE3E4E9),
    surface = Color(0xFF24262C),
    onSurface = Color(0xFFE3E4E9),
    surfaceVariant = Color(0xFF33353D),
    onSurfaceVariant = Color(0xFFC3C6CF),
    surfaceContainerLowest = Color(0xFF18191D),
    surfaceContainerLow = Color(0xFF232529),
    surfaceContainer = Color(0xFF282A31),
    surfaceContainerHigh = Color(0xFF2E3038),
    surfaceContainerHighest = Color(0xFF34363F),
    primary = primary,
    onPrimary = onPrimary,
    primaryContainer = primaryContainer,
    onPrimaryContainer = onPrimaryContainer,
    secondary = secondary,
    secondaryContainer = secondaryContainer,
    tertiary = tertiary,
    tertiaryContainer = tertiaryContainer,
)

private fun paletteLight(
    primary: Color,
    onPrimary: Color,
    primaryContainer: Color,
    onPrimaryContainer: Color,
    secondary: Color,
    secondaryContainer: Color,
    tertiary: Color,
    tertiaryContainer: Color,
): ColorScheme = lightColorScheme(
    background = Color(0xFFF3F1EA),
    onBackground = Color(0xFF1D1E23),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1D1E23),
    surfaceVariant = Color(0xFFE4E1D8),
    onSurfaceVariant = Color(0xFF4A4D55),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF6F4EE),
    surfaceContainer = Color(0xFFEFEDE6),
    surfaceContainerHigh = Color(0xFFE8E5DC),
    surfaceContainerHighest = Color(0xFFE1DED4),
    primary = primary,
    onPrimary = onPrimary,
    primaryContainer = primaryContainer,
    onPrimaryContainer = onPrimaryContainer,
    secondary = secondary,
    secondaryContainer = secondaryContainer,
    tertiary = tertiary,
    tertiaryContainer = tertiaryContainer,
)

/** Curated app palettes (MD3 tonal ramps; surfaces shared with Izs). */
val AppPalettes = listOf(
    AppPalette(
        id = "izs",
        name = "Izs (default)",
        dark = IzsDarkColors,
        light = IzsLightColors,
    ),
    AppPalette(
        id = "ocean",
        name = "Ocean",
        dark = paletteDark(
            primary = Color(0xFFA8C7FA),
            onPrimary = Color(0xFF0A305F),
            primaryContainer = Color(0xFF004A77),
            onPrimaryContainer = Color(0xFFD3E3FD),
            secondary = Color(0xFFBDC7DC),
            secondaryContainer = Color(0xFF394456),
            tertiary = Color(0xFFD5B8E5),
            tertiaryContainer = Color(0xFF4B3A5E),
        ),
        light = paletteLight(
            primary = Color(0xFF0B57D0),
            onPrimary = Color(0xFFFFFFFF),
            primaryContainer = Color(0xFFD3E3FD),
            onPrimaryContainer = Color(0xFF041E49),
            secondary = Color(0xFF575F71),
            secondaryContainer = Color(0xFFDBE2F0),
            tertiary = Color(0xFF6B4E7A),
            tertiaryContainer = Color(0xFFF0DCF8),
        ),
    ),
    AppPalette(
        id = "forest",
        name = "Forest",
        dark = paletteDark(
            primary = Color(0xFF8BD5A6),
            onPrimary = Color(0xFF00391D),
            primaryContainer = Color(0xFF00522B),
            onPrimaryContainer = Color(0xFFC2F0C9),
            secondary = Color(0xFFB7CCB8),
            secondaryContainer = Color(0xFF394B3E),
            tertiary = Color(0xFFBFD68F),
            tertiaryContainer = Color(0xFF3B4A1F),
        ),
        light = paletteLight(
            primary = Color(0xFF146C2E),
            onPrimary = Color(0xFFFFFFFF),
            primaryContainer = Color(0xFFC2F0C9),
            onPrimaryContainer = Color(0xFF072711),
            secondary = Color(0xFF4F6353),
            secondaryContainer = Color(0xFFD2E8D4),
            tertiary = Color(0xFF556500),
            tertiaryContainer = Color(0xFFD9E7A5),
        ),
    ),
    AppPalette(
        id = "sunset",
        name = "Sunset",
        dark = paletteDark(
            primary = Color(0xFFFFB787),
            onPrimary = Color(0xFF502400),
            primaryContainer = Color(0xFF743500),
            onPrimaryContainer = Color(0xFFFFDBCA),
            secondary = Color(0xFFE3BFA9),
            secondaryContainer = Color(0xFF4E3A2E),
            tertiary = Color(0xFFE8C07A),
            tertiaryContainer = Color(0xFF4A3613),
        ),
        light = paletteLight(
            primary = Color(0xFF96490C),
            onPrimary = Color(0xFFFFFFFF),
            primaryContainer = Color(0xFFFFDBCA),
            onPrimaryContainer = Color(0xFF341100),
            secondary = Color(0xFF6F5B4D),
            secondaryContainer = Color(0xFFFBDECB),
            tertiary = Color(0xFF7A5900),
            tertiaryContainer = Color(0xFFFFE0A6),
        ),
    ),
    AppPalette(
        id = "grape",
        name = "Grape",
        dark = paletteDark(
            primary = Color(0xFFD0BCFF),
            onPrimary = Color(0xFF381E72),
            primaryContainer = Color(0xFF4F378B),
            onPrimaryContainer = Color(0xFFEADDFF),
            secondary = Color(0xFFCCC2DC),
            secondaryContainer = Color(0xFF4A4458),
            tertiary = Color(0xFFEFB8C8),
            tertiaryContainer = Color(0xFF633B48),
        ),
        light = paletteLight(
            primary = Color(0xFF6750A4),
            onPrimary = Color(0xFFFFFFFF),
            primaryContainer = Color(0xFFEADDFF),
            onPrimaryContainer = Color(0xFF21005D),
            secondary = Color(0xFF625B71),
            secondaryContainer = Color(0xFFE8DEF8),
            tertiary = Color(0xFF7D5260),
            tertiaryContainer = Color(0xFFFFD8E4),
        ),
    ),
)

/** Resolves a palette id; unknown garbage falls back to Izs. */
fun resolveAppPalette(id: String?): AppPalette =
    AppPalettes.find { it.id == id } ?: AppPalettes.first()

/**
 * Match the app appearance to the color scheme (Tabby desktop "Follow the
 * color scheme" parity: `themes.service.ts applyThemeVariables`).
 *
 * Agreed rules:
 * - Source = the active non-profile color scheme (synced YAML vs local
 *   device, same resolution as the terminal — no per-profile override).
 * - Dark/light is automatic from luminance (`bg < fg` = dark); the App
 *   theme System/Dark/Light setting is ignored while the toggle is ON.
 * - Full-style: background/surface + container ramp + primary/error come
 *   from the scheme, so the top bar (`surface`), dialogs/menus
 *   (`surfaceContainerHigh`), buttons (`primary`) and forms
 *   (`surfaceContainerHighest` + focused `primary`) follow automatically —
 *   no per-screen hardcoded colors.
 */
fun isSchemeDark(scheme: id.web.izs.sshclient.core.config.TerminalColorScheme): Boolean {
    val bg = id.web.izs.sshclient.core.config.schemeColorArgb(scheme.background)?.let { Color(it) }
        ?: return true
    val fg = id.web.izs.sshclient.core.config.schemeColorArgb(scheme.foreground)?.let { Color(it) }
        ?: return true
    return bg.luminance() < fg.luminance()
}

private fun mix(a: Color, b: Color, ratio: Float): Color {
    val r = ratio.coerceIn(0f, 1f)
    return Color(
        red = a.red + (b.red - a.red) * r,
        green = a.green + (b.green - a.green) * r,
        blue = a.blue + (b.blue - a.blue) * r,
        alpha = a.alpha + (b.alpha - a.alpha) * r,
    )
}

private fun onColorFor(container: Color): Color =
    if (container.luminance() > 0.5f) Color(0xFF1D1E23) else Color.White

fun schemeToAppColorScheme(
    scheme: id.web.izs.sshclient.core.config.TerminalColorScheme,
): ColorScheme {
    val bg = id.web.izs.sshclient.core.config.schemeColorArgb(scheme.background)?.let { Color(it) }
        ?: return IzsDarkColors
    val fg = id.web.izs.sshclient.core.config.schemeColorArgb(scheme.foreground)?.let { Color(it) }
        ?: return IzsDarkColors
    val dark = bg.luminance() < fg.luminance()
    fun slot(i: Int, fallback: Color): Color =
        scheme.colors.getOrNull(i)?.let { id.web.izs.sshclient.core.config.schemeColorArgb(it) }
            ?.let { Color(it) } ?: fallback

    // accentIndex=4 ala desktop; danger=colors[1].
    val accent = slot(4, if (dark) Color(0xFFA8C7FA) else Color(0xFF0B57D0))
    val error = slot(1, Color(0xFFB00020))
    val tertiary = slot(2, if (dark) Color(0xFF8BD5A6) else Color(0xFF146C2E))
    // Desktop-style secondary: less(bg) — stays neutral, off the bg.
    val secondary = mix(bg, fg, if (dark) 0.55f else 0.45f)

    val primaryContainer = mix(accent, bg, if (dark) 0.55f else 0.7f)
    val secondaryContainer = mix(secondary, bg, if (dark) 0.5f else 0.7f)
    val tertiaryContainer = mix(tertiary, bg, if (dark) 0.55f else 0.7f)
    val errorContainer = mix(error, bg, if (dark) 0.55f else 0.75f)

    val surfaceVariant = mix(bg, fg, 0.10f)
    val outline = mix(bg, fg, 0.30f)
    val outlineVariant = mix(bg, fg, 0.14f)
    // Elevation ramp follows M3 (dark: brighter when higher, light: darker).
    val lowest = if (dark) mix(bg, Color.Black, 0.25f) else bg
    val low = if (dark) mix(bg, Color.Black, 0.10f) else mix(bg, Color.Black, 0.03f)
    val container = if (dark) mix(bg, fg, 0.06f) else mix(bg, Color.Black, 0.05f)
    val high = if (dark) mix(bg, fg, 0.12f) else mix(bg, Color.Black, 0.08f)
    val highest = if (dark) mix(bg, fg, 0.18f) else mix(bg, Color.Black, 0.11f)

    return if (dark) {
        darkColorScheme(
            background = bg,
            onBackground = fg,
            surface = bg,
            onSurface = fg,
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = mix(fg, bg, 0.30f),
            surfaceContainerLowest = lowest,
            surfaceContainerLow = low,
            surfaceContainer = container,
            surfaceContainerHigh = high,
            surfaceContainerHighest = highest,
            surfaceTint = accent,
            primary = accent,
            onPrimary = onColorFor(accent),
            primaryContainer = primaryContainer,
            onPrimaryContainer = onColorFor(primaryContainer),
            secondary = secondary,
            onSecondary = onColorFor(secondary),
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = onColorFor(secondaryContainer),
            tertiary = tertiary,
            onTertiary = onColorFor(tertiary),
            tertiaryContainer = tertiaryContainer,
            onTertiaryContainer = onColorFor(tertiaryContainer),
            error = error,
            onError = onColorFor(error),
            errorContainer = errorContainer,
            onErrorContainer = onColorFor(errorContainer),
            outline = outline,
            outlineVariant = outlineVariant,
            inverseSurface = fg,
            inverseOnSurface = bg,
            inversePrimary = accent,
            scrim = Color.Black,
        )
    } else {
        lightColorScheme(
            background = bg,
            onBackground = fg,
            surface = bg,
            onSurface = fg,
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = mix(fg, bg, 0.30f),
            surfaceContainerLowest = lowest,
            surfaceContainerLow = low,
            surfaceContainer = container,
            surfaceContainerHigh = high,
            surfaceContainerHighest = highest,
            surfaceTint = accent,
            primary = accent,
            onPrimary = onColorFor(accent),
            primaryContainer = primaryContainer,
            onPrimaryContainer = onColorFor(primaryContainer),
            secondary = secondary,
            onSecondary = onColorFor(secondary),
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = onColorFor(secondaryContainer),
            tertiary = tertiary,
            onTertiary = onColorFor(tertiary),
            tertiaryContainer = tertiaryContainer,
            onTertiaryContainer = onColorFor(tertiaryContainer),
            error = error,
            onError = onColorFor(error),
            errorContainer = errorContainer,
            onErrorContainer = onColorFor(errorContainer),
            outline = outline,
            outlineVariant = outlineVariant,
            inverseSurface = fg,
            inverseOnSurface = bg,
            inversePrimary = accent,
            scrim = Color.Black,
        )
    }
}

/**
 * Effective dark mode for an app-theme pref value (System/Dark/Light).
 * Shared by MainActivity (chrome) and TerminalScreen (fallback-scheme
 * substitution) so both agree.
 */
@Composable
fun rememberAppDarkTheme(themeMode: String): Boolean = when (themeMode) {
    ConfigDisk.THEME_LIGHT -> false
    ConfigDisk.THEME_SYSTEM -> isSystemInDarkTheme()
    else -> true
}
