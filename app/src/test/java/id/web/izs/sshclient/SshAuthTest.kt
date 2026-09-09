package id.web.izs.sshclient

import id.web.izs.sshclient.core.config.SshOptions
import id.web.izs.sshclient.core.config.SshProfile
import id.web.izs.sshclient.core.ssh.SshAuthFailed
import id.web.izs.sshclient.core.ssh.SshConnector
import id.web.izs.sshclient.core.ssh.friendlyAuthError
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.nio.file.Files

/**
 * Auth-failover contract (desktop prompt-password parity):
 * - failures surface as typed [SshAuthFailed] (the UI offers the password
 *   dialog on exactly this type), never the raw sshj "Exhausted available
 *   authentication methods" text.
 */
class SshAuthTest {

    private lateinit var sshd: SshServer
    private var port: Int = 0

    @Before
    fun startServer() {
        val keyFile = Files.createTempFile("mina-auth-host", ".key")
        sshd = SshServer.setUpDefaultServer()
        sshd.port = 0
        sshd.keyPairProvider = SimpleGeneratorHostKeyProvider(keyFile)
        sshd.passwordAuthenticator = PasswordAuthenticator { _, _, _ -> false }
        sshd.start()
        port = sshd.port
    }

    @After
    fun stopServer() {
        try { sshd.stop(true) } catch (_: Exception) { }
    }

    private fun profile() = SshProfile(
        id = "auth-probe",
        name = "probe",
        options = SshOptions(host = "127.0.0.1", port = port, user = "tester"),
    )

    @Test
    fun `friendlyAuthError hides the raw Exhausted text`() {
        assertEquals(
            "wrong password or key",
            friendlyAuthError("Exhausted available authentication methods"),
        )
        assertEquals(
            "server rejected the credentials",
            friendlyAuthError("   "),
        )
        assertEquals("publickey auth failed", friendlyAuthError("publickey auth failed"))
    }

    @Test
    fun `wrong password throws typed SshAuthFailed without Exhausted text`() = runBlocking {
        withTimeout(60_000) {
            val conn = SshConnector()
            try {
                conn.connectTransport(
                    profile = profile(),
                    password = "wrong",
                    keys = emptyList(),
                    verifyHostKeys = false,
                    knownHosts = emptyList(),
                    timeoutMs = 15_000,
                    cacheDir = Files.createTempDirectory("sshcache").toFile(),
                )
                fail("expected SshAuthFailed")
            } catch (e: SshAuthFailed) {
                assertFalse(e.message ?: "", (e.message ?: "").contains("Exhausted"))
                assertTrue(e.message ?: "", (e.message ?: "").contains("wrong password or key"))
            }
        }
    }

    @Test
    fun `missing credentials throw typed SshAuthFailed`() = runBlocking {
        withTimeout(60_000) {
            val conn = SshConnector()
            try {
                conn.connectTransport(
                    profile = profile(),
                    password = null,
                    keys = emptyList(),
                    verifyHostKeys = false,
                    knownHosts = emptyList(),
                    timeoutMs = 15_000,
                    cacheDir = Files.createTempDirectory("sshcache").toFile(),
                )
                fail("expected SshAuthFailed")
            } catch (e: SshAuthFailed) {
                assertEquals("No saved password or key for this profile", e.message)
            }
        }
    }
}
