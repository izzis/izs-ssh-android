package id.web.izs.sshclient.core.ssh

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.RemoteFile
import net.schmizz.sshj.sftp.SFTPClient
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.EnumSet

/**
 * SFTP file transfer over an already-authenticated [SSHClient] (own fresh
 * transport or a multiplex-shared one — SFTP is just another channel, so
 * it rides alongside shell tabs). Three functions by design: list a
 * directory, download (remote -> local), upload (local -> remote).
 * No rename/delete/mkdir — out of scope.
 *
 * Both directions stream in 64 KB chunks with a progress callback, so
 * multi-hundred-MB files never sit fully in RAM. All blocking I/O runs
 * on [Dispatchers.IO]; [onProgress] is invoked on that same thread with
 * (transferredBytes, totalBytes) — total is -1 when the server does not
 * report a size. Failures throw IllegalStateException with a user-facing
 * message (missing file, auth/channel errors included).
 *
 * Cancellation = abort + cleanup, nothing else:
 * - cancelling the caller's Job closes the transfer's own SFTP channel,
 *   so even a stuck in-flight read/write fails at once;
 * - the failure surfaces as CancellationException (user-cancel, by type);
 * - a cancelled download deletes its partial local file; the source
 *   (upload) is never touched.
 * Any other failure throws IllegalStateException with a user-facing message.
 */
object SftpTransfer {

    /** One directory entry: the minimum the file-list UI needs. */
    data class RemoteEntry(
        val name: String,
        val isDirectory: Boolean,
        val size: Long,
        /** Server mtime, epoch seconds (0 when unknown). */
        val mtime: Long,
    )

    /**
     * List [remote] (directories first, then alphabetical; `.`/`..`
     * excluded).
     */
    suspend fun listDir(client: SSHClient, remote: String): List<RemoteEntry> =
        withContext(Dispatchers.IO) {
            require(remote.isNotBlank()) { "Remote path is empty" }
            try {
                client.newSFTPClient().use { sftp ->
                    sftp.ls(remote)
                        .filter { it.name != "." && it.name != ".." }
                        .map { r ->
                            val attrs = try { r.attributes } catch (_: Exception) { null }
                            RemoteEntry(
                                name = r.name,
                                isDirectory = r.isDirectory,
                                size = attrs?.size ?: -1L,
                                mtime = try { attrs?.mtime ?: 0L } catch (_: Exception) { 0L },
                            )
                        }
                        .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                }
            } catch (e: IllegalArgumentException) {
                throw e
            } catch (e: Exception) {
                throw IllegalStateException("SFTP list failed: ${e.message ?: e::class.simpleName}")
            }
        }

    /**
     * Download [remote] to [local] (parent dirs created, existing file
     * overwritten).
     */
    suspend fun download(
        client: SSHClient,
        remote: String,
        local: File,
        onProgress: (transferred: Long, total: Long) -> Unit = { _, _ -> },
    ): Unit = withContext(Dispatchers.IO) {
        require(remote.isNotBlank()) { "Remote path is empty" }
        val job = coroutineContext[Job]
        // Plain handle vars + manual close (no nested inline `use`): every
        // exit path below must stay visible — cancel included.
        var sftp: SFTPClient? = null
        var rf: RemoteFile? = null
        var out: FileOutputStream? = null
        try {
            // Each transfer owns a fresh SFTP channel, so aborting (or
            // failing) here never disturbs sibling tabs or other transfers.
            val c = client.newSFTPClient()
            sftp = c
            // Abort = close the channel: an in-flight blocking read fails
            // at once instead of waiting for the chunk (or a stuck server).
            // (Fires on normal completion too; then everything is already
            // closed and the call below is a swallowed no-op.)
            job?.invokeOnCompletion {
                runCatching { c.close() }
            }
            val f = c.open(remote, EnumSet.of(OpenMode.READ))
            rf = f
            val total = try { f.length() } catch (_: Exception) { -1L }
            local.parentFile?.mkdirs()
            val o = FileOutputStream(local)
            out = o
            val buf = ByteArray(64 * 1024)
            var offset = 0L
            while (true) {
                // Cancel -> abort at the next chunk boundary. A cancelled
                // transfer always throws below, never a silent success.
                if (job?.isCancelled == true) throw CancellationException("Transfer cancelled")
                val n = f.read(offset, buf, 0, buf.size)
                if (n < 0) break
                o.write(buf, 0, n)
                offset += n
                onProgress(offset, total)
            }
            if (job?.isCancelled == true) throw CancellationException("Transfer cancelled")
        } catch (e: CancellationException) {
            // Cancelled: the partial file must not survive (a half file
            // looks complete but is corrupt). Best-effort delete, then
            // surface user-cancel by type.
            runCatching { local.delete() }
            throw e
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            // Close-induced failure while cancelled IS a user cancel (the
            // channel close above), not an error — surface by type.
            if (job?.isCancelled == true) {
                runCatching { local.delete() }
                throw CancellationException("Transfer cancelled")
            }
            throw IllegalStateException("SFTP download failed: ${e.message ?: e::class.simpleName}")
        } finally {
            // Abort + close only — never throw from here. The source file
            // (upload) is never touched; the partial destination
            // (download) is deleted on the cancel paths above.
            runCatching { out?.close() }
            runCatching { rf?.close() }
            runCatching { sftp?.close() }
        }
    }

    /**
     * Upload [local] to [remote] (remote file created/truncated).
     */
    suspend fun upload(
        client: SSHClient,
        local: File,
        remote: String,
        onProgress: (transferred: Long, total: Long) -> Unit = { _, _ -> },
    ): Unit = withContext(Dispatchers.IO) {
        require(remote.isNotBlank()) { "Remote path is empty" }
        require(local.isFile) { "Local file not found: ${local.name}" }
        val total = local.length()
        val job = coroutineContext[Job]
        var sftp: SFTPClient? = null
        var rf: RemoteFile? = null
        var ins: FileInputStream? = null
        try {
            val c = client.newSFTPClient()
            sftp = c
            job?.invokeOnCompletion {
                runCatching { c.close() }
            }
            val f = c.open(remote, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC))
            rf = f
            val i = FileInputStream(local)
            ins = i
            val buf = ByteArray(64 * 1024)
            var offset = 0L
            while (true) {
                if (job?.isCancelled == true) throw CancellationException("Transfer cancelled")
                val n = i.read(buf)
                if (n < 0) break
                f.write(offset, buf, 0, n)
                offset += n
                onProgress(offset, total)
            }
            if (job?.isCancelled == true) throw CancellationException("Transfer cancelled")
            // Note: a cancelled upload can leave a partial REMOTE file (the
            // channel is already closed, so it cannot be removed here). It is
            // harmless: the next upload truncates it. The local source file
            // is never touched.
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            if (job?.isCancelled == true) {
                throw CancellationException("Transfer cancelled")
            }
            throw IllegalStateException("SFTP upload failed: ${e.message ?: e::class.simpleName}")
        } finally {
            // Abort + close only — never throw from here. The local source
            // is never touched; a partial remote file is truncated by the
            // next upload.
            runCatching { ins?.close() }
            runCatching { rf?.close() }
            runCatching { sftp?.close() }
        }
    }
}
