package id.web.izs.sshclient

import id.web.izs.sshclient.core.config.SshOptions
import id.web.izs.sshclient.core.config.SshProfile
import id.web.izs.sshclient.core.ssh.SftpTransfer
import id.web.izs.sshclient.core.ssh.SshConnector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.shell.ShellFactory
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.random.Random

/**
 * SFTP download/upload against a local MINA sshd with the SFTP subsystem
 * (known-good server). Test-only MINA SFTP dep; never ships in the APK.
 */
class SftpTransferTest {

    private lateinit var sshd: SshServer
    private var port: Int = 0
    private lateinit var serverRoot: File
    private lateinit var localDir: File

    @Before
    fun startServer() {
        serverRoot = Files.createTempDirectory("sftp-server").toFile()
        localDir = Files.createTempDirectory("sftp-local").toFile()
        val keyFile = Files.createTempFile("mina-host", ".key")
        sshd = SshServer.setUpDefaultServer()
        sshd.port = 0
        sshd.keyPairProvider = SimpleGeneratorHostKeyProvider(keyFile)
        sshd.passwordAuthenticator = PasswordAuthenticator { _, _, _ -> true }
        sshd.subsystemFactories = listOf(SftpSubsystemFactory())
        sshd.fileSystemFactory = VirtualFileSystemFactory(serverRoot.toPath())
        // Minimal shell so the shared-transport test can open a shell
        // channel next to SFTP (SFTP-only servers reject pty/shell).
        sshd.shellFactory = ShellFactory { HoldCommand() }
        sshd.start()
        port = sshd.port
    }

    @After
    fun stopServer() {
        try { sshd.stop(true) } catch (_: Exception) { }
        serverRoot.deleteRecursively()
        localDir.deleteRecursively()
    }

    private fun profile() = SshProfile(
        id = "sftp-probe",
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
    fun `download fetches exact bytes with progress`() = runBlocking {
        withTimeout(60_000) {
            val payload = Random.nextBytes(300_000)
            File(serverRoot, "hello.bin").writeBytes(payload)
            val conn = SshConnector()
            val t = connect(conn)
            try {
                val seen = mutableListOf<Pair<Long, Long>>()
                val dest = File(localDir, "sub/dir/hello.bin")
                SftpTransfer.download(t.client, "hello.bin", dest) { done, total ->
                    seen.add(done to total)
                }
                assertArrayEquals(payload, dest.readBytes())
                assertTrue("no progress reported", seen.isNotEmpty())
                assertEquals(payload.size.toLong(), seen.last().first)
                assertEquals(payload.size.toLong(), seen.last().second)
            } finally {
                t.close()
            }
        }
    }

    @Test
    fun `upload stores exact bytes readable back`() = runBlocking {
        withTimeout(60_000) {
            val payload = Random.nextBytes(300_000)
            val src = File(localDir, "up.bin").also { it.writeBytes(payload) }
            val conn = SshConnector()
            val t = connect(conn)
            try {
                var last: Pair<Long, Long>? = null
                SftpTransfer.upload(t.client, src, "up.bin") { done, total ->
                    last = done to total
                }
                assertArrayEquals(payload, File(serverRoot, "up.bin").readBytes())
                assertEquals(payload.size.toLong(), last?.first)
                assertEquals(payload.size.toLong(), last?.second)
            } finally {
                t.close()
            }
        }
    }

    @Test
    fun `download of a missing file fails with a clear message`() = runBlocking {
        withTimeout(60_000) {
            val conn = SshConnector()
            val t = connect(conn)
            try {
                try {
                    SftpTransfer.download(t.client, "nope.bin", File(localDir, "nope.bin"))
                    fail("Expected IllegalStateException")
                } catch (e: IllegalStateException) {
                    assertTrue(e.message!!.startsWith("SFTP download failed"))
                }
                assertFalse(File(localDir, "nope.bin").exists())
            } finally {
                t.close()
            }
        }
    }

    @Test
    fun `upload of a missing local file is rejected before contact`() = runBlocking {
        withTimeout(60_000) {
            val conn = SshConnector()
            val t = connect(conn)
            try {
                try {
                    SftpTransfer.upload(t.client, File(localDir, "ghost.bin"), "ghost.bin")
                    fail("Expected IllegalArgumentException")
                } catch (e: IllegalArgumentException) {
                    assertTrue(e.message!!.contains("Local file not found"))
                }
            } finally {
                t.close()
            }
        }
    }

    @Test
    fun `large file round-trips in chunks without full buffering`() = runBlocking {
        withTimeout(120_000) {
            // 5 MB pseudo-random (low compression, real bytes on the wire):
            // xorshift64* fill, then SHA-256 the file to compare by digest
            // instead of holding two 20 MB arrays in RAM at once.
            val size = 5 * 1024 * 1024
            val src = File(localDir, "big.bin")
            var state = 0x123456789ABCDEFUL
            val digestSrc = java.security.MessageDigest.getInstance("SHA-256")
            src.outputStream().buffered(1 shl 20).use { out ->
                var left = size
                val chunk = ByteArray(1 shl 20)
                while (left > 0) {
                    val n = minOf(chunk.size, left)
                    var i = 0
                    while (i < n) {
                        state = state xor (state shr 12)
                        state = state xor (state shl 25)
                        state = state xor (state shr 27)
                        val v = state * 2685821657736338717UL
                        for (b in 0 until 8) {
                            if (i >= n) break
                            chunk[i++] = (v shr (b * 8)).toByte()
                        }
                    }
                    out.write(chunk, 0, n)
                    digestSrc.update(chunk, 0, n)
                    left -= n
                }
            }
            val conn = SshConnector()
            val t = connect(conn)
            try {
                var upLast = 0L
                var upCalls = 0
                SftpTransfer.upload(t.client, src, "big.bin") { done, total ->
                    upLast = done
                    upCalls++
                    assertEquals(size.toLong(), total)
                }
                assertEquals(size.toLong(), upLast)
                assertTrue("expected chunked progress, got $upCalls calls", upCalls > 10)
                val dest = File(localDir, "big-dl.bin")
                var downLast = 0L
                SftpTransfer.download(t.client, "big.bin", dest) { done, _ ->
                    downLast = done
                }
                assertEquals(size.toLong(), downLast)
                assertEquals(size.toLong(), dest.length())
                val digestDl = java.security.MessageDigest.getInstance("SHA-256")
                dest.inputStream().buffered(1 shl 20).use { ins ->
                    val buf = ByteArray(1 shl 20)
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        digestDl.update(buf, 0, n)
                    }
                }
                assertArrayEquals(digestSrc.digest(), digestDl.digest())
            } finally {
                t.close()
            }
        }
    }

    @Test
    fun `transfer works on a shared transport next to a shell`() = runBlocking {
        withTimeout(60_000) {
            val payload = "shared-ride".toByteArray()
            File(serverRoot, "shared.bin").writeBytes(payload)
            val conn = SshConnector()
            val t = connect(conn)
            try {
                val shell = conn.openShellOnTransport(t.client, profile())
                shell.onClosed = {}
                try {
                    val dest = File(localDir, "shared.bin")
                    SftpTransfer.download(t.client, "shared.bin", dest)
                    assertArrayEquals(payload, dest.readBytes())
                    assertTrue("transport died under SFTP", t.client.isConnected)
                } finally {
                    try { shell.close() } catch (_: Exception) { }
                }
            } finally {
                t.close()
            }
        }
    }

    @Test
    fun `listDir returns entries sorted dirs-first`() = runBlocking {
        withTimeout(60_000) {
            File(serverRoot, "b.txt").writeBytes("b".toByteArray())
            File(serverRoot, "a.txt").writeBytes("a".toByteArray())
            File(serverRoot, "sub").mkdir()
            val conn = SshConnector()
            val t = connect(conn)
            try {
                val entries = SftpTransfer.listDir(t.client, ".")
                assertEquals(
                    listOf("sub" to true, "a.txt" to false, "b.txt" to false),
                    entries.map { it.name to it.isDirectory },
                )
                assertEquals(1L, entries.first { it.name == "a.txt" }.size)
                try {
                    SftpTransfer.listDir(t.client, "no-such-dir")
                    fail("Expected IllegalStateException")
                } catch (e: IllegalStateException) {
                    assertTrue(e.message!!.startsWith("SFTP list failed"))
                }
            } finally {
                t.close()
            }
        }
    }

    @Test
    fun `cancelling a download stops it and deletes the partial file`() = runBlocking {
        withTimeout(60_000) {
            val payload = Random.nextBytes(2_000_000)
            File(serverRoot, "cancel.bin").writeBytes(payload)
            val conn = SshConnector()
            val t = connect(conn)
            try {
                val dest = File(localDir, "cancel.bin")
                var callbacks = 0
                val firstChunk = CompletableDeferred<Unit>()
                val job = launch {
                    SftpTransfer.download(t.client, "cancel.bin", dest) { done, _ ->
                        callbacks++
                        if (done > 0) firstChunk.complete(Unit)
                    }
                }
                // Cancel mid-flight, from the test thread.
                firstChunk.await()
                val start = System.currentTimeMillis()
                job.cancel()
                // The abort surfaces as CancellationException on a real
                // server (pending read fails on channel close); the MINA
                // test server answers the raced read with EOF instead, which
                // ends the loop silently. Both are "stopped", so accept
                // either — what matters is observable: it stops fast, the
                // job is cancelled, and no partial file survives.
                try {
                    job.join()
                } catch (e: CancellationException) {
                    // Ideal path: user-cancel by type.
                }
                assertTrue("cancel took too long", System.currentTimeMillis() - start < 15_000)
                assertTrue("cancel did not engage", job.isCancelled)
                assertFalse("transfer completed despite cancel", dest.exists() && dest.length() == payload.size.toLong())
                assertFalse("partial file survived cancel", dest.exists())
                assertTrue("no progress happened at all", callbacks > 0)
            } finally {
                t.close()
            }
        }
    }

    /** Minimal shell: holds the channel open, discards input. */
    private class HoldCommand : Command {
        private var input: java.io.InputStream? = null
        private var callback: ExitCallback? = null
        private var pump: Thread? = null

        override fun setInputStream(`in`: java.io.InputStream) { input = `in` }
        override fun setOutputStream(out: java.io.OutputStream) { }
        override fun setErrorStream(err: java.io.OutputStream) { }
        override fun setExitCallback(cb: ExitCallback) { callback = cb }

        override fun start(channel: ChannelSession, env: Environment) {
            pump = kotlin.concurrent.thread(isDaemon = true, name = "mina-hold") {
                try {
                    val buf = ByteArray(4096)
                    while (true) {
                        if ((input?.read(buf) ?: -1) < 0) break
                    }
                } catch (_: Exception) {
                } finally {
                    try { callback?.onExit(0) } catch (_: Exception) { }
                }
            }
        }

        override fun destroy(channel: ChannelSession) {
            pump?.interrupt()
            pump = null
        }
    }
}
