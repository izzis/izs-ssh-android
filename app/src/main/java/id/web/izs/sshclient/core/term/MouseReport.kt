package id.web.izs.sshclient.core.term

/**
 * Mouse tracking protocols (xterm.js CoreMouseService DEFAULT_PROTOCOLS
 * parity): which event types an active `CSI ? 9/1000/1002/1003 h` mode
 * accepts. NONE = no reporting (touch keeps its local gestures).
 */
enum class MouseProtocol { NONE, X10, VT200, DRAG, ANY }

/** xterm.js CoreMouseButton parity — the bit layout feeds eventCode. */
object MouseButton {
    const val LEFT = 0
    const val MIDDLE = 1
    const val RIGHT = 2
    const val NONE = 3
    const val WHEEL = 4
}

/** xterm.js CoreMouseAction parity (LEFT/RIGHT are wheel-only). */
object MouseAction {
    const val UP = 0
    const val DOWN = 1
    const val LEFT = 2
    const val RIGHT = 3
    const val MOVE = 32
}

/**
 * One pointer event for [MouseReporter]: grid cell in 0-based coords
 * (the encoder applies the 1-based report offset), button/action as
 * [MouseButton]/[MouseAction]. Modifiers are absent — touch has none
 * (Tabby parity, shift-drag always selects locally instead).
 */
data class MouseEvt(
    val col: Int,
    val row: Int,
    val button: Int,
    val action: Int,
)

/**
 * Port of xterm.js CoreMouseService.triggerMouseEvent: range check,
 * nonsense-combo filter, cell-level move debounce, per-protocol restrict,
 * then DEFAULT (`CSI M Pb Px Py`) or SGR (`CSI < Pb ; Px ; Py M|m`)
 * encoding driven by the emulator's live `?1002/?1006` state. Returns
 * null when nothing goes out — restricted, debounced, or (DEFAULT only)
 * above the addressable 223-cell range, exactly like the source.
 */
class MouseReporter(private val emulator: TerminalEmulator) {

    private val esc = 27.toChar()

    /** Last REPORTED event, 1-based (xterm.js _lastEvent parity). */
    private var last: MouseEvt? = null

    fun report(e: MouseEvt): ByteArray? {
        val protocol = emulator.mouseProtocol
        if (protocol == MouseProtocol.NONE) return null
        if (e.col !in 0 until emulator.cols || e.row !in 0 until emulator.rows) return null
        if (e.button == MouseButton.WHEEL && e.action == MouseAction.MOVE) return null
        if (e.button == MouseButton.NONE && e.action != MouseAction.MOVE) return null
        if (e.button != MouseButton.WHEEL &&
            (e.action == MouseAction.LEFT || e.action == MouseAction.RIGHT)
        ) return null
        // Reports are 1-based (CoreMouseService does e.col++/e.row++ before
        // debounce, restrict and encode).
        val n = e.copy(col = e.col + 1, row = e.row + 1)
        if (e.action == MouseAction.MOVE && last == n) return null
        val allowed = when (protocol) {
            MouseProtocol.NONE -> false
            MouseProtocol.X10 -> e.button != MouseButton.WHEEL && e.action == MouseAction.DOWN
            MouseProtocol.VT200 -> e.action != MouseAction.MOVE
            MouseProtocol.DRAG -> !(e.action == MouseAction.MOVE && e.button == MouseButton.NONE)
            MouseProtocol.ANY -> true
        }
        if (!allowed) return null
        val bytes = if (emulator.mouseSgr) encodeSgr(n) else encodeDefault(n)
        last = n
        return bytes
    }

    /**
     * `CSI M Pb Px Py`: single byte per value, so nothing above 255 fits —
     * xterm.js suppresses the whole report in that case (vte/konsole do
     * the same; xterm itself sends 0;0).
     */
    private fun encodeDefault(e: MouseEvt): ByteArray? {
        val params = intArrayOf(eventCode(e, isSgr = false) + 32, e.col + 32, e.row + 32)
        if (params.any { it > 255 }) return null
        return byteArrayOf(
            0x1B, '['.code.toByte(), 'M'.code.toByte(),
            params[0].toByte(), params[1].toByte(), params[2].toByte(),
        )
    }

    /** `CSI < Pb ; Px ; Py M|m`: unbounded coords, real release reports. */
    private fun encodeSgr(e: MouseEvt): ByteArray {
        val code = eventCode(e, isSgr = true)
        val end = if (e.action == MouseAction.UP && e.button != MouseButton.WHEEL) 'm' else 'M'
        return "$esc[<${code};${e.col};${e.row}$end".toByteArray(Charsets.US_ASCII)
    }

    /** Modifier + button + action packing (modifiers always 0 here). */
    private fun eventCode(e: MouseEvt, isSgr: Boolean): Int {
        var code = 0
        if (e.button == MouseButton.WHEEL) {
            // Wheel: 64 | direction (UP=0 -> 64, DOWN=1 -> 65).
            code = 64 or e.action
        } else {
            code = e.button and 3
            if (e.button and 4 != 0) code = code or 64
            if (e.button and 8 != 0) code = code or 128
            when {
                e.action == MouseAction.MOVE -> code = code or MouseAction.MOVE
                // Only SGR can report the button on release; the 3-byte
                // default encoding has to collapse to NONE (3).
                e.action == MouseAction.UP && !isSgr -> code = code or MouseButton.NONE
            }
        }
        return code
    }
}
