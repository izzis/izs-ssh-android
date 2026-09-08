package id.web.izs.sshclient.ui.screens

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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import id.web.izs.sshclient.core.config.RawConfigStore
import id.web.izs.sshclient.core.config.RemoteConfigMeta
import id.web.izs.sshclient.ui.AppState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings > Config Sync (desktop parity, mobile-combined).
 *
 * One screen for everything the old three screens (setup / cloud list /
 * options) did separately: connection (host+token+test), full cloud config
 * management (list / download-switch / upload / create / delete), and
 * auto-sync + parts options. Used from first-run setup AND from Settings,
 * so cloud configs can always be switched and managed.
 */
@Composable
fun ConfigSyncScreen(
    state: AppState,
    onDownloaded: () -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf(state.disk.host ?: "") }
    var token by remember { mutableStateOf(state.disk.token ?: "") }
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

    val connected = !state.disk.host.isNullOrBlank() && !state.disk.token.isNullOrBlank()
    val showHttpWarning = remember(host) {
        host.isNotBlank() && !RawConfigStore.isHttps(host.trim().trimEnd('/'))
    }

    fun reload() {
        scope.launch {
            busy = true
            error = null
            try {
                items = withContext(Dispatchers.IO) {
                    state.repo.listRemote(state.disk.host!!, state.disk.token!!)
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
            state.repo.uploadAsCurrent(state.disk.host!!, state.disk.token!!, meta.id, "android-1.0.0")
        }
        state.refresh()
        reload()
    }
    suspend fun doDownload(meta: RemoteConfigMeta) {
        withContext(Dispatchers.IO) {
            state.repo.downloadIntoLocal(state.disk.host!!, state.disk.token!!, meta.id)
        }
        state.refresh()
        // An encrypted download lands on the profile list, which auto-shows
        // the vault unlock dialog (desktop unlock-modal parity).
        onDownloaded()
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Config Sync", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Active ID: ${state.disk.configId.takeIf { it >= 0 } ?: "-"} · " +
                "Last remote change: ${state.disk.lastRemoteChange.ifBlank { "-" }}",
            style = MaterialTheme.typography.bodySmall,
        )

        // ---- connection ----
        Text("Connection", style = MaterialTheme.typography.titleMedium)
        if (showHttpWarning) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text(
                    "Warning: non-HTTPS host. The synced YAML payload can lead to terminal " +
                        "command execution if the connection is MITM'd. Use only on trusted LANs.",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            label = { Text("Sync host (https://… or http://IP)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text("Token") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
        )
        Button(
            onClick = {
                scope.launch {
                    busy = true
                    error = null
                    info = null
                    try {
                        withContext(Dispatchers.IO) { state.repo.testConnection(host, token) }
                        state.disk.host = RawConfigStore.normalizeHost(host)
                        state.disk.token = token
                        info = "Connected — cloud configs below."
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
        ) { Text("Test & Save Connection") }

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
                                state.repo.createRemote(state.disk.host!!, state.disk.token!!, newName)
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
        if (busy) CircularProgressIndicator()
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        info?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        for (meta in items ?: emptyList()) {
            val active = meta.id == state.disk.configId
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        (if (active) "● " else "") + meta.name,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "modified: ${meta.modifiedAt.ifBlank { "-" }}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                if (active) scope.launch {
                                    busy = true
                                    try { doDownload(meta) } catch (e: Exception) { error = e.message } finally { busy = false }
                                } else confirmDownload = meta
                            },
                        ) { Text("Download") }
                        OutlinedButton(
                            onClick = {
                                if (meta.id == state.disk.configId) scope.launch {
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
            Text("Auto-sync (check every 60 seconds)")
        }
        Text("Synced parts (desktop parts parity):", style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = pHotkeys, onCheckedChange = { pHotkeys = it })
            Text("hotkeys")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = pAppearance, onCheckedChange = { pAppearance = it })
            Text("appearance")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = pVault, onCheckedChange = { pVault = it })
            Text("vault")
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
                        state.refresh()
                    } catch (e: Exception) {
                        error = "Failed: ${e.message}"
                    } finally {
                        busy = false
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save & Check Now") }
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back") }
    }

    confirmDownload?.let { meta ->
        AlertDialog(
            onDismissRequest = { confirmDownload = null },
            title = { Text("Overwrite the local config and start syncing?") },
            text = { Text("This overwrites the local profiles with \"${meta.name}\" (desktop parity).") },
            confirmButton = {
                Button(onClick = {
                    confirmDownload = null
                    scope.launch {
                        busy = true
                        try { doDownload(meta) } catch (e: Exception) { error = e.message } finally { busy = false }
                    }
                }) { Text("Overwrite local & sync") }
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
                }) { Text("Overwrite remote & sync") }
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
                                state.repo.deleteRemote(state.disk.host!!, state.disk.token!!, meta.id)
                            }
                            reload()
                        } catch (e: Exception) { error = e.message } finally { busy = false }
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}
