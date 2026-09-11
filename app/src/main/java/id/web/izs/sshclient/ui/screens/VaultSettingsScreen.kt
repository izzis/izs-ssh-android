package id.web.izs.sshclient.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import id.web.izs.sshclient.ui.AppState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings > Vault, mirroring the desktop vault tab (vaultSettingsTab):
 * set master passphrase (unconfigured) / change it / erase the vault /
 * "Encrypt config file" option. Deviation: no per-secret browser on mobile —
 * passwords and keys resolve inside each profile at connect time.
 */
private enum class Pending { Change, Erase, EncryptOn, EncryptOff }

@Composable
fun VaultSettingsScreen(
    state: AppState,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val vault = state.loaded?.domain?.vault
    val encrypted = state.loaded?.domain?.encrypted == true
    val locked = state.loaded?.needsPassphrase == true

    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var showUnlock by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<Pending?>(null) }
    var showSetPass by remember { mutableStateOf(false) }
    var changeMode by remember { mutableStateOf(false) }
    var showErase by remember { mutableStateOf(false) }

    fun runOp(op: suspend () -> Unit, done: String) {
        scope.launch {
            busy = true
            msg = null
            try {
                withContext(Dispatchers.IO) { op() }
                state.refresh()
                msg = done
            } catch (e: Exception) {
                msg = "Couldn't save: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    fun runPending(p: Pending) {
        when (p) {
            Pending.Change -> {
                changeMode = true
                showSetPass = true
            }
            Pending.Erase -> showErase = true
            Pending.EncryptOn -> runOp({ state.repo.setConfigEncrypted(true) }, "Config file encryption is on.")
            Pending.EncryptOff -> runOp({ state.repo.setConfigEncrypted(false) }, "Config file encryption is off.")
        }
    }

    /** Desktop parity: change/erase/encrypt-on-locked-vault prompt for unlock first. */
    fun requireUnlock(p: Pending) {
        if (locked) {
            pending = p
            showUnlock = true
        } else {
            runPending(p)
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenHeader("Vault", onBack)
        if (vault == null) {
            Icon(
                Icons.Filled.Key,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                "Vault is an always-encrypted container for secrets such as SSH passwords and private key passphrases.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(
                enabled = !busy && state.loaded != null,
                onClick = {
                    changeMode = false
                    showSetPass = true
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Set master passphrase") }
        } else {
            Text(
                if (locked) "Encryption: ${if (encrypted) "On" else "Off"}, Locked"
                else "Encryption: ${if (encrypted) "On" else "Off"}, Unlocked for this session",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (locked) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Lock, contentDescription = null)
                    Text("Vault is locked", style = MaterialTheme.typography.titleMedium)
                }
                Button(
                    onClick = {
                        pending = null
                        showUnlock = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Unlock vault") }
            }
            Text("Options", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(
                enabled = !busy,
                onClick = { requireUnlock(Pending.Change) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Change the master passphrase") }
            OutlinedButton(
                enabled = !busy,
                onClick = { requireUnlock(Pending.Erase) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Erase the Vault") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Encrypt config file")
                    Text(
                        "Stores the entire configuration in the vault",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = encrypted,
                    enabled = !busy,
                    onCheckedChange = { on ->
                        requireUnlock(if (on) Pending.EncryptOn else Pending.EncryptOff)
                    },
                )
            }
        }
        if (busy) CircularProgressIndicator()
        msg?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }

    if (showSetPass) {
        SetVaultPassphraseDialog(
            title = if (changeMode) "Change the master passphrase" else "Set master passphrase",
            confirmLabel = "Set passphrase",
            onConfirm = { pass ->
                showSetPass = false
                if (changeMode) runOp({ state.repo.changeVaultPassphrase(pass) }, "Master passphrase changed.")
                else runOp(
                    { state.repo.setVaultPassphrase(pass) },
                    "Vault is ready. Saved passwords were moved to the vault.",
                )
            },
            onDismiss = { showSetPass = false },
        )
    }

    if (showUnlock) {
        VaultUnlockDialog(
            state = state,
            onUnlocked = {
                showUnlock = false
                pending?.let { runPending(it) }
                pending = null
            },
            onNoConfig = {
                showUnlock = false
                pending = null
                scope.launch { state.refresh() }
            },
            onDismiss = {
                showUnlock = false
                pending = null
            },
            dismissible = true,
        )
    }

    if (showErase) {
        AlertDialog(
            onDismissRequest = { showErase = false },
            title = { Text("Delete vault contents?") },
            text = {
                Text(
                    "The vault is removed and the config is restored to an unencrypted config. " +
                        "Passwords and keys stored in the vault are lost. " +
                        "You will need to enter them again for each profile afterwards. This cannot be undone.",
                )
            },
            confirmButton = {
                Button(onClick = {
                    showErase = false
                    runOp({ state.repo.eraseVault() }, "Vault deleted.")
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { showErase = false }) { Text("Keep") } },
        )
    }
}
