package id.web.izs.sshclient

import id.web.izs.sshclient.core.config.IZS_DEFAULT_SCHEME
import id.web.izs.sshclient.core.config.RawConfigStore
import id.web.izs.sshclient.core.config.SchemeSource
import id.web.izs.sshclient.core.config.TABBY_DEFAULT_SCHEME
import id.web.izs.sshclient.core.config.TerminalColorScheme
import id.web.izs.sshclient.core.config.argbToHex
import id.web.izs.sshclient.core.config.contrastRatio
import id.web.izs.sshclient.core.config.deleteCustomByName
import id.web.izs.sshclient.core.config.effectiveTerminalScheme
import id.web.izs.sshclient.core.config.normalizeSchemeColor
import id.web.izs.sshclient.core.config.parseSchemeJson
import id.web.izs.sshclient.core.config.parseSchemeSource
import id.web.izs.sshclient.core.config.parseTerminalColorScheme
import id.web.izs.sshclient.core.config.resolveActiveScheme
import id.web.izs.sshclient.core.config.schemeColorArgb
import id.web.izs.sshclient.core.config.schemeReadabilityIssues
import id.web.izs.sshclient.core.config.toJsonString
import id.web.izs.sshclient.core.config.toRawMap
import id.web.izs.sshclient.core.config.upsertCustom
import id.web.izs.sshclient.ui.screens.shadeSteps
import id.web.izs.sshclient.core.term.TerminalEmulator
import org.junit.Assert.*
import org.junit.Test

/** Color scheme model, YAML plumbing, resolution order, emulator palette. */
class ColorSchemeTest {
    private val esc = 27.toChar()

    private fun schemeMap() = linkedMapOf<String, Any?>(
        "name" to "Test",
        "foreground" to "#CACACA",
        "background" to "#171717",
        "cursor" to "#BBBBBB",
        "colors" to (0..15).map { "#%06x".format(it * 0x111111) },
        "selection" to "#88888888",
    )

    @Test fun parseValidNormalizes() {
        val s = parseTerminalColorScheme(schemeMap())!!
        assertEquals("Test", s.name)
        assertEquals("#cacaca", s.foreground)
        assertEquals(16, s.colors.size)
        assertEquals("#88888888", s.selection)
        assertNull(s.selectionForeground)
    }

    @Test fun parseRejectsBadShapes() {
        assertNull(parseTerminalColorScheme(null))
        assertNull(parseTerminalColorScheme("nope"))
        assertNull(parseTerminalColorScheme(schemeMap() - "name"))
        assertNull(parseTerminalColorScheme(schemeMap() + ("foreground" to "red")))
        assertNull(parseTerminalColorScheme(schemeMap() + ("colors" to listOf("#000000"))))
        assertNull(parseTerminalColorScheme(schemeMap() + ("colors" to (0..15).map { "zzz" })))
    }

    @Test fun normalizeAndArgb() {
        assertEquals("#aabbcc", normalizeSchemeColor("#ABC"))
        assertEquals("#aabbcc", normalizeSchemeColor("  #AABBCC "))
        assertEquals("#aabbccdd", normalizeSchemeColor("#aabbccdd"))
        assertNull(normalizeSchemeColor("red"))
        assertNull(normalizeSchemeColor("#12345"))
        assertEquals(0xFFAABBCC.toInt(), schemeColorArgb("#aabbcc"))
        assertEquals(0x80123456.toInt(), schemeColorArgb("#80123456"))
    }

    @Test fun roundTripRawMap() {
        val s = parseTerminalColorScheme(schemeMap())!!
        val back = parseTerminalColorScheme(s.toRawMap())!!
        assertEquals(s, back)
    }

    @Test fun readabilityGates() {
        // Izs Default is readable.
        assertTrue(schemeReadabilityIssues(IZS_DEFAULT_SCHEME).isEmpty())
        // C64 class: body contrast 2.26.
        val c64 = IZS_DEFAULT_SCHEME.copy(name = "C64like", foreground = "#7869c4", background = "#40318d")
        assertTrue(schemeReadabilityIssues(c64).any { it.startsWith("Low text contrast") })
        // Atom class: bright slot invisible on background.
        val atom = IZS_DEFAULT_SCHEME.copy(
            name = "Atomlike",
            colors = IZS_DEFAULT_SCHEME.colors.toMutableList().also { it[8] = "#000000" },
        )
        assertTrue(schemeReadabilityIssues(atom).any { it.contains("color8") })
        // color0 black-on-black is exempt (universal xterm convention).
        val blackZero = IZS_DEFAULT_SCHEME.copy(
            name = "ZeroBlack",
            colors = IZS_DEFAULT_SCHEME.colors.toMutableList().also { it[0] = "#000000" },
        )
        assertTrue(schemeReadabilityIssues(blackZero).isEmpty())
    }

    @Test fun contrastSanity() {
        val white = 0xFFFFFFFF.toInt()
        val black = 0xFF000000.toInt()
        assertEquals(21.0, contrastRatio(white, black), 0.01)
        assertEquals(1.0, contrastRatio(black, black), 0.001)
    }

    @Test fun resolutionOrder() {
        val profile = TABBY_DEFAULT_SCHEME
        val global = IZS_DEFAULT_SCHEME
        assertEquals(profile, effectiveTerminalScheme(profile, global))
        assertEquals(global, effectiveTerminalScheme(null, global))
        assertEquals(IZS_DEFAULT_SCHEME, effectiveTerminalScheme(null, null))
    }

    @Test fun globalYamlRoundTrip() {
        val doc = linkedMapOf<String, Any?>("version" to 1)
        assertNull(RawConfigStore.terminalColorSchemeRaw(doc))
        RawConfigStore.setTerminalColorScheme(doc, TABBY_DEFAULT_SCHEME)
        assertEquals(TABBY_DEFAULT_SCHEME, RawConfigStore.terminalColorSchemeRaw(doc))
        // Other terminal keys survive the write.
        RawConfigStore.setShowRecentProfiles(doc, 5)
        RawConfigStore.setTerminalColorScheme(doc, null)
        assertNull(RawConfigStore.terminalColorSchemeRaw(doc))
        assertEquals(5, RawConfigStore.showRecentProfiles(doc))
        // Prunes the empty terminal map.
        val bare = linkedMapOf<String, Any?>("version" to 1)
        RawConfigStore.setTerminalColorScheme(bare, TABBY_DEFAULT_SCHEME)
        RawConfigStore.setTerminalColorScheme(bare, null)
        assertFalse(bare.containsKey("terminal"))
    }

    @Test fun customYamlRoundTrip() {
        val doc = linkedMapOf<String, Any?>("version" to 1)
        assertTrue(RawConfigStore.customColorSchemesRaw(doc).isEmpty())
        RawConfigStore.setCustomColorSchemes(doc, listOf(TABBY_DEFAULT_SCHEME, IZS_DEFAULT_SCHEME))
        assertEquals(
            listOf(TABBY_DEFAULT_SCHEME, IZS_DEFAULT_SCHEME),
            RawConfigStore.customColorSchemesRaw(doc),
        )
        RawConfigStore.setCustomColorSchemes(doc, emptyList())
        assertFalse((doc["terminal"] as? Map<*, *>)?.containsKey("customColorSchemes") == true)
    }

    @Test fun toDomainReadsSchemes() {
        val doc = RawConfigStore.loadRaw(
            "version: 1\nterminal:\n  colorScheme:\n    name: G\n    foreground: '#ffffff'\n" +
                "    background: '#000000'\n    cursor: '#ffffff'\n" +
                "    colors: ['#000000', '#111111', '#222222', '#333333', '#444444', " +
                "'#555555', '#666666', '#777777', '#888888', '#999999', '#aaaaaa', " +
                "'#bbbbbb', '#cccccc', '#dddddd', '#eeeeee', '#ffffff']\n" +
                "  customColorSchemes:\n    - name: C\n      foreground: '#ffffff'\n" +
                "      background: '#000000'\n      cursor: '#ffffff'\n" +
                "      colors: ['#000000', '#111111', '#222222', '#333333', '#444444', " +
                "'#555555', '#666666', '#777777', '#888888', '#999999', '#aaaaaa', " +
                "'#bbbbbb', '#cccccc', '#dddddd', '#eeeeee', '#ffffff']\n" +
                "profiles:\n  - id: p1\n    type: ssh\n    name: P\n" +
                "    terminalColorScheme:\n      name: S\n      foreground: '#ffffff'\n" +
                "      background: '#000000'\n      cursor: '#ffffff'\n" +
                "      colors: ['#000000', '#111111', '#222222', '#333333', '#444444', " +
                "'#555555', '#666666', '#777777', '#888888', '#999999', '#aaaaaa', " +
                "'#bbbbbb', '#cccccc', '#dddddd', '#eeeeee', '#ffffff']\n" +
                "    options: {host: h}\n",
        )
        val domain = RawConfigStore.toDomain(doc)
        assertEquals("G", domain.terminalColorScheme?.name)
        assertEquals(listOf("C"), domain.customColorSchemes.map { it.name })
        assertEquals("S", domain.profiles.single().terminalColorScheme?.name)
    }

    @Test fun updateProfileMapWritesAndRemovesScheme() {
        val base = linkedMapOf<String, Any?>(
            "id" to "p1", "type" to "ssh", "name" to "P",
            "options" to linkedMapOf<String, Any?>("host" to "h"),
        )
        val p = RawConfigStore.toDomain(
            linkedMapOf<String, Any?>("profiles" to listOf(base)),
        ).profiles.single()
        val withScheme = RawConfigStore.updateProfileMap(
            base, p.copy(terminalColorScheme = TABBY_DEFAULT_SCHEME), null, null, emptyList(),
        )
        val reparsed = RawConfigStore.toDomain(
            linkedMapOf<String, Any?>("profiles" to listOf(withScheme)),
        ).profiles.single()
        assertEquals(TABBY_DEFAULT_SCHEME, reparsed.terminalColorScheme)
        val removed = RawConfigStore.updateProfileMap(withScheme, reparsed.copy(terminalColorScheme = null), null, null, emptyList())
        assertFalse(removed.containsKey("terminalColorScheme"))
    }

    @Test fun emulatorAppliesPalette() {
        val t = TerminalEmulator(20, 4)
        assertEquals(TerminalEmulator.FG, t.cellAt(0, 0).fg)
        t.setPalette(TABBY_DEFAULT_SCHEME)
        // New blanks + SGR reset use the scheme foreground/background.
        t.feed("$esc[0m ")
        val fg = schemeColorArgb("#cacaca")!!
        val bg = schemeColorArgb("#171717")!!
        assertEquals(fg, t.cellAt(0, 0).fg)
        assertEquals(bg, t.cellAt(0, 0).bg)
        // SGR red maps to the scheme's color1 (#ff615a), not the default.
        t.feed("$esc[31mX")
        assertEquals(schemeColorArgb("#ff615a")!!, t.cellAt(1, 0).fg)
        // Backdrop follows the scheme background.
        assertEquals(bg, t.paletteBg)
        assertEquals(fg, t.paletteFg)
    }

    @Test fun emulatorDefaultPaletteUnchanged() {
        // No setPalette call: pixel-identical to the pre-schemes behavior.
        val t = TerminalEmulator(20, 4)
        t.feed("$esc[31mX")
        assertEquals(TerminalEmulator.color256(1), t.cellAt(0, 0).fg)
    }

    @Test fun desktopYamlCompatibilityUntouched() {
        // Desktop-shape terminal block: light scheme + sibling keys + an
        // unknown key + a profile override with an unknown option key.
        // The main feature (desktop YAML compat) must never be disturbed:
        // our writes touch ONLY colorScheme/customColorSchemes.
        val colors = (0..15).map { "#%06x".format(it * 0x111111) }
        val doc = RawConfigStore.loadRaw(
            "version: 1\nterminal:\n  showRecentProfiles: 5\n  desktopOnly: 1\n" +
                "  colorScheme:\n    name: G\n    foreground: '#ffffff'\n" +
                "    background: '#000000'\n    cursor: '#ffffff'\n" +
                "    colors: ['#000000', '#111111', '#222222', '#333333', '#444444', " +
                "'#555555', '#666666', '#777777', '#888888', '#999999', '#aaaaaa', " +
                "'#bbbbbb', '#cccccc', '#dddddd', '#eeeeee', '#ffffff']\n" +
                "  lightColorScheme:\n    name: L\n    foreground: '#000000'\n" +
                "    background: '#ffffff'\n    cursor: '#000000'\n" +
                "    colors: ['#000000', '#111111', '#222222', '#333333', '#444444', " +
                "'#555555', '#666666', '#777777', '#888888', '#999999', '#aaaaaa', " +
                "'#bbbbbb', '#cccccc', '#dddddd', '#eeeeee', '#ffffff']\n" +
                "profiles:\n  - id: p1\n    type: ssh\n    name: P\n    fontFamily: X\n" +
                "    terminalColorScheme:\n      name: S\n      foreground: '#ffffff'\n" +
                "      background: '#000000'\n      cursor: '#ffffff'\n" +
                "      colors: ['#000000', '#111111', '#222222', '#333333', '#444444', " +
                "'#555555', '#666666', '#777777', '#888888', '#999999', '#aaaaaa', " +
                "'#bbbbbb', '#cccccc', '#dddddd', '#eeeeee', '#ffffff']\n" +
                "    options: {host: h}\n",
        )
        assertEquals(colors, RawConfigStore.terminalColorSchemeRaw(doc)?.colors)
        // Global rewrite preserves every sibling + profile extras.
        val mutable = LinkedHashMap<String, Any?>(doc)
        RawConfigStore.setTerminalColorScheme(mutable, TABBY_DEFAULT_SCHEME)
        RawConfigStore.setCustomColorSchemes(mutable, listOf(IZS_DEFAULT_SCHEME))
        val redumped = RawConfigStore.loadRaw(RawConfigStore.dumpRaw(mutable))
        @Suppress("UNCHECKED_CAST")
        val term = redumped["terminal"] as Map<String, Any?>
        assertEquals(TABBY_DEFAULT_SCHEME, RawConfigStore.terminalColorSchemeRaw(redumped))
        assertEquals(listOf(IZS_DEFAULT_SCHEME), RawConfigStore.customColorSchemesRaw(redumped))
        assertEquals(5, (term["showRecentProfiles"] as? Number)?.toInt())
        assertEquals(1, (term["desktopOnly"] as? Number)?.toInt())
        @Suppress("UNCHECKED_CAST")
        val light = term["lightColorScheme"] as Map<String, Any?>
        assertEquals("L", light["name"])
        @Suppress("UNCHECKED_CAST")
        val p = ((redumped["profiles"] as List<*>).single() as Map<String, Any?>)
        assertEquals("X", p["fontFamily"])
        assertEquals("S", parseTerminalColorScheme(p["terminalColorScheme"])?.name)
        // Removing the global restores absent (null), siblings intact.
        RawConfigStore.setTerminalColorScheme(mutable, null)
        val cleared = RawConfigStore.loadRaw(RawConfigStore.dumpRaw(mutable))
        assertNull(RawConfigStore.terminalColorSchemeRaw(cleared))
        @Suppress("UNCHECKED_CAST")
        val term2 = cleared["terminal"] as Map<String, Any?>
        assertEquals("L", (term2["lightColorScheme"] as? Map<*, *>)?.get("name"))
    }

    @Test fun argbHexRoundTrip() {
        // Regression: red must stay red (a substring(0,7) cut once turned
        // #F44336 into #FFF443 yellow by keeping the alpha digits).
        assertEquals("#f44336", argbToHex(0xFFF44336.toInt()))
        assertEquals("#000000", argbToHex(0xFF000000.toInt()))
        assertEquals("#ffffff", argbToHex(0xFFFFFFFF.toInt()))
        assertEquals("#80123456", argbToHex(0x80123456.toInt()))
        assertEquals("#88888888", argbToHex(0x88888888.toInt()))
        // Full circle through the parser.
        for (hex in listOf("#f44336", "#000000", "#ffffff", "#80123456", "#88888888")) {
            assertEquals(hex, argbToHex(schemeColorArgb(hex)!!))
        }
    }

    @Test fun shadeStepsNineLevels() {
        val base = 0xFFF44336.toInt()
        val steps = shadeSteps(base)
        assertEquals(9, steps.size)
        assertEquals(base, steps[4])
        // Dark-to-light ramp: luminance rises monotonically.
        fun lum(c: Int): Double {
            val r = ((c shr 16) and 0xFF) / 255.0
            val g = ((c shr 8) and 0xFF) / 255.0
            val b = (c and 0xFF) / 255.0
            return 0.2126 * r + 0.7152 * g + 0.0722 * b
        }
        for (i in 0 until 8) assertTrue(lum(steps[i]) < lum(steps[i + 1]))
        // All opaque.
        assertTrue(steps.all { ((it ushr 24) and 0xFF) == 255 })
    }

    @Test fun customUpsertAndDeleteByName() {
        // Desktop saveScheme/deleteScheme parity: rename + Save creates,
        // same name replaces, delete drops by name.
        val a = TABBY_DEFAULT_SCHEME
        val b = IZS_DEFAULT_SCHEME
        assertEquals(listOf(a), upsertCustom(emptyList(), a))
        assertEquals(listOf(a, b), upsertCustom(listOf(a), b))
        // Rename + Save keeps the old entry as a separate custom (desktop).
        val renamed = b.copy(name = "B2")
        assertEquals(listOf(a, b, renamed), upsertCustom(listOf(a, b), renamed))
        // Same-name replace keeps position semantics (filter + append).
        val a2 = a.copy(foreground = "#ffffff")
        assertEquals(listOf(a2), upsertCustom(listOf(a), a2))
        assertEquals(emptyList<TerminalColorScheme>(), deleteCustomByName(listOf(a), "Tabby Default"))
        assertEquals(listOf(a), deleteCustomByName(listOf(a), "Missing"))
    }

    @Test fun deviceJsonRoundTrip() {
        val s = parseTerminalColorScheme(schemeMap())!!
        assertEquals(s, parseSchemeJson(s.toJsonString()))
        assertNull(parseSchemeJson(""))
        assertNull(parseSchemeJson("{broken"))
        assertNull(parseSchemeJson("""{"name":"x"}"""))
    }

    @Test fun sourceResolution() {
        val profile = TABBY_DEFAULT_SCHEME
        val global = IZS_DEFAULT_SCHEME.copy(name = "G")
        val local = IZS_DEFAULT_SCHEME.copy(name = "L")
        // Synced: profile > global, local ignored.
        assertEquals(profile, resolveActiveScheme(profile, global, SchemeSource.SYNCED, local))
        assertEquals(global, resolveActiveScheme(null, global, SchemeSource.SYNCED, local))
        assertEquals(IZS_DEFAULT_SCHEME, resolveActiveScheme(null, null, SchemeSource.SYNCED, local))
        // Local: profile > device, synced global ignored.
        assertEquals(profile, resolveActiveScheme(profile, global, SchemeSource.LOCAL, local))
        assertEquals(local, resolveActiveScheme(null, global, SchemeSource.LOCAL, local))
        assertEquals(IZS_DEFAULT_SCHEME, resolveActiveScheme(null, global, SchemeSource.LOCAL, null))
        // Source parse lenient.
        assertEquals(SchemeSource.LOCAL, parseSchemeSource("local"))
        assertEquals(SchemeSource.SYNCED, parseSchemeSource("synced"))
        assertEquals(SchemeSource.SYNCED, parseSchemeSource(null))
        assertEquals(SchemeSource.SYNCED, parseSchemeSource("bogus"))
    }

    @Test fun emulatorRemapsOldCellsOnSwitch() {
        val t = TerminalEmulator(20, 4)
        // Old-palette content: default text, SGR red, SGR bright-black.
        t.feed("AB${esc}[31mC$esc[0m${esc}[90mD")
        val oldBg = TerminalEmulator.BG
        assertEquals(oldBg, t.cellAt(0, 0).bg)
        // Scroll row 0 into history (exercises shared-row remap coverage).
        // Scroll row 0 into history (exercises shared-row remap coverage).
        t.feed("$esc[0m\n\n\n\n")
        assertEquals(1, t.historyRowCount())
        t.setPalette(TABBY_DEFAULT_SCHEME)
        val bg = schemeColorArgb("#171717")!!
        val fg = schemeColorArgb("#cacaca")!!
        // Live blanks followed the switch...
        assertEquals(bg, t.cellAt(0, 0).bg)
        assertEquals(fg, t.cellAt(0, 0).fg)
        // ...and so did the scrollback row (default, SGR red, bright-black).
        val h0 = requireNotNull(t.historyCell(0, 0))
        assertEquals(bg, h0.bg)
        assertEquals(fg, h0.fg)
        assertEquals(schemeColorArgb("#ff615a")!!, requireNotNull(t.historyCell(0, 2)).fg)
        assertEquals(schemeColorArgb("#313131")!!, requireNotNull(t.historyCell(0, 3)).fg)
        // Switching to the identical scheme is a no-op (no corruption).
        t.setPalette(TABBY_DEFAULT_SCHEME)
        assertEquals(bg, requireNotNull(t.historyCell(0, 0)).bg)
        assertEquals(schemeColorArgb("#ff615a")!!, requireNotNull(t.historyCell(0, 2)).fg)
    }
}
