package id.web.izs.sshclient.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import id.web.izs.sshclient.core.config.RawConfigStore
import id.web.izs.sshclient.core.config.RemoteConfigMeta
import id.web.izs.sshclient.ui.AppState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings > Config Sync (desktop parity, mobile-combined).
 *
 * One screen for connection (host+token+test), full cloud config
 * management (list / download-switch / upload / create / delete), and
 * auto-sync + parts options. Reached from Settings only — first-run setup
 * is gone (home is always the profile list on top
 * of a seeded empty local config).
 */
@Composable
fun ConfigSyncScreen(
    state: AppState,
    onDownloaded: () -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    // Keyed on the YAML sync target: abortPendingImport() restores the
    // previous target, and the fields must follow (typing alone never writes
    // disk, so no reset-while-typing).
    val yamlTarget = state.loaded?.domain?.configSync
    var host by remember(yamlTarget?.host) { mutableStateOf(yamlTarget?.host ?: "") }
    var token by remember(yamlTarget?.token) { mutableStateOf(yamlTarget?.token ?: "") }
    var items by remember { mutableStateOf<List<RemoteConfigMeta>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    var newName by remember { mutableStateOf("") }
    var confirmDownload by remember { mutableStateOf<RemoteConfigMeta?>(null) }
    var confirmUpload by remember { mutableStateOf<RemoteConfigMeta?>(null) }
    var confirmDelete by remember { mutableStateOf<RemoteConfigMeta?>(null) }
    var auto by remember { mutableStateOf(state.disk.auto) }
    var pHotkeys by remember { mutableStateOf(state.disk.partsHotkeys) }
    var pAppearance by remember { mutableStateOf(state.disk.partsAppearance) }
    var pVault by remember { mutableStateOf(state.disk.partsVault) }
    // Shown when a fresh download lands locked (downloaded blob uses a
    // different master password): ask for it here instead of silently
    // popping back with a locked config and no prompt.
    var showUnlock by remember { mutableStateOf(false) }

    val connected = !yamlTarget?.host.isNullOrBlank() && !yamlTarget?.token.isNullOrBlank()
    val syncId = yamlTarget?.configID ?: -1L
    val showHttpWarning = remember(host) {
        host.isNotBlank() && !RawConfigStore.isHttps(host.trim().trimEnd('/'))
    }

    fun reload() {
        scope.launch {
            busy = true
            error = null
            try {
                items = withContext(Dispatchers.IO) {
                    state.repo.listRemote(host, token)
                }
            } catch (e: Exception) {
                error = e.message
            } finally {
                busy = false
            }
        }
    }
    LaunchedEffect(connected) { if (connected) reload() }

    suspend fun doUpload(meta: RemoteConfigMeta) {
        withContext(Dispatchers.IO) {
            state.repo.uploadAsCurrent(host, token, meta.id, "android-1.0.0")
        }
        state.refresh()
        reload()
    }
    suspend fun doDownload(meta: RemoteConfigMeta) {
        // Adopt the write's own Loaded (a refresh() here would re-decrypt +
        // re-parse for nothing). A locked encrypted shell keeps the unlock
        // dialog on THIS screen: popping back silently would leave the new
        // config locked with no prompt until the next app start.
        val loaded = withContext(Dispatchers.IO) {
            state.repo.downloadIntoLocal(host, token, meta.id)
        }
        state.adopt(loaded)
        if (loaded.unlockRequired) {
            showUnlock = true
        } else {
            onDownloaded()
        }
    }

    /**
     * Cancel a fresh download whose shell was never unlocked: restore the
     * pre-download local config (failed import), not erase anything.
     * Wired to both Cancel and Delete in the post-download unlock dialog.
     */
    fun abortImport() {
        scope.launch {
            busy = true
            error = null
            try {
                val restored = withContext(Dispatchers.IO) { state.repo.abortPendingImport() }
                state.adopt(restored)
                info = "Import cancelled — previous local config restored."
            } catch (e: Exception) {
                error = e.message
            } finally {
                busy = false
                showUnlock = false
            }
        }
    }
    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenHeader("Config Sync", onBack)
        Text(
            "Current config: ${syncId.takeIf { it >= 0 } ?: "-"}, " +
                "updated ${state.disk.lastRemoteChange.ifBlank { "-" }}",
            style = MaterialTheme.typography.bodySmall,
        )

        // ---- connection ----
        Text("Connection", style = MaterialTheme.typography.titleMedium)
        if (showHttpWarning) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text(
                    "Insecure connection. Sync is sent in cleartext and can be " +
                        "intercepted. HTTP is allowed only for local addresses " +
                        "(LAN/loopback) — use HTTPS for anything else.",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            label = { Text("Sync host") },
            placeholder = { Text("https://example.com") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text("Secret sync token") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        Button(
            onClick = {
                scope.launch {
                    busy = true
                    error = null
                    info = null
                    try {
                        withContext(Dispatchers.IO) { state.repo.testConnection(host, token) }
                        withContext(Dispatchers.IO) { state.repo.setSyncTarget(host, token) }
                        state.refresh()
                        info = "Connected."
                        reload()
                    } catch (e: Exception) {
                        error = e.message ?: "Connection failed"
                    } finally {
                        busy = false
                    }
                }
            },
            enabled = !busy && host.isNotBlank() && token.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Test and save") }

        // ---- cloud configs ----
        Text("Cloud configs", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                label = { Text("New config name") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            Button(
                enabled = connected && !busy && newName.isNotBlank(),
                onClick = {
                    scope.launch {
                        busy = true
                        try {
                            val created = withContext(Dispatchers.IO) {
                                state.repo.createRemote(host, token, newName)
                            }
                            newName = ""
                            if (created.id >= 0) doUpload(created) else reload()
                        } catch (e: Exception) {
                            error = e.message
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text("Create") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        info?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        for (meta in items ?: emptyList()) {
            val active = meta.id == syncId
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        (if (active) "● " else "") + meta.name,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "Modified: ${meta.modifiedAt.ifBlank { "-" }}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            // Always confirm — even re-downloading the active
                            // config overwrites the local profiles.
                            onClick = { confirmDownload = meta },
                        ) { Text("Download") }
                        OutlinedButton(
                            onClick = {
                                if (meta.id == syncId) scope.launch {
                                    busy = true
                                    try { doUpload(meta) } catch (e: Exception) { error = e.message } finally { busy = false }
                                } else confirmUpload = meta
                            },
                        ) { Text("Upload") }
                        TextButton(onClick = { confirmDelete = meta }) { Text("Delete") }
                    }
                }
            }
        }

        // ---- options ----
        Text("Options", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = auto, onCheckedChange = { auto = it })
            Column(Modifier.weight(1f)) {
                Text("Sync automatically")
                Text(
                    "Upload changes and check for updates every minute.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text("Synced parts:", style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = pHotkeys, onCheckedChange = { pHotkeys = it })
            Text("Sync hotkeys")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = pAppearance, onCheckedChange = { pAppearance = it })
            Text("Sync window settings")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = pVault, onCheckedChange = { pVault = it })
            Text("Sync Vault")
        }
        Button(
            enabled = !busy,
            onClick = {
                state.disk.auto = auto
                state.disk.partsHotkeys = pHotkeys
                state.disk.partsAppearance = pAppearance
                state.disk.partsVault = pVault
                scope.launch {
                    busy = true
                    try {
                        val name = withContext(Dispatchers.IO) { state.repo.autoSyncTick() }
                        info = if (name != null) "Auto-sync: config \"$name\" downloaded" else "Settings saved"
                        // Wait for the reload before clearing busy: a tick that
                        // fetched a different-passphrase blob lands locked and
                        // must prompt here, not silently.
                        state.refresh {
                            busy = false
                            if (state.loaded?.unlockRequired == true) showUnlock = true
                        }
                    } catch (e: Exception) {
                        error = "Couldn't save: ${e.message}"
                        busy = false
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save and check now") }
    }

    confirmDownload?.let { meta ->
        AlertDialog(
            onDismissRequest = { confirmDownload = null },
            title = { Text("Overwrite the local config and start syncing?") },
            text = { Text("This replaces the local profiles with \"${meta.name}\".") },
            confirmButton = {
                Button(onClick = {
                    confirmDownload = null
                    scope.launch {
                        busy = true
                        try { doDownload(meta) } catch (e: Exception) { error = e.message } finally { busy = false }
                    }
                }) { Text("Replace and sync") }
            },
            dismissButton = { TextButton(onClick = { confirmDownload = null }) { Text("Cancel") } },
        )
    }
    confirmUpload?.let { meta ->
        AlertDialog(
            onDismissRequest = { confirmUpload = null },
            title = { Text("Overwrite the remote config and start syncing?") },
            text = { Text("This overwrites \"${meta.name}\" on the cloud with the local config.") },
            confirmButton = {
                Button(onClick = {
                    confirmUpload = null
                    scope.launch {
                        busy = true
                        try { doUpload(meta) } catch (e: Exception) { error = e.message } finally { busy = false }
                    }
                }) { Text("Replace and sync") }
            },
            dismissButton = { TextButton(onClick = { confirmUpload = null }) { Text("Cancel") } },
        )
    }
    confirmDelete?.let { meta ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete the remote config?") },
            text = { Text("Delete \"${meta.name}\" from the sync server.") },
            confirmButton = {
                Button(onClick = {
                    confirmDelete = null
                    scope.launch {
                        busy = true
                        try {
                            withContext(Dispatchers.IO) {
                                state.repo.deleteRemote(host, token, meta.id)
                            }
                            reload()
                        } catch (e: Exception) { error = e.message } finally { busy = false }
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }

    // Busy overlay as a window Dialog: always centered on screen (the old
    // inline spinner sat mid-list and was invisible when scrolled down to
    // the Download buttons), and it swallows touches so a second
    // Download/Upload can't fire mid-operation. No dismiss: it clears
    // itself when every caller resets busy.
    if (busy) {
        Dialog(onDismissRequest = { }) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
    }

    // Fresh encrypted download with a different master password: the unlock
    // (and the deferred vault re-encrypt) happens here. Cancelling or
    // deleting before the first unlock just fails the import — the previous
    // local config is restored, nothing is erased.
    if (showUnlock) {
        VaultUnlockDialog(
            state = state,
            onUnlocked = { showUnlock = false; onDownloaded() },
            onNoConfig = { showUnlock = false },
            onDismiss = { abortImport() },
            dismissible = true,
            onDeleteConfirmed = { abortImport() },
            deleteTitle = "Cancel the import?",
            deleteText = "The downloaded config needs a different passphrase. " +
                "Cancel the import and keep the previous local config?",
            deleteButtonText = "Cancel import",
            deleteConfirmButtonText = "Yes, cancel",
            showCancelButton = false,
        )
    }
}
