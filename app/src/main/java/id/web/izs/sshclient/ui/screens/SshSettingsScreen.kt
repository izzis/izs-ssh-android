package id.web.izs.sshclient.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import id.web.izs.sshclient.core.config.RawConfigStore
import id.web.izs.sshclient.ui.AppState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings > SSH (desktop SSH-tab parity, mobile-relevant subset).
 * verifyHostKeys + warnOnClose are wired: WinSCP/agent options are
 * Windows-only on desktop and meaningless on Android. Plaintext configs
 * only — on encrypted stores the ssh section lives inside the vault blob
 * (edited on desktop).
 */
@Composable
fun SshSettingsScreen(
    state: AppState,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val encrypted = state.loaded?.domain?.encrypted == true
    var verify by remember(state.loaded) {
        mutableStateOf(state.loaded?.domain?.ssh?.verifyHostKeys ?: true)
    }
    var warn by remember(state.loaded) {
        mutableStateOf(state.loaded?.domain?.ssh?.warnOnClose ?: false)
    }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenHeader("SSH", onBack)
        if (encrypted) {
            Text(
                "This config is encrypted: SSH options live inside the vault blob " +
                    "and are edited on desktop. Mobile keeps them read-only to " +
                    "preserve upload parity.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = verify,
                onCheckedChange = { verify = it },
                enabled = !encrypted && !busy,
            )
            Column {
                Text("Verify host keys when connecting")
                Text(
                    "Trust-on-first-use: new keys are recorded, changed keys are rejected.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = warn,
                onCheckedChange = { warn = it },
                enabled = !encrypted && !busy,
            )
            Column {
                Text("Warn when closing active connections")
                Text(
                    "Ask before disconnecting a live session. A profile with " +
                        "its own warnOnClose set still wins (desktop parity).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        msg?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (busy) CircularProgressIndicator()
        Button(
            enabled = !encrypted && !busy,
            onClick = {
                scope.launch {
                    busy = true
                    msg = null
                    try {
                        withContext(Dispatchers.IO) {
                            state.repo.updateLocalRaw { raw ->
                                @Suppress("UNCHECKED_CAST")
                                val ssh = LinkedHashMap(
                                    (raw[RawConfigStore.KEY_SSH] as? Map<String, Any?>) ?: emptyMap(),
                                )
                                ssh["verifyHostKeys"] = verify
                                ssh["warnOnClose"] = warn
                                raw[RawConfigStore.KEY_SSH] = ssh
                            }
                        }
                        state.refresh()
                        msg = "Saved"
                    } catch (e: Exception) {
                        msg = "Failed: ${e.message}"
                    } finally {
                        busy = false
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save") }
    }
}
