package id.web.izs.sshclient

import id.web.izs.sshclient.core.term.MouseAction
import id.web.izs.sshclient.core.term.MouseButton
import id.web.izs.sshclient.core.term.MouseEvt
import id.web.izs.sshclient.core.term.MouseReporter
import id.web.izs.sshclient.core.term.TerminalEmulator
import org.junit.Assert.*
import org.junit.Test

/**
 * xterm.js CoreMouseService parity for touch -> mouse reports:
 * protocol restrict, cell debounce, DEFAULT/SGR encodings, range gate.
 */
class MouseReportTest {
    private val esc = 27.toChar()

    private fun term(cols: Int = 20, rows: Int = 6) = TerminalEmulator(cols, rows)

    /** Expected 3-byte-default report: ESC [ M Pb Px Py (1-based coords). */
    private fun def(code: Int, col1: Int, row1: Int): ByteArray =
        byteArrayOf(
            0x1B, '['.code.toByte(), 'M'.code.toByte(),
            (code + 32).toByte(), (col1 + 32).toByte(), (row1 + 32).toByte(),
        )

    private fun sgr(code: Int, col1: Int, row1: Int, end: Char): ByteArray =
        "$esc[<${code};${col1};${row1}$end".toByteArray(Charsets.US_ASCII)

    @Test
    fun `no active protocol reports nothing`() {
        val r = MouseReporter(term())
        assertNull(r.report(MouseEvt(0, 0, MouseButton.LEFT, MouseAction.DOWN)))
        assertNull(r.report(MouseEvt(1, 1, MouseButton.LEFT, MouseAction.MOVE)))
    }

    @Test
    fun `vt200 reports press and release, default encoding`() {
        val t = term()
        t.feed("$esc[?1000h")
        val r = MouseReporter(t)
        assertArrayEquals(def(0, 1, 1), r.report(MouseEvt(0, 0, MouseButton.LEFT, MouseAction.DOWN)))
        // Release collapses to NONE (3) outside SGR.
        assertArrayEquals(def(3, 1, 1), r.report(MouseEvt(0, 0, MouseButton.LEFT, MouseAction.UP)))
        // Motion is restricted under VT200.
        assertNull(r.report(MouseEvt(1, 1, MouseButton.LEFT, MouseAction.MOVE)))
    }

    @Test
    fun `sgr encoding keeps the button on release and reports wheel`() {
        val t = term()
        t.feed("$esc[?1000h$esc[?1006h")
        val r = MouseReporter(t)
        assertArrayEquals(sgr(0, 1, 1, 'M'), r.report(MouseEvt(0, 0, MouseButton.LEFT, MouseAction.DOWN)))
        assertArrayEquals(sgr(0, 1, 1, 'm'), r.report(MouseEvt(0, 0, MouseButton.LEFT, MouseAction.UP)))
        assertArrayEquals(sgr(64, 3, 4, 'M'), r.report(MouseEvt(2, 3, MouseButton.WHEEL, MouseAction.UP)))
        assertArrayEquals(sgr(65, 3, 4, 'M'), r.report(MouseEvt(2, 3, MouseButton.WHEEL, MouseAction.DOWN)))
    }

    @Test
    fun `x10 reports press only`() {
        val t = term()
        t.feed("$esc[?9h")
        val r = MouseReporter(t)
        assertArrayEquals(def(0, 2, 3), r.report(MouseEvt(1, 2, MouseButton.LEFT, MouseAction.DOWN)))
        assertNull(r.report(MouseEvt(1, 2, MouseButton.LEFT, MouseAction.UP)))
        assertNull(r.report(MouseEvt(1, 2, MouseButton.LEFT, MouseAction.MOVE)))
        assertNull(r.report(MouseEvt(1, 2, MouseButton.WHEEL, MouseAction.UP)))
    }

    @Test
    fun `drag reports held motion but never free move`() {
        val t = term()
        t.feed("$esc[?1002h")
        val r = MouseReporter(t)
        // Held motion: button | 32 = 0 | 32.
        assertArrayEquals(def(32, 2, 2), r.report(MouseEvt(1, 1, MouseButton.LEFT, MouseAction.MOVE)))
        assertNull(r.report(MouseEvt(1, 1, MouseButton.NONE, MouseAction.MOVE)))
        assertArrayEquals(def(0, 2, 2), r.report(MouseEvt(1, 1, MouseButton.LEFT, MouseAction.DOWN)))
        assertArrayEquals(def(3, 2, 2), r.report(MouseEvt(1, 1, MouseButton.LEFT, MouseAction.UP)))
        assertArrayEquals(def(64, 2, 2), r.report(MouseEvt(1, 1, MouseButton.WHEEL, MouseAction.UP)))
    }

    @Test
    fun `move debounces per cell`() {
        val t = term()
        t.feed("$esc[?1003h")
        val r = MouseReporter(t)
        val first = r.report(MouseEvt(5, 5, MouseButton.NONE, MouseAction.MOVE))
        assertNotNull(first)
        // Same cell, same button: suppressed.
        assertNull(r.report(MouseEvt(5, 5, MouseButton.NONE, MouseAction.MOVE)))
        // New cell: reported again.
        assertNotNull(r.report(MouseEvt(6, 5, MouseButton.NONE, MouseAction.MOVE)))
        // Same cell but a different button: reported (not a duplicate).
        assertNotNull(r.report(MouseEvt(6, 5, MouseButton.LEFT, MouseAction.MOVE)))
    }

    @Test
    fun `default encoding suppresses past addressable range, sgr does not`() {
        val t = term(cols = 250, rows = 6)
        t.feed("$esc[?1000h")
        val r = MouseReporter(t)
        // col 223 -> 1-based 224 -> +32 = 256: no single byte left.
        assertNull(r.report(MouseEvt(223, 0, MouseButton.LEFT, MouseAction.DOWN)))
        t.feed("$esc[?1006h")
        assertNotNull(r.report(MouseEvt(223, 0, MouseButton.LEFT, MouseAction.DOWN)))
    }

    @Test
    fun `cells outside the grid never report`() {
        val t = term(cols = 10, rows = 4)
        t.feed("$esc[?1003h")
        val r = MouseReporter(t)
        assertNull(r.report(MouseEvt(-1, 0, MouseButton.NONE, MouseAction.MOVE)))
        assertNull(r.report(MouseEvt(0, 4, MouseButton.NONE, MouseAction.MOVE)))
        assertNull(r.report(MouseEvt(10, 0, MouseButton.NONE, MouseAction.MOVE)))
    }

    @Test
    fun `nonsense button-action combos are dropped`() {
        val t = term()
        t.feed("$esc[?1003h")
        val r = MouseReporter(t)
        assertNull(r.report(MouseEvt(0, 0, MouseButton.WHEEL, MouseAction.MOVE)))
        assertNull(r.report(MouseEvt(0, 0, MouseButton.NONE, MouseAction.DOWN)))
        assertNull(r.report(MouseEvt(0, 0, MouseButton.LEFT, MouseAction.LEFT)))
    }

    @Test
    fun `disabling the mode or resetting clears reporting`() {
        val t = term()
        t.feed("$esc[?1002h$esc[?1006h")
        assertEquals(id.web.izs.sshclient.core.term.MouseProtocol.DRAG, t.mouseProtocol)
        assertTrue(t.mouseSgr)
        t.feed("$esc[?1002l$esc[?1006l")
        assertEquals(id.web.izs.sshclient.core.term.MouseProtocol.NONE, t.mouseProtocol)
        assertFalse(t.mouseSgr)
        t.feed("$esc[?1000h$esc[?1006h")
        t.resetTerminalModes()
        assertEquals(id.web.izs.sshclient.core.term.MouseProtocol.NONE, t.mouseProtocol)
        assertFalse(t.mouseSgr)
    }
}
