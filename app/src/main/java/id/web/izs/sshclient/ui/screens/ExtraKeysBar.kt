package id.web.izs.sshclient.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.web.izs.sshclient.core.term.KIND_MENU
import id.web.izs.sshclient.core.term.KIND_STICKY_ALT
import id.web.izs.sshclient.core.term.KIND_STICKY_CTRL
import id.web.izs.sshclient.core.term.KeyDef
import id.web.izs.sshclient.core.term.KeyLayout
import id.web.izs.sshclient.core.term.KeyStep
import id.web.izs.sshclient.core.term.stepsDisplay
import id.web.izs.sshclient.core.term.weight

/**
 * Docked extra-keys bar rendered from a [KeyLayout].
 *
 * Shared by the live terminal and the layout editor preview: a single
 * composable, so the preview is pixel-identical to the terminal by
 * construction (same buttons, same black dock, same spacing).
 *
 * Keys are uniform by default; only an explicit per-key color tints a
 * button (white/black label by luminance) and an active sticky modifier
 * highlights. The editor's step badges keep their own key-vs-text code.
 *
 * Layout sibling below the grid (NOT an overlay), so the grid can never
 * slide behind it. Pure black melts the bar into the keyboard dead zone.
 */
@Composable
fun ExtraKeysBar(
    layout: KeyLayout,
    enabled: Boolean,
    ctrlActive: Boolean,
    altActive: Boolean,
    onSendSteps: (List<KeyStep>) -> Unit,
    onToggleCtrl: () -> Unit,
    onToggleAlt: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RectangleShape,
        color = Color.Black,
    ) {
        Column {
            HorizontalDivider(thickness = 1.dp, color = Color.White.copy(alpha = 0.1f))
            Column(
                Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (row in layout.rows) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        for (key in row) {
                            // Explicit per-key color is the only tint; default
                            // buttons all look the same. An active sticky keeps
                            // its highlight even over an explicit color —
                            // state beats decoration.
                            val explicit = key.buttonColor()
                            when (key.kind) {
                                KIND_STICKY_CTRL -> ExtraKeyBtn(
                                    key.label, enabled, Modifier.weight(key.weight()),
                                    fill = if (ctrlActive) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        explicit?.first
                                    },
                                    content = if (ctrlActive) null else explicit?.second,
                                ) { onToggleCtrl() }
                                KIND_STICKY_ALT -> ExtraKeyBtn(
                                    key.label, enabled, Modifier.weight(key.weight()),
                                    fill = if (altActive) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        explicit?.first
                                    },
                                    content = if (altActive) null else explicit?.second,
                                ) { onToggleAlt() }
                                KIND_MENU -> MenuKeyBtn(
                                    key, enabled, Modifier.weight(key.weight()), onSendSteps,
                                )
                                else -> {
                                    ExtraKeyBtn(
                                        key.label, enabled, Modifier.weight(key.weight()),
                                        fill = explicit?.first,
                                        content = explicit?.second,
                                    ) { onSendSteps(key.steps) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Menu key: the label carries a chevron; tap opens its items as a popup. */
@Composable
private fun MenuKeyBtn(
    key: KeyDef,
    enabled: Boolean,
    modifier: Modifier,
    onSendSteps: (List<KeyStep>) -> Unit,
) {
    var open by remember(key) { mutableStateOf(false) }
    val explicit = key.buttonColor()
    Box(modifier) {
        ExtraKeyBtn(
            key.label + " ▾", enabled, Modifier.fillMaxWidth(),
            fill = explicit?.first,
            content = explicit?.second,
        ) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (item in key.items) {
                val steps = item.steps
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(item.label)
                            Text(
                                stepsDisplay(steps),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    onClick = {
                        open = false
                        onSendSteps(steps)
                    },
                )
            }
        }
    }
}

/**
 * Compact extra key. [fill] tints the button (null = neutral); [content]
 * overrides the label color (null = button default). An explicit per-key
 * color passes both; kind tints pass fill only, keeping default content.
 */
@Composable
private fun ExtraKeyBtn(
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    fill: Color? = null,
    content: Color? = null,
    onTap: () -> Unit,
) {
    OutlinedButton(
        onClick = onTap,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 8.dp),
        colors = if (fill != null) {
            ButtonDefaults.outlinedButtonColors(
                containerColor = fill,
                contentColor = content ?: MaterialTheme.colorScheme.primary,
            )
        } else {
            ButtonDefaults.outlinedButtonColors()
        },
    ) {
        Text(label, fontSize = 11.sp)
    }
}

/**
 * Explicit per-key color as (container, content): white text on dark
 * colors, black on light ones. Null when the key stores no valid color.
 */
private fun KeyDef.buttonColor(): Pair<Color, Color>? {
    val argb = id.web.izs.sshclient.core.config.profileColorArgb(color) ?: return null
    val fill = Color(argb)
    // Relative luminance from the raw channels (gamma skipped — plenty
    // for a text-contrast threshold).
    val r = (argb shr 16 and 0xFF) / 255f
    val g = (argb shr 8 and 0xFF) / 255f
    val b = (argb and 0xFF) / 255f
    val dark = 0.2126f * r + 0.7152f * g + 0.0722f * b < 0.5f
    return fill to if (dark) Color.White else Color.Black
}
