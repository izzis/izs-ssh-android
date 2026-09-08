package id.web.izs.sshclient.core.term

/**
 * A pragmatic VT100/xterm-subset terminal emulator (pure Kotlin, no Android
 * deps, fully unit-tested). Powers the real shell screen: colored output,
 * cursor addressing (vim/htop), and the alternate screen buffer.
 *
 * Covered: printable cells, BS/HT/LF/CR, SGR colors (16/256/truecolor,
 * bold, inverse), cursor moves (A-H, E-G, d, s/u, M), erase (J/K/X),
 * insert/delete lines/chars (L/M/P/@), scroll (S/T) + margins (r),
 * wrap (with pending-wrap), show/hide cursor (?25), alt buffer (?1049).
 * Ignored: OSC title, charsets, scroll-region origin mode, wide chars
 * (treated as single cells), visual bell, bracketed paste, mouse.
 */
class TerminalEmulator(cols: Int = 80, rows: Int = 24) {

    data class Cell(var ch: Char = ' ', var fg: Int = FG, var bg: Int = BG, var bold: Boolean = false)

    companion object {
        const val FG = 0xFFE8E8E8.toInt()
        // Pure black: blends the grid with the full-bleed page background
        // (xterm theme in tabby-android uses #000000 too).
        const val BG = 0xFF000000.toInt()
        const val MAX_HISTORY = 2000

        private val STD = intArrayOf(
            0xFF000000.toInt(), 0xFFCD0000.toInt(), 0xFF00CD00.toInt(), 0xFFCDCD00.toInt(),
            0xFF0000EE.toInt(), 0xFFCD00CD.toInt(), 0xFF00CDCD.toInt(), 0xFFE5E5E5.toInt(),
            0xFF7F7F7F.toInt(), 0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFFFFFF00.toInt(),
            0xFF5C5CFF.toInt(), 0xFFFF00FF.toInt(), 0xFF00FFFF.toInt(), 0xFFFFFFFF.toInt(),
        )

        fun color256(n: Int): Int = when {
            n < 0 || n > 255 -> FG
            n < 16 -> STD[n]
            n < 232 -> {
                val v = n - 16
                val r = v / 36
                val g = (v % 36) / 6
                val b = v % 6
                fun comp(c: Int) = if (c == 0) 0 else 55 + 40 * c
                (0xFF shl 24) or (comp(r) shl 16) or (comp(g) shl 8) or comp(b)
            }
            else -> {
                val g = 8 + 10 * (n - 232)
                (0xFF shl 24) or (g shl 16) or (g shl 8) or g
            }
        }
    }

    private fun blank() = Cell()
    private fun newGrid(c: Int, r: Int) = Array(r) { Array(c) { blank() } }

    var cols: Int = cols
        private set
    var rows: Int = rows
        private set

    private var primary = newGrid(cols, rows)
    private var alt = newGrid(cols, rows)
    private val history = ArrayDeque<Array<Cell>>()

    /**
     * Scrollback cap in lines (Settings > Terminal). 0 disables history.
     * Lowering trims immediately. History rows keep their original width —
     * the view pads rows narrower than the current grid.
     */
    var maxHistory: Int = MAX_HISTORY
        set(v) {
            field = v.coerceIn(0, 100_000)
            while (history.size > field) history.removeFirst()
        }

    /** Lines currently held in scrollback (0 while the alt buffer is up). */
    fun historyRowCount(): Int = if (altActive) 0 else history.size

    /** A scrollback cell, or null past the row's stored width (renders blank). */
    fun historyCell(row: Int, x: Int): Cell? =
        if (altActive) null else history.getOrNull(row)?.getOrNull(x)

    private var grid: Array<Array<Cell>> = primary
    var altActive: Boolean = false
        private set

    var cursorX: Int = 0
        private set
    var cursorY: Int = 0
        private set
    var showCursor: Boolean = true
        private set

    private var fg = FG
    private var bg = BG
    private var bold = false
    private var inverse = false
    private var wrapEnabled = true
    private var wrapPending = false
    private var topMargin = 0
    private var bottomMargin = rows - 1
    private var savedX = 0
    private var savedY = 0

    /** Bumped per feed() call; the UI recomposes the Canvas on change. */
    var version: Long = 0L
        private set

    fun cellAt(x: Int, y: Int): Cell = grid[y.coerceIn(0, rows - 1)][x.coerceIn(0, cols - 1)]

    /** Plain-text snapshot (history + current grid) for copy-to-clipboard. */
    fun plainText(): String = buildString {
        for (row in history) {
            append(row.map { it.ch }.joinToString("").trimEnd())
            append('\n')
        }
        for (row in grid) {
            append(row.map { it.ch }.joinToString("").trimEnd())
            append('\n')
        }
    }

    /**
     * Dynamic resize (window-change parity): rebuild both buffers keeping
     * the overlapping top-left content, clamp the cursor, reset scroll
     * margins to full. History is kept as-is (rows may be narrower).
     */
    fun resize(newCols: Int, newRows: Int) {
        val c = newCols.coerceIn(20, 256)
        val r = newRows.coerceIn(8, 64)
        if (c == cols && r == rows) return
        fun move(old: Array<Array<Cell>>): Array<Array<Cell>> {
            val fresh = Array(r) { Array(c) { blank() } }
            for (y in 0 until minOf(r, rows)) {
                for (x in 0 until minOf(c, cols)) {
                    fresh[y][x] = old[y][x]
                }
            }
            return fresh
        }
        primary = move(primary)
        alt = move(alt)
        grid = if (altActive) alt else primary
        cols = c
        rows = r
        topMargin = 0
        bottomMargin = rows - 1
        cursorX = cursorX.coerceIn(0, cols - 1)
        cursorY = cursorY.coerceIn(0, rows - 1)
        savedX = savedX.coerceIn(0, cols - 1)
        savedY = savedY.coerceIn(0, rows - 1)
        wrapPending = false
        version++
    }

    // ---- input ----

    private enum class State { GROUND, ESC, ESC_SKIP, CSI, OSC, OSC_ESC }

    private var state = State.GROUND
    private var csiParams = StringBuilder()
    private var csiPrivate = false

    fun feed(s: String) {
        if (s.isEmpty()) return
        for (c in s) process(c)
        version++
    }

    private fun process(c: Char) {
        when (state) {
            State.GROUND -> when {
                c == '\u001B' -> state = State.ESC
                c == '\u0000' || c == '\u0007' -> Unit
                c == '\b' -> {
                    wrapPending = false
                    if (cursorX > 0) cursorX--
                }
                c == '\t' -> advanceTab()
                c == '\n' -> {
                    wrapPending = false
                    lineFeed()
                }
                c == '\r' -> {
                    wrapPending = false
                    cursorX = 0
                }
                c == '\u007F' -> Unit
                c >= ' ' -> putChar(c)
                // Other C0/C1 controls ignored.
            }
            State.ESC -> when (c) {
                '[' -> {
                    state = State.CSI
                    csiParams = StringBuilder()
                    csiPrivate = false
                }
                ']' -> state = State.OSC
                '(', ')', '#' -> state = State.ESC_SKIP
                'M' -> {
                    state = State.GROUND
                    reverseIndex()
                }
                '7' -> {
                    state = State.GROUND
                    savedX = cursorX
                    savedY = cursorY
                }
                '8' -> {
                    state = State.GROUND
                    cursorX = savedX.coerceIn(0, cols - 1)
                    cursorY = savedY.coerceIn(0, rows - 1)
                }
                'c' -> {
                    state = State.GROUND
                    reset()
                }
                else -> state = State.GROUND
            }
            State.CSI -> when {
                c == '?' && csiParams.isEmpty() -> csiPrivate = true
                c in '0'..'9' || c == ';' -> csiParams.append(c)
                c in '@'..'~' -> {
                    state = State.GROUND
                    dispatchCsi(c)
                }
                else -> state = State.GROUND
            }
            State.OSC -> when {
                c == '\u0007' -> state = State.GROUND
                c == '\u001B' -> state = State.OSC_ESC
            }
            State.ESC_SKIP -> state = State.GROUND
            State.OSC_ESC -> state = State.GROUND
        }
    }

    private fun params(default: Int): List<Int> {
        if (csiParams.isEmpty()) return listOf(default)
        return csiParams.toString().split(';').map { it.toIntOrNull() ?: default }
    }

    private fun dispatchCsi(final: Char) {
        wrapPending = false
        val p = params(1)
        fun n(i: Int) = p.getOrElse(i) { 1 }.coerceAtLeast(1)
        when (final) {
            'A' -> cursorY = (cursorY - n(0)).coerceAtLeast(topMargin)
            'B' -> cursorY = (cursorY + n(0)).coerceAtMost(bottomMargin)
            'C' -> cursorX = (cursorX + n(0)).coerceAtMost(cols - 1)
            'D' -> cursorX = (cursorX - n(0)).coerceAtLeast(0)
            'E' -> {
                cursorY = (cursorY + n(0)).coerceAtMost(bottomMargin)
                cursorX = 0
            }
            'F' -> {
                cursorY = (cursorY - n(0)).coerceAtLeast(topMargin)
                cursorX = 0
            }
            'G' -> cursorX = (n(0) - 1).coerceIn(0, cols - 1)
            'H', 'f' -> {
                cursorY = (n(0) - 1).coerceIn(topMargin, bottomMargin)
                cursorX = (n(1) - 1).coerceIn(0, cols - 1)
            }
            'd' -> cursorY = (n(0) - 1).coerceIn(topMargin, bottomMargin)
            'J' -> when (params(0)[0]) {
                0 -> eraseRange(cursorX, cursorY, cols - 1, rows - 1)
                1 -> eraseRange(0, 0, cursorX, cursorY)
                2 -> clearGrid()
                3 -> {
                    clearGrid()
                    history.clear()
                }
            }
            'K' -> when (params(0)[0]) {
                0 -> eraseRange(cursorX, cursorY, cols - 1, cursorY)
                1 -> eraseRange(0, cursorY, cursorX, cursorY)
                2 -> eraseRange(0, cursorY, cols - 1, cursorY)
            }
            'X' -> {
                val count = n(0)
                for (i in 0 until count) {
                    val x = cursorX + i
                    if (x < cols) grid[cursorY][x] = blank()
                }
            }
            'S' -> repeat(n(0)) { scrollUp() }
            'T' -> repeat(n(0)) { scrollDown() }
            'L' -> repeat(n(0)) { insertLines() }
            'M' -> if (csiPrivate) Unit else repeat(n(0)) { deleteLines() }
            'P' -> repeat(1) {
                val count = n(0)
                val row = grid[cursorY]
                for (i in cursorX until cols) {
                    row[i] = if (i + count < cols) row[i + count] else blank()
                }
            }
            '@' -> {
                val count = n(0)
                val row = grid[cursorY]
                for (i in cols - 1 downTo cursorX) {
                    row[i] = if (i - count >= cursorX) row[i - count] else blank()
                }
            }
            's' -> {
                savedX = cursorX
                savedY = cursorY
            }
            'u' -> {
                cursorX = savedX.coerceIn(0, cols - 1)
                cursorY = savedY.coerceIn(0, rows - 1)
            }
            'r' -> {
                val q = params(0)
                val top = (q.getOrElse(0) { 1 }) - 1
                val bottom = (q.getOrElse(1) { rows }) - 1
                if (top in 0 until rows && bottom in 0 until rows && top < bottom) {
                    topMargin = top
                    bottomMargin = bottom
                    cursorX = 0
                    cursorY = topMargin
                } else {
                    topMargin = 0
                    bottomMargin = rows - 1
                }
            }
            'm' -> sgr(if (csiParams.isEmpty()) listOf(0) else csiParams.toString().split(';').map { it.toIntOrNull() ?: 0 })
            'h' -> if (csiPrivate) setPrivate(params(0)[0], true)
            'l' -> if (csiPrivate) setPrivate(params(0)[0], false)
        }
    }

    private fun setPrivate(code: Int, on: Boolean) {
        when (code) {
            25 -> showCursor = on
            7 -> wrapEnabled = on
            1049 -> if (on) {
                savedX = cursorX
                savedY = cursorY
                altActive = true
                grid = alt
                clearGrid()
                cursorX = 0
                cursorY = 0
            } else {
                altActive = false
                grid = primary
                cursorX = savedX.coerceIn(0, cols - 1)
                cursorY = savedY.coerceIn(0, rows - 1)
            }
        }
    }

    private fun sgr(codes: List<Int>) {
        var i = 0
        while (i < codes.size) {
            when (val c = codes[i]) {
                0 -> {
                    fg = FG
                    bg = BG
                    bold = false
                    inverse = false
                }
                1 -> bold = true
                22 -> bold = false
                7 -> inverse = true
                27 -> inverse = false
                in 30..37 -> fg = STD[c - 30]
                39 -> fg = FG
                in 40..47 -> bg = STD[c - 40]
                49 -> bg = BG
                in 90..97 -> fg = STD[c - 90 + 8]
                in 100..107 -> bg = STD[c - 100 + 8]
                38, 48 -> {
                    val isFg = c == 38
                    val mode = codes.getOrElse(i + 1) { -1 }
                    if (mode == 5 && i + 2 < codes.size) {
                        val color = color256(codes[i + 2])
                        if (isFg) fg = color else bg = color
                        i += 2
                    } else if (mode == 2 && i + 4 < codes.size) {
                        val r = codes[i + 2].coerceIn(0, 255)
                        val g = codes[i + 3].coerceIn(0, 255)
                        val b = codes[i + 4].coerceIn(0, 255)
                        val color = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                        if (isFg) fg = color else bg = color
                        i += 4
                    } else {
                        i++
                    }
                }
            }
            i++
        }
    }

    // ---- grid ops ----

    private fun curCell(): Cell {
        val f = if (inverse) bg else fg
        val b = if (inverse) fg else bg
        return Cell(fg = f, bg = b, bold = bold)
    }

    private fun putChar(c: Char) {
        if (wrapPending) {
            // Consume the pending wrap exactly once: without this reset,
            // EVERY following char wrapped again (vertical single-char
            // columns on any line longer than the grid).
            wrapPending = false
            cursorX = 0
            lineFeed()
        }
        val cell = curCell()
        cell.ch = c
        grid[cursorY][cursorX] = cell
        if (cursorX == cols - 1) {
            wrapPending = wrapEnabled
        } else {
            cursorX++
        }
    }

    private fun advanceTab() {
        wrapPending = false
        cursorX = ((cursorX / 8) + 1) * 8
        if (cursorX >= cols) cursorX = cols - 1
    }

    private fun lineFeed() {
        if (cursorY == bottomMargin) scrollUp()
        else cursorY = (cursorY + 1).coerceAtMost(rows - 1)
    }

    private fun reverseIndex() {
        wrapPending = false
        if (cursorY == topMargin) scrollDown()
        else cursorY = (cursorY - 1).coerceAtLeast(0)
    }

    private fun scrollUp() {
        val fullScreen = topMargin == 0 && bottomMargin == rows - 1
        if (!altActive && fullScreen) {
            history.addLast(grid[0])
            while (history.size > maxHistory) history.removeFirst()
        }
        for (y in topMargin until bottomMargin) {
            grid[y] = grid[y + 1]
        }
        grid[bottomMargin] = Array(cols) { blank() }
    }

    private fun scrollDown() {
        for (y in bottomMargin downTo topMargin + 1) {
            grid[y] = grid[y - 1]
        }
        grid[topMargin] = Array(cols) { blank() }
    }

    private fun insertLines() {
        for (y in bottomMargin downTo cursorY + 1) {
            grid[y] = grid[y - 1]
        }
        grid[cursorY] = Array(cols) { blank() }
    }

    private fun deleteLines() {
        for (y in cursorY until bottomMargin) {
            grid[y] = grid[y + 1]
        }
        grid[bottomMargin] = Array(cols) { blank() }
    }

    private fun eraseRange(x0: Int, y0: Int, x1: Int, y1: Int) {
        for (y in y0..y1) {
            if (y !in 0 until rows) continue
            val from = if (y == y0) x0.coerceIn(0, cols - 1) else 0
            val to = if (y == y1) x1.coerceIn(0, cols - 1) else cols - 1
            for (x in from..to) grid[y][x] = blank()
        }
    }

    private fun clearGrid() {
        for (y in 0 until rows) {
            for (x in 0 until cols) grid[y][x] = blank()
        }
    }

    private fun reset() {
        fg = FG
        bg = BG
        bold = false
        inverse = false
        wrapEnabled = true
        wrapPending = false
        topMargin = 0
        bottomMargin = rows - 1
        altActive = false
        grid = primary
        history.clear()
        clearGrid()
        for (row in alt) for (i in row.indices) row[i] = blank()
        cursorX = 0
        cursorY = 0
        showCursor = true
    }
}
