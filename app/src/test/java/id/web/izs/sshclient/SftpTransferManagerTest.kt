package id.web.izs.sshclient

import id.web.izs.sshclient.core.config.SshOptions
import id.web.izs.sshclient.core.config.SshProfile
import id.web.izs.sshclient.core.ssh.SftpTransferManager
import id.web.izs.sshclient.core.ssh.SftpTransferManager.Status
import id.web.izs.sshclient.core.ssh.SshConnector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.random.Random

/**
 * Session-owned transfer host against a local MINA sshd. Proves the UI
 * contract: rows track progress to a terminal state, per-item cancel
 * aborts with partial cleanup, and cancelAll aborts everything.
 */
class SftpTransferManagerTest {

    private lateinit var sshd: SshServer
    private var port: Int = 0
    private lateinit var serverRoot: File
    private lateinit var localDir: File
    private lateinit var mgrScope: CoroutineScope

    @Before
    fun startServer() {
        serverRoot = Files.createTempDirectory("sftpm-server").toFile()
        localDir = Files.createTempDirectory("sftpm-local").toFile()
        val keyFile = Files.createTempFile("mina-host", ".key")
        sshd = SshServer.setUpDefaultServer()
        sshd.port = 0
        sshd.keyPairProvider = SimpleGeneratorHostKeyProvider(keyFile)
        sshd.passwordAuthenticator = PasswordAuthenticator { _, _, _ -> true }
        sshd.subsystemFactories = listOf(SftpSubsystemFactory())
        sshd.fileSystemFactory = VirtualFileSystemFactory(serverRoot.toPath())
        sshd.start()
        port = sshd.port
        mgrScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    }

    @After
    fun stopServer() {
        try { mgrScope.cancel() } catch (_: Exception) { }
        try { sshd.stop(true) } catch (_: Exception) { }
        serverRoot.deleteRecursively()
        localDir.deleteRecursively()
    }

    private fun profile() = SshProfile(
        id = "sftpm-probe",
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

    /** Await a terminal state for [id]; returns the row. */
    private suspend fun awaitTerminal(
        mgr: SftpTransferManager,
        id: String,
    ): SftpTransferManager.Transfer {
        var row: SftpTransferManager.Transfer? = null
        withTimeout(60_000) {
            while (true) {
                row = mgr.transfers.value.firstOrNull { it.id == id }
                if (row != null && row.status != Status.RUNNING) break
                delay(50)
            }
        }
        return row!!
    }

    @Test
    fun `download completes as DONE with exact bytes`() = runBlocking {
        val payload = Random.nextBytes(300_000)
        File(serverRoot, "a.bin").writeBytes(payload)
        val conn = SshConnector()
        val t = connect(conn)
        try {
            val mgr = SftpTransferManager(mgrScope)
            val dest = File(localDir, "a.bin")
            val id = mgr.startDownload(t.client, "a.bin", dest)
            val row = awaitTerminal(mgr, id)
            assertEquals(Status.DONE, row.status)
            assertEquals(payload.size.toLong(), row.done)
            assertArrayEquals(payload, dest.readBytes())
            mgr.clearFinished()
            assertTrue(mgr.transfers.value.isEmpty())
        } finally {
            t.close()
        }
    }

    @Test
    fun `upload completes as DONE and settles the staged copy`() = runBlocking {
        val payload = Random.nextBytes(300_000)
        val staged = File(localDir, "staged.bin").also { it.writeBytes(payload) }
        val conn = SshConnector()
        val t = connect(conn)
        try {
            val mgr = SftpTransferManager(mgrScope)
            var settled: Status? = null
            // Test plays the UI role: the staged cache copy is the
            // caller's, deleted via onSettled (the manager never touches it).
            val id = mgr.startUpload(t.client, staged, "staged.bin") {
                staged.delete()
                settled = it
            }
            val row = awaitTerminal(mgr, id)
            assertEquals(Status.DONE, row.status)
            assertArrayEquals(payload, File(serverRoot, "staged.bin").readBytes())
            assertEquals(Status.DONE, settled)
            assertFalse("staged cache copy must be deleted on settle", staged.exists())
        } finally {
            t.close()
        }
    }

    @Test
    fun `cancel aborts one download and deletes the partial`() = runBlocking {
        // Gated cancel: deterministic abort on the first chunk (no timing race).
        val payload = Random.nextBytes(2_000_000)
        File(serverRoot, "big.bin").writeBytes(payload)
        val conn = SshConnector()
        val t = connect(conn)
        try {
            val mgr = SftpTransferManager(mgrScope)
            val dest = File(localDir, "big.bin")
            val id = mgr.startDownload(t.client, "big.bin", dest)
            withTimeout(60_000) {
                while (mgr.transfers.value.firstOrNull { it.id == id }?.done == 0L) delay(10)
                assertTrue(mgr.cancel(id))
            }
            val row = awaitTerminal(mgr, id)
            assertEquals(Status.CANCELLED, row.status)
            assertFalse("partial file must not survive cancel", dest.exists())
        } finally {
            t.close()
        }
    }

    @Test
    fun `cancelAll aborts every running transfer`() = runBlocking {
        val payload = Random.nextBytes(2_000_000)
        File(serverRoot, "c1.bin").writeBytes(payload)
        File(serverRoot, "c2.bin").writeBytes(payload)
        val conn = SshConnector()
        val t = connect(conn)
        try {
            val mgr = SftpTransferManager(mgrScope)
            val d1 = File(localDir, "c1.bin")
            val d2 = File(localDir, "c2.bin")
            val id1 = mgr.startDownload(t.client, "c1.bin", d1)
            val id2 = mgr.startDownload(t.client, "c2.bin", d2)
            withTimeout(60_000) {
                while (mgr.transfers.value.count { it.done > 0 } < 2) delay(10)
                mgr.cancelAll()
            }
            assertEquals(Status.CANCELLED, awaitTerminal(mgr, id1).status)
            assertEquals(Status.CANCELLED, awaitTerminal(mgr, id2).status)
            assertFalse(d1.exists())
            assertFalse(d2.exists())
        } finally {
            t.close()
        }
    }

    @Test
    fun `missing file surfaces FAILED with a message`() = runBlocking {
        val conn = SshConnector()
        val t = connect(conn)
        try {
            val mgr = SftpTransferManager(mgrScope)
            val id = mgr.startDownload(t.client, "nope.bin", File(localDir, "nope.bin"))
            val row = awaitTerminal(mgr, id)
            assertEquals(Status.FAILED, row.status)
            assertNotNull(row.error)
        } finally {
            t.close()
        }
    }

    @Test
    fun `finished download file is takeable once for the Save-as picker`() = runBlocking {
        val payload = Random.nextBytes(300_000)
        File(serverRoot, "take.bin").writeBytes(payload)
        val conn = SshConnector()
        val t = connect(conn)
        try {
            val mgr = SftpTransferManager(mgrScope)
            val dest = File(localDir, "take.bin")
            val id = mgr.startDownload(t.client, "take.bin", dest)
            assertEquals(Status.DONE, awaitTerminal(mgr, id).status)
            // Survives a "reopen": the take works after completion, exactly
            // once — a second sheet must not re-trigger the picker.
            assertArrayEquals(payload, mgr.takeDownloadFile(id)!!.readBytes())
            assertNull(mgr.takeDownloadFile(id))
            assertNull(mgr.takeDownloadFile("unknown-id"))
        } finally {
            t.close()
        }
    }

    @Test
    fun `clearFinished drops rows and deletes untaken finished files`() = runBlocking {
        val payload = Random.nextBytes(100_000)
        File(serverRoot, "drop.bin").writeBytes(payload)
        val conn = SshConnector()
        val t = connect(conn)
        try {
            val mgr = SftpTransferManager(mgrScope)
            val dest = File(localDir, "drop.bin")
            val id = mgr.startDownload(t.client, "drop.bin", dest)
            assertEquals(Status.DONE, awaitTerminal(mgr, id).status)
            // Never taken for Save-as (sheet stayed closed): clearing must
            // not strand the complete cache file.
            mgr.clearFinished()
            assertTrue(mgr.transfers.value.isEmpty())
            assertFalse("untaken finished file must not leak", dest.exists())
        } finally {
            t.close()
        }
    }
}
