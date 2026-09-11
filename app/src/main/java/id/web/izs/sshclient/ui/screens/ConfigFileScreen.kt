package id.web.izs.sshclient.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.content.ClipData
import id.web.izs.sshclient.core.config.RawConfigStore
import id.web.izs.sshclient.ui.AppState
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Hard cap for a single import read (a desktop config is ~tens of KB). */
private const val MAX_IMPORT_BYTES = 2 * 1024 * 1024

/**
 * Settings > Config file, parity with the desktop Config file tab
 * (settingsTab: configFile = config.readRaw()).
 *
 * Shows the LIVE store, not the disk shell: everything decrypted and
 * readable while unlocked (vault secrets stay inside the opaque blob,
 * exactly like desktop); the encrypted shell only while locked, with an
 * unlock action. Desktop allows editing + save; on mobile v1 the
 * equivalent is Import: take a full YAML (a file, or text already on
 * the clipboard — e.g. copied from desktop Tabby's Config file tab)
 * to replace the local config — no sync server needed. The local sync
 * target (configSync host/token/id) is always kept, never taken from
 * the import.
 *
 * Large configs (thousands of lines) are never rendered in an editor:
 * the file/clipboard is read and validated off the Main thread and only
 * a summary (profile count) is shown before confirming.
 */
@Composable
fun ConfigFileScreen(
    state: AppState,
    onBack: () -> Unit,
) {
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val loaded = state.loaded
    val locked = loaded?.needsPassphrase == true
    val encrypted = loaded?.domain?.encrypted == true
    var showUnlock by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    // Live view (decrypted when unlocked), re-dumped per loaded store.
    val yaml = remember(loaded) { loaded?.let { RawConfigStore.dumpRaw(it.store) } }
    val hasLocal = remember(loaded) { !state.disk.loadYaml().isNullOrBlank() }
    val viewProfiles = remember(loaded) { (loaded?.store?.get("profiles") as? List<*>)?.size }
    val viewLabel = when {
        locked -> "Encrypted. Unlock to view."
        encrypted -> "Decrypted view. Vault contents stay encrypted."
        else -> "Current config"
    }
    // Import (file or clipboard, local-only, no sync server).
    var importing by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }
    var importInfo by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var pendingText by remember { mutableStateOf<String?>(null) }

    /** Pure pre-parse for the confirm message; null when invalid. */
    fun preview(text: String): ImportPreview? = try {
        val doc = RawConfigStore.parseImport(text)
        val profiles = doc[RawConfigStore.KEY_PROFILES] as? List<*>
        ImportPreview(
            profileCount = profiles?.size,
            encryptedShell = RawConfigStore.isEncrypted(doc) &&
                RawConfigStore.storedVault(doc) != null,
        )
    } catch (_: IllegalArgumentException) {
        null
    }

    /** Validate incoming text (already fully read) and either show the
     * confirm dialog or an error — never renders the text itself. */
    fun handleIncoming(text: String) {
        if (text.length > MAX_IMPORT_BYTES) {
            importError = "File is too large. Maximum size is 2 MB."
            return
        }
        val pv = preview(text)
        if (pv == null) {
            importError = try {
                RawConfigStore.parseImport(text)
                "Not a Tabby config"
            } catch (e: IllegalArgumentException) {
                e.message
            }
        } else {
            importError = null
            pendingText = text
        }
    }

    /**
     * Cancel a fresh import whose shell was never unlocked (wrong/unknown
     * new passphrase): restore the pre-import local config (failed import),
     * not erase anything.
     */
    fun abortConfigImport() {
        scope.launch {
            busy = true
            importError = null
            try {
                val restored = withContext(Dispatchers.IO) { state.repo.abortPendingImport() }
                state.adopt(restored)
                importInfo = "Import cancelled — previous config restored."
            } catch (e: Exception) {
                importError = e.message
            } finally {
                busy = false
                showUnlock = false
            }
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            importError = null
            try {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val out = ByteArrayOutputStream()
                        val buf = ByteArray(8192)
                        var total = 0
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > MAX_IMPORT_BYTES) {
                                throw IllegalStateException("File is too large. Maximum size is 2 MB.")
                            }
                            out.write(buf, 0, n)
                        }
                        out.toString(Charsets.UTF_8)
                    } ?: throw IllegalStateException("Could not read the file")
                }
                handleIncoming(text)
            } catch (e: Exception) {
                importError = e.message
            } finally {
                busy = false
            }
        }
    }

    fun pasteFromClipboard() {
        scope.launch {
            busy = true
            importError = null
            try {
                val data = clipboard.getClipEntry()?.clipData
                val text = if (data == null) "" else buildString {
                    for (i in 0 until data.itemCount) {
                        if (isNotEmpty()) append('\n')
                        append(data.getItemAt(i).coerceToText(context)?.toString() ?: "")
                    }
                }
                if (text.isBlank()) {
                    importError = "Clipboard is empty. Copy the config first."
                } else {
                    // Validation itself runs here (Main); SnakeYAML on ~2 MB
                    // is milliseconds — the old jank was rendering 4000
                    // lines in a TextField, which no longer exists.
                    handleIncoming(text)
                }
            } catch (e: Exception) {
                importError = e.message
            } finally {
                busy = false
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenHeader("Config file", onBack)
        if (!importing) {
            Text(
                "Config ${state.disk.configId.takeIf { it >= 0 } ?: "-"}, $viewLabel, " +
                    "${yaml?.length ?: 0} characters, " +
                    "${viewProfiles?.let { "$it profiles" } ?: "no config loaded"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                "Replace the config on this device with a full Tabby config file (for example, copied from " +
                    "Tabby on desktop). No sync server needed. " +
                    "Your sync target stays unchanged.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (locked && !importing) {
            Button(
                onClick = { showUnlock = true },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Unlock to view full config") }
        }
        if (!importing) {
            Card(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (!hasLocal) {
                    Text(
                        "No config on this device. Tap Import below to load one from a file or the clipboard, " +
                            "or open Config Sync to download one.",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Text(
                        yaml ?: "",
                        modifier = Modifier
                            .padding(12.dp)
                            .verticalScroll(rememberScrollState())
                            .horizontalScroll(rememberScrollState()),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        } else {
            Card(modifier = Modifier.weight(1f).fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = !busy,
                        onClick = { filePicker.launch("*/*") },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Choose file") }
                    OutlinedButton(
                        enabled = !busy,
                        onClick = { pasteFromClipboard() },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Paste from clipboard") }
                }
            }
        }
        if (copied) {
            Text("Copied to clipboard", color = MaterialTheme.colorScheme.primary)
        }
        importError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        importInfo?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        if (busy) CircularProgressIndicator()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            if (!importing) {
                OutlinedButton(
                    onClick = {
                        importError = null
                        importInfo = null
                        importing = true
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Import") }
                Button(
                    enabled = !yaml.isNullOrBlank(),
                    onClick = {
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("tabby-config", yaml!!)))
                        }
                        copied = true
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Copy") }
            } else {
                TextButton(
                    onClick = { importing = false; importError = null },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Cancel") }
            }
        }
    }

    pendingText?.let { text ->
        val pv = remember(text) { preview(text) }
        if (pv != null) {
            AlertDialog(
                onDismissRequest = { pendingText = null },
                title = { Text("Replace the config on this device?") },
                text = {
                    Text(
                        "This replaces the profiles on this device with the imported config" +
                            (pv.profileCount?.let { " ($it profiles)" } ?: "") +
                            (if (pv.encryptedShell) " (encrypted. The vault passphrase will be asked right after import.)" else "") +
                            ". Your sync target stays unchanged.",
                    )
                },
                confirmButton = {
                    Button(onClick = {
                        pendingText = null
                        scope.launch {
                            busy = true
                            importError = null
                            try {
                                val count = withContext(Dispatchers.IO) {
                                    val l = state.repo.importRawYaml(text)
                                    state.adopt(l)
                                    (l.store["profiles"] as? List<*>)?.size
                                }
                                importing = false
                                importInfo = "Imported${count?.let { " ($it profiles)" } ?: ""}"
                                // A fully encrypted import blocks the listing:
                                // ask for the passphrase now instead of
                                // leaving the app locked without a prompt.
                                // unlockRequired only — a locked
                                // plaintext-with-blob stays usable as-is.
                                if (state.loaded?.unlockRequired == true) showUnlock = true
                            } catch (e: Exception) {
                                importError = e.message
                            } finally {
                                busy = false
                            }
                        }
                    }) { Text("Replace") }
                },
                dismissButton = { TextButton(onClick = { pendingText = null }) { Text("Cancel") } },
            )
        }
    }

    if (showUnlock) {
        // Fresh import with a different master password: cancelling or
        // deleting before the first unlock just fails the import (restore
        // the previous config). An ordinary locked config keeps the
        // default erase-local flow (desktop "Erase config" parity).
        if (state.loaded?.pendingRewrite == true) {
            VaultUnlockDialog(
                state = state,
                onUnlocked = { showUnlock = false },
                onNoConfig = { showUnlock = false },
                onDismiss = { abortConfigImport() },
                dismissible = true,
                onDeleteConfirmed = { abortConfigImport() },
                deleteTitle = "Cancel the import?",
                deleteText = "The imported config needs a different passphrase. " +
                    "Cancel the import and keep the previous local config?",
                deleteButtonText = "Cancel import",
                deleteConfirmButtonText = "Yes, cancel",
                showCancelButton = false,
            )
        } else {
            VaultUnlockDialog(
                state = state,
                onUnlocked = { showUnlock = false },
                onNoConfig = { showUnlock = false },
                onDismiss = { showUnlock = false },
                dismissible = true,
            )
        }
    }
}

/** Pre-import summary for the overwrite confirmation. */
private data class ImportPreview(
    val profileCount: Int?,
    val encryptedShell: Boolean,
)
