package id.web.izs.sshclient.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.web.izs.sshclient.core.term.TerminalEmulator

/**
 * Cell metrics for a monospace font size, shared by the view and the
 * window-change measurement so both agree. Returns (charWidthPx, lineHeightPx).
 *
 * Computed arithmetically (Roboto Mono advance is exactly 0.6em), never by
 * measuring: measuring during first composition can return width 0 while
 * fonts load, and the cached zero collapsed the whole grid to 0x0.
 */
@Composable
fun rememberTerminalCell(fontSp: Float): Pair<Float, Float> {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    return remember(density, fontSp) {
        // Like xterm, columns are exactly one advance wide: measure the real
        // monospace advance (averaged over 40 glyphs) instead of assuming
        // 0.6em, so laid-out rows land pixel-perfect on the grid. The Canvas
        // text style must stay identical to this one.
        val style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = fontSp.sp)
        val w = measurer.measure("0123456789".repeat(4), style).size.width / 40f
        val h = with(density) { (fontSp * 1.25f).sp.toPx() }
        w to h
    }
}

/**
 * Termux-like terminal grid: user-chosen monospace font, Canvas cells with
 * per-cell fg/bg/bold + block cursor. The grid scrolls on both axes and
 * auto-scrolls just enough to keep the cursor visible — so an open soft
 * keyboard can never cover what is being typed.
 *
 * Performance: the keyboard animation resizes this scope every frame. The
 * expensive part (full-grid Canvas redraw) lives in [TerminalCanvas], whose
 * params are all Compose-stable — so size-only recompositions skip it and
 * the redraw runs only when [version] (or grid dims) actually change. The
 * emulator mutates in place, but every mutation bumps [version], so
 * (ref, version) fully describes the visible state.
 */
@Composable
fun TerminalView(
    emulator: TerminalEmulator,
    version: Long,
    fontSp: Float,
    cell: Pair<Float, Float>,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Pixels at the viewport bottom covered by docked bars below the grid.
     * Layout bars shrink the viewport itself, so this is normally 0 — the
     * cursor margin below still applies.
     */
    bottomReservePx: Float = 0f,
    /**
     * Horizontal breathing room (screen-protector edges): the grid is drawn
     * offset by this on both sides so edge columns stay visible.
     */
    sidePadPx: Float = 0f,
) {
    val density = LocalDensity.current
    val vScroll = rememberScrollState()
    val hScroll = rememberScrollState()
    // Single-sourced metrics (measured once by the caller): two independent
    // computations once disagreed and collapsed a canvas to 0x0.
    val (charW, lineH) = cell
    // Stable holder: the draw lambda reads the CURRENT emulator without
    // subscribing this composition to its in-place mutations (version does
    // the invalidating). Keeps TerminalCanvas skippable.
    val emuRef = rememberUpdatedState(emulator)

    BoxWithConstraints(modifier = modifier.clickable(onClick = onTap)) {
        val viewportW = with(density) { maxWidth.toPx() }
        val viewportH = with(density) { maxHeight.toPx() }
        // Keep the cursor visible with a 2-line / 8-column margin, above
        // any docked bar below the grid.
        suspend fun keepCursorVisible() {
            val visH = (viewportH - bottomReservePx).coerceAtLeast(lineH * 3)
            val cx = sidePadPx + emulator.cursorX * charW
            val cy = emulator.cursorY * lineH
            val minX = (cx - charW * 8).coerceAtLeast(0f)
            val wantX = (cx + charW * 2 - viewportW).coerceAtLeast(0f)
            if (cx < hScroll.value) hScroll.scrollTo(minX.toInt())
            else if (cx + charW > hScroll.value + viewportW) hScroll.scrollTo(wantX.toInt())
            val minY = (cy - lineH * 2).coerceAtLeast(0f)
            val wantY = (cy + lineH * 2 - visH).coerceAtLeast(0f)
            if (cy < vScroll.value) vScroll.scrollTo(minY.toInt())
            else if (cy + lineH > vScroll.value + visH) vScroll.scrollTo(wantY.toInt())
        }
        // New output: scroll at once.
        LaunchedEffect(version) { keepCursorVisible() }
        // Viewport changes (keyboard open/close, rotation, bar toggle):
        // single snap, no follow-up motion (no animation).
        LaunchedEffect(viewportW, viewportH, bottomReservePx) {
            keepCursorVisible()
        }
        // Scroll lives OUTSIDE the Canvas: a scrolled Canvas reported a
        // collapsed draw scope on-device, swallowing all text.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(vScroll)
                .horizontalScroll(hScroll),
        ) {
            TerminalCanvas(
                emuRef = emuRef,
                version = version,
                fontSp = fontSp,
                charW = charW,
                lineH = lineH,
                sidePadPx = sidePadPx,
                cols = emulator.cols,
                rows = emulator.rows,
            )
        }
        // NOTE: no explicit read of `version` needed — a changed argument
        // value recomposes this function, which redraws the Canvas.
    }
}

/**
 * The expensive full-grid draw, isolated so per-frame size changes during
 * the keyboard animation skip it. Every param is Compose-stable, so this
 * recomposes only when version/dims/font/padding actually change.
 */
@Composable
private fun TerminalCanvas(
    emuRef: State<TerminalEmulator>,
    version: Long,
    fontSp: Float,
    charW: Float,
    lineH: Float,
    sidePadPx: Float,
    cols: Int,
    rows: Int,
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = fontSp.sp)
    val gridW = with(density) { (sidePadPx * 2 + charW * cols).toDp() }
    val gridH = with(density) { (lineH * rows).toDp() }
    // The grid mutates in place (same emulator reference), so the Canvas
    // node is keyed by version: a new node per update guarantees the
    // frame is never served stale, regardless of lambda caching.
    key(version) {
        Canvas(modifier = Modifier.size(gridW, gridH)) {
            drawTerminal(emuRef.value, measurer, style, charW, lineH, sidePadPx)
        }
    }
}

private fun DrawScope.drawTerminal(
    emulator: TerminalEmulator,
    measurer: TextMeasurer,
    style: TextStyle,
    charW: Float,
    lineH: Float,
    sidePadPx: Float,
) {
    // Base backdrop (letterbox area included).
    drawRect(Color(TerminalEmulator.BG), size = size)
    for (y in 0 until emulator.rows) {
        val top = y * lineH
        val cols = emulator.cols
        // Backgrounds: one rect per contiguous run (default BG already
        // painted by the backdrop above).
        var runStart = -1
        var runBg = 0
        fun flushRun(end: Int) {
            if (runStart >= 0 && runBg != TerminalEmulator.BG) {
                drawRect(
                    color = Color(runBg),
                    topLeft = Offset(sidePadPx + runStart * charW, top),
                    size = Size((end - runStart) * charW + 1f, lineH + 1f),
                )
            }
            runStart = -1
        }
        // Text: ONE layout per row (was: one per cell). Single-char layouts
        // dominated frame time on low-end phones — this is ~25x less work.
        var allSpace = true
        val rowText = buildAnnotatedString {
            for (x in 0 until cols) {
                val cell = emulator.cellAt(x, y)
                val isCursor = emulator.showCursor && x == emulator.cursorX && y == emulator.cursorY
                val bg = if (isCursor) cell.fg else cell.bg
                if (runStart < 0 || bg != runBg) {
                    flushRun(x)
                    runStart = x
                    runBg = bg
                }
                if (cell.ch != ' ') allSpace = false
                pushStyle(
                    SpanStyle(
                        color = Color(if (isCursor) cell.bg else cell.fg),
                        fontWeight = if (cell.bold) FontWeight.Bold else FontWeight.Normal,
                    ),
                )
                append(cell.ch)
            }
        }
        flushRun(cols)
        if (!allSpace) {
            drawText(
                textMeasurer = measurer,
                text = rowText,
                topLeft = Offset(sidePadPx, top),
                style = style,
            )
        }
    }
}
