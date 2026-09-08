package id.web.izs.sshclient

import id.web.izs.sshclient.core.vault.VaultCrypto
import org.junit.Assert.*
import org.junit.Test

/**
 * Vault crypto parity tests (desktop vault.service.ts).
 * Pure JVM — VaultCrypto uses java.util.Base64, no Android dependency.
 */
class VaultCryptoTest {

    @Test
    fun `encrypt then decrypt round-trips the payload`() {
        val configJson = """{"version":1,"profiles":[{"id":"ssh:abc","type":"ssh"}]}"""
        val secretsJson = """[{"type":"ssh:password","key":{"user":"root"},"value":"s3cr3t"}]"""
        val vault = VaultCrypto.encrypt(configJson, secretsJson, "correct horse")
        assertEquals(1, vault.version)
        // 8-byte salt -> 16 hex chars; 16-byte IV -> 32 hex chars
        assertEquals(16, vault.keySalt.length)
        assertEquals(32, vault.iv.length)
        assertTrue(vault.contents.isNotBlank())

        val (cfg, sec) = VaultCrypto.decrypt(vault, "correct horse")
        assertEquals(configJson, cfg)
        assertEquals(secretsJson, sec)
    }

    @Test
    fun `wrong passphrase throws BadDecrypt`() {
        val vault = VaultCrypto.encrypt("""{"a":1}""", "[]", "right")
        try {
            VaultCrypto.decrypt(vault, "wrong")
            fail("expected BadDecryptException")
        } catch (e: VaultCrypto.BadDecryptException) {
            assertTrue(e.message!!.contains("BAD_DECRYPT"))
        }
    }

    @Test
    fun `unsupported version is rejected`() {
        val vault = VaultCrypto.encrypt("""{"a":1}""", "[]", "pw").copy(version = 99)
        try {
            VaultCrypto.decrypt(vault, "pw")
            fail("expected UnsupportedVersionException")
        } catch (e: VaultCrypto.UnsupportedVersionException) {
            // expected
        }
    }

    @Test
    fun `decryptIfNeeded passes through when not encrypted`() {
        assertNull(VaultCrypto.decryptIfNeeded(false, null, null))
        val vault = VaultCrypto.encrypt("""{"a":1}""", "[]", "pw")
        assertNull(VaultCrypto.decryptIfNeeded(false, vault, "pw"))
    }

    @Test
    fun `two encryptions differ (random salt and iv)`() {
        val a = VaultCrypto.encrypt("""{"a":1}""", "[]", "pw")
        val b = VaultCrypto.encrypt("""{"a":1}""", "[]", "pw")
        assertNotEquals(a.contents, b.contents)
        assertNotEquals(a.keySalt, b.keySalt)
        assertNotEquals(a.iv, b.iv)
    }
}
