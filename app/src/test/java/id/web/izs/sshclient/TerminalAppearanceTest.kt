package id.web.izs.sshclient

import id.web.izs.sshclient.core.config.RawConfigStore
import id.web.izs.sshclient.core.config.asMutableStringMap
import id.web.izs.sshclient.core.config.SOURCE_CODE_PRO_YAML_NAME
import id.web.izs.sshclient.core.config.SchemeSource
import id.web.izs.sshclient.core.config.TerminalCursor
import id.web.izs.sshclient.core.config.TerminalFont
import id.web.izs.sshclient.core.config.isFallbackScheme
import id.web.izs.sshclient.core.config.parseTerminalCursor
import id.web.izs.sshclient.core.config.resolveTerminalFont
import id.web.izs.sshclient.core.config.terminalCursorYamlName
import id.web.izs.sshclient.core.config.terminalFontYamlName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalAppearanceTest {

    @Test fun resolveFont_absentIsSystem() {
        assertEquals(TerminalFont.SYSTEM, resolveTerminalFont(null))
        assertEquals(TerminalFont.SYSTEM, resolveTerminalFont("  "))
    }

    @Test fun resolveFont_sourceCodePro() {
        assertEquals(TerminalFont.SOURCE_CODE_PRO, resolveTerminalFont("Source Code Pro"))
        assertEquals(TerminalFont.SOURCE_CODE_PRO, resolveTerminalFont("source code pro"))
    }

    @Test fun resolveFont_unknownDesktopFontIsSystem() {
        // Never fatal, never rewritten: rendered as system monospace here.
        assertEquals(TerminalFont.SYSTEM, resolveTerminalFont("JetBrains Mono"))
        assertEquals(TerminalFont.SYSTEM, resolveTerminalFont("Menlo"))
    }

    @Test fun fontYamlName_systemIsAbsent() {
        assertNull(terminalFontYamlName(TerminalFont.SYSTEM))
        assertEquals(SOURCE_CODE_PRO_YAML_NAME, terminalFontYamlName(TerminalFont.SOURCE_CODE_PRO))
    }

    @Test fun parseCursor_allShapes() {
        assertEquals(TerminalCursor.BLOCK, parseTerminalCursor("block"))
        assertEquals(TerminalCursor.BEAM, parseTerminalCursor("beam"))
        assertEquals(TerminalCursor.UNDERLINE, parseTerminalCursor("underline"))
        assertEquals(TerminalCursor.BEAM, parseTerminalCursor("BEAM"))
    }

    @Test fun parseCursor_garbageFallsBackToBlock() {
        assertEquals(TerminalCursor.BLOCK, parseTerminalCursor(null))
        assertEquals(TerminalCursor.BLOCK, parseTerminalCursor(""))
        assertEquals(TerminalCursor.BLOCK, parseTerminalCursor("bar"))
    }

    @Test fun cursorYamlName_desktopSpelling() {
        assertEquals("block", terminalCursorYamlName(TerminalCursor.BLOCK))
        assertEquals("beam", terminalCursorYamlName(TerminalCursor.BEAM))
        assertEquals("underline", terminalCursorYamlName(TerminalCursor.UNDERLINE))
    }

    @Test fun yamlFont_setRemoveAndEmptyMap() {
        val doc = linkedMapOf<String, Any?>()
        RawConfigStore.setTerminalFont(doc, SOURCE_CODE_PRO_YAML_NAME)
        assertEquals(SOURCE_CODE_PRO_YAML_NAME, RawConfigStore.terminalFontName(doc))
        // SYSTEM removes the key — and the terminal map when left empty.
        RawConfigStore.setTerminalFont(doc, null)
        assertNull(RawConfigStore.terminalFontName(doc))
        assertFalse(doc.containsKey(RawConfigStore.KEY_TERMINAL))
    }

    @Test fun yamlCursor_explicitPersistsEvenWhenDefault() {
        val doc = linkedMapOf<String, Any?>()
        // Absent = desktop default (block), like showRecentProfiles.
        assertEquals(TerminalCursor.BLOCK, RawConfigStore.terminalCursor(doc))
        RawConfigStore.setTerminalCursor(doc, TerminalCursor.BEAM)
        assertEquals(TerminalCursor.BEAM, RawConfigStore.terminalCursor(doc))
        RawConfigStore.setTerminalCursor(doc, TerminalCursor.BLOCK)
        assertEquals("block", (doc[RawConfigStore.KEY_TERMINAL] as Map<*, *>)["cursor"])
    }

    @Test fun yamlBlink_defaultTrueGarbageSafe() {        val doc = linkedMapOf<String, Any?>()
        assertTrue(RawConfigStore.terminalCursorBlink(doc))
        RawConfigStore.setTerminalCursorBlink(doc, false)
        assertFalse(RawConfigStore.terminalCursorBlink(doc))
        val term = doc[RawConfigStore.KEY_TERMINAL].asMutableStringMap()!!
        term["cursorBlink"] = "yes"
        doc[RawConfigStore.KEY_TERMINAL] = term
        assertTrue(RawConfigStore.terminalCursorBlink(doc))
    }

    @Test fun fallback_allNullChain() {
        assertTrue(isFallbackScheme(null, null, SchemeSource.SYNCED, null))
        assertTrue(isFallbackScheme(null, null, SchemeSource.LOCAL, null))
    }

    @Test fun fallback_anyExplicitValueWins() {
        val s = id.web.izs.sshclient.core.config.IZS_DEFAULT_SCHEME
        assertFalse(isFallbackScheme(s, null, SchemeSource.SYNCED, null))
        assertFalse(isFallbackScheme(null, s, SchemeSource.SYNCED, null))
        assertFalse(isFallbackScheme(null, null, SchemeSource.LOCAL, s))
        // Cross-mode values are ignored, like resolveActiveScheme.
        assertTrue(isFallbackScheme(null, s, SchemeSource.LOCAL, null))
        assertTrue(isFallbackScheme(null, null, SchemeSource.SYNCED, s))
    }

    @Test fun lightFallback_parsesAndDiffersFromDark() {
        val light = id.web.izs.sshclient.core.config.IZS_DEFAULT_LIGHT_SCHEME
        val dark = id.web.izs.sshclient.core.config.IZS_DEFAULT_SCHEME
        // Same ANSI colors, light chrome.
        assertEquals(dark.colors, light.colors)
        assertEquals("#ffffff", light.background)
        // Readable: dark fg on light bg (desktop minimumContrastRatio is 4).
        val fg = id.web.izs.sshclient.core.config.schemeColorArgb(light.foreground)!!
        val bg = id.web.izs.sshclient.core.config.schemeColorArgb(light.background)!!
        assertTrue(
            id.web.izs.sshclient.core.config.contrastRatio(fg, bg) >= 4.0,
        )
    }
}
