package id.web.izs.sshclient.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import id.web.izs.sshclient.core.sync.SyncRepository
import id.web.izs.sshclient.ui.AppState
import kotlinx.coroutines.launch

/**
 * Profile editor (mobile v1: SSH profiles only).
 *
 * Lazy-unlock parity: opening this screen NEVER asks for the passphrase.
 * It is requested only when vault contents are actually needed —
 * revealing the password, or saving secret-affecting changes
 * (repo throws "Vault is locked", the dialog opens, the save retries).
 * Non-secret edits on a locked plaintext-with-blob config save directly.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileEditScreen(
    state: AppState,
    profileId: String,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val original = remember(state.loaded, profileId) {
        state.displayProfiles().find { it.id == profileId }
    }
    val groups = remember(state.loaded) { state.displayGroups() }
    val vaultPresent = state.loaded?.domain?.vault != null
    val locked = state.loaded?.needsPassphrase == true

    var name by remember(original) { mutableStateOf(original?.name ?: "") }
    var groupId by remember(original) { mutableStateOf(original?.group ?: "") }
    var host by remember(original) { mutableStateOf(original?.options?.host ?: "") }
    var portText by remember(original) { mutableStateOf(original?.options?.port?.toString() ?: "22") }
    var user by remember(original) { mutableStateOf(original?.options?.user ?: "") }
    var auth by remember(original) { mutableStateOf(original?.options?.auth ?: "password") }
    var passwordTouched by remember(original) { mutableStateOf(false) }
    var passwordText by remember(original) { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }
    var removedRefs by remember(original) { mutableStateOf(setOf<String>()) }
    var addedKeys by remember { mutableStateOf(listOf<Pair<String, String>>()) }
    var showAddKey by remember { mutableStateOf(false) }
    var showUnlock by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var pendingSave by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }

    if (original == null) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Profile not found", color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back") }
        }
        return
    }
    if (original.type != "ssh") {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Only SSH profiles can be edited on mobile v1.")
            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back") }
        }
        return
    }

    val hasSavedPassword = state.passwordFor(original) != null
    val effectivePassword: String? = when {
        !reveal -> null
        passwordTouched -> passwordText.ifBlank { null }
        else -> state.passwordFor(original)
    }
    val liveRefs = remember(original, removedRefs, addedKeys) {
        (original.options.privateKeys - removedRefs)
    }

    fun doSave() {
        scope.launch {
            busy = true
            msg = null
            try {
                val port = portText.toIntOrNull()?.takeIf { it in 1..65535 }
                    ?: original.options.port
                val updated = original.copy(
                    name = name.ifBlank { original.name },
                    group = groupId.ifBlank { null },
                    options = original.options.copy(
                        host = host.trim(),
                        port = port,
                        user = user.trim().ifBlank { original.options.user },
                        auth = auth.ifBlank { null },
                        // Final vault refs are assembled by the repo
                        // (existing minus removed plus newly stored).
                        privateKeys = liveRefs,
                    ),
                )
                state.repo.updateProfile(
                    profileId = profileId,
                    original = original,
                    updated = updated,
                    newGroupId = groupId.ifBlank { "" }.takeIf { it != (original.group ?: "") },
                    newGroupName = groups.find { it.id == groupId }?.name,
                    secretEdits = SyncRepository.ProfileSecretEdits(
                        password = passwordTouched.takeIf { it }?.let { passwordText },
                        newKeyPems = addedKeys,
                        removedKeyRefs = removedRefs.toList(),
                    ),
                )
                state.refresh { onBack() }
            } catch (e: IllegalStateException) {
                // Lazy unlock: the save actually needs vault contents.
                if ((e.message ?: "").contains("locked", ignoreCase = true)) {
                    pendingSave = true
                    showUnlock = true
                } else {
                    msg = "Failed: ${e.message}"
                }
            } catch (e: Exception) {
                msg = "Failed: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    fun doDelete() {
        scope.launch {
            busy = true
            msg = null
            try {
                state.repo.deleteProfile(profileId, original)
                state.refresh { onBack() }
            } catch (e: IllegalStateException) {
                // Lazy unlock: re-encrypting the shell needs the passphrase.
                if ((e.message ?: "").contains("locked", ignoreCase = true)) {
                    pendingDelete = true
                    showUnlock = true
                } else {
                    msg = "Failed: ${e.message}"
                }
            } catch (e: Exception) {
                msg = "Failed: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Edit profile", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(
            value = name, onValueChange = { name = it },
            label = { Text("Name") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
        )
        GroupDropdown(groups = groups, selected = groupId, onSelect = { groupId = it })
        OutlinedTextField(
            value = host, onValueChange = { host = it },
            label = { Text("Host") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = portText, onValueChange = { portText = it.filter { c -> c.isDigit() }.take(5) },
                label = { Text("Port") }, modifier = Modifier.weight(1f), singleLine = true,
            )
            OutlinedTextField(
                value = user, onValueChange = { user = it },
                label = { Text("User") }, modifier = Modifier.weight(1f), singleLine = true,
            )
        }
        AuthDropdown(selected = auth, onSelect = { auth = it })
        OutlinedTextField(
            value = if (passwordTouched) passwordText else "",
            onValueChange = { passwordText = it; passwordTouched = true },
            label = { Text("Password") },
            placeholder = {
                Text(
                    when {
                        locked -> "locked — tap the eye to unlock"
                        hasSavedPassword -> "saved — leave empty to keep, clear to remove"
                        else -> "no password saved"
                    },
                )
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = {
                    if (locked) {
                        pendingSave = false
                        showUnlock = true
                    } else {
                        reveal = !reveal
                    }
                }) {
                    Icon(
                        if (reveal) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (reveal) "Hide password" else "Show password",
                    )
                }
            },
        )
        if (reveal) {
            Text(
                "Current: ${effectivePassword ?: "(none)"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text("Private keys (${liveRefs.size + addedKeys.size})", style = MaterialTheme.typography.titleMedium)
        if (vaultPresent && locked) {
            Text(
                "Key list is locked — unlock to manage keys.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        for (ref in liveRefs) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    if (ref.startsWith("vault://")) "vault file • …${ref.takeLast(8)}"
                    else ref.take(44),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    enabled = !vaultPresent || !locked,
                    onClick = { removedRefs = removedRefs + ref },
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = "Remove key")
                }
            }
        }
        for ((pem, desc) in addedKeys) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "new • ${desc.ifBlank { "pasted key" }} (${pem.lines().size} lines)",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { addedKeys = addedKeys - (pem to desc) }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Discard key")
                }
            }
        }
        OutlinedButton(
            enabled = !vaultPresent || !locked,
            onClick = { showAddKey = true },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Add private key") }
        if (busy) CircularProgressIndicator()
        msg?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("Cancel") }
            OutlinedButton(
                enabled = !busy,
                onClick = { showDeleteConfirm = true },
                modifier = Modifier.weight(1f),
            ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            Button(enabled = !busy, onClick = { doSave() }, modifier = Modifier.weight(1f)) {
                Text("Save")
            }
        }
    }

    if (showAddKey) {
        AddKeyDialog(
            onAdd = { pem, desc ->
                showAddKey = false
                addedKeys = addedKeys + (pem to desc)
            },
            onDismiss = { showAddKey = false },
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete this profile?") },
            text = {
                Text(
                    "“${original.name}” is removed from the config. " +
                        "Saved vault secrets are kept (they may serve other profiles). " +
                        "This cannot be undone.",
                )
            },
            confirmButton = {
                Button(onClick = {
                    showDeleteConfirm = false
                    doDelete()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Keep") } },
        )
    }

    if (showUnlock) {
        VaultUnlockDialog(
            state = state,
            onUnlocked = {
                showUnlock = false
                when {
                    pendingDelete -> {
                        pendingDelete = false
                        doDelete()
                    }
                    pendingSave -> {
                        pendingSave = false
                        doSave()
                    }
                    else -> reveal = true
                }
            },
            onNoConfig = {
                showUnlock = false
                pendingSave = false
                pendingDelete = false
            },
            onDismiss = {
                showUnlock = false
                pendingSave = false
                pendingDelete = false
            },
            dismissible = true,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupDropdown(
    groups: List<id.web.izs.sshclient.core.config.ProfileGroup>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val label = when {
        selected.isBlank() -> "No group"
        else -> groups.find { it.id == selected }?.name ?: selected
    }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = label, onValueChange = { },
            readOnly = true, label = { Text("Group") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("No group") },
                onClick = { onSelect(""); expanded = false },
            )
            for (g in groups) {
                DropdownMenuItem(
                    text = { Text(g.name) },
                    onClick = { onSelect(g.id); expanded = false },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AuthDropdown(selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val choices = remember(selected) {
        (listOf("password", "publicKey", "agent", "keyboardInteractive") + selected)
            .filter { it.isNotBlank() }.distinct()
    }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected.ifBlank { "password" }, onValueChange = { },
            readOnly = true, label = { Text("Auth method") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (c in choices) {
                DropdownMenuItem(
                    text = { Text(c) },
                    onClick = { onSelect(c); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun AddKeyDialog(onAdd: (pem: String, desc: String) -> Unit, onDismiss: () -> Unit) {
    var pem by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add private key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = pem, onValueChange = { pem = it },
                    label = { Text("Paste PEM (or key path for plaintext configs)") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                )
                OutlinedTextField(
                    value = desc, onValueChange = { desc = it },
                    label = { Text("Description (vault label)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            Button(enabled = pem.isNotBlank(), onClick = { onAdd(pem.trim(), desc.trim()) }) {
                Text("Add")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
