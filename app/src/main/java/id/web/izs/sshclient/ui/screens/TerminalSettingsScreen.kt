package id.web.izs.sshclient.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import id.web.izs.sshclient.core.term.MAX_MACRO_STEP_DELAY_MS
import id.web.izs.sshclient.ui.AppState

/**
 * Settings > Terminal: font size (same pref as the A-/A+ menu), the
 * scrollback buffer (stepper + free numeric input, lines), the macro step
 * delay, and a shortcut into the extra-keys layout editor. All persist
 * immediately — no Save button.
 */
const val SCROLLBACK_STEP = 1000
const val SCROLLBACK_MAX = 100_000
const val STEP_DELAY_STEP_MS = 20L

@Composable
fun TerminalSettingsScreen(
    state: AppState,
    onEditKeys: () -> Unit,
    onBack: () -> Unit,
) {
    var fontSp by remember { mutableFloatStateOf(state.disk.terminalFontSp) }
    var scrollback by remember { mutableIntStateOf(state.disk.terminalScrollback) }
    var boxText by remember { mutableStateOf(scrollback.toString()) }
    var stepDelayMs by remember { mutableLongStateOf(state.disk.macroStepDelayMs) }
    var delayText by remember { mutableStateOf(stepDelayMs.toString()) }

    fun commitScrollback(v: Int) {
        val c = v.coerceIn(0, SCROLLBACK_MAX)
        scrollback = c
        boxText = c.toString()
        state.disk.terminalScrollback = c
    }

    fun commitStepDelay(v: Long) {
        val c = v.coerceIn(0L, MAX_MACRO_STEP_DELAY_MS)
        stepDelayMs = c
        delayText = c.toString()
        state.disk.macroStepDelayMs = c
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenHeader("Terminal", onBack)
        Text("Extra keys", style = MaterialTheme.typography.titleMedium)
        Text(
            "The button bar below the terminal: labels, sequences, and popups.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable(onClick = onEditKeys),
        ) {
            IconButton(onClick = onEditKeys) {
                Icon(Icons.Filled.Keyboard, contentDescription = "Edit extra keys")
            }
            Text(
                "Edit layout",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        Text("Font size", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = {
                    fontSp = (fontSp - 1f).coerceIn(8f, 24f)
                    state.disk.terminalFontSp = fontSp
                },
            ) { Icon(Icons.Filled.Remove, contentDescription = "Smaller") }
            Text(
                "${fontSp.toInt()}sp",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            IconButton(
                onClick = {
                    fontSp = (fontSp + 1f).coerceIn(8f, 24f)
                    state.disk.terminalFontSp = fontSp
                },
            ) { Icon(Icons.Filled.Add, contentDescription = "Bigger") }
        }
        Text("Scrollback buffer", style = MaterialTheme.typography.titleMedium)
        Text(
            "Lines kept above the live grid. Drag up in the terminal to " +
                "read history; new output follows only while parked at the bottom.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { commitScrollback(scrollback - SCROLLBACK_STEP) }) {
                Icon(Icons.Filled.Remove, contentDescription = "Less")
            }
            // Same look as the font row above: borderless number (tap to
            // type any value), not a boxed field.
            BasicTextField(
                value = boxText,
                onValueChange = { t ->
                    // Digits only; empty lets the user clear before retyping
                    // (last valid value stays saved until valid input lands).
                    val digits = t.filter { it.isDigit() }
                    boxText = digits
                    digits.toIntOrNull()?.let { commitScrollback(it) }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                textStyle = MaterialTheme.typography.titleLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                ),
                modifier = Modifier.width(120.dp),
            )
            Text(
                "lines",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(onClick = { commitScrollback(scrollback + SCROLLBACK_STEP) }) {
                Icon(Icons.Filled.Add, contentDescription = "More")
            }
        }
        // Labels computed first: String.format only applies to the segment
        // it is called on, so inline %,d placeholders across concatenated
        // segments would leak literally (past bug: user saw "max %,d").
        val maxLabel = "%,d".format(SCROLLBACK_MAX)
        val stepLabel = "%,d".format(SCROLLBACK_STEP)
        Text(
            "0 = off, max $maxLabel. ±$stepLabel per tap, or tap the number " +
                "to type. Applies live; lowering trims immediately. Very " +
                "large buffers use lots of RAM.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("Macro step delay", style = MaterialTheme.typography.titleMedium)
        Text(
            "Pause between the steps of a multi-step key, so each part " +
                "registers in order (vim :q! needs ESC, then text, then Enter).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { commitStepDelay(stepDelayMs - STEP_DELAY_STEP_MS) }) {
                Icon(Icons.Filled.Remove, contentDescription = "Less")
            }
            BasicTextField(
                value = delayText,
                onValueChange = { t ->
                    val digits = t.filter { it.isDigit() }
                    delayText = digits
                    digits.toLongOrNull()?.let { commitStepDelay(it) }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                textStyle = MaterialTheme.typography.titleLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                ),
                modifier = Modifier.width(120.dp),
            )
            Text(
                "ms",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(onClick = { commitStepDelay(stepDelayMs + STEP_DELAY_STEP_MS) }) {
                Icon(Icons.Filled.Add, contentDescription = "More")
            }
        }
        Text(
            "0 = no pause, max $MAX_MACRO_STEP_DELAY_MS. ±$STEP_DELAY_STEP_MS per tap, " +
                "or tap the number to type. Applies to the next macro sent.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
