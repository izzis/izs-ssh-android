package id.web.izs.sshclient.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
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
