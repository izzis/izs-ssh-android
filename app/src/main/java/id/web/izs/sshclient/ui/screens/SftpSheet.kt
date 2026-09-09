package id.web.izs.sshclient.ui.screens

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import id.web.izs.sshclient.core.ssh.SftpTransfer
import id.web.izs.sshclient.core.ssh.SftpTransferManager
import id.web.izs.sshclient.ui.SshSessionViewModel
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient

/**
 * SFTP browser + transfers as a bottom sheet over the terminal.
 *
 * Lifetime rule (the point of this sheet): dismissing or backing out of
 * here is UI-only — transfers are owned by the session's
 * [SftpTransferManager] and keep running in the background. Only the
 * per-item Cancel aborts one transfer, and only session
 * disconnect/close aborts them all.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SftpSheet(
    sessionViewModel: SshSessionViewModel,
    sessionId: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Open at half by POSITION, not content size: SheetState starts at the
    // partial anchor, so a long file list does not push the sheet to full
    // on open — and full stays reachable by drag (the list is unbounded).
    // (rememberModalBottomSheetState in this BOM has no initialValue, and
    // capping the list instead would remove the full anchor entirely.)
    val density = LocalDensity.current
    val sheetState = remember(density) {
        with(density) {
            SheetState(
                skipPartiallyExpanded = false,
                positionalThreshold = { 56.dp.toPx() },
                velocityThreshold = { 125.dp.toPx() },
                initialValue = SheetValue.PartiallyExpanded,
            )
        }
    }
    val handle = remember(sessionId) { sessionViewModel.get(sessionId) }
    // Session-owned: survives this sheet, tab switches, and rotation.
    val manager = remember(sessionId) { sessionViewModel.sftpOf(sessionId) }
    val transfers by manager.transfers.collectAsState()

    var dir by remember { mutableStateOf(".") }
    var entries by remember { mutableStateOf<List<SftpTransfer.RemoteEntry>?>(null) }
    var listing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    // Download handshake: a finished download's cache file is taken from
    // the (session-scoped) manager for the Save-as picker. Terminal
    // non-DONE rows take nothing (the manager already deleted their
    // partial file). Untaken = already saved/cleared.
    var pickerName by remember { mutableStateOf<String?>(null) }
    var pendingFile by remember { mutableStateOf<File?>(null) }
    // Upload DONE ids already re-listed (reload once per finished upload).
    val reloaded = remember { mutableSetOf<String>() }
    // Same-name collision: everything the choice needs, INCLUDING the dir
    // (the user may navigate elsewhere while the dialog is open — the
    // upload must land where the file was picked, not where they are now).
    // A silent TRUNC here would destroy the server's file without asking.
    var overwriteAsk by remember { mutableStateOf<PendingUpload?>(null) }

    /** Start the upload now that the name question is settled. */
    fun beginUpload(client: SSHClient, staged: File, dir: String, remoteName: String) {
        // The staged cache copy is deleted on settle (any terminal state);
        // the user's own file is never touched. displayName keeps the
        // random staged filename ("sftp-up-…") out of the transfer rows.
        manager.startUpload(client, staged, joinDir(dir, remoteName), remoteName) {
            staged.delete()
        }
    }

    val downloadPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*"),
    ) { uri ->
        val tmp = pendingFile
        pendingFile = null
        val name = pickerName
        pickerName = null
        if (tmp == null) return@rememberLauncherForActivityResult
        if (uri == null) {
            tmp.delete()
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        tmp.inputStream().use { ins -> ins.copyTo(out) }
                    } ?: throw IllegalStateException("Could not write file")
                }
                info = "Saved $name"
            } catch (e: Exception) {
                error = e.message
            } finally {
                tmp.delete()
            }
        }
    }

    suspend fun reload() {
        val client = handle?.shell?.client ?: return
        listing = true
        error = null
        try {
            entries = withContext(Dispatchers.IO) { SftpTransfer.listDir(client, dir) }
        } catch (e: Exception) {
            error = e.message
        } finally {
            listing = false
        }
    }
    LaunchedEffect(sessionId, dir) { reload() }

    // Settle handshake: DONE downloads go to the Save-as picker, finished
    // uploads refresh the listing. Runs on every list change; each id is
    // handled once (take is destructive / reloaded guard).
    LaunchedEffect(transfers) {
        for (t in transfers) {
            if (t.status == SftpTransferManager.Status.RUNNING) continue
            // DONE-only take: FAILED/CANCELLED rows must fall through to
            // the error display below (their partial is already deleted).
            val tmp = if (t.direction == SftpTransferManager.Direction.DOWNLOAD &&
                t.status == SftpTransferManager.Status.DONE
            ) {
                manager.takeDownloadFile(t.id)
            } else {
                null
            }
            if (tmp != null) {
                pickerName = t.name
                downloadPicker.launch(t.name)
                // NOTE: dest already taken above; the tmp file reference
                // is kept in pendingFile below for the picker result.
                pendingFile = tmp
                continue
            }
            if (t.direction == SftpTransferManager.Direction.UPLOAD &&
                t.status == SftpTransferManager.Status.DONE &&
                reloaded.add(t.id)
            ) {
                info = "Uploaded ${t.name}"
                reload()
            }
            if (t.status == SftpTransferManager.Status.FAILED && reloaded.add(t.id)) {
                error = t.error ?: "Transfer failed"
            }
        }
    }

    val uploadPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val client = handle?.shell?.client ?: return@rememberLauncherForActivityResult
        scope.launch {
            error = null
            info = null
            try {
                // Real file name via DISPLAY_NAME: a SAF uri's last segment
                // is a numeric document id (".../document/1234"), NOT the
                // name — using it would upload as a random-number file.
                val name = displayName(context, uri)
                    ?: uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')
                        ?.takeIf { it.isNotBlank() } ?: "upload.bin"
                val staged = withContext(Dispatchers.IO) {
                    val tmp = File.createTempFile("sftp-up-", "-$name", context.cacheDir)
                    context.contentResolver.openInputStream(uri)?.use { ins ->
                        tmp.outputStream().use { out -> ins.copyTo(out) }
                    } ?: throw IllegalStateException("Could not read file")
                    tmp
                }
                // Same name already on the server: ask instead of silently
                // overwriting (upload opens TRUNC). The dialog below starts
                // it as Overwrite or Keep both ("name (1).ext").
                if (entries?.any { !it.isDirectory && it.name == name } == true) {
                    overwriteAsk = PendingUpload(staged, name, dir, client)
                } else {
                    beginUpload(client, staged, dir, name)
                }
            } catch (e: Exception) {
                error = e.message
            }
        }
    }

    fun download(entry: SftpTransfer.RemoteEntry) {
        val client = handle?.shell?.client ?: return
        scope.launch {
            error = null
            info = null
            try {
                val tmp = withContext(Dispatchers.IO) {
                    File.createTempFile("sftp-dl-", "-${entry.name}", context.cacheDir)
                }
                // No local bookkeeping: the manager owns id -> tmp
                // (takeDownloadFile), so a dismissed + reopened sheet still
                // finds the finished file. On start failure the temp dies here.
                try {
                    manager.startDownload(client, joinDir(dir, entry.name), tmp)
                } catch (e: Exception) {
                    tmp.delete()
                    throw e
                }
            } catch (e: Exception) {
                error = e.message
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "SFTP · ${handle?.profileSnapshot?.name ?: "session"}",
                style = MaterialTheme.typography.titleMedium,
            )
            if (handle == null) {
                Text("Session closed", color = MaterialTheme.colorScheme.error)
                return@Column
            }
            val hasShell by handle.hasShell.collectAsState()
            if (!hasShell) {
                Text(
                    "Not connected — SFTP rides the session transport.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            // Active + finished transfers: rows survive dismiss/back (the
            // manager owns them); Cancel aborts one, disconnect aborts all.
            if (transfers.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (t in transfers) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(
                                if (t.direction == SftpTransferManager.Direction.DOWNLOAD) {
                                    Icons.Filled.Download
                                } else {
                                    Icons.Filled.Upload
                                },
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Column(Modifier.weight(1f)) {
                                Text(t.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                val label = when (t.status) {
                                    SftpTransferManager.Status.RUNNING ->
                                        "${formatSize(t.done)}${if (t.total > 0) " / ${formatSize(t.total)}" else ""}"
                                    SftpTransferManager.Status.DONE -> "Done"
                                    SftpTransferManager.Status.FAILED -> t.error ?: "Failed"
                                    SftpTransferManager.Status.CANCELLED -> "Cancelled"
                                }
                                Text(
                                    label,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (t.status == SftpTransferManager.Status.FAILED) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                                if (t.status == SftpTransferManager.Status.RUNNING) {
                                    if (t.total > 0) LinearProgressIndicator(
                                        progress = { t.done.toFloat() / t.total.toFloat() },
                                        modifier = Modifier.fillMaxWidth(),
                                    ) else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                }
                            }
                            if (t.status == SftpTransferManager.Status.RUNNING) {
                                TextButton(onClick = { manager.cancel(t.id) }) { Text("Cancel") }
                            }
                        }
                    }
                    if (transfers.any { it.status != SftpTransferManager.Status.RUNNING }) {
                        TextButton(
                            onClick = { manager.clearFinished() },
                            modifier = Modifier.align(Alignment.End),
                        ) { Text("Clear finished") }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val parent = parentDir(dir)
                IconButton(enabled = parent != null && !listing && hasShell, onClick = { dir = parent!! }) {
                    Icon(Icons.Filled.ArrowUpward, contentDescription = "Up one directory")
                }
                Text(
                    dir,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                IconButton(enabled = hasShell, onClick = { uploadPicker.launch("*/*") }) {
                    Icon(Icons.Filled.Upload, contentDescription = "Upload file")
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            info?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            if (listing && entries == null) CircularProgressIndicator()
            Card(
                // Fill the sheet height from the first frame: with the list
                // taking all remaining space, content height never changes
                // when entries/transfers land — so the sheet opens at half
                // (initialValue) and STAYS there, no balloon-then-settle.
                // Full stays reachable by drag; the list scrolls inside.
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) {
                val list = entries
                when {
                    list == null -> Text(
                        "Loading…",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    list.isEmpty() -> Text(
                        "Empty directory",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    else -> LazyColumn {
                        items(list, key = { (if (it.isDirectory) "d:" else "f:") + it.name }) { e ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.fillMaxWidth()
                                    .clickable(enabled = e.isDirectory && hasShell) { dir = joinDir(dir, e.name) }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                            ) {
                                Icon(
                                    if (e.isDirectory) Icons.Filled.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(e.name, style = MaterialTheme.typography.bodyMedium)
                                    if (!e.isDirectory) {
                                        Text(
                                            formatSize(e.size),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                if (!e.isDirectory) {
                                    IconButton(enabled = hasShell, onClick = { download(e) }) {
                                        Icon(Icons.Filled.Download, contentDescription = "Download ${e.name}")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        // Same-name choice: Overwrite (replace the server file), Keep both
        // (upload as "name (1).ext"), or Cancel (drop the staged copy).
        // The stored dir (not the current one) wins: the dialog may outlive
        // a navigation.
        overwriteAsk?.let { ask ->
            val (staged, name, dirAtPick, client) = ask
            AlertDialog(
                onDismissRequest = {
                    staged.delete()
                    overwriteAsk = null
                },
                title = { Text("File exists") },
                text = { Text("“$name” already exists in this folder.") },
                confirmButton = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = {
                                overwriteAsk = null
                                beginUpload(client, staged, dirAtPick, name)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Overwrite") }
                        OutlinedButton(
                            onClick = {
                                val taken = entries
                                    ?.filter { !it.isDirectory }
                                    ?.map { it.name }
                                    ?.toSet() ?: emptySet()
                                val free = freeName(name, taken)
                                overwriteAsk = null
                                beginUpload(client, staged, dirAtPick, free)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Keep both") }
                        TextButton(
                            onClick = {
                                staged.delete()
                                overwriteAsk = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Cancel") }
                    }
                },
                dismissButton = null,
            )
        }
    }
}

private fun joinDir(dir: String, name: String): String = if (dir == ".") name else "$dir/$name"

/** Upload waiting on the same-name choice (dir frozen at pick time). */
private data class PendingUpload(
    val staged: File,
    val name: String,
    val dir: String,
    val client: SSHClient,
)

/** First non-colliding sibling: "a.txt" -> "a (1).txt" -> "a (2).txt" … */
private fun freeName(wanted: String, taken: Set<String>): String {
    if (wanted !in taken) return wanted
    val dot = wanted.lastIndexOf('.')
    val stem = if (dot > 0) wanted.substring(0, dot) else wanted
    val ext = if (dot > 0) wanted.substring(dot) else ""
    var i = 1
    while ("$stem ($i)$ext" in taken) i++
    return "$stem ($i)$ext"
}

/** Real file name for a SAF uri (fallback chain is at the call site). */
private fun displayName(context: Context, uri: Uri): String? {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) return c.getString(0)?.takeIf { it.isNotBlank() }
    }
    return null
}

private fun parentDir(dir: String): String? {
    if (dir == ".") return null
    if (!dir.contains('/')) return "."
    return dir.substringBeforeLast('/')
}

/** Human size: B / KB / MB / GB with one decimal; negative (unknown) -> "—". */
private fun formatSize(bytes: Long): String {
    if (bytes < 0) return "—"
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    return "%.1f GB".format(mb / 1024.0)
}
