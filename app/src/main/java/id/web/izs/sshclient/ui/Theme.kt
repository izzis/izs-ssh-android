package id.web.izs.sshclient.ui

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Default (and currently only) theme: dark, but not pitch black.
 * Baseline Material dark uses near-black surfaces (#121212 family);
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
