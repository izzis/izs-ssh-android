package id.web.izs.sshclient

import id.web.izs.sshclient.core.config.SshAlgorithms
import id.web.izs.sshclient.core.ssh.SshAlgorithmFactories
import org.junit.Assert.*
import org.junit.Test

class SshAlgorithmFactoriesTest {

    /** Desktop default names with no sshj equivalent (skipped like desktop filters). */
    private val expectedSkippedKex = setOf(
        "mlkem768x25519-sha256",
        "ext-info-c",
        "ext-info-s",
        "kex-strict-c-v00@openssh.com",
        "kex-strict-s-v00@openssh.com",
    )

    @Test
    fun `desktop defaults resolve except known sshj gaps`() {
        val d = SshAlgorithms.DEFAULTS
        assertEquals(
            d.getValue(SshAlgorithms.KEX) - expectedSkippedKex,
            SshAlgorithmFactories.resolveKex(d.getValue(SshAlgorithms.KEX)).map { it.name },
        )
        assertEquals(
            d.getValue(SshAlgorithms.CIPHER),
            SshAlgorithmFactories.resolveCiphers(d.getValue(SshAlgorithms.CIPHER)).map { it.name },
        )
        assertEquals(
            d.getValue(SshAlgorithms.HMAC),
            SshAlgorithmFactories.resolveMacs(d.getValue(SshAlgorithms.HMAC)).map { it.name },
        )
        assertEquals(
            d.getValue(SshAlgorithms.SERVER_HOST_KEY),
            SshAlgorithmFactories.resolveHostKeys(d.getValue(SshAlgorithms.SERVER_HOST_KEY)).map { it.name },
        )
        assertEquals(
            d.getValue(SshAlgorithms.COMPRESSION),
            SshAlgorithmFactories.resolveCompressions(d.getValue(SshAlgorithms.COMPRESSION)).map { it.name },
        )
        assertEquals(
            mapOf(SshAlgorithms.KEX to expectedSkippedKex.toList()),
            SshAlgorithmFactories.skipped(d),
        )
    }

    @Test
    fun `resolution preserves stored order and drops unknowns`() {
        val resolved = SshAlgorithmFactories.resolveCiphers(
            listOf("aes128-ctr", "nope-1", "aes256-ctr"),
        )
        assertEquals(listOf("aes128-ctr", "aes256-ctr"), resolved.map { it.name })
    }

    @Test
    fun `configFor orders hostkeys desktop-first on defaults`() {
        val cfg = SshAlgorithmFactories.configFor(emptyMap())
        assertEquals(
            listOf(
                "ecdsa-sha2-nistp256",
                "ecdsa-sha2-nistp384",
                "ecdsa-sha2-nistp521",
                "ssh-ed25519",
                "rsa-sha2-256",
                "rsa-sha2-512",
                "ssh-rsa",
            ).filter { it in SshAlgorithmFactories.defaultHostKeyTypes() },
            cfg.keyAlgorithms.map { it.name },
        )
        // Other categories stay sshj stock on defaults.
        assertTrue(cfg.cipherFactories.isNotEmpty())
    }

    @Test
    fun `configFor puts known hostkey types first`() {
        val cfg = SshAlgorithmFactories.configFor(emptyMap(), listOf("ssh-ed25519"))
        val names = cfg.keyAlgorithms.map { it.name }
        assertEquals("ssh-ed25519", names.first())
    }

    @Test
    fun `configFor applies custom lists in stored order`() {
        val cfg = SshAlgorithmFactories.configFor(
            mapOf(SshAlgorithms.CIPHER to listOf("aes128-ctr", "aes256-ctr")),
        )
        assertEquals(
            listOf("aes128-ctr", "aes256-ctr"),
            cfg.cipherFactories.map { it.name },
        )
        // Untouched categories keep sshj defaults.
        assertTrue(cfg.macFactories.isNotEmpty())
    }

    @Test
    fun `configFor falls back per category when nothing resolves`() {
        val cfg = SshAlgorithmFactories.configFor(
            mapOf(SshAlgorithms.KEX to listOf("mlkem768x25519-sha256")),
        )
        // Unresolvable-only list -> sshj default KEX kept (negotiation-safe).
        assertTrue(cfg.keyExchangeFactories.any { it.name == "curve25519-sha256" })
    }

    @Test
    fun `orderedHostKeyNames keeps explicit custom order`() {
        val custom = listOf("rsa-sha2-256", "ssh-ed25519")
        assertEquals(
            listOf("ssh-ed25519", "rsa-sha2-256"),
            SshAlgorithmFactories.orderedHostKeyNames(custom, listOf("ssh-ed25519"), true),
        )
        assertEquals(
            custom,
            SshAlgorithmFactories.orderedHostKeyNames(custom, emptyList(), true),
        )
    }
}
