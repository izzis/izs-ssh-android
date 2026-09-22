package id.web.izs.sshclient.ui.screens

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
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
 *
 * Windowing lives in [AnchoredSheet] (shared with the New-tab picker):
 * the header below (title + directory navigation) is the drag-handle
 * zone, the file list scrolls first and only edge leftover moves the
 * sheet half <-> full <-> hidden — so a drag down past the top of the
 * list collapses full -> half -> away instead of fighting the scroll.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SftpSheet(
    sessionViewModel: SshSessionViewModel,
    sessionId: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val handle = remember(sessionId) { sessionViewModel.get(sessionId) }
    // Session-owned: survives this sheet, tab switches, and rotation.
    val manager = remember(sessionId) { sessionViewModel.sftpOf(sessionId) }
    val transfers by manager.transfers.collectAsState()

    var dir by remember { mutableStateOf(".") }
    var entries by remember { mutableStateOf<List<SftpTransfer.RemoteEntry>?>(null) }
    var listing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    // Desktop sftpPanel parity: filter box hidden until the header
    // button shows it; the X (or the button again) hides + clears.
    var showFilter by remember { mutableStateOf(false) }
    var filterText by remember { mutableStateOf("") }
    fun clearFilter() {
        showFilter = false
        filterText = ""
    }
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
                    } ?: throw IllegalStateException("Could not write the file")
                }
                info = "Saved \"$name\"."
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
    LaunchedEffect(sessionId, dir) {
        // Desktop parity: navigating clears the filter.
        clearFilter()
        reload()
    }

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
                info = "Uploaded \"${t.name}\"."
                reload()
            }
            if (t.status == SftpTransferManager.Status.FAILED && reloaded.add(t.id)) {
                error = t.error ?: "Transfer failed. Try again."
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
                    } ?: throw IllegalStateException("Could not read the file")
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

    if (handle == null) {
        AnchoredSheet(
            onDismiss = onDismiss,
            handle = {
                Text(
                    "SFTP",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            },
        ) {
            Text("This session is closed.", color = MaterialTheme.colorScheme.error)
        }
        return
    }
    val hasShell by handle.hasShell.collectAsState()

    AnchoredSheet(
        onDismiss = onDismiss,
        handle = {
            // Title + filter toggle share one row (filter at the right,
            // desktop sftpPanel header parity). The box below appears in
            // the handle zone, so it never scrolls away.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Text(
                    "SFTP: ${handle.profileSnapshot.name}",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                IconButton(onClick = { if (showFilter) clearFilter() else showFilter = true }) {
                    Icon(
                        Icons.Filled.FilterList,
                        contentDescription = if (showFilter) "Hide filter" else "Show filter",
                        tint = if (showFilter) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
            if (showFilter) {
                // Compact custom box (46dp): OutlinedTextField enforces
                // a 56dp min height, so shrinking it clips the text —
                // here every size is ours, nothing to clip. Border
                // follows focus like the M3 field; the X always shows
                // (desktop filter-bar parity) and hides + clears.
                val filterInteraction = remember { MutableInteractionSource() }
                val filterFocused by filterInteraction.collectIsFocusedAsState()
                BasicTextField(
                    value = filterText,
                    onValueChange = { filterText = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    interactionSource = filterInteraction,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(46.dp)
                        .border(
                            if (filterFocused) 2.dp else 1.dp,
                            if (filterFocused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            RoundedCornerShape(4.dp),
                        )
                        .padding(horizontal = 12.dp),
                    decorationBox = { inner ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            Box(Modifier.weight(1f)) {
                                if (filterText.isEmpty()) {
                                    Text(
                                        "Filter...",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                    )
                                }
                                inner()
                            }
                            IconButton(
                                onClick = { clearFilter() },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "Clear filter",
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    },
                )
            }
            // Directory navigation lives in the handle zone (like the
            // New-tab search): always visible, never scrolls away, and
            // its touches never fight the file list.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                val parent = parentDir(dir)
                IconButton(enabled = parent != null && !listing && hasShell, onClick = { dir = parent!! }) {
                    Icon(Icons.Filled.ArrowUpward, contentDescription = "Up one directory")
                }
                // Breadcrumb-style wrap (desktop sftpPanel parity): one
                // chunk per segment, chunks after the first start with
                // "/", so the FlowRow only breaks between segments and
                // every continuation line starts with "/".
                FlowRow(modifier = Modifier.weight(1f)) {
                    for (chunk in pathChunks(dir)) {
                        Text(
                            chunk,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                IconButton(enabled = hasShell, onClick = { uploadPicker.launch("*/*") }) {
                    Icon(Icons.Filled.Upload, contentDescription = "Upload file")
                }
            }
        },
    ) {
        if (!hasShell) {
            Text(
                "Not connected. Connect the session to use SFTP.",
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
                                SftpTransferManager.Status.CANCELLED -> "Canceled"
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
                    ) { Text("Clear completed transfers") }
                }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        info?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        if (listing && entries == null) CircularProgressIndicator()
        Card(
            // The list takes all remaining space, so content height never
            // changes when entries/transfers land — the sheet opens at
            // half and STAYS there, no balloon-then-settle. Full stays
            // reachable by drag; the list scrolls inside, and a drag
            // down past its top collapses the sheet instead.
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) {
            // Desktop parity: blank filter (or hidden box) shows all;
            // otherwise a case-insensitive name match.
            val filtering = showFilter && filterText.isNotBlank()
            val list = entries
            val visible = if (!filtering) list else list?.filter { it.name.contains(filterText, ignoreCase = true) }
            if (visible == null) {
                Text(
                    "Loading...",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                // Always a full-size list — even for the empty note. A
                // plain Text leaves a dead zone: touches there dispatch
                // no nested scroll, so a collapse-drag on the empty area
                // dies silently. The LazyColumn fills the card, so every
                // finger position scrolls first and collapses at the edge.
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    if (visible.isEmpty()) {
                        item(key = "empty") {
                            Text(
                                if (filtering) {
                                    "No files match the filter \"$filterText\""
                                } else {
                                    "Empty directory"
                                },
                                modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    } else {
                        items(visible, key = { (if (it.isDirectory) "d:" else "f:") + it.name }) { e ->
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
            text = { Text("\"$name\" already exists in this folder.") },
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

private fun joinDir(dir: String, name: String): String = if (dir == ".") name else "$dir/$name"

/**
 * Wrap-friendly chunks: ["."] or ["seg", "/seg", …]. Only the joints
 * between chunks may break, so every wrapped line starts with "/".
 */
private fun pathChunks(dir: String): List<String> {
    if (dir == ".") return listOf(".")
    val parts = dir.split('/')
    return parts.mapIndexed { i, p -> if (i == 0) p else "/$p" }
}

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
