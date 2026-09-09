package id.web.izs.sshclient.core.ssh

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.schmizz.sshj.SSHClient
import java.io.File
import java.util.UUID

/**
 * Session-scoped SFTP transfer host: one row per transfer (progress +
 * terminal state), each running in its own Job on [scope].
 *
 * Ownership rules (the whole contract):
 * - the manager outlives any screen: dismissing/backing out of the SFTP UI
 *   touches NOTHING — transfers keep running in the background;
 * - per-item Cancel aborts that transfer only (its own SFTP channel is
 *   closed, so even a stuck chunk fails at once);
 * - [cancelAll] aborts everything owned here — wired to session
 *   disconnect/close, never to back/dismiss;
 * - a cancelled/failed download's partial local file is deleted; the
 *   source file of an upload is never touched.
 */
class SftpTransferManager(private val scope: CoroutineScope) {

    enum class Direction { DOWNLOAD, UPLOAD }
    enum class Status { RUNNING, DONE, FAILED, CANCELLED }

    data class Transfer(
        val id: String,
        val name: String,
        val direction: Direction,
        val done: Long = 0L,
        val total: Long = -1L,
        val status: Status = Status.RUNNING,
        val error: String? = null,
    )

    private val _transfers = MutableStateFlow<List<Transfer>>(emptyList())
    val transfers: StateFlow<List<Transfer>> = _transfers.asStateFlow()

    private val guard = Any()
    private val jobs = mutableMapOf<String, Job>()
    // Finished download id -> its complete local file, until the UI takes
    // it for the Save-as picker. Session-scoped (like the jobs): reopening
    // the sheet after a background download still finds its file. Uploads
    // never land here (their staged copy is deleted via onSettled).
    private val dests = mutableMapOf<String, File>()

    /** Download [remote] to [local]; returns the transfer id for [cancel]. */
    fun startDownload(client: SSHClient, remote: String, local: File): String {
        require(remote.isNotBlank()) { "Remote path is empty" }
        val name = remote.substringAfterLast('/').takeIf { it.isNotBlank() } ?: remote
        val id = track(name, Direction.DOWNLOAD, onSettled = { status ->
            if (status != Status.DONE) runCatching { local.delete() }
        }) { onProgress ->
            SftpTransfer.download(client, remote, local, onProgress)
        }
        synchronized(guard) { dests[id] = local }
        return id
    }

    /**
     * Upload [local] to [remote]; [displayName] is the row label (the
     * local file is usually a random-named staged cache copy, so the UI
     * passes the real name). [onSettled] always runs once the transfer
     * reaches a terminal state (UI uses it to delete its staged copy).
     */
    fun startUpload(
        client: SSHClient,
        local: File,
        remote: String,
        displayName: String = local.name,
        onSettled: (Status) -> Unit = {},
    ): String {
        require(remote.isNotBlank()) { "Remote path is empty" }
        return track(displayName, Direction.UPLOAD, onSettled) { onProgress ->
            SftpTransfer.upload(client, local, remote, onProgress)
        }
    }

    /** Abort one transfer (best-effort false when unknown/already finished). */
    fun cancel(id: String): Boolean {
        val job = synchronized(guard) { jobs[id] } ?: return false
        job.cancel()
        return true
    }

    /** Abort everything owned here (session disconnect/close — never back). */
    fun cancelAll() {
        val all = synchronized(guard) { jobs.values.toList() }
        all.forEach { it.cancel() }
    }

    /** Take a finished download's local file (once); null when unknown/already taken. */
    fun takeDownloadFile(id: String): File? = synchronized(guard) { dests.remove(id) }

    /** Drop DONE/FAILED/CANCELLED rows from the list. */
    fun clearFinished() {
        _transfers.update { list ->
            val running = list.filter { it.status == Status.RUNNING }
            // Untaken finished downloads would strand their cache file
            // (nobody will ever Save-as them) — delete with the row.
            // Uploads never have dests; taken ones already removed.
            val dead = list.map { it.id }.toSet() - running.map { it.id }.toSet()
            synchronized(guard) {
                dead.forEach { dests.remove(it)?.let { f -> runCatching { f.delete() } } }
            }
            running
        }
    }

    private fun progress(id: String, done: Long, total: Long) {
        _transfers.update { list ->
            list.map { if (it.id == id) it.copy(done = done, total = total) else it }
        }
    }

    private fun mark(id: String, status: Status, error: String? = null) {
        _transfers.update { list ->
            list.map { if (it.id == id) it.copy(status = status, error = error) else it }
        }
    }

    private fun track(
        name: String,
        direction: Direction,
        onSettled: (Status) -> Unit = {},
        block: suspend ((Long, Long) -> Unit) -> Unit,
    ): String {
        val id = UUID.randomUUID().toString()
        _transfers.update { it + Transfer(id = id, name = name, direction = direction) }
        val job = scope.launch {
            var status = Status.FAILED
            var error: String? = "Transfer failed"
            try {
                block { done, total -> progress(id, done, total) }
                status = Status.DONE
                error = null
            } catch (e: CancellationException) {
                status = Status.CANCELLED
                error = null
                throw e
            } catch (e: Exception) {
                status = Status.FAILED
                error = e.message ?: "Transfer failed"
            } finally {
                // Settle first, publish second: when the UI observes a
                // terminal row, side-effects (cache cleanup) are done.
                runCatching { onSettled(status) }
                mark(id, status, error)
                synchronized(guard) { jobs.remove(id) }
            }
        }
        synchronized(guard) { jobs[id] = job }
        return id
    }
}
