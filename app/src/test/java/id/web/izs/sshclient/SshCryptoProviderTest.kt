package id.web.izs.sshclient

import id.web.izs.sshclient.core.ssh.SshConnector
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.*
import org.junit.Test
import java.security.Security
import javax.crypto.KeyAgreement

/**
 * Regression test for "no such algorithm X25519" on Android.
 *
 * sshj pins JCE provider "BC" for curve25519 key exchange, but Android ships a
 * stripped "BC" without X25519 and sshj's own registration silently no-ops
 * there (duplicate provider name). SshConnector.ensureProvider() must swap in
 * the full BouncyCastle from our bcprov dependency. Pure JVM executable.
 */
class SshCryptoProviderTest {

    @Test
    fun `ensureProvider makes X25519 available under BC`() {
        SshConnector.ensureProvider()
        val provider = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME)
        assertNotNull("provider BC must be registered", provider)
        assertEquals(
            BouncyCastleProvider::class.java.name,
            provider.javaClass.name,
        )
        // This is the exact lookup sshj's Curve25519DH performs via SecurityUtils.
        val ka = KeyAgreement.getInstance("X25519", BouncyCastleProvider.PROVIDER_NAME)
        assertNotNull(ka)
    }

    @Test
    fun `ensureProvider is idempotent`() {
        SshConnector.ensureProvider()
        SshConnector.ensureProvider()
        assertNotNull(Security.getProvider(BouncyCastleProvider.PROVIDER_NAME))
    }
}
