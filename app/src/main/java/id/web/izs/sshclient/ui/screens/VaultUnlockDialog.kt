package id.web.izs.sshclient.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import id.web.izs.sshclient.ui.AppState

/**
 * Parity with the desktop unlock-vault modal: the passphrase prompt is a popup
 * dialog, never a full page. Shown non-dismissible when the vault must be
 * unlocked to proceed; dismissible when opened manually (e.g. Settings > Vault).
 * Failed decrypt (BAD_DECRYPT) -> Retry / Delete local config / Cancel.
 */
@Composable
fun VaultUnlockDialog(
    state: AppState,
    onUnlocked: () -> Unit,
    onNoConfig: () -> Unit,
    onDismiss: () -> Unit = { },
    dismissible: Boolean = true,
    /**
     * Replaces the default "erase the local config" delete flow (desktop
     * "Erase config" parity). Used after a fresh download/import whose
     * shell was never unlocked: cancelling/deleting there must just fail
     * the import (restore the previous config), not erase anything.
     */
    onDeleteConfirmed: (() -> Unit)? = null,
    deleteTitle: String = "Delete the local config?",
    deleteText: String = "This cannot be undone. You can re-sync from the cloud with the correct passphrase.",
    /** Label of the button that opens the delete/cancel confirmation. */
    deleteButtonText: String = "Delete saved config",
    /** Label of the confirm button inside that confirmation dialog. */
    deleteConfirmButtonText: String = "Delete",
    /**
     * Show the plain Cancel TextButton next to it. Hidden in abort mode
     * where Cancel would do the exact same thing (back-press/outside-tap
     * still dismiss via [onDismiss] when [dismissible]).
     */
    showCancelButton: Boolean = true,
) {
    var pass by remember { mutableStateOf("") }
    var showDelete by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (dismissible) onDismiss() },
        title = { Text("Encrypted config") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "The vault must be unlocked to load the configuration. " +
                        "The passphrase is kept only for this session.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = pass,
                    onValueChange = { pass = it },
                    label = { Text("Vault passphrase") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(
                enabled = !state.loading && pass.isNotEmpty(),
                onClick = { state.unlock(pass) { ok -> if (ok) onUnlocked() } },
            ) { Text(if (state.loading) "Unlocking..." else "Unlock") }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { showDelete = true }) { Text(deleteButtonText) }
                if (dismissible && showCancelButton) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                }
            }
        },
    )

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text(deleteTitle) },
            text = { Text(deleteText) },
            confirmButton = {
                Button(onClick = {
                    showDelete = false
                    if (onDeleteConfirmed != null) {
                        onDeleteConfirmed()
                    } else {
                        state.disk.clearYaml()
                        state.repo.forgetPassphrase()
                        // Rebuild from disk (seeds a fresh empty config when
                        // erased — desktop "continue with empty config"
                        // parity) so the UI never strands on a stale locked
                        // view that can only fail with "No local config".
                        state.refresh { onNoConfig() }
                    }
                }) { Text(deleteConfirmButtonText) }
            },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text("Cancel") } },
        )
    }
}
