package id.web.izs.sshclient

import id.web.izs.sshclient.core.config.SshOptions
import id.web.izs.sshclient.core.config.SshProfile
import id.web.izs.sshclient.core.ssh.SshConnector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.shell.ShellFactory
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Decisive multiplex test against a local MINA sshd (known-good server).
 *
 * Bug under test: with `reuseSession=true` two tabs share one transport;
 * explicitly disconnecting tab A killed tab B ("Session ended" + Retry).
 * The device log showed the transport REALLY died ~1.7s after the channel
 * close, with refcounting correct (ADOPT 2->3, release->2, no drop).
 *
 * - If these tests PASS, our stack is clean (channel close is transport-safe)
 *   and the verdict is T1: the user's real server tears the whole connection
 *   down when a channel closes.
 * - If they FAIL, the verdict is T2: a bug in our stack (sshj usage or the
 *   pool) kills the shared transport on channel close.
 *
 * Test-only MINA dep; never ships in the APK.
 */
class SshMultiplexTest {

    private lateinit var sshd: SshServer
    private var port: Int = 0
    /** Server-side commands in creation order (oracle for channel-close). */
    private val commands = Collections.synchronizedList(mutableListOf<EchoCommand>())

    @Before
    fun startServer() {
        val keyFile = Files.createTempFile("mina-host", ".key")
        sshd = SshServer.setUpDefaultServer()
        sshd.port = 0
        sshd.keyPairProvider = SimpleGeneratorHostKeyProvider(keyFile)
        sshd.passwordAuthenticator = PasswordAuthenticator { _, _, _ -> true }
        sshd.shellFactory = ShellFactory { EchoCommand().also { commands.add(it) } }
        sshd.start()
        port = sshd.port
    }

    @After
    fun stopServer() {
        try { sshd.stop(true) } catch (_: Exception) { }
    }

    private fun profile() = SshProfile(
        id = "multiplex-probe",
        name = "probe",
        options = SshOptions(host = "127.0.0.1", port = port, user = "tester"),
    )

    private suspend fun connect(conn: SshConnector): SshConnector.ConnectedTransport =
        conn.connectTransport(
            profile = profile(),
            password = "secret",
            keys = emptyList(),
            verifyHostKeys = false,
            knownHosts = emptyList(),
            timeoutMs = 15_000,
            cacheDir = Files.createTempDirectory("sshcache").toFile(),
        )

    @Test
    fun `closing one shell on a shared transport leaves the sibling alive`() = runBlocking {
        withTimeout(60_000) {
            val conn = SshConnector()
            val t = connect(conn)
            try {
                val a = conn.openShellOnTransport(t.client, profile())
                val b = conn.openShellOnTransport(t.client, profile())
                // Mirror the VM: shared shells release the pool on close —
                // they must NOT disconnect the client (the legacy null path).
                a.onClosed = {}
                b.onClosed = {}
                val seen = mutableListOf<String>()
                val collect = launch(Dispatchers.IO) { b.output.collect { seen.add(it) } }
                try {
                    // Sanity: the sibling channel works before the close.
                    b.send("BEFORE-MARKER")
                    assertTrue("sibling echo before close", awaitMarker(seen, "BEFORE-MARKER"))

                    // Explicit close of tab A (what the power button does).
                    a.close()

                    // Transport must still be up…
                    assertTrue("transport died after channel close", t.client.isConnected)
                    // …and the sibling must still echo…
                    b.send("AFTER-MARKER")
                    assertTrue("sibling echo after close", awaitMarker(seen, "AFTER-MARKER"))
                    // …and a third channel must open on the same transport.
                    conn.openShellOnTransport(t.client, profile()).close()
                } finally {
                    collect.cancel()
                    try { b.close() } catch (_: Exception) { }
                }
            } finally {
                t.close()
            }
        }
    }

    @Test
    fun `solo transports are independent control`() = runBlocking {
        withTimeout(60_000) {
            val conn = SshConnector()
            val t1 = connect(conn)
            val t2 = connect(conn)
            try {
                assertNotSame(t1.client, t2.client)
                val s1 = conn.openShellOnTransport(t1.client, profile())
                val s2 = conn.openShellOnTransport(t2.client, profile())
                val seen = mutableListOf<String>()
                val collect = launch(Dispatchers.IO) { s2.output.collect { seen.add(it) } }
                try {
                    s1.close()
                    t1.close()
                    assertTrue(t2.client.isConnected)
                    s2.send("SOLO-MARKER")
                    assertTrue("solo sibling echo", awaitMarker(seen, "SOLO-MARKER"))
                } finally {
                    collect.cancel()
                    try { s2.close() } catch (_: Exception) { }
                }
            } finally {
                t2.close()
            }
        }
    }

    /**
     * Desktop close parity (shell.ts `SSHShellSession.destroy`): closing a
     * tab on a shared transport must NOT send channel-close to the server —
     * MINA calls `Command.destroy` on channel close, so the server-side
     * command objects are the oracle. Only the final transport teardown may
     * end them (client-side disconnect, always asserted).
     */
    @Test
    fun `closing a shared tab sends no channel close to the server`() = runBlocking {
        withTimeout(60_000) {
            val conn = SshConnector()
            val t = connect(conn)
            try {
                val a = conn.openShellOnTransport(t.client, profile())
                assertTrue("server saw tab A", awaitCondition({ commands.size >= 1 }))
                val b = conn.openShellOnTransport(t.client, profile())
                assertTrue("server saw tab B", awaitCondition({ commands.size >= 2 }))
                // Mirror the VM: shared shells only release the pool on close.
                a.onClosed = {}
                b.onClosed = {}
                val seen = mutableListOf<String>()
                val collect = launch(Dispatchers.IO) { b.output.collect { seen.add(it) } }

                a.close()
                // A would-be channel-close arrives in ms locally; 1.5s leaves
                // no room for a late arrival to hide.
                delay(1_500)
                assertFalse("server saw channel close for tab A", commands[0].destroyed.get())
                assertTrue("transport dropped after tab close", t.client.isConnected)
                b.send("SHARED-MARKER")
                assertTrue("sibling echo after tab close", awaitMarker(seen, "SHARED-MARKER"))

                b.close()
                delay(1_500)
                assertFalse("server saw channel close for tab B", commands[1].destroyed.get())
                collect.cancel()

                // Last release disconnects the transport (client-side fact).
                t.close()
                assertFalse(t.client.isConnected)
            } finally {
                try { t.close() } catch (_: Exception) { }
            }
        }
    }

    private suspend fun awaitMarker(seen: List<String>, marker: String, timeoutMs: Long = 10_000): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (seen.any { it.contains(marker) }) return true
            delay(50)
        }
        return false
    }

    private suspend fun awaitCondition(cond: () -> Boolean, timeoutMs: Long = 10_000): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (cond()) return true
            delay(50)
        }
        return false
    }

    /** Raw byte echo: no PTY processing, so markers come back verbatim. */
    private class EchoCommand : Command {
        private var input: InputStream? = null
        private var output: OutputStream? = null
        private var callback: ExitCallback? = null
        private var pump: Thread? = null
        /** Set when the server tears the channel down (channel-close oracle). */
        val destroyed = AtomicBoolean(false)

        override fun setInputStream(`in`: InputStream) { input = `in` }
        override fun setOutputStream(out: OutputStream) { output = out }
        override fun setErrorStream(err: OutputStream) { }
        override fun setExitCallback(cb: ExitCallback) { callback = cb }

        override fun start(channel: ChannelSession, env: Environment) {
            pump = thread(isDaemon = true, name = "mina-echo") {
                try {
                    val inp = input ?: return@thread
                    val out = output ?: return@thread
                    val buf = ByteArray(4096)
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        out.flush()
                    }
                } catch (_: Exception) {
                    // Channel closed.
                } finally {
                    try { callback?.onExit(0) } catch (_: Exception) { }
                }
            }
        }

        override fun destroy(channel: ChannelSession) {
            destroyed.set(true)
            pump?.interrupt()
            pump = null
        }
    }
}
