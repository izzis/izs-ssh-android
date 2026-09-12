package id.web.izs.sshclient.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FormatColorReset
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import id.web.izs.sshclient.core.config.ForwardedPort
import id.web.izs.sshclient.core.config.LoginScript
import id.web.izs.sshclient.core.config.PROFILE_COLORS
import id.web.izs.sshclient.core.config.SshAlgorithms
import id.web.izs.sshclient.core.config.SshProfile
import id.web.izs.sshclient.core.config.TerminalColorScheme
import id.web.izs.sshclient.core.config.normalizeProfileColor
import id.web.izs.sshclient.core.config.profileColorArgb
import id.web.izs.sshclient.core.sync.SyncRepository
import id.web.izs.sshclient.core.vault.SavedKeyInfo
import id.web.izs.sshclient.ui.AppState
import kotlinx.coroutines.launch

private val EDIT_TABS = listOf("General", "Ports", "Advanced", "Ciphers", "Colours", "Login")

/**
 * Profile editor with desktop-tab parity (General / Ports / Advanced /
 * Ciphers / Colours / Login scripts).
 *
 * `profileId == "new"` creates a profile (repo mints `ssh:custom:<uuid>`).
 *
 * Default-state rule (the cloud YAML omits defaults by design): fields left
 * at desktop defaults are REMOVED from YAML on save (see updateProfileMap);
 * the Domain view re-applies defaults transiently. So this editor always
 * works on the defaulted Domain model and blank/default input means "omit".
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
    val isNew = profileId == "new"
    val scope = rememberCoroutineScope()
    val original = remember(state.loaded, profileId) {
        if (isNew) null
        else state.displayProfiles().find { it.id == profileId }
    }
    val groups = remember(state.loaded) { state.displayGroups() }
    val vaultPresent = state.loaded?.domain?.vault != null
    val locked = state.loaded?.needsPassphrase == true
    val o = original?.options

    var tab by remember { mutableIntStateOf(0) }

    // ---- General ----
    var name by remember(original) { mutableStateOf(o?.let { original.name } ?: "") }
    var groupId by remember(original) { mutableStateOf(original?.group ?: "") }
    // Identity color (profile-level `color`, desktop tab-colorbar parity).
    // Stored normalized; blank = default = omit on save.
    var color by remember(original) { mutableStateOf(normalizeProfileColor(original?.color) ?: "") }
    var host by remember(original) { mutableStateOf(o?.host ?: "") }
    var portText by remember(original) { mutableStateOf(o?.port?.toString() ?: "22") }
    var user by remember(original) { mutableStateOf(o?.user ?: "root") }
    var auth by remember(original) { mutableStateOf(o?.auth ?: "") }
    // Desktop connectionMode parity (sshProfileSettings.component.ts:48-56):
    // priority proxyCommand > jumpHost > socksProxy > httpProxy > direct.
    // Save nulls the non-selected mode's fields (desktop save() parity).
    var connectionMode by remember(original) {
        mutableStateOf(
            when {
                !o?.proxyCommand.isNullOrBlank() -> "proxyCommand"
                !o?.jumpHost.isNullOrBlank() -> "jumpHost"
                !o?.socksProxyHost.isNullOrBlank() -> "socksProxy"
                !o?.httpProxyHost.isNullOrBlank() -> "httpProxy"
                else -> "direct"
            },
        )
    }
    var proxyCommand by remember(original) { mutableStateOf(o?.proxyCommand ?: "") }
    var jumpHost by remember(original) { mutableStateOf(o?.jumpHost ?: "") }
    var socksProxyHost by remember(original) { mutableStateOf(o?.socksProxyHost ?: "") }
    var socksProxyPortText by remember(original) { mutableStateOf(o?.socksProxyPort?.toString() ?: "") }
    var httpProxyHost by remember(original) { mutableStateOf(o?.httpProxyHost ?: "") }
    var httpProxyPortText by remember(original) { mutableStateOf(o?.httpProxyPort?.toString() ?: "") }
    var passwordTouched by remember(original) { mutableStateOf(false) }
    var passwordText by remember(original) { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }
    var removedRefs by remember(original) { mutableStateOf(setOf<String>()) }
    var addedKeys by remember { mutableStateOf(listOf<Pair<String, String>>()) }
    // Existing vault refs attached in this session (desktop selector parity:
    // no vault write, the ref is just added to options.privateKeys on save).
    var attachedRefs by remember(original) { mutableStateOf(listOf<String>()) }
    var showAddKey by remember { mutableStateOf(false) }
    var showUseSaved by remember { mutableStateOf(false) }
    var pendingUseSaved by remember { mutableStateOf(false) }

    // ---- Colours ----
    // Per-profile terminal scheme override (desktop sshProfileSettings
    // parity). Null = follow the global `terminal.colorScheme`.
    var scheme by remember(original) { mutableStateOf(original?.terminalColorScheme) }

    // ---- Ports / Ciphers / Login ----
    var forwards by remember(original) { mutableStateOf(o?.forwardedPorts ?: emptyList()) }
    var ciphers by remember(original) { mutableStateOf(o?.algorithms ?: SshAlgorithms.DEFAULTS) }
    var scripts by remember(original) { mutableStateOf(o?.scripts ?: emptyList()) }

    // ---- Advanced ----
    var x11 by remember(original) { mutableStateOf(o?.x11 ?: false) }
    var agentForward by remember(original) { mutableStateOf(o?.agentForward ?: false) }
    var skipBanner by remember(original) { mutableStateOf(o?.skipBanner ?: false) }
    var reuseSession by remember(original) { mutableStateOf(o?.reuseSession ?: true) }
    // No warnOnClose editor (desktop parity: it lives in Settings > SSH;
    // a stored per-profile value is preserved untouched via the copy below).
    var keepaliveText by remember(original) { mutableStateOf(o?.keepaliveInterval?.toString() ?: "5000") }
    var keepaliveMaxText by remember(original) { mutableStateOf(o?.keepaliveCountMax?.toString() ?: "10") }
    // The Domain view fills the transient 20000 default, so a stored-absent
    // readyTimeout shows as blank (= default = omit on save). An explicit
    // 20000 collapses to the same effective value — harmless.
    var readyTimeoutText by remember(original) {
        mutableStateOf(o?.readyTimeout?.takeIf { it != 20000L }?.toString() ?: "")
    }

    var showUnlock by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showNewGroup by remember { mutableStateOf(false) }
    var newGroupName by remember { mutableStateOf("") }
    var pendingNewGroup by remember { mutableStateOf(false) }
    var pendingSave by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }

    if (!isNew && original == null) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ScreenHeader("Edit profile", onBack)
            Text("Profile not found", color = MaterialTheme.colorScheme.error)
        }
        return
    }
    if (!isNew && original?.type != "ssh") {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ScreenHeader("Edit profile", onBack)
            Text("Only SSH profiles can be edited on this device.")
        }
        return
    }

    val hasSavedPassword = original?.let { state.passwordFor(it) } != null
    val effectivePassword: String? = when {
        !reveal -> null
        passwordTouched -> passwordText.ifBlank { null }
        else -> original?.let { state.passwordFor(it) }
    }
    val liveRefs = remember(original, removedRefs) {
        (original?.options?.privateKeys ?: emptyList()) - removedRefs
    }
    // Vault keys available to attach (empty while locked — the picker then
    // routes through unlock first, like the desktop "Vault is locked" throw).
    val savedKeys = remember(state.loaded) { state.savedKeys() }
    val secrets = SyncRepository.ProfileSecretEdits(
        password = passwordTouched.takeIf { it }?.let { passwordText },
        newKeyPems = addedKeys,
        removedKeyRefs = removedRefs.toList(),
    )

    fun buildOptions() = (o ?: id.web.izs.sshclient.core.config.SshOptions()).copy(
        host = host.trim(),
        port = portText.toIntOrNull()?.takeIf { it in 1..65535 }
            ?: if (isNew) 22 else (o?.port ?: 22),
        user = user.trim().ifBlank { "root" },
        auth = auth.ifBlank { null },
        // Only the selected connection mode's fields survive (desktop save
        // parity); the rest are nulled so the YAML stays unambiguous.
        proxyCommand = proxyCommand.trim().ifBlank { null }.takeIf { connectionMode == "proxyCommand" },
        jumpHost = jumpHost.trim().ifBlank { null }.takeIf { connectionMode == "jumpHost" },
        socksProxyHost = socksProxyHost.trim().ifBlank { null }.takeIf { connectionMode == "socksProxy" },
        socksProxyPort = socksProxyPortText.toIntOrNull()?.takeIf { it in 1..65535 }
            .takeIf { connectionMode == "socksProxy" },
        httpProxyHost = httpProxyHost.trim().ifBlank { null }.takeIf { connectionMode == "httpProxy" },
        httpProxyPort = httpProxyPortText.toIntOrNull()?.takeIf { it in 1..65535 }
            .takeIf { connectionMode == "httpProxy" },
        x11 = x11,
        agentForward = agentForward,
        skipBanner = skipBanner,
        reuseSession = reuseSession,
        keepaliveInterval = keepaliveText.toLongOrNull()?.takeIf { it > 0 } ?: 5000,
        keepaliveCountMax = keepaliveMaxText.toIntOrNull()?.takeIf { it > 0 } ?: 10,
        readyTimeout = readyTimeoutText.toLongOrNull()?.takeIf { it > 0 },
        algorithms = ciphers,
        forwardedPorts = forwards,
        scripts = scripts,
        // Final vault refs are assembled by the repo
        // (existing minus removed plus newly stored). Vault refs attached
        // from already-stored keys ride along untouched (no vault write).
        privateKeys = liveRefs + attachedRefs,
    )

    fun doSave() {
        if (host.isBlank()) {
            msg = "Enter a host name or IP address."
            return
        }
        scope.launch {
            busy = true
            msg = null
            try {
                if (isNew) {
                    state.repo.createProfile(
                        profile = SshProfile(
                            id = "",
                            name = name.ifBlank { "New profile" },
                            color = color.ifBlank { null },
                            terminalColorScheme = scheme,
                            options = buildOptions(),
                        ),
                        groupId = groupId.ifBlank { null },
                        groupName = groups.find { it.id == groupId }?.name,
                        secretEdits = secrets,
                    )
                } else {
                    val orig = original!!
                    val port = portText.toIntOrNull()?.takeIf { it in 1..65535 } ?: orig.options.port
                    val updated = orig.copy(
                        name = name.ifBlank { orig.name },
                        group = groupId.ifBlank { null },
                        color = color.ifBlank { null },
                        terminalColorScheme = scheme,
                        options = buildOptions().copy(
                            port = port,
                            user = user.trim().ifBlank { orig.options.user },
                        ),
                    )
                    state.repo.updateProfile(
                        profileId = profileId,
                        original = orig,
                        updated = updated,
                        newGroupId = groupId.ifBlank { "" }.takeIf { it != (orig.group ?: "") },
                        newGroupName = groups.find { it.id == groupId }?.name,
                        secretEdits = secrets,
                    )
                }
                state.refresh { onBack() }
            } catch (e: IllegalStateException) {
                // Lazy unlock: the save actually needs vault contents.
                if ((e.message ?: "").contains("locked", ignoreCase = true)) {
                    pendingSave = true
                    showUnlock = true
                } else {
                    msg = "Couldn't save: ${e.message}"
                }
            } catch (e: Exception) {
                msg = "Couldn't save: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    fun doNewGroup(name: String) {
        scope.launch {
            busy = true
            msg = null
            try {
                val id = java.util.UUID.randomUUID().toString()
                state.repo.createGroup(id, name.trim())
                state.refresh { groupId = id }
            } catch (e: IllegalStateException) {
                // Encrypted shell: re-encrypting needs the passphrase.
                if ((e.message ?: "").contains("locked", ignoreCase = true)) {
                    newGroupName = name
                    pendingNewGroup = true
                    showUnlock = true
                } else {
                    msg = "Couldn't save: ${e.message}"
                }
            } catch (e: Exception) {
                msg = "Couldn't save: ${e.message}"
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
                state.repo.deleteProfile(profileId, original!!)
                state.refresh { onBack() }
            } catch (e: IllegalStateException) {
                // Lazy unlock: re-encrypting the shell needs the passphrase.
                if ((e.message ?: "").contains("locked", ignoreCase = true)) {
                    pendingDelete = true
                    showUnlock = true
                } else {
                    msg = "Couldn't save: ${e.message}"
                }
            } catch (e: Exception) {
                msg = "Couldn't save: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            if (isNew) "New profile" else "Edit profile",
            onBack,
            Modifier.padding(top = 8.dp),
        )
        PrimaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 16.dp) {
            EDIT_TABS.forEachIndexed { i, title ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title) })
            }
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (tab) {
                0 -> GeneralTab(
                    name = name, onName = { name = it },
                    groups = groups, groupId = groupId, onGroup = { groupId = it },
                    onNewGroup = { newGroupName = ""; showNewGroup = true },
                    color = color, onColor = { color = it },
                    host = host, onHost = { host = it },
                    portText = portText, onPort = { portText = it.filter { c -> c.isDigit() }.take(5) },
                    user = user, onUser = { user = it },
                    auth = auth, onAuth = { auth = it },
                    connectionMode = connectionMode, onConnectionMode = { connectionMode = it },
                    proxyCommand = proxyCommand, onProxyCommand = { proxyCommand = it },
                    jumpHost = jumpHost, onJumpHost = { jumpHost = it },
                    socksProxyHost = socksProxyHost, onSocksProxyHost = { socksProxyHost = it },
                    socksProxyPortText = socksProxyPortText,
                    onSocksProxyPort = { socksProxyPortText = it.filter { c -> c.isDigit() }.take(5) },
                    httpProxyHost = httpProxyHost, onHttpProxyHost = { httpProxyHost = it },
                    httpProxyPortText = httpProxyPortText,
                    onHttpProxyPort = { httpProxyPortText = it.filter { c -> c.isDigit() }.take(5) },
                    passwordTouched = passwordTouched,
                    passwordText = passwordText,
                    onPassword = { passwordText = it; passwordTouched = true },
                    locked = locked,
                    hasSavedPassword = hasSavedPassword,
                    reveal = reveal,
                    onReveal = {
                        if (locked) {
                            pendingSave = false
                            showUnlock = true
                        } else {
                            reveal = !reveal
                        }
                    },
                    effectivePassword = effectivePassword,
                    liveRefs = liveRefs,
                    addedKeys = addedKeys,
                    onRemoveRef = { removedRefs = removedRefs + it },
                    onDiscardKey = { addedKeys = addedKeys - it },
                    attachedRefs = attachedRefs,
                    onDiscardAttached = { attachedRefs = attachedRefs - it },
                    keyLabel = { state.keyLabel(it) },
                    savedKeys = savedKeys,
                    vaultPresent = vaultPresent,
                    onAddKey = { showAddKey = true },
                    onUseSavedKey = {
                        if (locked) {
                            pendingUseSaved = true
                            showUnlock = true
                        } else {
                            showUseSaved = true
                        }
                    },
                )
                1 -> PortsTab(forwards = forwards, onChange = { forwards = it })
                2 -> AdvancedTab(
                    x11 = x11, onX11 = { x11 = it },
                    agentForward = agentForward, onAgentForward = { agentForward = it },
                    skipBanner = skipBanner, onSkipBanner = { skipBanner = it },
                    reuseSession = reuseSession, onReuseSession = { reuseSession = it },
                    keepaliveText = keepaliveText,
                    onKeepalive = { keepaliveText = it.filter { c -> c.isDigit() }.take(7) },
                    keepaliveMaxText = keepaliveMaxText,
                    onKeepaliveMax = { keepaliveMaxText = it.filter { c -> c.isDigit() }.take(4) },
                    readyTimeoutText = readyTimeoutText,
                    onReadyTimeout = { readyTimeoutText = it.filter { c -> c.isDigit() }.take(7) },
                )
                3 -> CiphersTab(checked = ciphers, onChange = { ciphers = it })
                4 -> ColoursTab(
                    global = state.loaded?.domain?.terminalColorScheme,
                    customs = state.loaded?.domain?.customColorSchemes ?: emptyList(),
                    selected = scheme,
                    onSelect = { scheme = it },
                )
                else -> ScriptsTab(scriptsList = scripts, onChange = { scripts = it })
            }
        }
        if (busy) CircularProgressIndicator(Modifier.padding(horizontal = 16.dp))
        msg?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp))
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) {
            OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("Cancel") }
            if (!isNew) {
                OutlinedButton(
                    enabled = !busy,
                    onClick = { showDeleteConfirm = true },
                    modifier = Modifier.weight(1f),
                ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            }
            Button(enabled = !busy, onClick = { doSave() }, modifier = Modifier.weight(1f)) {
                Text("Save")
            }
        }
    }

    if (showAddKey) {
        // Desktop addNewFile parity: the label defaults to
        // "private key for <profile>" (desktop appends the picked file name,
        // which paste has none of) — the field is optional, not required.
        val defaultKeyDesc = "private key for ${name.ifBlank { original?.name ?: "New profile" }}"
        AddKeyDialog(
            defaultDesc = defaultKeyDesc,
            onAdd = { pem, desc ->
                showAddKey = false
                addedKeys = addedKeys + (pem to desc)
            },
            onDismiss = { showAddKey = false },
        )
    }

    if (showUseSaved) {
        UseSavedKeyDialog(
            saved = savedKeys,
            // Already attached refs (stored or pasted this session) are
            // hidden so the same key cannot be attached twice.
            attached = (liveRefs + attachedRefs).toSet(),
            onConfirm = { refs ->
                showUseSaved = false
                attachedRefs = attachedRefs + refs
            },
            onDismiss = { showUseSaved = false },
        )
    }

    if (showNewGroup) {
        AlertDialog(
            onDismissRequest = { showNewGroup = false },
            title = { Text("New group") },
            text = {
                OutlinedTextField(
                    value = newGroupName,
                    onValueChange = { newGroupName = it },
                    label = { Text("Group name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            },
            confirmButton = {
                Button(
                    enabled = newGroupName.isNotBlank() && !busy,
                    onClick = {
                        showNewGroup = false
                        doNewGroup(newGroupName)
                    },
                ) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showNewGroup = false }) { Text("Cancel") } },
        )
    }

    if (showDeleteConfirm && !isNew) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete this profile?") },
            text = {
                Text(
                    "\"${original?.name}\" will be removed from the config. " +
                        "Saved vault secrets are kept because other profiles may use them. " +
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
                    pendingUseSaved -> {
                        pendingUseSaved = false
                        showUseSaved = true
                    }
                    pendingDelete -> {
                        pendingDelete = false
                        doDelete()
                    }
                    pendingSave -> {
                        pendingSave = false
                        doSave()
                    }
                    pendingNewGroup -> {
                        pendingNewGroup = false
                        doNewGroup(newGroupName)
                    }
                    else -> reveal = true
                }
            },
            onNoConfig = {
                showUnlock = false
                pendingSave = false
                pendingDelete = false
                pendingNewGroup = false
                pendingUseSaved = false
            },
            onDismiss = {
                showUnlock = false
                pendingSave = false
                pendingDelete = false
                pendingNewGroup = false
                pendingUseSaved = false
            },
            dismissible = true,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongParameterList")
private fun GeneralTab(
    name: String, onName: (String) -> Unit,
    groups: List<id.web.izs.sshclient.core.config.ProfileGroup>,
    groupId: String, onGroup: (String) -> Unit, onNewGroup: () -> Unit,
    color: String, onColor: (String) -> Unit,
    host: String, onHost: (String) -> Unit,
    portText: String, onPort: (String) -> Unit,
    user: String, onUser: (String) -> Unit,
    auth: String, onAuth: (String) -> Unit,
    connectionMode: String, onConnectionMode: (String) -> Unit,
    proxyCommand: String, onProxyCommand: (String) -> Unit,
    jumpHost: String, onJumpHost: (String) -> Unit,
    socksProxyHost: String, onSocksProxyHost: (String) -> Unit,
    socksProxyPortText: String, onSocksProxyPort: (String) -> Unit,
    httpProxyHost: String, onHttpProxyHost: (String) -> Unit,
    httpProxyPortText: String, onHttpProxyPort: (String) -> Unit,
    passwordTouched: Boolean, passwordText: String, onPassword: (String) -> Unit,
    locked: Boolean, hasSavedPassword: Boolean,
    reveal: Boolean, onReveal: () -> Unit,
    effectivePassword: String?,
    liveRefs: List<String>,
    addedKeys: List<Pair<String, String>>,
    onRemoveRef: (String) -> Unit,
    onDiscardKey: (Pair<String, String>) -> Unit,
    attachedRefs: List<String>,
    onDiscardAttached: (String) -> Unit,
    keyLabel: (String) -> String,
    savedKeys: List<SavedKeyInfo>,
    vaultPresent: Boolean,
    onAddKey: () -> Unit,
    onUseSavedKey: () -> Unit,
) {
    var showColour by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = name, onValueChange = onName,
            label = { Text("Name") }, modifier = Modifier.weight(1f), singleLine = true,
        )
        ColourDot(selected = color, onClick = { showColour = true })
    }
    if (showColour) {
        AlertDialog(
            onDismissRequest = { showColour = false },
            title = { Text("Profile colour") },
            text = {
                ColourPicker(
                    selected = color,
                    onSelect = { onColor(it); showColour = false },
                )
            },
            confirmButton = { TextButton(onClick = { showColour = false }) { Text("Done") } },
        )
    }
    GroupDropdown(groups = groups, selected = groupId, onSelect = onGroup, onNewGroup = onNewGroup)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = host, onValueChange = onHost,
            label = { Text("Host") }, modifier = Modifier.weight(2f), singleLine = true,
        )
        OutlinedTextField(
            value = portText, onValueChange = onPort,
            label = { Text("Port") }, modifier = Modifier.weight(1f), singleLine = true,
        )
    }
    OutlinedTextField(
        value = user, onValueChange = onUser,
        label = { Text("User") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
    )
    AuthDropdown(selected = auth, onSelect = onAuth)
    // Desktop parity (sshProfileSettings "Set password / Forget"): Forget
    // stages the removal — the saved password is dropped when you Save
    // (repo removes the vault secret / plaintext literal; nothing is
    // written before that, like every other edit on this screen).
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = if (passwordTouched) passwordText else "",
            onValueChange = onPassword,
            label = { Text("Password") },
            placeholder = {
                Text(
                    when {
                        locked -> "Locked. Tap Show to unlock."
                        hasSavedPassword -> "Saved. Leave empty to keep it."
                        else -> "No password saved."
                    },
                )
            },
            modifier = Modifier.weight(1f),
            singleLine = true,
            visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
            // Keep password keyboard (no predictions) even while revealed.
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                IconButton(onClick = onReveal) {
                    Icon(
                        if (reveal) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (reveal) "Hide password" else "Show password",
                    )
                }
            },
        )
        if (hasSavedPassword && !passwordTouched) {
            TextButton(onClick = { onPassword("") }) { Text("Forget") }
        }
    }
    if (passwordTouched && passwordText.isEmpty() && hasSavedPassword) {
        Text(
            "The password will be removed when you save.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    // No vault means the repo stores the literal in the profile YAML
    // (SyncRepository.saveProfile passwordField parity), so warn before
    // the user types a password they assume is encrypted.
    if (!vaultPresent && passwordTouched && passwordText.isNotBlank()) {
        Text(
            "No vault yet. This password will be saved as plain text. " +
                "Set a master passphrase first (Settings > Vault) to keep it encrypted.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    if (reveal) {
        Text(
            "Saved password: ${effectivePassword ?: "None"}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Text(
        "Private keys (${liveRefs.size + addedKeys.size + attachedRefs.size})",
        style = MaterialTheme.typography.titleMedium,
    )
    if (vaultPresent && locked) {
        Text(
            "The key list is locked. Unlock to manage keys.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    for (ref in liveRefs) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                keyLabel(ref),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                enabled = !vaultPresent || !locked,
                onClick = { onRemoveRef(ref) },
            ) {
                Icon(Icons.Filled.Delete, contentDescription = "Remove key")
            }
        }
    }
    for (entry in addedKeys) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                "New key: ${entry.second.ifBlank { "pasted key" }}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { onDiscardKey(entry) }) {
                Icon(Icons.Filled.Delete, contentDescription = "Discard key")
            }
        }
    }
    for (ref in attachedRefs) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                "Saved key: ${keyLabel(ref)}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { onDiscardAttached(ref) }) {
                Icon(Icons.Filled.Delete, contentDescription = "Detach key")
            }
        }
    }
    if (vaultPresent) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                enabled = !locked,
                onClick = onAddKey,
                modifier = Modifier.weight(1f),
            ) { Text("Paste new key") }
            OutlinedButton(
                onClick = onUseSavedKey,
                modifier = Modifier.weight(1f),
            ) { Text("Use saved key") }
        }
        if (locked) {
            Text(
                "Unlock to paste a new key or pick a saved one.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        OutlinedButton(
            onClick = onAddKey,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Add private key") }
    }
    Text("Connection", style = MaterialTheme.typography.titleMedium)
    ConnectionDropdown(selected = connectionMode, onSelect = onConnectionMode)
    if (connectionMode != "direct" && connectionMode != "socksProxy") {
        Text(
            "This connection type works on desktop. This device always connects directly.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    when (connectionMode) {
        "proxyCommand" -> OutlinedTextField(
            value = proxyCommand, onValueChange = onProxyCommand,
            label = { Text("Proxy command") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
        )
        "jumpHost" -> OutlinedTextField(
            value = jumpHost, onValueChange = onJumpHost,
            label = { Text("Jump host") },
            placeholder = { Text("Profile name or ID") },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
        )
        "socksProxy" -> Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedTextField(
                value = socksProxyHost, onValueChange = onSocksProxyHost,
                label = { Text("SOCKS host") }, modifier = Modifier.weight(1f), singleLine = true,
            )
            OutlinedTextField(
                value = socksProxyPortText, onValueChange = onSocksProxyPort,
                label = { Text("Port") }, modifier = Modifier.weight(1f), singleLine = true,
            )
        }
        "httpProxy" -> Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedTextField(
                value = httpProxyHost, onValueChange = onHttpProxyHost,
                label = { Text("HTTP proxy host") }, modifier = Modifier.weight(1f), singleLine = true,
            )
            OutlinedTextField(
                value = httpProxyPortText, onValueChange = onHttpProxyPort,
                label = { Text("Port") }, modifier = Modifier.weight(1f), singleLine = true,
            )
        }
    }
}

/**
 * Compact identity-color selector: a dot beside the Name field. The swatch
 * grid lives in a dialog so the General tab stays short.
 */
@Composable
private fun ColourDot(selected: String, onClick: () -> Unit) {
    val argb = profileColorArgb(selected)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(
                if (argb != null) Color(argb)
                else MaterialTheme.colorScheme.surfaceVariant,
            )
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
            .clickable(onClick = onClick),
    ) {
        if (argb == null) {
            Icon(
                Icons.Filled.FormatColorReset,
                contentDescription = "Pick profile colour",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Identity color picker (profile-level `color`, desktop tab-colorbar
 * parity — not the terminal color scheme, which lives in the Colours tab).
 * Preset swatches + Default; a stored custom hex (e.g. from desktop) shows
 * as an extra selected swatch instead of being hidden.
 */
@Composable
private fun ColourPicker(selected: String, onSelect: (String) -> Unit) {
    val swatches = remember(selected) {
        if (selected.isNotBlank() && !PROFILE_COLORS.contains(selected)) {
            listOf(selected) + PROFILE_COLORS
        } else PROFILE_COLORS
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // FlowRow, not fixed chunked rows: 7×40dp swatches overflow the
        // dialog on narrow phones and the clipped rightmost swatch reads
        // as an oval. Wrapping keeps every swatch a full circle.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ColourSwatch(hex = "", isSelected = selected.isBlank(), onSelect = onSelect)
            swatches.forEach { hex -> ColourSwatch(hex = hex, isSelected = hex == selected, onSelect = onSelect) }
        }
    }
}

@Composable
private fun ColourSwatch(hex: String, isSelected: Boolean, onSelect: (String) -> Unit) {
    val ring = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent
    if (hex.isBlank()) {
        // Default: no color stored.
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(2.dp, ring, CircleShape)
                .clickable { onSelect("") },
        ) {
            Icon(
                Icons.Filled.FormatColorReset,
                contentDescription = "Default colour",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    val argb = profileColorArgb(hex) ?: return
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Color(argb))
            .border(2.dp, ring, CircleShape)
            .clickable { onSelect(hex) },
    )
}

@Composable
private fun PortsTab(forwards: List<ForwardedPort>, onChange: (List<ForwardedPort>) -> Unit) {
    Text("Port forwarding", style = MaterialTheme.typography.titleMedium)
    Text(
        "Local and Remote rules open when this device connects. " +
            "Dynamic (SOCKS) stays desktop-only.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    for ((i, f) in forwards.withIndex()) {
        ForwardCard(
            f = f,
            // NOTE: the lambda must use its `updated` parameter — writing back
            // the captured `f` makes every keystroke a no-op (fields uneditable).
            onUpdate = { updated -> onChange(forwards.toMutableList().also { it[i] = updated }) },
            onRemove = { onChange(forwards.toMutableList().also { it.removeAt(i) }) },
        )
    }
    OutlinedButton(
        onClick = { onChange(forwards + ForwardedPort()) },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Add forwarding") }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ForwardCard(f: ForwardedPort, onUpdate: (ForwardedPort) -> Unit, onRemove: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            var typeOpen by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(
                expanded = typeOpen,
                onExpandedChange = { typeOpen = it },
                modifier = Modifier.weight(1f),
            ) {
                OutlinedTextField(
                    value = f.type, onValueChange = { },
                    readOnly = true, label = { Text("Type") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(typeOpen) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                )
                ExposedDropdownMenu(expanded = typeOpen, onDismissRequest = { typeOpen = false }) {
                    for (t in listOf("Local", "Remote", "Dynamic")) {
                        DropdownMenuItem(
                            text = { Text(t) },
                            onClick = { onUpdate(f.copy(type = t)); typeOpen = false },
                        )
                    }
                }
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Filled.Delete, contentDescription = "Remove forwarding")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = f.host, onValueChange = { onUpdate(f.copy(host = it)) },
                label = { Text("Listen interface") }, modifier = Modifier.weight(1f), singleLine = true,
            )
            OutlinedTextField(
                value = f.port.toString(), onValueChange = { onUpdate(f.copy(port = it.toIntOrNull() ?: f.port)) },
                label = { Text("Listen port") }, modifier = Modifier.weight(1f), singleLine = true,
            )
        }
        if (f.type != "Dynamic") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = f.targetAddress, onValueChange = { onUpdate(f.copy(targetAddress = it)) },
                    label = { Text("Target host") }, modifier = Modifier.weight(1f), singleLine = true,
                )
                OutlinedTextField(
                    value = f.targetPort.toString(),
                    onValueChange = { onUpdate(f.copy(targetPort = it.toIntOrNull() ?: f.targetPort)) },
                    label = { Text("Target port") }, modifier = Modifier.weight(1f), singleLine = true,
                )
            }
        }
        OutlinedTextField(
            value = f.description, onValueChange = { onUpdate(f.copy(description = it)) },
            label = { Text("Description") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
        )
    }
}

@Composable
@Suppress("LongParameterList")
private fun AdvancedTab(
    x11: Boolean, onX11: (Boolean) -> Unit,
    agentForward: Boolean, onAgentForward: (Boolean) -> Unit,
    skipBanner: Boolean, onSkipBanner: (Boolean) -> Unit,
    reuseSession: Boolean, onReuseSession: (Boolean) -> Unit,
    keepaliveText: String, onKeepalive: (String) -> Unit,
    keepaliveMaxText: String, onKeepaliveMax: (String) -> Unit,
    readyTimeoutText: String, onReadyTimeout: (String) -> Unit,
) {
    Text("Session", style = MaterialTheme.typography.titleMedium)
    CheckRow("X11 forwarding (desktop only)", x11, onX11)
    CheckRow("Forward SSH agent (desktop only)", agentForward, onAgentForward)
    CheckRow("Skip banner (desktop only)", skipBanner, onSkipBanner)
    CheckRow("Reuse session", reuseSession, onReuseSession)
    Text(
        "Reuse session shares one connection for all tabs of this profile. " +
            "When off, every tab connects separately. Options marked " +
            "(desktop only) are stored for desktop and have no effect on this device.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text("Timeouts", style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(
        value = keepaliveText, onValueChange = onKeepalive,
        label = { Text("Keep-alive interval") },
        placeholder = { Text("In milliseconds. Default: 5000.") },
        modifier = Modifier.fillMaxWidth(), singleLine = true,
    )
    OutlinedTextField(
        value = keepaliveMaxText, onValueChange = onKeepaliveMax,
        label = { Text("Keep-alive max misses") },
        placeholder = { Text("Default: 10.") },
        modifier = Modifier.fillMaxWidth(), singleLine = true,
    )
    Text(
        "Sends keep-alive messages at this interval.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        value = readyTimeoutText, onValueChange = onReadyTimeout,
        label = { Text("Ready timeout") },
        placeholder = { Text("In milliseconds. Empty uses the default.") },
        modifier = Modifier.fillMaxWidth(), singleLine = true,
    )
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun CiphersTab(checked: Map<String, List<String>>, onChange: (Map<String, List<String>>) -> Unit) {
    Text("Algorithms", style = MaterialTheme.typography.titleMedium)
    Text(
        "Uncheck to restrict negotiation. Unchanged lists use the default algorithms.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val labels = mapOf(
        SshAlgorithms.CIPHER to "Ciphers",
        SshAlgorithms.KEX to "Key exchange",
        SshAlgorithms.HMAC to "HMAC",
        SshAlgorithms.SERVER_HOST_KEY to "Host keys",
        SshAlgorithms.COMPRESSION to "Compression",
    )
    for (type in SshAlgorithms.TYPES) {
        Text(labels.getValue(type), style = MaterialTheme.typography.titleSmall)
        val defaults = SshAlgorithms.DEFAULTS.getValue(type)
        val current = checked[type] ?: defaults
        val candidates = (defaults + current.filter { it !in defaults }).distinct()
        for (algo in candidates) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Checkbox(
                    checked = algo in current,
                    onCheckedChange = { on ->
                        val next = if (on) current + algo else current - algo
                        onChange(checked + (type to next))
                    },
                )
                Text(
                    algo,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ColoursTab(
    global: TerminalColorScheme?,
    customs: List<TerminalColorScheme>,
    selected: TerminalColorScheme?,
    onSelect: (TerminalColorScheme?) -> Unit,
) {
    val builtins = rememberBuiltinSchemes()
    // Picker identity is by VALUE: a scheme picked earlier from either list
    // matches here without name lookups (desktop binds objects too).
    // Customs first, like desktop (custom.concat(stock)).
    val all = remember(builtins, customs) { customs + builtins }
    Text("Colours", style = MaterialTheme.typography.titleMedium)
    Text(
        "Terminal colours for this profile only.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    SchemePickerList(
        schemes = all,
        selected = selected,
        onSelect = onSelect,
        badgeFor = { if (it in customs) "Custom" else null },
        globalRow = {
            Card(modifier = Modifier.fillMaxWidth().clickable { onSelect(null) }) {
                Row(
                    Modifier.padding(12.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    RadioButton(selected = selected == null, onClick = null)
                    Column(Modifier.weight(1f)) {
                        Text("Use global default", style = MaterialTheme.typography.titleSmall)
                        Text(
                            global?.name ?: "System default",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun ScriptsTab(scriptsList: List<LoginScript>, onChange: (List<LoginScript>) -> Unit) {
    Text("Login scripts", style = MaterialTheme.typography.titleMedium)
    Text(
        "Wait for a prompt, then send a reply. Matching can be exact or a regular expression.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    for ((i, s) in scriptsList.withIndex()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Step ${i + 1}",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { onChange(scriptsList.toMutableList().also { it.removeAt(i) }) }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Remove step")
                }
            }
            OutlinedTextField(
                value = s.expect,
                onValueChange = { v -> onChange(scriptsList.toMutableList().also { it[i] = s.copy(expect = v) }) },
                label = { Text("Expect") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            )
            OutlinedTextField(
                value = s.send,
                onValueChange = { v -> onChange(scriptsList.toMutableList().also { it[i] = s.copy(send = v) }) },
                label = { Text("Send") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            )
            Row(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Checkbox(
                        checked = s.isRegex,
                        onCheckedChange = { v ->
                            onChange(scriptsList.toMutableList().also { it[i] = s.copy(isRegex = v) })
                        },
                    )
                    Text("Regex", style = MaterialTheme.typography.bodyMedium)
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Checkbox(
                        checked = s.optional,
                        onCheckedChange = { v ->
                            onChange(scriptsList.toMutableList().also { it[i] = s.copy(optional = v) })
                        },
                    )
                    Text("Optional", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
    OutlinedButton(
        onClick = { onChange(scriptsList + LoginScript()) },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Add step") }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectionDropdown(selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val titles = mapOf(
        "direct" to "Direct",
        "proxyCommand" to "Proxy command",
        "jumpHost" to "Jump host",
        "socksProxy" to "SOCKS proxy",
        "httpProxy" to "HTTP proxy",
    )
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = titles[selected] ?: "Direct", onValueChange = { },
            readOnly = true, label = { Text("Connection") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for ((value, title) in titles) {
                // jumpHost / proxyCommand / httpProxy can't connect from this
                // device (SOCKS can — it left the disabled list). They stay
                // visible so values synced from desktop round-trip untouched,
                // but switching INTO them from the phone is disabled — except
                // the currently active mode, which must remain shown/selected
                // or its value would look lost.
                val enabled = value == "direct" || value == "socksProxy" || value == selected
                DropdownMenuItem(
                    text = { Text(if (enabled) title else "$title (desktop only)") },
                    enabled = enabled,
                    onClick = { onSelect(value); expanded = false },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupDropdown(
    groups: List<id.web.izs.sshclient.core.config.ProfileGroup>,
    selected: String,
    onSelect: (String) -> Unit,
    onNewGroup: () -> Unit,
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
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
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
            DropdownMenuItem(
                text = { Text("New group...") },
                onClick = { expanded = false; onNewGroup() },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AuthDropdown(selected: String, onSelect: (String) -> Unit) {
    // Desktop parity (sshProfileSettings.component.pug:110-171): Auto is
    // auth=null (try everything); an explicit choice restricts the methods
    // at connect (agent errors clearly, interactive narrows to password).
    var expanded by remember { mutableStateOf(false) }
    val choices = listOf(
        "" to "Auto",
        "password" to "Password",
        "publicKey" to "Key",
        "agent" to "Agent",
        "keyboardInteractive" to "Interactive",
    )
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = choices.toMap()[selected] ?: selected, onValueChange = { },
            readOnly = true, label = { Text("Auth method") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for ((value, label) in choices) {
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = { onSelect(value); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun AddKeyDialog(defaultDesc: String, onAdd: (pem: String, desc: String) -> Unit, onDismiss: () -> Unit) {
    var pem by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add private key") },
        text = {
            // The PEM box is a FIXED-height window (long pastes scroll
            // inside it) and the whole content scrolls too, so the label
            // field below is always reachable on small screens.
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = pem, onValueChange = { pem = it },
                    label = { Text("Paste private key") },
                    modifier = Modifier.fillMaxWidth().height(200.dp),
                    minLines = 3,
                )
                OutlinedTextField(
                    value = desc, onValueChange = { desc = it },
                    label = { Text("Description (optional)") },
                    placeholder = { Text(defaultDesc) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            Button(
                enabled = pem.isNotBlank(),
                onClick = { onAdd(pem.trim(), desc.trim().ifBlank { defaultDesc }) },
            ) {
                Text("Add")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Desktop vault.selectAndStoreFile selector parity ("Select file": existing
 * vault keys by description, multi-pick on mobile). Picking attaches the
 * `vault://` ref — the vault payload itself is never rewritten.
 */
@Composable
private fun UseSavedKeyDialog(
    saved: List<SavedKeyInfo>,
    attached: Set<String>,
    onConfirm: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val available = remember(saved, attached) { saved.filter { it.ref !in attached } }
    var picked by remember(available) { mutableStateOf(setOf<String>()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Use saved key") },
        text = {
            if (available.isEmpty()) {
                Text("No saved keys yet. Paste a new key first.")
            } else {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    for (k in available) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable {
                                picked = if (k.ref in picked) picked - k.ref else picked + k.ref
                            },
                        ) {
                            Checkbox(
                                checked = k.ref in picked,
                                onCheckedChange = { on ->
                                    picked = if (on) picked + k.ref else picked - k.ref
                                },
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    k.description.ifBlank { "saved key" },
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    "…${k.ref.takeLast(8)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (available.isNotEmpty()) {
                Button(
                    enabled = picked.isNotEmpty(),
                    onClick = { onConfirm(available.filter { it.ref in picked }.map { it.ref }) },
                ) {
                    Text("Add (${picked.size})")
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
