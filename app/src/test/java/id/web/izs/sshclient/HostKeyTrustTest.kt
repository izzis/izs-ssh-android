package id.web.izs.sshclient

import id.web.izs.sshclient.core.config.KnownHostEntry
import id.web.izs.sshclient.core.ssh.HostKeyTrust
import net.schmizz.sshj.common.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

/**
 * Host-key trust: desktop digest parity, exact {host,port,type} matching,
 * legacy prefs upgrade, and known-first negotiation order.
 */
class HostKeyTrustTest {

    companion object {
        // Independent vector: `ssh-keygen -t rsa -b 2048`, `ssh-keygen -lf`
        // prints SHA256:f7ZbSffqobcR9WIXGaWk6EaQo2oWgxJn2O65kxY8akw (RSA).
        const val EXPECTED_FP = "f7ZbSffqobcR9WIXGaWk6EaQo2oWgxJn2O65kxY8akw"
        const val WIRE_B64 =
            "AAAAB3NzaC1yc2EAAAADAQABAAABAQDaGkuvMtl8ZGGt0FuGye04+Z0uYSfT034ECF61ZS5oo+IH/a+CbzPIwcy+H1hb/VEm31iEB179aoMAljkajsJUuEk86tYQR3xKHuFktOShDTkmUIafRxusMIXxpsgkyFGyYq+SVuGcBdaIHptA2PGiXmW3nBgWjl4zdhsPQZNsE8w5OD/7LiQ1DABwy6HUatIoQT8chU4ldualclNsDub5xkhGZrpItEtX0zU138eOFAqB8fcvNf7Mt4daDnahCi0qhG6u3JImaHvm+KD0Y3mWeVUIfPMyV+W2CyBONvYzHoDTszncBKmxHcHQozkWH38UnCU0DSzcldldkuTecQJ/"

        /** Rebuilds the RSA key from the wire blob (independent of sshj encode). */
        fun vectorKey(): PublicKey {
            val buf = Buffer.PlainBuffer(Base64.getDecoder().decode(WIRE_B64))
            assertEquals("ssh-rsa", buf.readString())
            val e = BigInteger(1, buf.readBytes())
            val n = BigInteger(1, buf.readBytes())
            return KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(n, e))
        }
    }

    @Test
    fun `digest matches ssh-keygen and type is the wire name`() {
        val key = vectorKey()
        assertEquals("ssh-rsa", HostKeyTrust.wireTypeOf(key))
        assertEquals(EXPECTED_FP, HostKeyTrust.normalizeDigest(HostKeyTrust.digestOf(key)))
    }

    @Test
    fun `known digest matches padding-tolerantly`() {
        val withPad = HostKeyTrust.digestOf(vectorKey())
        val stripped = withPad.trimEnd('=')
        val entry = KnownHostEntry("h", 22, "ssh-rsa", stripped)
        assertTrue(
            HostKeyTrust.decide(listOf(entry), emptyList(), "h", 22, "ssh-rsa", withPad, "x")
                is HostKeyTrust.Verdict.Known,
        )
    }

    @Test
    fun `same host different digest is mismatched with previous`() {
        val entries = listOf(
            KnownHostEntry("h", 22, "ssh-rsa", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="),
            KnownHostEntry("h", 22, "ecdsa-sha2-nistp256", "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB="),
        )
        val v = HostKeyTrust.decide(entries, emptyList(), "h", 22, "ssh-rsa", "CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC=", "x")
        assertTrue(v is HostKeyTrust.Verdict.Mismatched)
        // Same-type previous preferred for display.
        assertEquals("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", (v as HostKeyTrust.Verdict.Mismatched).previousDigest)
    }

    @Test
    fun `port is part of the identity`() {
        val entries = listOf(KnownHostEntry("h", 22, "ssh-rsa", EXPECTED_FP))
        assertTrue(
            HostKeyTrust.decide(entries, emptyList(), "h", 2222, "ssh-rsa", EXPECTED_FP, "x")
                is HostKeyTrust.Verdict.Unknown,
        )
    }

    @Test
    fun `legacy line matches blob and upgrades to the live port`() {
        val blob = Base64.getEncoder().encodeToString(vectorKey().encoded)
        val line = "example.com|RSA|$blob"
        val v = HostKeyTrust.decide(
            emptyList(), listOf(line), "example.com", 22,
            "ssh-rsa", EXPECTED_FP, blob, upgradePort = 2222,
        )
        assertTrue(v is HostKeyTrust.Verdict.LegacyHit)
        val hit = v as HostKeyTrust.Verdict.LegacyHit
        assertEquals(line, hit.line)
        assertEquals(KnownHostEntry("example.com", 2222, "ssh-rsa", EXPECTED_FP), hit.upgrade)
    }

    @Test
    fun `legacy mismatch falls through to unknown`() {
        val line = "example.com|RSA|AAAAAAAAAAAAAAAAAAAAAA=="
        assertTrue(
            HostKeyTrust.decide(emptyList(), listOf(line), "example.com", 22, "ssh-rsa", EXPECTED_FP, "DIFFERENT")
                is HostKeyTrust.Verdict.Unknown,
        )
    }

    @Test
    fun `findExistingAlgorithms puts known types first`() {
        val entries = listOf(KnownHostEntry("h", 22, "ssh-ed25519", EXPECTED_FP))
        val configured = listOf("ecdsa-sha2-nistp256", "ssh-ed25519", "rsa-sha2-256")
        assertEquals(
            listOf("ssh-ed25519"),
            HostKeyTrust.findExistingAlgorithms(entries, "h", 22, configured, false),
        )
    }

    @Test
    fun `findExistingAlgorithms falls back to desktop order on defaults`() {
        val configured = listOf("ssh-ed25519", "ecdsa-sha2-nistp256", "rsa-sha2-256", "ssh-rsa")
        assertEquals(
            listOf("ecdsa-sha2-nistp256", "ssh-ed25519", "rsa-sha2-256", "ssh-rsa"),
            HostKeyTrust.findExistingAlgorithms(emptyList(), "h", 22, configured, false),
        )
    }

    @Test
    fun `findExistingAlgorithms respects an explicit custom list`() {
        val configured = listOf("rsa-sha2-256", "ssh-ed25519")
        assertEquals(
            configured,
            HostKeyTrust.findExistingAlgorithms(emptyList(), "h", 22, configured, true),
        )
    }

    @Test
    fun `appendKnownHost upserts by host port type`() {
        val doc = linkedMapOf<String, Any?>()
        val e1 = KnownHostEntry("h", 22, "ssh-rsa", "AAA=")
        val e2 = KnownHostEntry("h", 22, "ssh-rsa", "BBB=")
        val e3 = KnownHostEntry("h", 2222, "ssh-rsa", "AAA=")
        id.web.izs.sshclient.core.config.RawConfigStore.appendKnownHost(doc, e1)
        id.web.izs.sshclient.core.config.RawConfigStore.appendKnownHost(doc, e2)
        id.web.izs.sshclient.core.config.RawConfigStore.appendKnownHost(doc, e3)
        @Suppress("UNCHECKED_CAST")
        val ssh = doc["ssh"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val list = ssh["knownHosts"] as List<Map<String, Any?>>
        assertEquals(2, list.size)
        assertEquals("BBB=", list.first { it["port"] == 22 }["digest"])
    }
}
