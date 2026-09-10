package id.web.izs.sshclient.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Font
import id.web.izs.sshclient.R
import id.web.izs.sshclient.core.term.isMonospaceSample
import id.web.izs.sshclient.core.config.TerminalCursor
import id.web.izs.sshclient.core.config.TerminalFont
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import id.web.izs.sshclient.core.term.TerminalEmulator
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Cell metrics for a monospace font size, shared by the view and the
 * window-change measurement so both agree. Returns (charWidthPx, lineHeightPx).
 *
 * Computed arithmetically (Roboto Mono advance is exactly 0.6em), never by
 * measuring: measuring during first composition can return width 0 while
 * fonts load, and the cached zero collapsed the whole grid to 0x0.
 */
@Composable
fun rememberTerminalCell(fontSp: Float, fontFamily: FontFamily): Pair<Float, Float> {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    return remember(density, fontSp, fontFamily) {
        // Like xterm, columns are exactly one advance wide: measure the real
        // monospace advance (averaged over 40 glyphs) instead of assuming
        // 0.6em, so laid-out rows land pixel-perfect on the grid. The Canvas
        // text style must stay identical to this one.
        val style = terminalTextStyle(fontFamily, fontSp)
        val w = measurer.measure("0123456789".repeat(4), style).size.width / 40f
        val h = with(density) { (fontSp * 1.25f).sp.toPx() }
        w to h
    }
}

/**
 * Single text-style source for terminal rendering AND the Appearance
 * preview: same family + size in both places, so the preview matches the
 * live grid by construction (WYSIWYG).
 */
fun terminalTextStyle(fontFamily: FontFamily, fontSp: Float): TextStyle =
    TextStyle(fontFamily = fontFamily, fontSize = fontSp.sp)

/**
 * Resolves a [TerminalFont] choice to a Compose family: the bundled
 * Source Code Pro (desktop fallback-file parity) or system monospace.
 *
 * The grid renderer draws whole rows at natural glyph advances, so a
 * proportional face silently breaks the grid (rows end early, dead space on
 * the right, correct server wrap). System "monospace" is not guaranteed
 * monospace — OEM fonts can replace it — so the System choice is measured
 * (narrow / wide / space vs digits); a proportional face falls back to the
 * bundled font for both measuring and drawing (single source: every caller
 * uses this function).
 */
@Composable
fun rememberTerminalFontFamily(font: TerminalFont): FontFamily {
    val measurer = rememberTextMeasurer()
    return remember(font) {
        if (font == TerminalFont.SOURCE_CODE_PRO) {
            FontFamily(Font(R.font.source_code_pro))
        } else {
            val sys = FontFamily.Monospace
            val probe = TextStyle(fontFamily = sys)
            val ref = measurer.measure("0000", probe).size.width / 4f
            val samples = listOf("iiii", "WWWW", "    ").map {
                measurer.measure(it, probe).size.width / 4f
            }
            if (isMonospaceSample(ref, samples)) sys
            else FontFamily(Font(R.font.source_code_pro))
        }
    }
}

/**
 * Termux-like terminal grid: user-chosen monospace font, Canvas cells with
 * per-cell fg/bg/bold + block/beam/underline cursor overlay. The grid
 * scrolls on both axes and
 * auto-scrolls just enough to keep the cursor visible — so an open soft
 * keyboard can never cover what is being typed.
 *
 * Scrollback: lines scrolled off the top live in the emulator history and
 * render above the grid as one continuous content block (hidden while the
 * alt buffer is up, so vim/htop stay fullscreen). Drag up to read history;
 * new output follows only while pinned to the live edge, and rows prepended
 * above are compensated so the view stays on the same text.
 *
 * Text selection is born ONLY from a committed hold (word) or a
 * triple-tap (line) — plain drags never summon it. Two-stage hold: 300ms
 * ticks haptically (release to commit the word, move to scroll); ~600ms
 * commits and extends the nearest endpoint until release, with edge-zone
 * auto-scroll. Summoning is vetoed once scrolled content moves. Endpoints
 * also move via immediate handle drags (a press on a handle locks scroll
 * at down); a Copy/Paste pill floats above the selection. Scroll stays on
 * while selecting (locked only for an endpoint drag) and a tap clears.
 * Absolute rows are scroll-stable (grid-up D cancels history-grow D), so
 * handles need no shifting — but a history SHRINK (clear screen) drops the
 * selection instead of highlighting wrong cells.
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
    /** Rendering font (Settings > Appearance). Measure + draw share it. */
    fontFamily: FontFamily = FontFamily.Monospace,
    /** Cursor shape (`terminal.cursor` YAML, Settings > Appearance). */
    cursor: TerminalCursor = TerminalCursor.BLOCK,
    /** Cursor blink (`terminal.cursorBlink` YAML). */
    cursorBlink: Boolean = true,
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
    /** Selection copy: receives the extracted text (caller toasts/clipboards). */
    onCopySelection: (String) -> Unit = {},
    /** Selection paste: sends the given text to the session as typed input. */
    onPasteSelection: (String) -> Unit = {},
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
    // Doubled as the selection-freeze flag: while selecting, output-follow
    // and compensate snaps stay off (handles ride the content instead).
    var touching by remember { mutableStateOf(false) }
    // Text selection in absolute rows (history above grid). Anchor/focus may
    // cross — drawing and extraction both normalize.
    var selAnchor by remember { mutableStateOf<TerminalEmulator.SelPoint?>(null) }
    var selFocus by remember { mutableStateOf<TerminalEmulator.SelPoint?>(null) }
    val selecting = selAnchor != null && selFocus != null
    // Cursor blink clock (xterm-like): steady while typing — the timer
    // restarts on every grid change — then ~530ms on/off. Only the
    // cursor overlay reads this, so blinking never redraws the grid.
    var blinkOn by remember(cursorBlink) { mutableStateOf(true) }
    LaunchedEffect(cursorBlink, emulator.showCursor, version) {
        blinkOn = true
        if (cursorBlink && emulator.showCursor) {
            while (true) {
                delay(530L)
                blinkOn = !blinkOn
            }
        }
    }
    val onTapState = rememberUpdatedState(onTap)
    val onCopyState = rememberUpdatedState(onCopySelection)
    val onPasteState = rememberUpdatedState(onPasteSelection)
    val clipboard = LocalClipboard.current
    val clipScope = rememberCoroutineScope()
    // Clipboard snapshot for the Paste button, refreshed every time a
    // selection starts (long-press or triple-tap). The read is suspend, so
    // it refreshes async — the button only needs a best-effort snapshot.
    var pasteText by remember { mutableStateOf("") }
    fun refreshPasteSnapshot() {
        clipScope.launch {
            pasteText = clipboard.getClipEntry()?.clipData?.getItemAt(0)?.text?.toString() ?: ""
        }
    }
    // Triple-tap chain: three taps <400ms apart on the same absolute row
    // select the whole line. Taps still focus the keyboard immediately
    // (no tap-delay tradeoff), the third tap just adds the selection.
    var tapCount by remember { mutableIntStateOf(0) }
    var lastTapMs by remember { mutableLongStateOf(0L) }
    var lastTapRow by remember { mutableIntStateOf(-1) }
    // Handle-drag auto-scroll: direction per axis (-1/0/+1), the parked
    // finger in viewport px, and which endpoint it stretches (0 = anchor,
    // 1 = focus). The loop below scrolls and re-derives the endpoint until
    // the finger leaves the edge zone or the content edge is reached.
    var autoV by remember { mutableIntStateOf(0) }
    var autoH by remember { mutableIntStateOf(0) }
    var dragVpX by remember { mutableFloatStateOf(0f) }
    var dragVpY by remember { mutableFloatStateOf(0f) }
    var dragWhich by remember { mutableIntStateOf(0) }
    // Scroll gate: scroll is ALWAYS on (even while selecting) and locks
    // only for an armed endpoint drag. The flip settles during the
    // stationary hold, so no race with arriving moves.
    var gestureLocked by remember { mutableStateOf(false) }
    // Dead-man switch for auto-scroll: only scrolls while drag events keep
    // arriving (a parked finger must never run the selection away).
    var lastDragMs by remember { mutableLongStateOf(0L) }

    val hapticView = LocalView.current
    /** Short tick: a hold just registered — release cleanly to commit. */
    fun hapticTick() {
        try {
            hapticView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        } catch (_: Exception) { }
    }

    fun clearSelection() {
        selAnchor = null
        selFocus = null
        autoV = 0
        autoH = 0
        gestureLocked = false
        touching = false
    }

    BoxWithConstraints(modifier = modifier) {
        val viewportW = with(density) { maxWidth.toPx() }
        val viewportH = with(density) { maxHeight.toPx() }
        // UpdatedState holders live here (before the selection helpers)
        // because gesture closures outlive recompositions.
        val lineHState = rememberUpdatedState(lineH)
        val vpHState = rememberUpdatedState(viewportH)
        val vpWState = rememberUpdatedState(viewportW)
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
        // Full content size, shared by the terminal canvas, the selection
        // highlight, and the draggable handles (all pan with the text).
        val gridWDp = with(density) { (sidePadPx * 2 + charW * emulator.cols).toDp() }
        val gridHDp = with(density) { (lineH * totalRows).toDp() }
        val stemPx = with(density) { 14.dp.toPx() }
        val dotRPx = with(density) { 7.dp.toPx() }
        // Edge-zone radius for handle/press auto-scroll (read per composition,
        // not per drag event).
        val edgeZonePx = with(density) { 56.dp.toPx() }

        // Scroll-gate flips for endpoint drags (handle grabs, two-stage-hold
        // extends). Both settle during a stationary hold, so arriving drag
        // moves never race the recomposition.
        fun lockGesture() {
            gestureLocked = true
            touching = true
        }
        fun unlockGesture() {
            gestureLocked = false
            touching = false
            autoV = 0
            autoH = 0
        }
        // Content px (handle boxes, auto-scroll fingers): the scroll is
        // already baked in — never add it again (double-counting jumps the
        // endpoint by scroll/lineH rows on scrolled-up sessions).
        fun contentToSel(cx: Float, cy: Float): TerminalEmulator.SelPoint {
            val total = emulator.historyRowCount() + emulator.rows
            val row = (cy / lineH).toInt().coerceIn(0, (total - 1).coerceAtLeast(0))
            val col = ((cx - sidePadPx) / charW).toInt().coerceIn(0, emulator.cols - 1)
            return TerminalEmulator.SelPoint(row, col)
        }
        fun toSelPoint(px: Float, py: Float): TerminalEmulator.SelPoint {
            // Viewport px (finger, taps, press-drags): content sits scroll below.
            return contentToSel(px + hScroll.value, py + vScroll.value)
        }
        fun startSelection(at: Offset) {
            val p = toSelPoint(at.x, at.y)
            val (s, e) = emulator.expandWord(p.row, p.col)
            selAnchor = TerminalEmulator.SelPoint(p.row, s)
            selFocus = TerminalEmulator.SelPoint(p.row, e)
            refreshPasteSnapshot()
            tapCount = 0
            // No scroll lock here: the finger may still be down (two-stage
            // hold), and only the extend/handle paths lock. Freezes follow
            // until the next touch cycle clears it.
            touching = true
        }
        fun selectLine(row: Int) {
            val total = emulator.historyRowCount() + emulator.rows
            val r = row.coerceIn(0, (total - 1).coerceAtLeast(0))
            selAnchor = TerminalEmulator.SelPoint(r, 0)
            selFocus = TerminalEmulator.SelPoint(r, emulator.cols - 1)
            refreshPasteSnapshot()
            tapCount = 0
            touching = true
        }
        // Handle drag in VIEWPORT px (callers in content space subtract the
        // scroll first): move the endpoint, then arm auto-scroll when the
        // finger parks inside the edge zone.
        fun dragHandle(which: Int, vpx: Float, vpy: Float) {
            val p = toSelPoint(vpx, vpy)
            if (which == 0) selAnchor = p else selFocus = p
            lastDragMs = System.currentTimeMillis()
            dragVpX = vpx
            dragVpY = vpy
            dragWhich = which
            autoV = when {
                vpy < edgeZonePx -> -1
                vpy > vpHState.value - edgeZonePx -> 1
                else -> 0
            }
            autoH = when {
                vpx < edgeZonePx -> -1
                vpx > vpWState.value - edgeZonePx -> 1
                else -> 0
            }
        }
        fun stopAutoScroll() {
            autoV = 0
            autoH = 0
        }
        // Shared tap path (single-tap clear/focus + triple-tap whole line),
        // used by the manual gesture detector below. Reads selAnchor/selFocus
        // directly (live State) instead of the derived `selecting` val, so it
        // stays correct inside never-restarted gesture closures.
        fun handleTap(offset: Offset) {
            if (selAnchor != null && selFocus != null) {
                clearSelection()
                tapCount = 0
            } else {
                val p = toSelPoint(offset.x, offset.y)
                val now = System.currentTimeMillis()
                if (tapCount >= 2 && now - lastTapMs < 400 && p.row == lastTapRow) {
                    selectLine(p.row)
                } else {
                    tapCount = if (now - lastTapMs < 400 && p.row == lastTapRow) tapCount + 1 else 1
                    lastTapMs = now
                    lastTapRow = p.row
                    onTapState.value()
                }
            }
        }
        // Normalized extremes (live version of selNorm for gesture closures).
        fun normEnds(): IntArray? {
            val a = selAnchor
            val f = selFocus
            if (a == null || f == null) return null
            return if (a.row > f.row || (a.row == f.row && a.col > f.col)) {
                intArrayOf(f.row, f.col, a.row, a.col)
            } else {
                intArrayOf(a.row, a.col, f.row, f.col)
            }
        }
        // True when a viewport press lands on a handle dot: the press then
        // belongs to the handle alone (a hold must never long-press-reselect
        // over an aimed selection). Covers the full 48dp touch box.
        fun onHandleDown(vpos: Offset): Boolean {
            val n = normEnds() ?: return false
            val touchR = with(density) { 34.dp.toPx() }
            fun hit(cc: Int, rr: Int): Boolean {
                val dx = sidePadPx + (cc + 0.5f) * charW - (vpos.x + hScroll.value)
                val dy = (rr + 1) * lineH + stemPx + dotRPx - (vpos.y + vScroll.value)
                return dx * dx + dy * dy <= touchR * touchR
            }
            return hit(n[1], n[0]) || hit(n[3], n[2])
        }
        // Endpoint nearest to a viewport finger: press-drag extends from it.
        fun nearestEndpoint(vpos: Offset): Int {
            val fx = vpos.x + hScroll.value
            val fy = vpos.y + vScroll.value
            fun d(p: TerminalEmulator.SelPoint?): Float {
                if (p == null) return Float.MAX_VALUE
                val dx = sidePadPx + (p.col + 0.5f) * charW - fx
                val dy = (p.row + 0.5f) * lineH - fy
                return dx * dx + dy * dy
            }
            return if (d(selAnchor) <= d(selFocus)) 0 else 1
        }
        // Hold drift budget: a human finger cannot hold pixel-still, but a
        // real scroll travels far. Past this radius a hold becomes a scroll.
        // (Scroll-start itself is authoritative via userScrolled() below.)
        val holdSlopPx = with(density) { 24.dp.toPx() }
        val selNorm = remember(selAnchor, selFocus) { normEnds() }
        // Recomputed on output ticks too: the text under a live selection
        // can change while the user aims the handles.
        val selText = remember(selAnchor, selFocus, drawTick) {
            val a = selAnchor
            val f = selFocus
            if (a == null || f == null) "" else emulator.selectedText(a, f)
        }
        fun maxFirst() = (totalRows - drawnN).coerceAtLeast(0)
        fun anchorFor(v: Float): Int = ((v / lineH).toInt() - 16).coerceIn(0, maxFirst())
        // Scroll-driven re-window (immediate): only when the visible range
        // exits the baked window. Covered scrolls cost nothing.
        LaunchedEffect(Unit) {
            snapshotFlow { vScroll.value }.collect { v ->
                val lh = lineHState.value
                val vpH = vpHState.value
                if (v < drawnFirst * lh || v + vpH > (drawnFirst + drawnN) * lh) {
                    val total = emulator.historyRowCount() + emulator.rows
                    drawnFirst = ((v / lh).toInt() - 16).coerceIn(0, (total - drawnN).coerceAtLeast(0))
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
        // Layout meaning changed under the handles (resize, font, alt
        // buffer switch): drop the selection, never highlight wrong cells.
        LaunchedEffect(fontSp, emulator.cols, emulator.rows, emulator.altActive) {
            clearSelection()
        }
        // Handle-drag auto-scroll: step the scroll, then stretch the dragged
        // endpoint to the parked finger (its viewport spot now maps to fresh
        // content). Deliberately slow (~8 rows/s) plus a dead-man switch: a
        // finger held still is aiming, not scrolling. Stops when the finger
        // leaves the zone, lifts, idles, or the scroll can't move further.
        LaunchedEffect(autoV, autoH) {
            while (autoV != 0 || autoH != 0) {
                if (System.currentTimeMillis() - lastDragMs > 600) {
                    stopAutoScroll()
                    break
                }
                val bv = vScroll.value
                val bh = hScroll.value
                if (autoV != 0) {
                    vScroll.scrollTo((bv + (lineH * autoV).toInt()).coerceIn(0, vScroll.maxValue))
                }
                if (autoH != 0) {
                    hScroll.scrollTo((bh + (charW * 2 * autoH).toInt()).coerceIn(0, hScroll.maxValue))
                }
                // Parked finger is viewport px: map straight (the scroll it
                // sits under already moved above — no double-count).
                val p = toSelPoint(dragVpX, dragVpY)
                if (dragWhich == 0) selAnchor = p else selFocus = p
                if (vScroll.value == bv && hScroll.value == bh) stopAutoScroll()
                delay(120)
            }
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
            // History shrank (clear screen): stored rows may point at
            // unrelated cells — drop instead of highlighting wrong text.
            if (selecting && historySize < lastHistory) clearSelection()
            if (!touching && !vScroll.isScrollInProgress) {
                if (pinned || vScroll.value >= vScroll.maxValue) {
                    keepCursorVisible()
                    pinned = true
                } else if (historySize > lastHistory) {
                    vScroll.scrollTo(vScroll.value + ((historySize - lastHistory) * lineH).toInt())
                }
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
                keepCursorVisible()
            }
        }
        // Scroll lives OUTSIDE the Canvas: a scrolled Canvas reported a
        // collapsed draw scope on-device, swallowing all text.
        Box(
            modifier = Modifier
                .pointerInput(Unit) {
                    // touching flag only: awaits WITHOUT
                    // consuming, so scroll/tap behavior is untouched.
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        touching = true
                        do {
                            val ev = awaitPointerEvent()
                        } while (ev.changes.any { it.pressed })
                        touching = false
                    }
                }
                // Keyed Unit (never restarts mid-gesture): all reads inside stay
                // live via State delegates / UpdatedState.
                .pointerInput(Unit) {
                    // Selection is born ONLY from a committed hold (word) or a
                    // triple-tap (line) — never from plain drags. Two-stage
                    // hold: 300ms ticks haptically (keep holding to extend,
                    // release to commit, move to scroll); ~600ms commits and
                    // extends the nearest endpoint until release. Summoning is
                    // vetoed the moment scrolled content actually moves.
                    // While SELECTING, holds claim nothing: tap clears, drags
                    // scroll. Pre-hold slop bails untouched so scroll keeps the
                    // gesture; nothing here ever consumes.
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // Scroll offsets at press time. During finger-down all
                        // programmatic snaps are frozen (touching gate), so ANY
                        // offset change below = the user scrolled — authoritative
                        // veto for summoning (a distance budget alone cannot see
                        // a 12px scroll that stays under it).
                        val sv0 = vScroll.value
                        val sh0 = hScroll.value
                        fun userScrolled(): Boolean =
                            vScroll.value != sv0 || hScroll.value != sh0
                        // A press on a handle belongs to the handle: lock
                        // scroll at down (settles before moves), wait the
                        // release, unlock. Taps do nothing; drags are the
                        // handle's own detector below.
                        if (onHandleDown(down.position)) {
                            lockGesture()
                            try {
                                var held = false
                                while (!held) {
                                    val ev = awaitPointerEvent()
                                    val c = ev.changes.firstOrNull { it.id == down.id }
                                    if (c == null || !c.pressed) held = true
                                }
                            } finally {
                                unlockGesture()
                            }
                            return@awaitEachGesture
                        }
                        // Slop-first race: non-null = dragged before timeout
                        // (scroll owns it — bail); null = released early
                        // (tap) or timed out (hold check below).
                        val slopHit = withTimeoutOrNull(300) {
                            awaitTouchSlopOrCancellation(down.id) { _, _ -> }
                        }
                        if (slopHit != null) return@awaitEachGesture
                        val cur = currentEvent.changes.firstOrNull { it.id == down.id }
                        if (cur == null || !cur.pressed) {
                            if (cur != null) handleTap(cur.position)
                            return@awaitEachGesture
                        }
                        // Held past 300ms: tick (the hold registered — keep
                        // holding to select+drag, release to commit the word,
                        // move to scroll). Stage 2 waits out a second 300ms.
                        // userScrolled() vetoes summoning the moment content
                        // actually moved; the distance budget below is only a
                        // backstop for scroll-clamped edges.
                        hapticTick()
                        // "released" | "moved" | "gone" | "held" (second
                        // timeout = still holding = commit + extend below).
                        val stage2 = withTimeoutOrNull(300L) {
                            var verdict = "held"
                            var open = true
                            while (open) {
                                val ev = awaitPointerEvent()
                                val c = ev.changes.firstOrNull { it.id == down.id }
                                if (c == null) {
                                    verdict = "gone"
                                    open = false
                                } else if (!c.pressed) {
                                    verdict = if (
                                        userScrolled() ||
                                        (c.position - down.position).getDistance() > holdSlopPx
                                    ) "moved" else "released"
                                    open = false
                                } else if (
                                    userScrolled() ||
                                    (c.position - down.position).getDistance() > holdSlopPx
                                ) {
                                    verdict = "moved"
                                    open = false
                                }
                            }
                            verdict
                        } ?: "held"
                        when (stage2) {
                            "released" -> {
                                if (selAnchor == null && selFocus == null) {
                                    startSelection(down.position)
                                }
                            }
                            "moved", "gone" -> return@awaitEachGesture
                            else -> {
                                // Held ~600ms: commit the word, tick again,
                                // lock scroll (settled — the finger is still),
                                // then extend the nearest endpoint to the
                                // finger until release (edge zone auto-scrolls
                                // via dragHandle, as with handle drags).
                                hapticTick()
                                if (selAnchor == null && selFocus == null) {
                                    startSelection(down.position)
                                }
                                lockGesture()
                                try {
                                    val pressWhich = nearestEndpoint(down.position)
                                    var done = false
                                    while (!done) {
                                        val ev = awaitPointerEvent()
                                        val c = ev.changes.firstOrNull { it.id == down.id }
                                        if (c == null || !c.pressed) done = true
                                        else dragHandle(pressWhich, c.position.x, c.position.y)
                                    }
                                } finally {
                                    unlockGesture()
                                }
                            }
                        }
                    }
                }
                .fillMaxSize()
                .verticalScroll(vScroll, enabled = !gestureLocked)
                .horizontalScroll(hScroll, enabled = !gestureLocked),
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
                gridW = gridWDp,
                gridH = gridHDp,
                fontFamily = fontFamily,
            )
            // Cursor overlay rides the same scrolled content (pan-locked
            // like the selection highlight). All shapes live here — the
            // grid below renders cursor cells normally — so blink costs
            // one tiny rect, never a full redraw.
            if (emulator.showCursor && blinkOn) {
                CursorOverlay(
                    tick = drawTick,
                    emulator = emuRef.value,
                    historyRows = historySize,
                    cursor = cursor,
                    fontSp = fontSp,
                    fontFamily = fontFamily,
                    charW = charW,
                    lineH = lineH,
                    sidePadPx = sidePadPx,
                    gridW = gridWDp,
                    gridH = gridHDp,
                )
            }
            // Selection overlay rides the same scrolled content, so the
            // highlight and handles pan pixel-locked with the text.
            val norm = selNorm
            if (selecting && norm != null) {
                val (r0, c0, r1, c1) = norm
                SelectionHighlight(
                    r0 = r0, c0 = c0, r1 = r1, c1 = c1,
                    cols = emulator.cols,
                    charW = charW, lineH = lineH, sidePadPx = sidePadPx,
                    stemPx = stemPx,
                    gridW = gridWDp, gridH = gridHDp,
                )
                // The handle under the finger always drives its own stored
                // endpoint (anchor or focus); extraction normalizes, so
                // crossing the other handle just works.
                SelHandle(
                    cxPx = sidePadPx + (c0 + 0.5f) * charW,
                    cyPx = (r0 + 1) * lineH + stemPx + dotRPx,
                    onDragContent = { x, y -> dragHandle(0, x - hScroll.value, y - vScroll.value) },
                    onDragEnd = { unlockGesture() },
                )
                SelHandle(
                    cxPx = sidePadPx + (c1 + 0.5f) * charW,
                    cyPx = (r1 + 1) * lineH + stemPx + dotRPx,
                    onDragContent = { x, y -> dragHandle(1, x - hScroll.value, y - vScroll.value) },
                    onDragEnd = { unlockGesture() },
                )
            }
        }
        // Copy/Paste float above the selection (below it when there is no
        // room), centered in the viewport. The offset lambda reads the scroll
        // in the layout phase, so the bar tracks panning with zero
        // recomposition — scrolling stays pure GPU translation. The pill
        // carries an opaque container: transparent buttons vanish against
        // terminal text. Paste sends the clipboard snapshot taken when the
        // selection started, then clears (back to typing); Copy keeps the
        // selection for adjusting.
        if (selecting && selNorm != null && (selText.isNotBlank() || pasteText.isNotEmpty())) {
            val (r0, _, r1, _) = selNorm
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHigh,
                    tonalElevation = 6.dp,
                    modifier = Modifier.offset {
                        val btnH = with(density) { 56.dp.toPx() }
                        val topVp = r0 * lineH - vScroll.value
                        val botVp = (r1 + 1) * lineH - vScroll.value
                        val y = (if (topVp > btnH + 8) topVp - btnH - 8 else botVp + 8)
                            .coerceIn(0f, (vpHState.value - btnH).coerceAtLeast(0f))
                        IntOffset(0, y.roundToInt())
                    },
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        if (selText.isNotBlank()) {
                            Button(onClick = { onCopyState.value(selText) }) { Text("Copy") }
                        }
                        if (pasteText.isNotEmpty()) {
                            OutlinedButton(
                                onClick = {
                                    onPasteState.value(pasteText)
                                    clearSelection()
                                    tapCount = 0
                                },
                            ) { Text("Paste") }
                        }
                    }
                }
            }
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
    gridW: Dp,
    gridH: Dp,
    fontFamily: FontFamily,
) {
    val measurer = rememberTextMeasurer()
    val style = terminalTextStyle(fontFamily, fontSp)
    // The grid mutates in place (same emulator reference), so the Canvas
    // node is keyed by tick: a new node per gated redraw guarantees the
    // frame is never served stale, regardless of lambda caching.
    key(tick) {
        Canvas(modifier = Modifier.size(gridW, gridH)) {
            drawTerminal(emuRef.value, historyRows, firstRow, rowCount, measurer, style, charW, lineH, sidePadPx)
        }
    }
}

/**
 * Cursor overlay: block/beam/underline at the live cursor cell, drawn over
 * the normally-rendered grid (same content size, so it pans pixel-locked).
 * Keyed by tick like the grid canvas: cursor moves always accompany a
 * version bump, and blink toggles recompose only this node.
 */
@Composable
private fun CursorOverlay(
    tick: Long,
    emulator: TerminalEmulator,
    historyRows: Int,
    cursor: TerminalCursor,
    fontSp: Float,
    fontFamily: FontFamily,
    charW: Float,
    lineH: Float,
    sidePadPx: Float,
    gridW: Dp,
    gridH: Dp,
) {
    val measurer = rememberTextMeasurer()
    val style = terminalTextStyle(fontFamily, fontSp)
    key(tick) {
        Canvas(modifier = Modifier.size(gridW, gridH)) {
            val cx = emulator.cursorX.coerceIn(0, (emulator.cols - 1).coerceAtLeast(0))
            val cy = (historyRows + emulator.cursorY).coerceAtLeast(0)
            val cell = emulator.cellAt(cx, emulator.cursorY)
            val fg = Color(cell.fg)
            val x0 = sidePadPx + cx * charW
            val y0 = cy * lineH
            when (cursor) {
                TerminalCursor.BEAM -> {
                    val w = 2.dp.toPx().coerceAtLeast(2f)
                    drawRect(fg, topLeft = Offset(x0, y0), size = Size(w, lineH))
                }
                TerminalCursor.UNDERLINE -> {
                    val h = (lineH * 0.12f).coerceAtLeast(2f)
                    drawRect(fg, topLeft = Offset(x0, y0 + lineH - h), size = Size(charW, h))
                }
                TerminalCursor.BLOCK -> {
                    drawRect(fg, topLeft = Offset(x0, y0), size = Size(charW, lineH))
                    val ch = cell.ch
                    if (ch != ' ') {
                        drawText(
                            textMeasurer = measurer,
                            text = ch.toString(),
                            topLeft = Offset(x0, y0),
                            style = style.copy(color = Color(cell.bg)),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Selection highlight: cheap rect-only overlay on its own node, so handle
 * drags redraw rects without re-laying any terminal text. Same content
 * size as the terminal canvas, so it pans pixel-locked with the text.
 * Also draws the handle stems (dots are draggable composables below).
 */
@Composable
private fun SelectionHighlight(
    r0: Int, c0: Int, r1: Int, c1: Int,
    cols: Int,
    charW: Float, lineH: Float, sidePadPx: Float,
    stemPx: Float,
    gridW: Dp, gridH: Dp,
) {
    val hl = androidx.compose.material3.MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
    val stem = androidx.compose.material3.MaterialTheme.colorScheme.primary
    Canvas(modifier = Modifier.size(gridW, gridH)) {
        for (r in r0..r1) {
            val from = if (r == r0) c0 else 0
            val to = if (r == r1) c1 else cols - 1
            drawRect(
                color = hl,
                topLeft = Offset(sidePadPx + from * charW, r * lineH),
                size = Size((to - from + 1) * charW, lineH),
            )
        }
        val sw = 2.dp.toPx()
        val sx = sidePadPx + (c0 + 0.5f) * charW
        drawLine(stem, Offset(sx, (r0 + 1) * lineH), Offset(sx, (r0 + 1) * lineH + stemPx), sw)
        val ex = sidePadPx + (c1 + 0.5f) * charW
        drawLine(stem, Offset(ex, (r1 + 1) * lineH), Offset(ex, (r1 + 1) * lineH + stemPx), sw)
    }
}

/**
 * Selection dot with a 48dp touch target. Immediate drag: the press already
 * belongs to the handle (the empty-area detector locked scroll at down and
 * bailed), so the first slop-cross starts moving the endpoint — no second
 * hold needed. Incremental deltas drive the point, so mid-drag
 * recompositions that move this box under the finger never jump.
 */
@Composable
private fun SelHandle(
    cxPx: Float,
    cyPx: Float,
    onDragContent: (Float, Float) -> Unit,
    onDragEnd: () -> Unit = {},
) {
    val density = LocalDensity.current
    val touchPx = with(density) { 48.dp.toPx() }
    val posState = rememberUpdatedState(Offset(cxPx, cyPx))
    val cbState = rememberUpdatedState(onDragContent)
    val endState = rememberUpdatedState(onDragEnd)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .offset { IntOffset((cxPx - touchPx / 2).roundToInt(), (cyPx - touchPx / 2).roundToInt()) }
            .size(with(density) { touchPx.toDp() })
            .pointerInput(Unit) {
                var cur = Offset.Zero
                var dragging = false
                detectDragGestures(
                    onDragStart = { startPos ->
                        // Absolute seed (not the dot center): the finger
                        // already traveled the touch slop to get here, and
                        // ignoring it offsets every drag ~1 line. Deltas
                        // after this stay immune to the box moving under
                        // the finger on recomposition.
                        val half = touchPx / 2
                        cur = Offset(
                            posState.value.x - half + startPos.x,
                            posState.value.y - half + startPos.y,
                        )
                        dragging = true
                    },
                    onDrag = { change, dragAmount ->
                        if (dragging) {
                            cur += dragAmount
                            cbState.value(cur.x, cur.y)
                        }
                        change.consume()
                    },
                    onDragEnd = { dragging = false; endState.value() },
                    onDragCancel = { dragging = false; endState.value() },
                )
            },
    ) {
        Box(
            Modifier.size(14.dp).background(
                androidx.compose.material3.MaterialTheme.colorScheme.primary,
                CircleShape,
            ),
        )
    }
}

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
    val total = historyRows + emulator.rows
    val endRow = minOf(firstRow + rowCount, total).coerceAtLeast(firstRow)
    // Backdrop covers the drawn window only (parent page is already black,
    // so undrawn rows show black through the transparent canvas). Uses the
    // session palette so non-black scheme backgrounds blend, not seam.
    if (endRow > firstRow) {
        drawRect(
            Color(emulator.paletteBg),
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
            if (runStart >= 0 && runBg != emulator.paletteBg) {
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
        val rowText = buildAnnotatedString {
            var spanFg = 0
            var spanBold = false
            var spanOpen = false
            for (x in 0 until cols) {
                val cell = if (inHistory) emulator.historyCell(i, x) else emulator.cellAt(x, gy)
                val bg = cell?.bg ?: emulator.paletteBg
                if (runStart < 0 || bg != runBg) {
                    flushRun(x)
                    runStart = x
                    runBg = bg
                }
                val ch = cell?.ch ?: ' '
                if (ch != ' ') allSpace = false
                val fgc = cell?.fg ?: emulator.paletteFg
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
