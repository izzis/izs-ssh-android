package id.web.izs.sshclient.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/**
 * Parity with the desktop SetVaultPassphraseModal: one dialog for both
 * initial set and master-passphrase change (desktop reuses the component),
 * with show/hide toggle and the unrecoverable-if-forgotten note.
 */
@Composable
fun SetVaultPassphraseDialog(
    title: String,
    confirmLabel: String,
    onConfirm: (passphrase: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var pass by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "You can change it later, but it's unrecoverable if forgotten.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = pass,
                    onValueChange = { pass = it },
                    label = { Text("Master passphrase") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = if (show) VisualTransformation.None
                    else PasswordVisualTransformation(),
                    // Keep password keyboard (no predictions) even while revealed.
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { show = !show }) {
                            Icon(
                                if (show) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (show) "Hide passphrase" else "Show passphrase",
                            )
                        }
                    },
                )
            }
        },
        confirmButton = {
            Button(
                enabled = pass.isNotBlank(),
                onClick = { onConfirm(pass) },
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
