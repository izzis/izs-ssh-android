package id.web.izs.sshclient

import id.web.izs.sshclient.ui.foldBannerText
import org.junit.Assert.*
import org.junit.Test

/**
 * Auth-banner folding for the in-terminal service line: server `\n`
 * becomes terminal `\r\n`, ragged right edges are trimmed, blank edge
 * lines (the banner packet's trailing newline) are dropped.
 */
class BannerTextTest {

    @Test
    fun `multiline banner folds to terminal endings`() {
        assertEquals(
            "line one\r\nline two",
            foldBannerText("line one\nline two\n"),
        )
    }

    @Test
    fun `blank-only banner folds to empty`() {
        assertEquals("", foldBannerText("\n  \n"))
    }

    @Test
    fun `single line passes through`() {
        assertEquals("Authorized users only", foldBannerText("Authorized users only"))
    }
}
