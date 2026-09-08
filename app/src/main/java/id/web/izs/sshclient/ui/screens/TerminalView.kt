package id.web.izs.sshclient.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
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
import android.util.Log
import id.web.izs.sshclient.BuildConfig
import id.web.izs.sshclient.core.term.TerminalEmulator
import kotlinx.coroutines.delay

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
 * Scrollback: lines scrolled off the top live in the emulator history and
 * render above the grid as one continuous content block (hidden while the
 * alt buffer is up, so vim/htop stay fullscreen). Drag up to read history;
 * new output follows only while pinned to the live edge, and rows prepended
 * above are compensated so the view stays on the same text.
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
    // Scrollback size, fresh each composition (version bumps per feed, so
    // this is never stale when it matters).
    val historySize = emulator.historyRowCount()
    // Follow-bottom: true while parked at the live edge. A user drag
    // ending above the edge unpins; output re-pins only at the edge, so
    // reading history is never yanked away mid-drag.
    var pinned by remember { mutableStateOf(true) }
    var lastHistory by remember { mutableStateOf(0) }
    // Finger-down flag: isScrollInProgress only covers post-slop drags, so
    // a programmatic snap during the slop phase would still fight the user.
    // The observer below never consumes — gesture behavior is unchanged.
    var touching by remember { mutableStateOf(false) }

    BoxWithConstraints(modifier = modifier.clickable(onClick = onTap)) {
        val viewportW = with(density) { maxWidth.toPx() }
        val viewportH = with(density) { maxHeight.toPx() }
        // Redraw window: rows [drawnFirst, drawnFirst+drawnN) are baked into
        // the canvas. Scrolling INSIDE the window is pure GPU translation
        // (zero CPU — nothing here subscribes to vScroll.value); a redraw
        // happens only when content changes, the scroll exits the window,
        // or sizes settle. The old code derived the window from the live
        // scroll offset, so EVERY scroll pixel — including every
        // cursor-follow snap during the keyboard animation — re-laid the
        // whole window (~1s/frame measured): that was the freeze.
        var drawnFirst by remember { mutableStateOf(0) }
        var drawnN by remember { mutableStateOf(64) }
        // drawTick gates canvas recomposition: version bumps per output
        // chunk, but a chunk that touches nothing in the drawn window
        // (reading history while output flows) skips the redraw entirely.
        var drawTick by remember { mutableStateOf(0L) }
        val totalRows = historySize + emulator.rows
        fun maxFirst() = (totalRows - drawnN).coerceAtLeast(0)
        fun anchorFor(v: Float): Int = ((v / lineH).toInt() - 16).coerceIn(0, maxFirst())
        val lineHState = rememberUpdatedState(lineH)
        val vpHState = rememberUpdatedState(viewportH)
        // Scroll-driven re-window (immediate): only when the visible range
        // exits the baked window. Covered scrolls cost nothing.
        LaunchedEffect(Unit) {
            snapshotFlow { vScroll.value }.collect { v ->
                val lh = lineHState.value
                val vpH = vpHState.value
                if (v < drawnFirst * lh || v + vpH > (drawnFirst + drawnN) * lh) {
                    val total = emulator.historyRowCount() + emulator.rows
                    drawnFirst = ((v / lh).toInt() - 16).coerceIn(0, (total - drawnN).coerceAtLeast(0))
                    if (BuildConfig.DEBUG) Log.d("TvScroll", "re-window first=$drawnFirst N=$drawnN")
                }
            }
        }
        // Mount + discrete changes (font/grid): re-window at once (never a
        // size-animation stream, so no debounce needed).
        LaunchedEffect(Unit) {
            drawnN = ((vpHState.value / lineHState.value).toInt() + 32).coerceAtLeast(8)
            val total = emulator.historyRowCount() + emulator.rows
            drawnFirst = (total - drawnN).coerceAtLeast(0)
        }
        LaunchedEffect(fontSp, emulator.cols, emulator.rows) {
            drawnN = ((vpHState.value / lineHState.value).toInt() + 32).coerceAtLeast(8)
            drawnFirst = anchorFor(vScroll.value.toFloat())
        }
        // Viewport-driven re-window (settle-debounced): during the keyboard
        // animation the stale window + GPU translation cover the frames;
        // undrawn strips are pure black (invisible seam), filled at settle.
        LaunchedEffect(viewportW, viewportH, bottomReservePx) {
            delay(150)
            drawnN = ((vpHState.value / lineHState.value).toInt() + 32).coerceAtLeast(8)
            drawnFirst = anchorFor(vScroll.value.toFloat())
        }
        // Keep the cursor visible with a 2-line / 8-column margin, above
        // any docked bar below the grid. Coords are absolute: history sits
        // above the live grid.
        suspend fun keepCursorVisible() {
            val visH = (viewportH - bottomReservePx).coerceAtLeast(lineH * 3)
            val cx = sidePadPx + emulator.cursorX * charW
            val cy = (historySize + emulator.cursorY) * lineH
            val minX = (cx - charW * 8).coerceAtLeast(0f)
            val wantX = (cx + charW * 2 - viewportW).coerceAtLeast(0f)
            if (cx < hScroll.value) hScroll.scrollTo(minX.toInt())
            else if (cx + charW > hScroll.value + viewportW) hScroll.scrollTo(wantX.toInt())
            val minY = (cy - lineH * 2).coerceAtLeast(0f)
            val wantY = (cy + lineH * 2 - visH).coerceAtLeast(0f)
            if (cy < vScroll.value) vScroll.scrollTo(minY.toInt())
            else if (cy + lineH > vScroll.value + visH) vScroll.scrollTo(wantY.toInt())
        }
        // New output: follow only when pinned (or already parked at the
        // edge); when unpinned, compensate rows prepended above so the view
        // stays on the same text instead of sliding down. Never fight a
        // finger-down or an in-progress drag/fling.
        LaunchedEffect(version) {
            if (!touching && !vScroll.isScrollInProgress) {
                if (pinned || vScroll.value >= vScroll.maxValue) {
                    keepCursorVisible()
                    pinned = true
                    if (BuildConfig.DEBUG) Log.d("TvScroll", "follow v=${vScroll.value} max=${vScroll.maxValue}")
                } else if (historySize > lastHistory) {
                    vScroll.scrollTo(vScroll.value + ((historySize - lastHistory) * lineH).toInt())
                    if (BuildConfig.DEBUG) Log.d("TvScroll", "compensate +${historySize - lastHistory}rows")
                }
            } else if (BuildConfig.DEBUG) {
                Log.d("TvScroll", "skip touching=$touching inProgress=${vScroll.isScrollInProgress}")
            }
            // Re-anchor the baked window + gate the redraw. Pinned always
            // redraws (new text at the live edge); growth shifts indices
            // (redraw); a live-covering window redraws (grid changed); a
            // history-only window with no growth skips entirely — zero
            // hitch while reading history during output floods.
            val hDelta = historySize - lastHistory
            val total = historySize + emulator.rows
            val mf = (total - drawnN).coerceAtLeast(0)
            drawnFirst = if (pinned) mf else (drawnFirst + hDelta).coerceIn(0, mf)
            if (pinned || hDelta != 0 || drawnFirst + drawnN > historySize) drawTick++
            lastHistory = historySize
        }
        // Drag end: unpin unless parked at the live edge.
        LaunchedEffect(vScroll.isScrollInProgress) {
            if (!vScroll.isScrollInProgress) pinned = vScroll.value >= vScroll.maxValue
        }
        // Viewport changes (keyboard open/close, rotation, bar toggle):
        // single snap when pinned and untouched, no follow-up motion.
        LaunchedEffect(viewportW, viewportH, bottomReservePx) {
            if (pinned && !touching && !vScroll.isScrollInProgress) {
                val b4 = vScroll.value
                keepCursorVisible()
                if (BuildConfig.DEBUG && kotlin.math.abs(vScroll.value - b4) > 1) {
                    Log.d("TvScroll", "viewport-snap $b4->${vScroll.value} max=${vScroll.maxValue}")
                }
            }
        }
        // Scroll lives OUTSIDE the Canvas: a scrolled Canvas reported a
        // collapsed draw scope on-device, swallowing all text.
        Box(
            modifier = Modifier
                .pointerInput(Unit) {
                    // Telemetry + touching flag only: awaits WITHOUT
                    // consuming, so scroll/tap behavior is untouched.
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        touching = true
                        if (BuildConfig.DEBUG) Log.d("TvScroll", "down v=${vScroll.value} max=${vScroll.maxValue}")
                        do {
                            val ev = awaitPointerEvent()
                        } while (ev.changes.any { it.pressed })
                        touching = false
                        if (BuildConfig.DEBUG) Log.d("TvScroll", "up v=${vScroll.value} max=${vScroll.maxValue}")
                    }
                }
                .fillMaxSize()
                .verticalScroll(vScroll)
                .horizontalScroll(hScroll),
        ) {
            TerminalCanvas(
                emuRef = emuRef,
                tick = drawTick,
                historyRows = historySize,
                firstRow = drawnFirst,
                rowCount = drawnN,
                totalRows = totalRows,
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
 * recomposes only on a gated redraw (tick), window shift, or dims change.
 */
@Composable
private fun TerminalCanvas(
    emuRef: State<TerminalEmulator>,
    tick: Long,
    historyRows: Int,
    firstRow: Int,
    rowCount: Int,
    totalRows: Int,
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
    // Full content size (correct scroll extents), but only the visible
    // window is drawn — the node records a small display list even with
    // 10k scrollback lines.
    val gridH = with(density) { (lineH * totalRows).toDp() }
    // The grid mutates in place (same emulator reference), so the Canvas
    // node is keyed by tick: a new node per gated redraw guarantees the
    // frame is never served stale, regardless of lambda caching.
    key(tick) {
        Canvas(modifier = Modifier.size(gridW, gridH)) {
            drawTerminal(emuRef.value, historyRows, firstRow, rowCount, measurer, style, charW, lineH, sidePadPx)
        }
    }
}

// Debug telemetry counters (timing only, no PII).
private var tvDrawN = 0L
private var tvDrawSlow = 0L
private var tvPhaseBuild = 0L
private var tvPhaseDrawT = 0L
private var tvPhaseRest = 0L

private fun DrawScope.drawTerminal(
    emulator: TerminalEmulator,
    historyRows: Int,
    firstRow: Int,
    rowCount: Int,
    measurer: TextMeasurer,
    style: TextStyle,
    charW: Float,
    lineH: Float,
    sidePadPx: Float,
) {
    val t0 = if (BuildConfig.DEBUG) System.nanoTime() else 0L
    val total = historyRows + emulator.rows
    val endRow = minOf(firstRow + rowCount, total).coerceAtLeast(firstRow)
    // Backdrop covers the drawn window only (parent page is already black,
    // so undrawn rows show black through the transparent canvas).
    if (endRow > firstRow) {
        drawRect(
            Color(TerminalEmulator.BG),
            topLeft = Offset(0f, firstRow * lineH),
            size = Size(size.width, (endRow - firstRow) * lineH),
        )
    }
    // One continuous block: scrollback rows first, then the live grid.
    // History cells may be narrower than the grid (pre-resize width) —
    // missing cells render blank.
    for (i in firstRow until endRow) {
        val top = i * lineH
        val inHistory = i < historyRows
        val gy = i - historyRows
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
        // Spans are merged per contiguous style run (was: one span per
        // cell): 80 single-char spans made paragraph layout pathological
        // (~15ms/row measured). Typical rows now emit a handful of spans.
        var allSpace = true
        var b0 = 0L
        var m0 = 0L
        if (BuildConfig.DEBUG) b0 = System.nanoTime()
        val rowText = buildAnnotatedString {
            var spanFg = 0
            var spanBold = false
            var spanOpen = false
            for (x in 0 until cols) {
                val cell = if (inHistory) emulator.historyCell(i, x) else emulator.cellAt(x, gy)
                val isCursor = !inHistory && emulator.showCursor && x == emulator.cursorX && gy == emulator.cursorY
                val bg = if (isCursor) cell?.fg ?: TerminalEmulator.FG else cell?.bg ?: TerminalEmulator.BG
                if (runStart < 0 || bg != runBg) {
                    flushRun(x)
                    runStart = x
                    runBg = bg
                }
                val ch = cell?.ch ?: ' '
                if (ch != ' ') allSpace = false
                val fgc = if (isCursor) cell?.bg ?: TerminalEmulator.BG else cell?.fg ?: TerminalEmulator.FG
                val bld = cell?.bold == true
                if (!spanOpen || fgc != spanFg || bld != spanBold) {
                    pushStyle(
                        SpanStyle(
                            color = Color(fgc),
                            fontWeight = if (bld) FontWeight.Bold else FontWeight.Normal,
                        ),
                    )
                    spanFg = fgc
                    spanBold = bld
                    spanOpen = true
                }
                append(ch)
            }
        }
        if (BuildConfig.DEBUG) {
            m0 = System.nanoTime()
            tvPhaseBuild += m0 - b0
        }
        flushRun(cols)
        if (!allSpace) {
            val d0 = if (BuildConfig.DEBUG) System.nanoTime() else 0L
            drawText(
                textMeasurer = measurer,
                text = rowText,
                topLeft = Offset(sidePadPx, top),
                style = style,
            )
            if (BuildConfig.DEBUG) {
                val d1 = System.nanoTime()
                tvPhaseDrawT += d1 - d0
                tvPhaseRest += d1 - m0 - (d1 - d0)
            }
        } else if (BuildConfig.DEBUG) {
            tvPhaseRest += System.nanoTime() - m0
        }
    }
    if (BuildConfig.DEBUG) {
        val ms = (System.nanoTime() - t0) / 1_000_000.0
        tvDrawN++
        // Slow frames always; fast ones sampled (rate info without spam).
        if (ms >= 8.0) {
            tvDrawSlow++
            Log.d(
                "TvPerf",
                "draw SLOW rows=${endRow - firstRow}/$total ms=${"%.1f".format(ms)} " +
                    "build=${"%.1f".format(tvPhaseBuild / 1_000_000.0)} " +
                    "drawT=${"%.1f".format(tvPhaseDrawT / 1_000_000.0)} " +
                    "rest=${"%.1f".format(tvPhaseRest / 1_000_000.0)}",
            )
        } else if (tvDrawN % 200 == 1L) {
            Log.d("TvPerf", "draw ok rows=${endRow - firstRow}/$total ms=${"%.1f".format(ms)} slow=$tvDrawSlow/$tvDrawN")
        }
        tvPhaseBuild = 0L
        tvPhaseDrawT = 0L
        tvPhaseRest = 0L
    }
}
