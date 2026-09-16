package id.web.izs.sshclient

import id.web.izs.sshclient.ui.AppPalettes
import id.web.izs.sshclient.ui.resolveAppPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppPaletteTest {

    @Test fun resolve_knownIds() {
        assertEquals("ocean", resolveAppPalette("ocean").id)
        assertEquals("izs", resolveAppPalette("izs").id)
    }

    @Test fun resolve_unknownFallsBackToIzs() {
        assertEquals("izs", resolveAppPalette(null).id)
        assertEquals("izs", resolveAppPalette("").id)
        assertEquals("izs", resolveAppPalette("midnight-ultra").id)
        // Removed "grape" palette id resolves to the default like garbage.
        assertEquals("izs", resolveAppPalette("grape").id)
    }

    @Test fun palettes_idsUniqueAndHaveBothModes() {
        val ids = AppPalettes.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(AppPalettes.size >= 2)
        for (p in AppPalettes) {
            // Dark and light must actually differ (else the theme toggle lies).
            assertTrue(p.name + " dark==light", p.dark.primary != p.light.primary)
        }
    }
}
