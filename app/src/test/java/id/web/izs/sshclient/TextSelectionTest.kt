package id.web.izs.sshclient

import id.web.izs.sshclient.core.term.TerminalEmulator
import id.web.izs.sshclient.core.term.TerminalEmulator.SelPoint
import org.junit.Assert.*
import org.junit.Test

/** Long-press text selection: extraction, clamping, word expansion. */
class TextSelectionTest {
    private fun term(cols: Int = 20, rows: Int = 6) = TerminalEmulator(cols, rows)

    @Test
    fun `single row partial selection`() {
        val t = term()
        t.feed("hello world")
        assertEquals("lo wo", t.selectedText(SelPoint(0, 3), SelPoint(0, 7)))
    }

    @Test
    fun `reversed points select the same text`() {
        val t = term()
        t.feed("hello world")
        assertEquals(
            t.selectedText(SelPoint(0, 0), SelPoint(0, 4)),
            t.selectedText(SelPoint(0, 4), SelPoint(0, 0)),
        )
    }

    @Test
    fun `multi row selection trims line ends`() {
        val t = term()
        t.feed("ab\r\ncdef\r\n12")
        // Trailing blanks of the first line never leak into the copy.
        assertEquals("ab\ncdef\n12", t.selectedText(SelPoint(0, 0), SelPoint(2, 1)))
    }

    @Test
    fun `out of range points clamp to content`() {
        val t = term()
        t.feed("hi")
        assertEquals("hi", t.selectedText(SelPoint(-5, -5), SelPoint(99, 99)))
    }

    @Test
    fun `selection reaches into scrollback history`() {
        val t = term(cols = 10, rows = 2)
        t.feed("aaa\r\nbbb\r\nccc\r\nddd\r\n")
        // Two oldest lines have scrolled into history (rows=2).
        assertTrue(t.historyRowCount() >= 2)
        val all = t.selectedText(SelPoint(0, 0), SelPoint(t.totalRowCount() - 1, 9))
        assertTrue(all.contains("aaa"))
        assertTrue(all.contains("ddd"))
    }

    @Test
    fun `absolute rows stay stable under scroll`() {
        val t = term(cols = 10, rows = 2)
        t.feed("aaa\r\nbbb\r\n")
        val rowOfBbb = (0 until t.totalRowCount()).first { t.textAtRow(it).startsWith("bbb") }
        t.feed("ccc\r\nddd\r\n")
        // Grid scrolled up by D AND history grew by D: the two cancel, so a
        // stored selection handle needs no shifting to stay on its text.
        assertEquals("bbb", t.textAtRow(rowOfBbb).substring(0, 3))
        assertEquals("bbb", t.selectedText(SelPoint(rowOfBbb, 0), SelPoint(rowOfBbb, 2)))
    }

    @Test
    fun `expandWord covers the non-blank run`() {
        val t = term()
        t.feed("ls /var/log/syslog")
        val (s, e) = t.expandWord(0, 5)
        assertEquals(3 to 17, s to e)
        assertEquals("/var/log/syslog", t.selectedText(SelPoint(0, s), SelPoint(0, e)))
    }

    @Test
    fun `expandWord on blank selects just itself`() {
        val t = term()
        t.feed("ab cd")
        assertEquals(2 to 2, t.expandWord(0, 2))
        assertTrue(t.selectedText(SelPoint(0, 2), SelPoint(0, 2)).isBlank())
    }
}
