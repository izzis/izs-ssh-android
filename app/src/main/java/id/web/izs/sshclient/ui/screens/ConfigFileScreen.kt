package id.web.izs.sshclient.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import id.web.izs.sshclient.core.config.RawConfigStore
import id.web.izs.sshclient.ui.AppState
import kotlinx.coroutines.launch

/**
 * Settings > Config file, parity with the desktop Config file tab
 * (settingsTab: configFile = config.readRaw()).
 *
 * Shows the LIVE store, not the disk shell: everything decrypted and
 * readable while unlocked (vault secrets stay inside the opaque blob,
 * exactly like desktop); the encrypted shell only while locked, with an
 * unlock action. Read-only on mobile v1 (desktop allows editing + save).
 */
@Composable
fun ConfigFileScreen(
    state: AppState,
    onBack: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
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
        locked -> "encrypted shell (unlock to view)"
        encrypted -> "decrypted view (vault contents stay encrypted)"
        else -> "live config"
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Config file", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Active ID: ${state.disk.configId.takeIf { it >= 0 } ?: "-"} · $viewLabel · " +
                "${yaml?.length ?: 0} chars · " +
                "${viewProfiles?.let { "$it profiles in view" } ?: "no view loaded"}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (locked) {
            Button(
                onClick = { showUnlock = true },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Unlock to view full config") }
        }
        Card(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (!hasLocal) {
                Text(
                    "No local config. Open Config Sync, connect, and download a cloud config first.",
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
        if (copied) {
            Text("Copied to clipboard", color = MaterialTheme.colorScheme.primary)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            TextButton(
                onClick = {
                    scope.launch {
                        state.refresh()
                        copied = false
                    }
                },
                modifier = Modifier.weight(1f),
            ) { Text("Refresh") }
            Button(
                enabled = !yaml.isNullOrBlank(),
                onClick = {
                    clipboard.setText(AnnotatedString(yaml!!))
                    copied = true
                },
                modifier = Modifier.weight(1f),
            ) { Text("Copy") }
        }
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back") }
    }

    if (showUnlock) {
        VaultUnlockDialog(
            state = state,
            onUnlocked = { showUnlock = false },
            onNoConfig = { showUnlock = false },
            onDismiss = { showUnlock = false },
            dismissible = true,
        )
    }
}
