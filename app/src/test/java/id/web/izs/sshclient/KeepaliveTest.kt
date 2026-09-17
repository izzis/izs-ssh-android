package id.web.izs.sshclient

import id.web.izs.sshclient.core.config.SshOptions
import id.web.izs.sshclient.core.config.SshProfile
import id.web.izs.sshclient.core.ssh.SshConnector
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.schmizz.keepalive.KeepAliveRunner
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.nio.file.Files

/**
 * keepaliveCountMax wiring — desktop parity, pure JVM.
 *
 * The profile value must reach sshj's KeepAliveRunner.maxAliveCount on the
 * live transport (ServerAliveCountMax semantics: drop after that many
 * unanswered heartbeats), not just round-trip through the YAML.
 */
class KeepaliveTest {

    private lateinit var sshd: SshServer
    private var sshPort: Int = 0

    @Before
    fun startServer() {
        val keyFile = Files.createTempFile("mina-keepalive-host", ".key")
        sshd = SshServer.setUpDefaultServer()
        sshd.port = 0
        sshd.keyPairProvider = SimpleGeneratorHostKeyProvider(keyFile)
        sshd.passwordAuthenticator = PasswordAuthenticator { _, _, _ -> true }
        sshd.start()
        sshPort = sshd.port
    }

    @After
    fun stopServer() {
        try { sshd.stop(true) } catch (_: Exception) { }
    }

    private suspend fun connect(options: SshOptions): SshConnector.ConnectedTransport =
        SshConnector().connectTransport(
            profile = SshProfile(id = "ka-probe", name = "probe", options = options),
            password = "secret",
            keys = emptyList(),
            verifyHostKeys = false,
            knownHosts = emptyList(),
            timeoutMs = 15_000,
            cacheDir = Files.createTempDirectory("sshcache-ka").toFile(),
        )

    @Test
    fun `custom keepalive values reach the transport`() = runBlocking {
        withTimeout(60_000) {
            val t = connect(
                SshOptions(host = "127.0.0.1", port = sshPort, user = "tester",
                    keepaliveInterval = 2000, keepaliveCountMax = 3),
            )
            try {
                val keepAlive = t.client.connection.keepAlive
                assertEquals(2, keepAlive.keepAliveInterval)
                assertTrue(keepAlive is KeepAliveRunner)
                assertEquals(3, (keepAlive as KeepAliveRunner).maxAliveCount)
            } finally {
                t.close()
            }
        }
    }

    @Test
    fun `defaults match desktop`() = runBlocking {
        withTimeout(60_000) {
            val t = connect(SshOptions(host = "127.0.0.1", port = sshPort, user = "tester"))
            try {
                val keepAlive = t.client.connection.keepAlive
                assertEquals(5, keepAlive.keepAliveInterval)
                assertTrue(keepAlive is KeepAliveRunner)
                assertEquals(10, (keepAlive as KeepAliveRunner).maxAliveCount)
            } finally {
                t.close()
            }
        }
    }
}
