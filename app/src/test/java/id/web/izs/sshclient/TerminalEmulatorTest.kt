package id.web.izs.sshclient

import id.web.izs.sshclient.core.term.TerminalEmulator
import org.junit.Assert.*
import org.junit.Test

/** VT100/xterm-subset coverage for the shell screen emulator. */
class TerminalEmulatorTest {
    private val esc = 27.toChar()

    private fun term(cols: Int = 20, rows: Int = 6) = TerminalEmulator(cols, rows)

    private fun rowText(t: TerminalEmulator, y: Int): String =
        (0 until t.cols).map { t.cellAt(it, y).ch }.joinToString("").trimEnd()

    @Test
    fun `plain text writes and wraps`() {
        val t = term(cols = 5, rows = 2)
        t.feed("abcdef")
        assertEquals("abcde", rowText(t, 0))
        assertEquals("f", rowText(t, 1))
    }

    @Test
    fun `wrap consumes pending exactly once (long lines stay horizontal)`() {
        val t = term(cols = 5, rows = 3)
        // Regression: without consuming wrapPending, every char after the
        // first wrap wrapped again (vertical single-char columns).
        t.feed("abcdefghi")
        assertEquals("abcde", rowText(t, 0))
        assertEquals("fghi", rowText(t, 1))
        assertEquals("", rowText(t, 2))
    }

    @Test
    fun `cr lf and backspace`() {
        val t = term()
        t.feed("ab")
        t.feed("\bX\rYZ\r\n12")
        assertEquals("YZ", rowText(t, 0))
        assertEquals("12", rowText(t, 1))
    }

    @Test
    fun `sgr colors and reset`() {
        val t = term()
        t.feed("$esc[31mR$esc[1;42mG$esc[0mN")
        assertEquals(0xFFCD0000.toInt(), t.cellAt(0, 0).fg)
        assertEquals(0xFFCD0000.toInt(), t.cellAt(1, 0).fg)
        assertEquals(0xFF00CD00.toInt(), t.cellAt(1, 0).bg)
        assertTrue(t.cellAt(1, 0).bold)
        assertEquals(TerminalEmulator.FG, t.cellAt(2, 0).fg)
        assertEquals(TerminalEmulator.BG, t.cellAt(2, 0).bg)
        assertFalse(t.cellAt(2, 0).bold)
    }

    @Test
    fun `sgr bright and 256 and truecolor`() {
        val t = term()
        t.feed("$esc[93mY$esc[38;5;200mP$esc[48;2;10;20;30mT")
        assertEquals(0xFFFFFF00.toInt(), t.cellAt(0, 0).fg)
        assertEquals(TerminalEmulator.color256(200), t.cellAt(1, 0).fg)
        assertEquals((0xFF shl 24) or (10 shl 16) or (20 shl 8) or 30, t.cellAt(2, 0).bg)
    }

    @Test
    fun `cursor addressing and erase`() {
        val t = term()
        t.feed("hello")
        t.feed("$esc[1;1HAB")
        assertEquals("ABllo", rowText(t, 0))
        t.feed("$esc[2K")
        assertEquals("", rowText(t, 0))
        t.feed("$esc[1;1Hxy$esc[1;5H12$esc[A$esc[C$esc[DZ")
        assertEquals("xy  12Z", rowText(t, 0))
    }

    @Test
    fun `erase display and cursor visibility`() {
        val t = term()
        t.feed("hi\nthere")
        t.feed("$esc[2J$esc[H")
        assertEquals("", rowText(t, 0))
        assertEquals("", rowText(t, 1))
        assertEquals(0, t.cursorX)
        assertEquals(0, t.cursorY)
        t.feed("$esc[?25l")
        assertFalse(t.showCursor)
        t.feed("$esc[?25h")
        assertTrue(t.showCursor)
    }

    @Test
    fun `alt buffer isolates fullscreen apps`() {
        val t = term()
        t.feed("shell-line")
        t.feed("$esc[?1049h")
        assertTrue(t.altActive)
        t.feed("$esc[Hhtop-view")
        assertEquals("htop-view", rowText(t, 0))
        t.feed("$esc[?1049l")
        assertFalse(t.altActive)
        assertEquals("shell-line", rowText(t, 0))
    }

    @Test
    fun `scroll pushes history and insert delete lines`() {
        val t = term(cols = 4, rows = 3)
        t.feed("a1\r\nb2\r\nc3\r\nd4")
        assertEquals("b2", rowText(t, 0))
        assertEquals("c3", rowText(t, 1))
        assertEquals("d4", rowText(t, 2))
        assertTrue(t.plainText().contains("a1"))
        t.feed("$esc[2;1H$esc[L")
        assertEquals("b2", rowText(t, 0))
        assertEquals("", rowText(t, 1))
        assertEquals("c3", rowText(t, 2))
    }

    @Test
    fun `osc title is swallowed not printed`() {
        val t = term()
        t.feed("$esc]0;my-title${7.toChar()}OK")
        assertEquals("OK", rowText(t, 0))
    }

    @Test
    fun `version bumps per feed`() {
        val t = term()
        val v0 = t.version
        t.feed("x")
        assertTrue(t.version > v0)
    }

    @Test
    fun `resize keeps overlapping content and clamps cursor`() {
        val t = term(cols = 40, rows = 20)
        t.feed("0123456789\r\nabcdefghij")
        t.resize(30, 10)
        assertEquals(30, t.cols)
        assertEquals(10, t.rows)
        assertEquals("0123456789", rowText(t, 0))
        assertEquals("abcdefghij", rowText(t, 1))
        t.feed("$esc[10;25H")
        assertEquals(24, t.cursorX)
        assertEquals(9, t.cursorY)
        // Grow again: old content stays, new space is blank.
        t.resize(40, 20)
        assertEquals("0123456789", rowText(t, 0))
        assertEquals("", rowText(t, 15))
        // No-op resize does not bump the version.
        val v = t.version
        t.resize(40, 20)
        assertEquals(v, t.version)
    }

    @Test
    fun `resize clamps to sane bounds`() {
        val t = term()
        t.resize(1, 2)
        assertEquals(20, t.cols)
        assertEquals(8, t.rows)
        t.resize(999, 999)
        assertEquals(256, t.cols)
        assertEquals(64, t.rows)
    }
}
