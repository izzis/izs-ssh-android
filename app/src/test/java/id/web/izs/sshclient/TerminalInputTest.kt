package id.web.izs.sshclient

import id.web.izs.sshclient.core.term.TerminalInput
import org.junit.Assert.*
import org.junit.Test

/**
 * Sticky CTRL/ALT mapping for the Termux-like extra-keys bar.
 * Assertions use char codes only — no control-char literals anywhere.
 */
class TerminalInputTest {

    private fun codeOf(text: String, ctrl: Boolean, alt: Boolean): List<Int> =
        TerminalInput.applySticky(text, ctrl, alt).map { it.code }

    @Test
    fun `plain text passes through`() {
        assertEquals("ls -la", TerminalInput.applySticky("ls -la", false, false))
        assertEquals("", TerminalInput.applySticky("", true, true))
    }

    @Test
    fun `ctrl maps letters to controls`() {
        assertEquals(listOf(0x03), codeOf("c", true, false))
        assertEquals(listOf(0x03), codeOf("C", true, false))
        assertEquals(listOf(0x1A), codeOf("z", true, false))
        assertEquals(listOf(0x04), codeOf("d", true, false))
    }

    @Test
    fun `ctrl maps symbols`() {
        assertEquals(listOf(0x00), codeOf(" ", true, false))
        assertEquals(listOf(0x1F), codeOf("_", true, false))
        assertEquals(listOf(0x1B), codeOf("[", true, false))
    }

    @Test
    fun `alt prefixes escape`() {
        assertEquals(listOf(0x1B, 'f'.code), codeOf("f", false, true))
        assertEquals(listOf(0x1B, 0x08), codeOf("h", true, true))
    }

    @Test
    fun `unmapped chars pass through under ctrl`() {
        assertEquals(listOf('5'.code), codeOf("5", true, false))
    }
}
