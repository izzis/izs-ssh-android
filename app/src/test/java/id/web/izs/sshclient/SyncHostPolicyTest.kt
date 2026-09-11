package id.web.izs.sshclient

import id.web.izs.sshclient.core.config.RawConfigStore
import org.junit.Assert.*
import org.junit.Test

/**
 * Sync cleartext policy: https:// always allowed, http:// only for local
 * targets (loopback, RFC 1918, link-local, .local-style, single-label LAN).
 * Public http:// must be refused so tokens + YAML never cross the internet
 * in cleartext. Enforced by TabbySyncApi on every request.
 */
class SyncHostPolicyTest {

    @Test
    fun `https is always allowed`() {
        assertTrue(RawConfigStore.isSyncHostAllowed("https://example.com"))
        assertTrue(RawConfigStore.isSyncHostAllowed("https://192.168.1.1:8443/api"))
        assertTrue(RawConfigStore.isSyncHostAllowed("HTTPS://EXAMPLE.COM/"))
    }

    @Test
    fun `non-http schemes are refused`() {
        assertFalse(RawConfigStore.isSyncHostAllowed("example.com"))
        assertFalse(RawConfigStore.isSyncHostAllowed("ftp://example.com"))
        assertFalse(RawConfigStore.isSyncHostAllowed(""))
    }

    @Test
    fun `public http is refused`() {
        assertFalse(RawConfigStore.isSyncHostAllowed("http://example.com"))
        assertFalse(RawConfigStore.isSyncHostAllowed("http://8.8.8.8"))
        assertFalse(RawConfigStore.isSyncHostAllowed("http://1.2.3.4:8080/api"))
        // Looks-private prefix but is a public DNS name.
        assertFalse(RawConfigStore.isSyncHostAllowed("http://10.0.0.1.evil.com"))
        assertFalse(RawConfigStore.isSyncHostAllowed("http://192.168.1.1.evil.com"))
        assertFalse(RawConfigStore.isSyncHostAllowed("http://172.16.0.1.evil.com"))
        // Just outside the 172.16/12 range.
        assertFalse(RawConfigStore.isSyncHostAllowed("http://172.15.0.1"))
        assertFalse(RawConfigStore.isSyncHostAllowed("http://172.32.0.1"))
        assertFalse(RawConfigStore.isSyncHostAllowed("http://203.0.113.7"))
    }

    @Test
    fun `loopback http is allowed`() {
        assertTrue(RawConfigStore.isSyncHostAllowed("http://localhost:8080"))
        assertTrue(RawConfigStore.isSyncHostAllowed("http://127.0.0.1"))
        assertTrue(RawConfigStore.isSyncHostAllowed("http://127.1.2.3:5000/api"))
        assertTrue(RawConfigStore.isSyncHostAllowed("http://[::1]:8080"))
    }

    @Test
    fun `rfc1918 http is allowed`() {
        assertTrue(RawConfigStore.isSyncHostAllowed("http://10.0.0.5"))
        assertTrue(RawConfigStore.isSyncHostAllowed("http://10.255.255.1:8080/x"))
        assertTrue(RawConfigStore.isSyncHostAllowed("http://192.168.1.1"))
        assertTrue(RawConfigStore.isSyncHostAllowed("http://192.168.0.100:5000/"))
        assertTrue(RawConfigStore.isSyncHostAllowed("http://172.16.0.9"))
        assertTrue(RawConfigStore.isSyncHostAllowed("http://172.31.255.254"))
        assertTrue(RawConfigStore.isSyncHostAllowed("http://172.20.4.2:8080"))
        assertTrue(RawConfigStore.isSyncHostAllowed("http://169.254.10.20"))
    }

    @Test
    fun `local names are allowed`() {
        assertTrue(RawConfigStore.isSyncHostAllowed("http://nas:5000"))
        assertTrue(RawConfigStore.isSyncHostAllowed("http://server.local"))
        assertTrue(RawConfigStore.isSyncHostAllowed("http://printer.lan:631/"))
        assertTrue(RawConfigStore.isSyncHostAllowed("http://box.home"))
        assertTrue(RawConfigStore.isSyncHostAllowed("http://svc.internal"))
        assertTrue(RawConfigStore.isSyncHostAllowed("http://user@nas:5000/x"))
        assertFalse(RawConfigStore.isSyncHostAllowed("http://nas.example.com"))
    }

    @Test
    fun `ipv6 local http is allowed`() {
        assertTrue(RawConfigStore.isSyncHostAllowed("http://[fd00::1]:8080"))
        assertTrue(RawConfigStore.isSyncHostAllowed("http://[fe80::1]"))
        assertFalse(RawConfigStore.isSyncHostAllowed("http://[2001:db8::1]"))
    }
}
