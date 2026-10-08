package id.web.izs.sshclient

import id.web.izs.sshclient.core.config.RawConfigStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** normalizeHost: trim, assume-https when the scheme is missing, reject the rest. */
class SyncHostNormalizeTest {
    @Test
    fun `missing scheme assumes https`() {
        assertEquals("https://sync.example.com", RawConfigStore.normalizeHost("sync.example.com"))
        assertEquals("https://192.168.1.10:8080", RawConfigStore.normalizeHost("192.168.1.10:8080"))
        assertEquals("https://sync.example.com", RawConfigStore.normalizeHost("  sync.example.com  "))
    }

    @Test
    fun `explicit schemes are kept as typed`() {
        assertEquals("http://192.168.1.10:8080", RawConfigStore.normalizeHost("http://192.168.1.10:8080"))
        assertEquals("https://sync.example.com", RawConfigStore.normalizeHost("https://sync.example.com"))
        // Scheme detection is case-insensitive (RFC 3986; Tabby's regex uses /i).
        assertEquals("HTTPS://sync.example.com", RawConfigStore.normalizeHost("HTTPS://sync.example.com"))
    }

    @Test
    fun `trailing slash and padding spaces are trimmed`() {
        assertEquals("https://sync.example.com", RawConfigStore.normalizeHost("https://sync.example.com/"))
        assertEquals("https://sync.example.com", RawConfigStore.normalizeHost("https://sync.example.com///"))
        assertEquals("https://sync.example.com", RawConfigStore.normalizeHost("sync.example.com/  "))
    }

    @Test
    fun `blank, foreign and half-typed schemes are rejected, never wrapped`() {
        assertThrows(IllegalArgumentException::class.java) { RawConfigStore.normalizeHost("") }
        assertThrows(IllegalArgumentException::class.java) { RawConfigStore.normalizeHost("   ") }
        // A foreign scheme must fail loudly — never become https://ftp://…
        assertThrows(IllegalArgumentException::class.java) { RawConfigStore.normalizeHost("ftp://files.example.com") }
        // Half a scheme ("http:/x") is a typo, not a scheme-less host.
        assertThrows(IllegalArgumentException::class.java) { RawConfigStore.normalizeHost("http:/sync.example.com") }
    }
}
