package id.web.izs.sshclient.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import id.web.izs.sshclient.core.config.RawConfigStore
import id.web.izs.sshclient.ui.AppState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Same as Settings > Config Sync in Tabby Desktop: host + token -> Test (GET /user) -> list.
 * http:// is allowed (self-hosted/LAN) with a non-HTTPS banner warning.
 */
@Composable
fun SyncSetupScreen(
    state: AppState,
    onConnected: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf(state.disk.host ?: "") }
    var token by remember { mutableStateOf(state.disk.token ?: "") }
    var busy by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }
    val showHttpWarning = remember(host) {
        host.isNotBlank() && !RawConfigStore.isHttps(host.trim().trimEnd('/'))
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Tabby Config Sync", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Same as Settings > Config Sync in Tabby Desktop. " +
                "The host can be https:// or a self-hosted http://IP.",
            style = MaterialTheme.typography.bodyMedium,
        )
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
        localError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.height(4.dp))
        Button(
            onClick = {
                scope.launch {
                    busy = true
                    localError = null
                    try {
                        withContext(Dispatchers.IO) {
                            state.repo.testConnection(host, token)
                        }
                        state.disk.host = RawConfigStore.normalizeHost(host)
                        state.disk.token = token
                        onConnected()
                    } catch (e: Exception) {
                        localError = e.message ?: "Connection failed"
                    } finally {
                        busy = false
                    }
                }
            },
            enabled = !busy && host.isNotBlank() && token.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (busy) CircularProgressIndicator() else Text("Test & Continue")
        }
    }
}
