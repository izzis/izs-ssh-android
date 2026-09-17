package id.web.izs.sshclient

import id.web.izs.sshclient.core.config.RawConfigStore
import id.web.izs.sshclient.core.config.asStringMap
import id.web.izs.sshclient.core.term.TerminalEmulator
import id.web.izs.sshclient.ui.screens.preparePaste
import id.web.izs.sshclient.ui.screens.trimPasted
import org.junit.Assert.*
import org.junit.Test

/**
 * Desktop clipboard parity (tabby-terminal Settings > Terminal > Clipboard,
 * BaseTerminalTab.paste, xtermFrontend): the 4 synced YAML keys with
 * desktop defaults + delete-on-default, the ?2004 bracketed-paste tracking,
 * and the pure paste-funnel halves (fold, replace, strip, trim).
 * `copyOnSelect`/`copyAsHTML` are intentionally NOT synced.
 */
class ClipboardParityTest {
    private val esc = 27.toChar()

    private fun termDoc(vararg pairs: Pair<String, Any?>): LinkedHashMap<String, Any?> =
        linkedMapOf("version" to 1, "terminal" to linkedMapOf(*pairs))

    // ---- YAML reads: absent = desktop default ----

    @Test
    fun `absent keys fall back to desktop defaults`() {
        val doc = linkedMapOf<String, Any?>("version" to 1)
        assertTrue(RawConfigStore.terminalBracketedPaste(doc))
        assertTrue(RawConfigStore.terminalWarnOnMultilinePaste(doc))
        assertFalse(RawConfigStore.terminalReplaceNewlinesWithSpacesOnPaste(doc))
        assertTrue(RawConfigStore.terminalTrimWhitespaceOnPaste(doc))
    }

    @Test
    fun `present values honored`() {
        val doc = termDoc(
            "bracketedPaste" to false,
            "warnOnMultilinePaste" to false,
            "replaceNewlinesWithSpacesOnPaste" to true,
            "trimWhitespaceOnPaste" to false,
        )
        assertFalse(RawConfigStore.terminalBracketedPaste(doc))
        assertFalse(RawConfigStore.terminalWarnOnMultilinePaste(doc))
        assertTrue(RawConfigStore.terminalReplaceNewlinesWithSpacesOnPaste(doc))
        assertFalse(RawConfigStore.terminalTrimWhitespaceOnPaste(doc))
    }

    @Test
    fun `non-boolean garbage falls back to default`() {
        val doc = termDoc("bracketedPaste" to "yes please")
        assertTrue(RawConfigStore.terminalBracketedPaste(doc))
    }

    // ---- YAML writes: minimal YAML, delete-on-default + prune ----

    @Test
    fun `setting non-default persists the key`() {
        val doc = linkedMapOf<String, Any?>("version" to 1)
        RawConfigStore.setTerminalBracketedPaste(doc, false)
        assertFalse(RawConfigStore.terminalBracketedPaste(doc))
    }

    @Test
    fun `setting default removes the key`() {
        val doc = termDoc("bracketedPaste" to false)
        RawConfigStore.setTerminalBracketedPaste(doc, true)
        val term = doc["terminal"] as? Map<*, *>
        assertTrue(term == null || !term.containsKey("bracketedPaste"))
    }

    @Test
    fun `empty terminal map is pruned`() {
        val doc = termDoc("warnOnMultilinePaste" to false)
        RawConfigStore.setTerminalWarnOnMultilinePaste(doc, true)
        assertFalse(doc.containsKey("terminal"))
    }

    @Test
    fun `sibling keys survive a default write`() {
        val doc = termDoc(
            "bracketedPaste" to false,
            "replaceNewlinesWithSpacesOnPaste" to true,
        )
        RawConfigStore.setTerminalBracketedPaste(doc, true)
        val term = doc["terminal"].asStringMap()!!
        assertFalse(term.containsKey("bracketedPaste"))
        assertEquals(true, term["replaceNewlinesWithSpacesOnPaste"])
    }

    // ---- ?2004 bracketed-paste tracking ----

    @Test
    fun `bracketed mode off until shell enables 2004`() {
        val t = TerminalEmulator(20, 6)
        assertFalse(t.supportsBracketedPaste())
        t.feed("$esc[?2004h")
        assertTrue(t.supportsBracketedPaste())
        t.feed("$esc[?2004l")
        assertFalse(t.supportsBracketedPaste())
    }

    @Test
    fun `resetTerminalModes clears stale bracketed flag`() {
        val t = TerminalEmulator(20, 6)
        t.feed("$esc[?2004h")
        t.resetTerminalModes()
        assertFalse(t.supportsBracketedPaste())
    }

    @Test
    fun `full reset clears bracketed flag`() {
        val t = TerminalEmulator(20, 6)
        t.feed("$esc[?2004h")
        t.feed("${esc}c")
        assertFalse(t.supportsBracketedPaste())
    }

    @Test
    fun `alternate screen tracks 1049`() {
        val t = TerminalEmulator(20, 6)
        assertFalse(t.isAlternateScreenActive())
        t.feed("$esc[?1049h")
        assertTrue(t.isAlternateScreenActive())
        t.feed("$esc[?1049l")
        assertFalse(t.isAlternateScreenActive())
    }

    // ---- preparePaste (fold, replace, desktop-exact strip) ----

    @Test
    fun `plain text is not multiline`() {
        val p = preparePaste("anaconda-ks.cfg", replaceNewlines = false, trim = true)
        assertEquals("anaconda-ks.cfg", p.data)
        assertFalse(p.isMultiline)
    }

    @Test
    fun `single line with trailing newline still warns like desktop`() {
        // Desktop-exact: the step-2 strip checks '\n' only, which never
        // survives the fold — so the trailing '\r' keeps it multiline and
        // the confirm dialog appears.
        val p = preparePaste("cmd\n", replaceNewlines = false, trim = true)
        assertEquals("cmd\r", p.data)
        assertTrue(p.isMultiline)
    }

    @Test
    fun `crlf folds to a single cr`() {
        val p = preparePaste("a\r\nb", replaceNewlines = false, trim = true)
        assertEquals("a\rb", p.data)
        assertTrue(p.isMultiline)
    }

    @Test
    fun `replace flattens to one line`() {
        val p = preparePaste("a\nb\r\nc", replaceNewlines = true, trim = true)
        assertEquals("a b c", p.data)
        assertFalse(p.isMultiline)
    }

    @Test
    fun `no trim keeps trailing newline`() {
        val p = preparePaste("cmd\n", replaceNewlines = false, trim = false)
        assertEquals("cmd\r", p.data)
        assertTrue(p.isMultiline)
    }

    @Test
    fun `empty stays empty`() {
        val p = preparePaste("", replaceNewlines = false, trim = true)
        assertEquals("", p.data)
        assertFalse(p.isMultiline)
    }

    // ---- trimPasted (step-4 trim) ----

    @Test
    fun `single line trims both ends`() {
        assertEquals("abc", trimPasted("  abc  "))
    }

    @Test
    fun `trailing newline trimmed`() {
        assertEquals("abc", trimPasted("abc\r"))
    }

    @Test
    fun `multiline trims end only`() {
        assertEquals("  a\rb", trimPasted("  a\rb  "))
    }
}
