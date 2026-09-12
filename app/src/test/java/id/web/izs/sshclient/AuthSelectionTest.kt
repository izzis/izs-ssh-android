package id.web.izs.sshclient

import id.web.izs.sshclient.core.config.SshOptions
import id.web.izs.sshclient.core.config.SshProfile
import id.web.izs.sshclient.core.ssh.SshAuthFailed
import id.web.izs.sshclient.core.ssh.SshConnector
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
import java.util.Collections

/**
 * Auth-method selection is honored (desktop ssh.ts init() parity) — never
 * silent Auto. Stage texts are the observable proof of which methods ran.
 */
class AuthSelectionTest {

    private lateinit var sshd: SshServer
    private var sshPort: Int = 0

    @Before
    fun startServer() {
        val keyFile = Files.createTempFile("mina-auth-host", ".key")
        sshd = SshServer.setUpDefaultServer()
        sshd.port = 0
        sshd.keyPairProvider = SimpleGeneratorHostKeyProvider(keyFile)
        // Only "secret" is valid — wrong typed passwords must really fail.
        sshd.passwordAuthenticator = PasswordAuthenticator { _, password, _ -> password == "secret" }
        sshd.start()
        sshPort = sshd.port
    }

    @After
    fun stopServer() {
        try { sshd.stop(true) } catch (_: Exception) { }
    }

    @Test
    fun `publicKey selection never tries password`() = runBlocking {
        withTimeout(60_000) {
            val stages = Collections.synchronizedList(mutableListOf<String>())
            try {
                SshConnector().connectTransport(
                    profile = profile(auth = "publicKey"),
                    password = "secret",
                    keys = emptyList(),
                    verifyHostKeys = false,
                    knownHosts = emptyList(),
                    timeoutMs = 15_000,
                    cacheDir = Files.createTempDirectory("sshcache-auth").toFile(),
                    onStage = { stages += it },
                )
                fail("no key saved must fail")
            } catch (e: SshAuthFailed) {
                assertTrue(e.message!!.contains("No private key"))
            }
            assertTrue(stages.none { it.contains("Checking password") })
        }
    }

    @Test
    fun `password selection never tries keys`() = runBlocking {
        withTimeout(60_000) {
            val stages = Collections.synchronizedList(mutableListOf<String>())
            val t = SshConnector().connectTransport(
                profile = profile(auth = "password"),
                password = "secret",
                keys = listOf(SshConnector.KeyInput(pem = "junk", passphrase = null)),
                verifyHostKeys = false,
                knownHosts = emptyList(),
                timeoutMs = 15_000,
                cacheDir = Files.createTempDirectory("sshcache-auth").toFile(),
                onStage = { stages += it },
            )
            try {
                assertTrue(t.client.isAuthenticated)
                assertTrue(stages.any { it.contains("Checking password") })
                assertTrue(stages.none { it.contains("Trying key") })
            } finally {
                t.close()
            }
        }
    }

    @Test
    fun `agent selection fails with a clear message`() = runBlocking {
        withTimeout(60_000) {
            try {
                SshConnector().connectTransport(
                    profile = profile(auth = "agent"),
                    password = "secret",
                    keys = emptyList(),
                    verifyHostKeys = false,
                    knownHosts = emptyList(),
                    timeoutMs = 15_000,
                    cacheDir = Files.createTempDirectory("sshcache-auth").toFile(),
                )
                fail("agent must fail fast")
            } catch (e: IllegalStateException) {
                assertTrue(e.message!!.contains("Agent"))
            }
        }
    }

    @Test
    fun `keyboardInteractive narrows to password`() = runBlocking {
        withTimeout(60_000) {
            val stages = Collections.synchronizedList(mutableListOf<String>())
            val t = SshConnector().connectTransport(
                profile = profile(auth = "keyboardInteractive"),
                password = "secret",
                keys = listOf(SshConnector.KeyInput(pem = "junk", passphrase = null)),
                verifyHostKeys = false,
                knownHosts = emptyList(),
                timeoutMs = 15_000,
                cacheDir = Files.createTempDirectory("sshcache-auth").toFile(),
                onStage = { stages += it },
            )
            try {
                assertTrue(t.client.isAuthenticated)
                assertTrue(stages.any { it.contains("Checking password") })
                assertTrue(stages.none { it.contains("Trying key") })
            } finally {
                t.close()
            }
        }
    }

    @Test
    fun `keyboardInteractive without password fails clearly`() = runBlocking {
        withTimeout(60_000) {
            try {
                SshConnector().connectTransport(
                    profile = profile(auth = "keyboardInteractive"),
                    password = null,
                    keys = listOf(SshConnector.KeyInput(pem = "junk", passphrase = null)),
                    verifyHostKeys = false,
                    knownHosts = emptyList(),
                    timeoutMs = 15_000,
                    cacheDir = Files.createTempDirectory("sshcache-auth").toFile(),
                )
                fail("no password must fail")
            } catch (e: SshAuthFailed) {
                assertTrue(e.message!!.contains("No saved password"))
            }
        }
    }

    @Test
    fun `typed failover password works despite publicKey selection`() = runBlocking {
        withTimeout(60_000) {
            // Exact user scenario: keys fail, the failover dialog password
            // must still be attempted even though `auth` says keys-only.
            val stages = Collections.synchronizedList(mutableListOf<String>())
            val t = SshConnector().connectTransport(
                profile = profile(auth = "publicKey"),
                password = "secret",
                passwordIsTyped = true,
                keys = listOf(SshConnector.KeyInput(pem = "junk", passphrase = null)),
                verifyHostKeys = false,
                knownHosts = emptyList(),
                timeoutMs = 15_000,
                cacheDir = Files.createTempDirectory("sshcache-auth").toFile(),
                onStage = { stages += it },
            )
            try {
                assertTrue(t.client.isAuthenticated)
                assertTrue(stages.any { it.contains("Checking password") })
            } finally {
                t.close()
            }
        }
    }

    @Test
    fun `wrong typed password still fails cleanly`() = runBlocking {
        withTimeout(60_000) {
            try {
                SshConnector().connectTransport(
                    profile = profile(auth = "publicKey"),
                    password = "wrong",
                    passwordIsTyped = true,
                    keys = listOf(SshConnector.KeyInput(pem = "junk", passphrase = null)),
                    verifyHostKeys = false,
                    knownHosts = emptyList(),
                    timeoutMs = 15_000,
                    cacheDir = Files.createTempDirectory("sshcache-auth").toFile(),
                )
                fail("wrong password must fail")
            } catch (e: SshAuthFailed) {
                assertTrue(e.message!!.contains("Auth failed"))
            }
        }
    }

    @Test
    fun `auto still tries password`() = runBlocking {        withTimeout(60_000) {
            val stages = Collections.synchronizedList(mutableListOf<String>())
            val t = SshConnector().connectTransport(
                profile = profile(auth = null),
                password = "secret",
                keys = emptyList(),
                verifyHostKeys = false,
                knownHosts = emptyList(),
                timeoutMs = 15_000,
                cacheDir = Files.createTempDirectory("sshcache-auth").toFile(),
                onStage = { stages += it },
            )
            try {
                assertTrue(t.client.isAuthenticated)
                assertTrue(stages.any { it.contains("Checking password") })
            } finally {
                t.close()
            }
        }
    }

    private fun profile(auth: String?) = SshProfile(
        id = "auth-probe",
        name = "probe",
        options = SshOptions(
            host = "127.0.0.1",
            port = sshPort,
            user = "tester",
            auth = auth,
        ),
    )
}
