package id.web.izs.sshclient

import id.web.izs.sshclient.core.term.DEFAULT_LAYOUT
import id.web.izs.sshclient.core.term.DEFAULT_MACRO_STEP_DELAY_MS
import id.web.izs.sshclient.core.term.KIND_MENU
import id.web.izs.sshclient.core.term.KIND_SEND
import id.web.izs.sshclient.core.term.KIND_STICKY_CTRL
import id.web.izs.sshclient.core.term.KeyDef
import id.web.izs.sshclient.core.term.KeyLayout
import id.web.izs.sshclient.core.term.KeyStep
import id.web.izs.sshclient.core.term.MOD_ALT
import id.web.izs.sshclient.core.term.MOD_CTRL
import id.web.izs.sshclient.core.term.MenuItemDef
import id.web.izs.sshclient.core.term.MACRO_PRESETS
import id.web.izs.sshclient.core.term.WIDTH_DOUBLE
import id.web.izs.sshclient.core.term.WIDTH_NORMAL
import id.web.izs.sshclient.core.term.WIDTH_WIDE
import id.web.izs.sshclient.core.term.describeStep
import id.web.izs.sshclient.core.term.loadKeyLayout
import id.web.izs.sshclient.core.term.maxLabelForWidth
import id.web.izs.sshclient.core.term.normalizeKeyLayout
import id.web.izs.sshclient.core.term.parseKeyLayout
import id.web.izs.sshclient.core.term.saveKeyLayout
import id.web.izs.sshclient.core.term.stepBytes
import id.web.izs.sshclient.core.term.stepsDisplay
import id.web.izs.sshclient.core.term.weight
import org.junit.Assert.*
import org.junit.Test

/** Extra-keys bar layout: normalization, steps, widths, persistence fallback. */
class ExtraKeyboardTest {
    private val esc = 27.toChar().toString()

    @Test
    fun `default layout survives save-load round trip`() {
        assertEquals(DEFAULT_LAYOUT, loadKeyLayout(saveKeyLayout(DEFAULT_LAYOUT)))
    }

    @Test
    fun `null blank and corrupt input fall back to default`() {
        assertEquals(DEFAULT_LAYOUT, loadKeyLayout(null))
        assertEquals(DEFAULT_LAYOUT, loadKeyLayout("  "))
        assertEquals(DEFAULT_LAYOUT, loadKeyLayout("{nope"))
        assertEquals(DEFAULT_LAYOUT, loadKeyLayout("[1,2]"))
    }

    @Test
    fun `unknown kind degrades to send, blank keys dropped`() {
        val layout = KeyLayout(
            1,
            listOf(
                listOf(
                    KeyDef("X", "mystery", steps = listOf(KeyStep("x"))),
                    KeyDef("  ", KIND_SEND, steps = listOf(KeyStep("y"))),
                    KeyDef("OK", KIND_SEND, steps = listOf(KeyStep("z"))),
                ),
            ),
        )
        val norm = normalizeKeyLayout(layout)
        assertEquals(1, norm.rows.size)
        assertEquals(
            listOf(
                KeyDef("X", KIND_SEND, steps = listOf(KeyStep("x"))),
                KeyDef("OK", KIND_SEND, steps = listOf(KeyStep("z"))),
            ),
            norm.rows[0],
        )
    }

    @Test
    fun `empty result falls back to default, never an empty bar`() {
        assertEquals(DEFAULT_LAYOUT, normalizeKeyLayout(KeyLayout(1, emptyList())))
        assertEquals(
            DEFAULT_LAYOUT,
            normalizeKeyLayout(KeyLayout(1, listOf(listOf(KeyDef("", KIND_SEND))))),
        )
    }

    @Test
    fun `sticky keys keep no payload, menu needs items`() {
        val sticky = normalizeKeyLayout(
            KeyLayout(1, listOf(listOf(KeyDef("C", KIND_STICKY_CTRL)))),
        )
        assertEquals(KeyDef("C", KIND_STICKY_CTRL), sticky.rows[0][0])
        // Menu without items is dropped -> default bar.
        val menuEmpty = normalizeKeyLayout(
            KeyLayout(1, listOf(listOf(KeyDef("M", KIND_MENU)))),
        )
        assertEquals(DEFAULT_LAYOUT, menuEmpty)
        val menu = normalizeKeyLayout(
            KeyLayout(
                1,
                listOf(
                    listOf(
                        KeyDef(
                            "M", KIND_MENU, items = listOf(
                                MenuItemDef("ls", steps = listOf(KeyStep("ls\n"))),
                                MenuItemDef("", steps = listOf(KeyStep("drop"))),
                                MenuItemDef("drop2"),
                            ),
                        ),
                    ),
                ),
            ),
        )
        assertEquals(listOf(KeyStep("ls\n")), menu.rows[0][0].items[0].steps)
    }

    @Test
    fun `width tiers map to weights, unknown falls back to normal`() {
        assertEquals(1f, KeyDef("A", KIND_SEND, width = WIDTH_NORMAL).weight())
        assertEquals(1.5f, KeyDef("A", KIND_SEND, width = WIDTH_WIDE).weight())
        assertEquals(2f, KeyDef("A", KIND_SEND, width = WIDTH_DOUBLE).weight())
        val norm = normalizeKeyLayout(
            KeyLayout(
                1,
                listOf(
                    listOf(
                        KeyDef("A", KIND_SEND, steps = listOf(KeyStep("a")), width = WIDTH_DOUBLE),
                        KeyDef("B", KIND_SEND, steps = listOf(KeyStep("b")), width = "huge"),
                    ),
                ),
            ),
        )
        assertEquals(WIDTH_DOUBLE, norm.rows[0][0].width)
        assertEquals(2f, norm.rows[0][0].weight())
        assertEquals(WIDTH_NORMAL, norm.rows[0][1].width)
    }

    @Test
    fun `label budget scales with width`() {
        assertEquals(8, maxLabelForWidth(WIDTH_NORMAL))
        assertEquals(12, maxLabelForWidth(WIDTH_WIDE))
        assertEquals(16, maxLabelForWidth(WIDTH_DOUBLE))
        assertEquals(8, maxLabelForWidth("huge"))
        val norm = normalizeKeyLayout(
            KeyLayout(
                1,
                listOf(
                    listOf(
                        KeyDef(
                            "1234567890123456", KIND_SEND,
                            steps = listOf(KeyStep("a")),
                            width = WIDTH_DOUBLE,
                        ),
                        KeyDef(
                            "1234567890123456", KIND_SEND,
                            steps = listOf(KeyStep("b")),
                            width = WIDTH_NORMAL,
                        ),
                    ),
                ),
            ),
        )
        assertEquals("1234567890123456", norm.rows[0][0].label)
        assertEquals("12345678", norm.rows[0][1].label)
    }

    @Test
    fun `explicit color keeps valid hex, drops junk`() {
        val norm = normalizeKeyLayout(
            KeyLayout(
                1,
                listOf(
                    listOf(
                        KeyDef(
                            "A", KIND_SEND, steps = listOf(KeyStep("a")),
                            color = "#FF9800",
                        ),
                        KeyDef(
                            "B", KIND_SEND, steps = listOf(KeyStep("b")),
                            color = "red",
                        ),
                    ),
                ),
            ),
        )
        assertEquals("#ff9800", norm.rows[0][0].color)
        assertEquals("", norm.rows[0][1].color)
    }

    @Test
    fun `counts and lengths are clamped`() {
        val bigRow = (1..20).map { KeyDef("K$it", KIND_SEND, steps = listOf(KeyStep("x"))) }
        val norm = normalizeKeyLayout(
            KeyLayout(1, listOf(bigRow, bigRow, bigRow, bigRow, bigRow, bigRow, bigRow)),
        )
        assertEquals(5, norm.rows.size)
        assertTrue(norm.rows.all { it.size == 8 })
        val long = normalizeKeyLayout(
            KeyLayout(1, listOf(listOf(KeyDef("1234567890", KIND_SEND, steps = listOf(KeyStep("x")))))),
        )
        assertEquals("12345678", long.rows[0][0].label)
        val manySteps = normalizeKeyLayout(
            KeyLayout(
                1,
                listOf(listOf(KeyDef("M", KIND_SEND, steps = (1..20).map { KeyStep("s$it") }))),
            ),
        )
        assertEquals(8, manySteps.rows[0][0].steps.size)
    }

    @Test
    fun `macro preset expands to ordered steps, never one blob`() {
        val vimQuit = MACRO_PRESETS.first { it.first == "VIM :q!" }.second
        assertEquals(
            listOf(
                KeyStep("ESC", preset = true),
                KeyStep(":q!"),
                KeyStep("ENTER", preset = true),
            ),
            vimQuit,
        )
        // The ordered bytes equal the classic sequence, sent packet by packet.
        assertEquals(esc, stepBytes(vimQuit[0]))
        assertEquals(":q!", stepBytes(vimQuit[1]))
        assertEquals("\r", stepBytes(vimQuit[2]))
    }

    @Test
    fun `stepBytes maps presets mods and text`() {
        assertEquals(esc + "[A", stepBytes(KeyStep("UP", preset = true)))
        assertEquals("ls", stepBytes(KeyStep("ls")))
        // CTRL single letter -> C0 control byte.
        assertEquals(3.toChar().toString(), stepBytes(KeyStep("c", MOD_CTRL)))
        assertEquals(3.toChar().toString(), stepBytes(KeyStep("C", MOD_CTRL)))
        // ALT text -> ESC-prefixed (single letters lowercased).
        assertEquals(esc + "x", stepBytes(KeyStep("X", MOD_ALT)))
        assertEquals(esc + "wq", stepBytes(KeyStep("wq", MOD_ALT)))
        // Unknown preset name degrades to literal text, never empty output.
        assertEquals("NOPE", stepBytes(KeyStep("NOPE", preset = true)))
    }

    @Test
    fun `step preview reads as key or text`() {
        assertEquals("ESC", describeStep(KeyStep("ESC", preset = true)))
        assertEquals("Ctrl+C", describeStep(KeyStep("C", MOD_CTRL)))
        assertEquals("Alt+x", describeStep(KeyStep("x", MOD_ALT)))
        assertEquals(":q!", describeStep(KeyStep(":q!")))
        assertEquals(
            "ESC :q! ENTER",
            stepsDisplay(
                listOf(
                    KeyStep("ESC", preset = true),
                    KeyStep(":q!"),
                    KeyStep("ENTER", preset = true),
                ),
            ),
        )
    }

    @Test
    fun `step delay default is sane`() {
        assertEquals(120L, DEFAULT_MACRO_STEP_DELAY_MS)
    }

    @Test
    fun `strict parse reports failure for import`() {
        assertNull(parseKeyLayout(""))
        assertNull(parseKeyLayout("nope"))
        assertNotNull(parseKeyLayout(saveKeyLayout(DEFAULT_LAYOUT)))
    }

    @Test
    fun `stored form is canonical`() {
        val messy = KeyLayout(
            99,
            listOf(
                listOf(
                    KeyDef(
                        "  padded  ", KIND_SEND,
                        steps = listOf(KeyStep("x")),
                        width = WIDTH_WIDE,
                    ),
                    KeyDef(
                        "M", KIND_MENU,
                        items = listOf(MenuItemDef("a", steps = listOf(KeyStep("b")))),
                    ),
                ),
            ),
        )
        val once = saveKeyLayout(messy)
        assertEquals(once, saveKeyLayout(loadKeyLayout(once)))
        val reloaded = loadKeyLayout(once)
        assertEquals(1, reloaded.v)
        assertEquals("padded", reloaded.rows[0][0].label)
        assertEquals(WIDTH_WIDE, reloaded.rows[0][0].width)
        assertEquals(1, reloaded.rows[0][1].items.size)
    }
}
