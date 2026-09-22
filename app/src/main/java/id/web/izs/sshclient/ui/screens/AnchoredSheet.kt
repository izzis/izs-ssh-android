package id.web.izs.sshclient.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** Fling velocity (px/s) that jumps the sheet to the next anchor. */
private const val SHEET_FLING_VELOCITY = 700f

/**
 * Custom half/full bottom sheet (Dialog-based), shared by the New-tab
 * picker and the SFTP browser.
 *
 * Why not ModalBottomSheet: the M3 sheet consumes upward drags for its
 * own anchor priority, so list drags intermittently moved the sheet
 * instead of scrolling. Here the main drag affordance is the header
 * zone (pill + [handle], outside the scroll); the list scrolls first
 * and only finger-drag leftover at the edge moves the sheet — past the
 * bottom expands half -> full, past the top shrinks full -> half ->
 * hidden. Fling momentum stops at the edge; a second drag picks the
 * next anchor. Settling only snaps a sheet that a drag left between
 * anchors.
 *
 * Contract: [handle] holds the non-scrollable header (title, search /
 * filter, navigation controls) — it is also the drag handle zone.
 * [content] holds everything scrollable, normally ending in a
 * `weight(1f)` list so the sheet height never changes when rows land
 * (the sheet opens at half and stays there until dragged).
 */
@Composable
fun AnchoredSheet(
    onDismiss: () -> Unit,
    handle: @Composable ColumnScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = { /* scrim tap + back go through hide() below */ },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val screenH = with(LocalDensity.current) { maxHeight.toPx() }
            val halfY = screenH / 2f
            // Sheet offset: 0 = full, halfY = half, screenH = hidden.
            val offset = remember(screenH) { Animatable(screenH) }
            val scope = rememberCoroutineScope()
            var gone by remember { mutableStateOf(false) }
            fun settle(target: Float, dismiss: Boolean) {
                scope.launch {
                    offset.animateTo(target, spring(stiffness = Spring.StiffnessMedium))
                    if (dismiss && !gone) {
                        gone = true
                        onDismiss()
                    }
                }
            }
            fun hide() = settle(screenH, true)
            // Nested drag from the list: the list always scrolls first.
            // Only finger-drag leftover at the edge moves the sheet —
            // drag up past the bottom expands half -> full, drag down
            // past the top shrinks full -> half -> hidden. Fling momentum
            // never moves the sheet; it stops at the edge and a second
            // drag decides the next anchor. Settling only snaps a sheet
            // that a drag left between anchors.
            val sheetNested = remember(screenH, halfY, onDismiss) {
                object : NestedScrollConnection {
                    override fun onPreScroll(
                        available: Offset,
                        source: NestedScrollSource,
                    ): Offset = Offset.Zero

                    override fun onPostScroll(
                        consumed: Offset,
                        available: Offset,
                        source: NestedScrollSource,
                    ): Offset {
                        if (source != NestedScrollSource.UserInput) return Offset.Zero
                        if (available.y < 0f && offset.value > 0f) {
                            val toConsume = available.y.coerceAtLeast(-offset.value)
                            val next = (offset.value + toConsume).coerceIn(0f, screenH)
                            scope.launch { offset.snapTo(next) }
                            return Offset(0f, toConsume)
                        }
                        if (available.y > 0f && offset.value < screenH) {
                            val toConsume = available.y.coerceAtMost(screenH - offset.value)
                            val next = (offset.value + toConsume).coerceIn(0f, screenH)
                            scope.launch { offset.snapTo(next) }
                            return Offset(0f, toConsume)
                        }
                        return Offset.Zero
                    }

                    override suspend fun onPreFling(available: Velocity): Velocity = Velocity.Zero

                    override suspend fun onPostFling(
                        consumed: Velocity,
                        available: Velocity,
                    ): Velocity {
                        val start = offset.value
                        val atAnchor = listOf(0f, halfY, screenH).any { abs(it - start) < 0.5f }
                        if (atAnchor) {
                            if (abs(screenH - start) < 0.5f && !gone) {
                                gone = true
                                onDismiss()
                                return available
                            }
                            return Velocity.Zero
                        }
                        val target = when {
                            available.y < -SHEET_FLING_VELOCITY -> if (start > halfY) halfY else 0f
                            available.y > SHEET_FLING_VELOCITY -> if (start < halfY) halfY else screenH
                            else -> listOf(0f, halfY, screenH).minBy { abs(it - start) }
                        }
                        offset.animateTo(target, spring(stiffness = Spring.StiffnessMedium))
                        if (target == screenH && !gone) {
                            gone = true
                            onDismiss()
                        }
                        return available
                    }
                }
            }
            // Enter at half (M3 sheet behavior).
            LaunchedEffect(screenH) {
                offset.animateTo(halfY, spring(stiffness = Spring.StiffnessMediumLow))
            }
            BackHandler { hide() }
            // Scrim fades with the sheet; tap dismisses with exit animation.
            Box(
                Modifier.fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.32f * (1f - (offset.value / screenH).coerceIn(0f, 1f))))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { hide() },
            )
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .requiredHeight(maxHeight)
                    .nestedScroll(sheetNested)
                    .offset { IntOffset(0, offset.value.roundToInt()) },
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                tonalElevation = 1.dp,
            ) {
                Column(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
                    // Handle zone: pill + caller header, draggable to
                    // half/full/hidden. Everything scrollable lives below,
                    // so these drags never fight the list.
                    Column(
                        Modifier.fillMaxWidth().padding(top = 4.dp)
                            .draggable(
                                orientation = Orientation.Vertical,
                                state = rememberDraggableState { d ->
                                    scope.launch {
                                        offset.snapTo((offset.value + d).coerceIn(0f, screenH))
                                    }
                                },
                                onDragStopped = { v ->
                                    val target = when {
                                        v < -SHEET_FLING_VELOCITY -> if (offset.value > halfY) halfY else 0f
                                        v > SHEET_FLING_VELOCITY -> if (offset.value < halfY) halfY else screenH
                                        else -> listOf(0f, halfY, screenH).minBy { abs(it - offset.value) }
                                    }
                                    settle(target, target == screenH)
                                },
                            ),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            Modifier.size(width = 32.dp, height = 4.dp)
                                .background(
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                    RoundedCornerShape(2.dp),
                                ),
                        )
                        handle()
                    }
                    content()
                }
            }
        }
    }
}
