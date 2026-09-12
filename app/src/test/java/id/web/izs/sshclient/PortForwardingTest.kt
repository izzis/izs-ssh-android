package id.web.izs.sshclient

import id.web.izs.sshclient.core.config.ForwardedPort
import id.web.izs.sshclient.core.config.SshOptions
import id.web.izs.sshclient.core.config.SshProfile
import id.web.izs.sshclient.core.ssh.SshConnector
import id.web.izs.sshclient.core.ssh.StartedForwards
import id.web.izs.sshclient.core.ssh.describeForward
import id.web.izs.sshclient.core.ssh.resolveForwardSpecs
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.forward.AcceptAllForwardingFilter
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import kotlin.concurrent.thread

/**
 * Port forwarding — desktop addPortForward parity, pure JVM.
 *
 * Pure validation ([resolveForwardSpecs]) plus live traffic proofs against
 * a local MINA sshd: a Local rule must carry bytes to the target, a Remote
 * rule must be reachable on the server-side bind, and a busy listen port
 * must abort the connect (never a half-forwarded transport).
 */
class PortForwardingTest {

    private lateinit var sshd: SshServer
    private var sshPort: Int = 0

    @Before
    fun startServer() {
        val keyFile = Files.createTempFile("mina-fw-host", ".key")
        sshd = SshServer.setUpDefaultServer()
        sshd.port = 0
        sshd.keyPairProvider = SimpleGeneratorHostKeyProvider(keyFile)
        sshd.passwordAuthenticator = PasswordAuthenticator { _, _, _ -> true }
        // Default test servers reject forwarding; real servers almost always
        // run AllowTcpForwarding yes — accept all so traffic actually flows.
        sshd.forwardingFilter = AcceptAllForwardingFilter()
        sshd.start()
        sshPort = sshd.port
    }

    @After
    fun stopServer() {
        try { sshd.stop(true) } catch (_: Exception) { }
    }

    // ── pure validation ──────────────────────────────────────────────

    @Test
    fun `empty rules resolve to nothing`() {
        assertTrue(resolveForwardSpecs(emptyList()).isEmpty())
    }

    @Test
    fun `blank hosts fall back to loopback`() {
        val spec = resolveForwardSpecs(
            listOf(ForwardedPort("Local", "", 8000, "", 80, "")),
        ).single()
        assertEquals("127.0.0.1", spec.host)
        assertEquals("127.0.0.1", spec.targetAddress)
        assertEquals("rule #1", spec.description)
    }

    @Test
    fun `out-of-range ports throw with user-facing message`() {
        try {
            resolveForwardSpecs(listOf(ForwardedPort("Local", "127.0.0.1", -1, "127.0.0.1", 80, "web")))
            fail("must throw for listen port -1")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("listen port -1"))
        }
        try {
            resolveForwardSpecs(listOf(ForwardedPort("Remote", "127.0.0.1", 9000, "127.0.0.1", 99999, "")))
            fail("must throw for target port 99999")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("target port 99999"))
        }
    }

    @Test
    fun `port zero stays ephemeral like desktop`() {
        // Node listen(0) allocates an ephemeral port; desktop never rejects
        // it, so neither do we (ServerSocket(0) / server-allocated binds).
        val spec = resolveForwardSpecs(
            listOf(ForwardedPort("Local", "127.0.0.1", 0, "127.0.0.1", 80, "")),
        ).single()
        assertEquals(0, spec.port)
    }

    @Test
    fun `dynamic rows throw desktop-only message`() {
        try {
            resolveForwardSpecs(listOf(ForwardedPort("Dynamic", "127.0.0.1", 1080, "", 0, "")))
            fail("must throw for Dynamic")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("not supported on this device"))
            assertTrue(e.message!!.contains("desktop"))
        }
    }

    @Test
    fun `unknown type throws naming the type`() {
        try {
            resolveForwardSpecs(listOf(ForwardedPort("Weird", "127.0.0.1", 8000, "127.0.0.1", 80, "")))
            fail("must throw for unknown type")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("Weird"))
        }
    }

    @Test
    fun `describe matches desktop toString wording`() {
        assertEquals(
            "(local) 127.0.0.1:8000 -> (remote) 127.0.0.1:80",
            describeForward(resolveForwardSpecs(
                listOf(ForwardedPort("Local", "127.0.0.1", 8000, "127.0.0.1", 80, "")),
            ).single()),
        )
        assertEquals(
            "(remote) 0.0.0.0:9000 -> (local) 127.0.0.1:3000",
            describeForward(resolveForwardSpecs(
                listOf(ForwardedPort("Remote", "0.0.0.0", 9000, "127.0.0.1", 3000, "")),
            ).single()),
        )
    }

    @Test
    fun `empty started forwards close silently`() {
        StartedForwards.empty().close()
    }

    // ── live traffic ─────────────────────────────────────────────────

    @Test
    fun `local forward carries bytes to the target`() = runBlocking {
        withTimeout(60_000) {
            val (echo, echoPort) = startEcho()
            try {
                val listenPort = freePort()
                val conn = SshConnector()
                val t = conn.connectTransport(
                    profile = profileWith(
                        ForwardedPort("Local", "127.0.0.1", listenPort, "127.0.0.1", echoPort, "web"),
                    ),
                    password = "secret",
                    keys = emptyList(),
                    verifyHostKeys = false,
                    knownHosts = emptyList(),
                    timeoutMs = 15_000,
                    cacheDir = Files.createTempDirectory("sshcache-fw").toFile(),
                )
                try {
                    assertTrue(t.forwards.warnings.isEmpty())
                    assertEchoRoundTrip(listenPort)
                } finally {
                    t.close()
                }
                // Torn down with the transport: the listen port is free again.
                try {
                    Socket().use { it.connect(InetSocketAddress("127.0.0.1", listenPort), 2_000) }
                    fail("forward listener must be gone after close")
                } catch (_: java.io.IOException) { }
            } finally {
                try { echo.close() } catch (_: Exception) { }
            }
        }
    }

    @Test
    fun `remote forward is reachable on the server-side bind`() = runBlocking {
        withTimeout(60_000) {
            val (echo, echoPort) = startEcho()
            try {
                val remotePort = freePort()
                val conn = SshConnector()
                val t = conn.connectTransport(
                    profile = profileWith(
                        ForwardedPort("Remote", "127.0.0.1", remotePort, "127.0.0.1", echoPort, "back"),
                    ),
                    password = "secret",
                    keys = emptyList(),
                    verifyHostKeys = false,
                    knownHosts = emptyList(),
                    timeoutMs = 15_000,
                    cacheDir = Files.createTempDirectory("sshcache-fw").toFile(),
                )
                try {
                    assertTrue(t.forwards.warnings.isEmpty())
                    assertEchoRoundTrip(remotePort)
                } finally {
                    t.close()
                }
            } finally {
                try { echo.close() } catch (_: Exception) { }
            }
        }
    }

    @Test
    fun `busy local listen port aborts the connect`() = runBlocking {
        withTimeout(60_000) {
            val squatter = ServerSocket(freePort(), 50, InetAddress.getByName("127.0.0.1"))
            try {
                val listenPort = squatter.localPort
                try {
                    SshConnector().connectTransport(
                        profile = profileWith(
                            ForwardedPort("Local", "127.0.0.1", listenPort, "127.0.0.1", 80, "web"),
                        ),
                        password = "secret",
                        keys = emptyList(),
                        verifyHostKeys = false,
                        knownHosts = emptyList(),
                        timeoutMs = 15_000,
                        cacheDir = Files.createTempDirectory("sshcache-fw").toFile(),
                    )
                    fail("busy listen port must abort the connect")
                } catch (e: IllegalStateException) {
                    assertTrue(e.message!!.contains("Local forward"))
                }
            } finally {
                try { squatter.close() } catch (_: Exception) { }
            }
        }
    }

    @Test
    fun `dynamic rule aborts the connect with desktop-only message`() = runBlocking {
        withTimeout(60_000) {
            try {
                SshConnector().connectTransport(
                    profile = profileWith(
                        ForwardedPort("Dynamic", "127.0.0.1", 1080, "127.0.0.1", 0, ""),
                    ),
                    password = "secret",
                    keys = emptyList(),
                    verifyHostKeys = false,
                    knownHosts = emptyList(),
                    timeoutMs = 15_000,
                    cacheDir = Files.createTempDirectory("sshcache-fw").toFile(),
                )
                fail("Dynamic must abort the connect")
            } catch (e: IllegalStateException) {
                assertTrue(e.message!!.contains("not supported on this device"))
            }
        }
    }

    // ── helpers ──────────────────────────────────────────────────────

    private fun profileWith(vararg fw: ForwardedPort) = SshProfile(
        id = "forward-probe",
        name = "probe",
        options = SshOptions(
            host = "127.0.0.1",
            port = sshPort,
            user = "tester",
            forwardedPorts = fw.toList(),
        ),
    )

    private fun freePort(): Int =
        ServerSocket(0).use { it.localPort }

    /** Loopback echo target: returns the server socket and its port. */
    private fun startEcho(): Pair<ServerSocket, Int> {
        val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true, name = "test-echo") {
            try {
                while (!server.isClosed) {
                    val s = server.accept()
                    thread(isDaemon = true) {
                        try {
                            s.getInputStream().copyTo(s.getOutputStream())
                        } catch (_: Exception) { } finally {
                            try { s.close() } catch (_: Exception) { }
                        }
                    }
                }
            } catch (_: Exception) { }
        }
        return server to server.localPort
    }

    private fun assertEchoRoundTrip(port: Int) {
        val payload = "forward-probe-bytes".toByteArray(Charsets.UTF_8)
        Socket().use { sock ->
            sock.connect(InetSocketAddress("127.0.0.1", port), 10_000)
            sock.soTimeout = 10_000
            sock.getOutputStream().write(payload)
            sock.getOutputStream().flush()
            val buf = ByteArray(payload.size)
            var read = 0
            while (read < buf.size) {
                val n = sock.getInputStream().read(buf, read, buf.size - read)
                if (n < 0) break
                read += n
            }
            assertArrayEquals(payload, buf.copyOf(read))
        }
    }
}
