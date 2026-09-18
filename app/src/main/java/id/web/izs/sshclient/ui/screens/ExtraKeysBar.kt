package id.web.izs.sshclient.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import id.web.izs.sshclient.core.term.REPEAT_TICK_MS
import id.web.izs.sshclient.core.term.isRepetitiveKey
import id.web.izs.sshclient.core.term.stepsDisplay
import id.web.izs.sshclient.core.term.weight
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    /**
     * Hold-to-repeat tick (Termux parity): fired once per [REPEAT_TICK_MS]
     * while a repetitive key is held, after the platform long-press
     * timeout. Null (the layout-editor preview) keeps every key tap-only.
     */
    onRepeatSteps: ((List<KeyStep>) -> Unit)? = null,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RectangleShape,
        // Theme-following (not hardcoded black): surfaceContainerLow is
        // near-black in the dark theme (current look preserved) and light
        // in the light theme. Buttons are already theme-adaptive, and the
        // terminal stage above keeps its own scheme background.
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column {
            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
            Column(
                Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
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
                                    val steps = key.steps
                                    ExtraKeyBtn(
                                        key.label, enabled, Modifier.weight(key.weight()),
                                        fill = explicit?.first,
                                        content = explicit?.second,
                                        onTap = { onSendSteps(steps) },
                                        onHoldTick = if (onRepeatSteps != null && isRepetitiveKey(key)) {
                                            { onRepeatSteps(steps) }
                                        } else {
                                            null
                                        },
                                    )
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
 *
 * [onHoldTick] turns the key into tap-or-hold (Termux parity); null keeps
 * the plain tap button. Sticky and menu keys always pass null.
 *
 * Uniform [ExtraKeyHeight] for every key (a plain Surface, not a Button:
 * M3 buttons enforce a 40dp min height internally, which would keep the
 * dead space this bar was slimmed to remove).
 */
private val ExtraKeyHeight = 38.dp

@Composable
private fun ExtraKeyBtn(
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    fill: Color? = null,
    content: Color? = null,
    onHoldTick: (() -> Unit)? = null,
    onTap: () -> Unit,
) {
    if (onHoldTick == null) {
        Surface(
            onClick = onTap,
            enabled = enabled,
            // Bar keys are plain tappables; focus returns to the pipe via
            // sendKeySteps (requestFocus + show) after every tap.
            modifier = modifier.height(ExtraKeyHeight),
            shape = RoundedCornerShape(6.dp),
            color = fill ?: Color.Transparent,
            contentColor = content ?: MaterialTheme.colorScheme.onSurface,
        ) {
            KeyFace(label, enabled, content)
        }
    } else {
        RepeatableKeyBtn(label, enabled, modifier, fill, content, onTap, onHoldTick)
    }
}

@Composable
private fun KeyFace(label: String, enabled: Boolean, content: Color?) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxSize(),
    ) {
        Text(
            label,
            fontSize = 11.sp,
            maxLines = 1,
            color = (content ?: MaterialTheme.colorScheme.onSurface)
                .copy(alpha = if (enabled) 1f else 0.38f),
        )
    }
}

/**
 * Tap-or-hold key (Termux ExtraKeysView parity): release before the
 * platform long-press timeout sends one tap; holding sends one tick per
 * [REPEAT_TICK_MS] until release, with no extra tap afterwards (the
 * consumed long-press suppresses onClick — no double send). Each tick is
 * one funnel call, so an active sticky is consumed by the first tick
 * exactly like a tap. Ripple and accessibility come from
 * combinedClickable, matching the plain Surface button.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun RepeatableKeyBtn(
    label: String,
    enabled: Boolean,
    modifier: Modifier,
    fill: Color?,
    content: Color?,
    onTap: () -> Unit,
    onTick: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    // Long-press timing follows the platform (combinedClickable reads the
    // system ViewConfiguration, like Termux); only the tick cadence is ours.
    val interactions = remember { MutableInteractionSource() }
    var repeatJob: Job? by remember { mutableStateOf<Job?>(null) }
    // Release (or gesture cancel) stops the ticker; the remembered source
    // outlives each press, and scope death cancels a runaway ticker.
    LaunchedEffect(interactions) {
        interactions.interactions.collect { i ->
            if (i is PressInteraction.Release || i is PressInteraction.Cancel) {
                repeatJob?.cancel()
                repeatJob = null
            }
        }
    }
    Surface(
        modifier = modifier
            .height(ExtraKeyHeight)
            .combinedClickable(
                enabled = enabled,
                onClick = onTap,
                onLongClick = {
                    // First tick lands on the long-press (Termux sends on
                    // timeout, never on DOWN); further ticks follow until
                    // the release above cancels the job.
                    onTick()
                    repeatJob?.cancel()
                    repeatJob = scope.launch {
                        while (true) {
                            delay(REPEAT_TICK_MS)
                            onTick()
                        }
                    }
                },
                indication = LocalIndication.current,
                interactionSource = interactions,
            ),
        shape = RoundedCornerShape(6.dp),
        color = fill ?: Color.Transparent,
        contentColor = content ?: MaterialTheme.colorScheme.onSurface,
    ) {
        KeyFace(label, enabled, content)
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
