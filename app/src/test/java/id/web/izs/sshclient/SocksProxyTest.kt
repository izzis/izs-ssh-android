package id.web.izs.sshclient

import id.web.izs.sshclient.core.config.SshOptions
import id.web.izs.sshclient.core.config.SshProfile
import id.web.izs.sshclient.core.ssh.SocksProxy
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
import java.io.DataInputStream
import java.io.EOFException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.Collections
import kotlin.concurrent.thread

/**
 * Outbound SOCKS proxy — desktop `socksProxy` parity, pure JVM.
 *
 * A minimal RFC 1928 (no-auth) SOCKS5 server proves the whole SSH transport
 * really rides the proxy: the handshake, auth, and shell setup all flow
 * through it, and the server observes the CONNECT for the SSH target.
 */
class SocksProxyTest {

    private lateinit var sshd: SshServer
    private var sshPort: Int = 0

    @Before
    fun startServer() {
        val keyFile = Files.createTempFile("mina-socks-host", ".key")
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

    // ── pure validation ──────────────────────────────────────────────

    @Test
    fun `blank host means direct`() {
        assertNull(SocksProxy.resolveAddress(null, 1080))
        assertNull(SocksProxy.resolveAddress("", 1080))
        assertNull(SocksProxy.resolveAddress("   ", 0))
    }

    @Test
    fun `missing or zero port defaults to 1080`() {
        assertEquals(1080, SocksProxy.resolveAddress("proxy.local", null)!!.port)
        assertEquals(1080, SocksProxy.resolveAddress("proxy.local", 0)!!.port)
        assertEquals(9050, SocksProxy.resolveAddress("proxy.local", 9050)!!.port)
    }

    @Test
    fun `out-of-range port throws user-facing message`() {
        try {
            SocksProxy.resolveAddress("proxy.local", 99999)
            fail("must throw for port 99999")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("99999"))
        }
    }

    // ── live proxying ────────────────────────────────────────────────

    @Test
    fun `ssh handshake and auth ride the socks proxy`() = runBlocking {
        withTimeout(60_000) {
            val fake = FakeSocks5()
            try {
                val t = SshConnector().connectTransport(
                    profile = SshProfile(
                        id = "socks-probe",
                        name = "probe",
                        options = SshOptions(
                            host = "127.0.0.1",
                            port = sshPort,
                            user = "tester",
                            socksProxyHost = "127.0.0.1",
                            socksProxyPort = fake.port,
                        ),
                    ),
                    password = "secret",
                    keys = emptyList(),
                    verifyHostKeys = false,
                    knownHosts = emptyList(),
                    timeoutMs = 15_000,
                    cacheDir = Files.createTempDirectory("sshcache-socks").toFile(),
                )
                try {
                    assertTrue(t.client.isAuthenticated)
                    // The proxy really saw the SSH target (not a direct dial).
                    assertEquals(listOf("127.0.0.1" to sshPort), fake.requests.toList())
                } finally {
                    t.close()
                }
            } finally {
                fake.close()
            }
        }
    }

    @Test
    fun `dead socks proxy fails the connect`() = runBlocking {
        withTimeout(60_000) {
            val deadPort = ServerSocket(0).use { it.localPort }
            try {
                SshConnector().connectTransport(
                    profile = SshProfile(
                        id = "socks-dead",
                        name = "dead",
                        options = SshOptions(
                            host = "127.0.0.1",
                            port = sshPort,
                            user = "tester",
                            socksProxyHost = "127.0.0.1",
                            socksProxyPort = deadPort,
                        ),
                    ),
                    password = "secret",
                    keys = emptyList(),
                    verifyHostKeys = false,
                    knownHosts = emptyList(),
                    timeoutMs = 15_000,
                    cacheDir = Files.createTempDirectory("sshcache-socks").toFile(),
                )
                fail("dead proxy must fail the connect")
            } catch (e: Exception) {
                assertTrue(e.message!!.isNotBlank())
            }
        }
    }

    // ── minimal RFC 1928 server (no-auth CONNECT only) ───────────────

    private class FakeSocks5 {
        val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val port: Int = server.localPort
        val requests = Collections.synchronizedList(mutableListOf<Pair<String, Int>>())

        init {
            thread(isDaemon = true, name = "fake-socks5") {
                try {
                    while (!server.isClosed) {
                        val s = server.accept()
                        thread(isDaemon = true) { serve(s) }
                    }
                } catch (_: Exception) { }
            }
        }

        fun close() {
            try { server.close() } catch (_: Exception) { }
        }

        private fun serve(client: Socket) {
            try {
                val input = DataInputStream(client.getInputStream())
                val output = client.getOutputStream()
                // Greeting: VER NMETHODS METHODS… — offer no-auth unconditionally.
                val ver = input.readUnsignedByte()
                if (ver != 0x05) return
                val nMethods = input.readUnsignedByte()
                input.readFully(ByteArray(nMethods))
                output.write(byteArrayOf(0x05, 0x00))
                output.flush()
                // Request: VER CMD RSV ATYP ADDR PORT.
                input.readUnsignedByte() // VER
                val cmd = input.readUnsignedByte()
                input.readUnsignedByte() // RSV
                if (cmd != 0x01) {
                    output.write(byteArrayOf(0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                    output.flush()
                    return
                }
                val host = when (input.readUnsignedByte()) {
                    0x01 -> {
                        val b = ByteArray(4)
                        input.readFully(b)
                        InetAddress.getByAddress(b).hostAddress
                    }
                    0x03 -> {
                        val n = input.readUnsignedByte()
                        val b = ByteArray(n)
                        input.readFully(b)
                        String(b, Charsets.US_ASCII)
                    }
                    0x04 -> {
                        val b = ByteArray(16)
                        input.readFully(b)
                        InetAddress.getByAddress(b).hostAddress
                    }
                    else -> return
                }
                val targetPort = input.readUnsignedShort()
                requests.add(host to targetPort)
                val target = try {
                    Socket().apply {
                        connect(java.net.InetSocketAddress(host, targetPort), 10_000)
                    }
                } catch (_: Exception) {
                    output.write(byteArrayOf(0x05, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                    output.flush()
                    return
                }
                output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                output.flush()
                val relay = thread(isDaemon = true) {
                    try { target.getInputStream().copyTo(client.getOutputStream()) } catch (_: Exception) { }
                }
                try {
                    client.getInputStream().copyTo(target.getOutputStream())
                } catch (_: Exception) { } finally {
                    try { target.close() } catch (_: Exception) { }
                    try { relay.join(2_000) } catch (_: Exception) { }
                }
            } catch (_: EOFException) {
            } catch (_: Exception) {
            } finally {
                try { client.close() } catch (_: Exception) { }
            }
        }
    }
}
