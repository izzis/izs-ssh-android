package id.web.izs.sshclient.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import id.web.izs.sshclient.core.config.IZS_DEFAULT_SCHEME
import id.web.izs.sshclient.core.config.RawConfigStore
import id.web.izs.sshclient.core.config.SchemeSource
import id.web.izs.sshclient.core.config.TerminalColorScheme
import id.web.izs.sshclient.core.config.deleteCustomByName
import id.web.izs.sshclient.core.config.parseSchemeJson
import id.web.izs.sshclient.core.config.parseSchemeSource
import id.web.izs.sshclient.core.config.toJsonString
import id.web.izs.sshclient.core.sync.SyncRepository
import id.web.izs.sshclient.data.local.ConfigDisk
import id.web.izs.sshclient.ui.AppState
import kotlinx.coroutines.launch

/**
 * Settings > Color scheme (desktop colorSchemeSettingsForMode parity):
 * a "Current color scheme" header (name + full preview + Edit, Delete when
 * the current matches a custom entry) over the searchable scheme list
 * (custom entries carry a Custom badge, customs first like desktop).
 * Save = global object + customs upsert by name (rename + Save creates a
 * new entry — no separate "new" flow, like desktop).
 *
 * Plus a phone-only source toggle (tabSource parity): synced YAML vs this
 * device (instant pref, no vault). Per-profile overrides live in the
 * profile editor Colours tab and apply in both modes.
 */
@Composable
fun ColorSchemeSettingsScreen(
    state: AppState,
    onEditCurrent: () -> Unit,
    onBack: () -> Unit,
) {
    val builtins = rememberBuiltinSchemes()
    val domain = state.loaded?.domain
    val global = domain?.terminalColorScheme
    val customs = domain?.customColorSchemes ?: emptyList()
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    // Source priority (tabSource parity): SYNCED = the YAML global wins;
    // LOCAL = this device's own scheme wins, synced global ignored. Local
    // writes are plain pref edits (instant, no vault decrypt).
    var source by remember { mutableStateOf(parseSchemeSource(state.disk.colorSchemeSource)) }
    var localSel by remember { mutableStateOf(parseSchemeJson(state.disk.localColorSchemeJson)) }
    // Disk prefs are not observable: re-read on resume so a device-scheme
    // edit (separate route) is reflected on return (keyLayout parity).
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                source = parseSchemeSource(state.disk.colorSchemeSource)
                localSel = parseSchemeJson(state.disk.localColorSchemeJson)
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    // Locked-vault retry (profile-editor pendingSave parity): the write
    // that hit "Vault is locked" reruns after a successful unlock.
    var showUnlock by remember { mutableStateOf(false) }
    var pendingRetry by remember { mutableStateOf<(() -> Unit)?>(null) }
    val scope = rememberCoroutineScope()

    fun runWrite(write: suspend () -> SyncRepository.Loaded) {
        msg = null
        scope.launch {
            busy = true
            try {
                // Adopt the write's own Loaded: a refresh() here would
                // re-decrypt + re-parse the whole store for nothing.
                val loaded = write()
                state.adopt(loaded)
            } catch (e: IllegalStateException) {
                // Lazy unlock: the blob must be rewritten.
                if ((e.message ?: "").contains("locked", ignoreCase = true)) {
                    pendingRetry = { runWrite(write) }
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

    fun commitGlobal(scheme: TerminalColorScheme?) {
        if (busy) return
        runWrite {
            state.repo.updateTerminalSection { raw -> RawConfigStore.setTerminalColorScheme(raw, scheme) }
        }
    }

    fun deleteCurrent() {
        val name = global?.name ?: return
        if (busy) return
        runWrite {
            state.repo.updateTerminalSection { raw ->
                RawConfigStore.setCustomColorSchemes(
                    raw,
                    deleteCustomByName(RawConfigStore.customColorSchemesRaw(raw), name),
                )
            }
        }
    }

    fun commitSource(v: SchemeSource) {
        source = v
        state.disk.colorSchemeSource =
            if (v == SchemeSource.LOCAL) ConfigDisk.SOURCE_LOCAL else ConfigDisk.SOURCE_SYNCED
    }

    /** Device-only pick: instant pref write, no YAML, no vault, no spinner. */
    fun commitLocal(scheme: TerminalColorScheme?) {
        localSel = scheme
        state.disk.localColorSchemeJson = scheme?.toJsonString() ?: ""
    }

    // Picker identity is by VALUE (data-class equals): a global object
    // picked earlier from either list matches here without name lookups.
    // Customs first, like desktop (custom.concat(stock)).
    val all = remember(builtins, customs) { customs + builtins }
    // Desktop getCurrentSchemeName parity: value-match custom/stock, else
    // the object is detached ("Custom").
    // In device mode the header shows the device scheme (no Edit — local
    // picks are final; full editing lives in synced mode).
    val effectiveCurrent = if (source == SchemeSource.LOCAL) localSel else global
    val currentCustom = effectiveCurrent?.let { g -> customs.find { it == g } }
    val currentBuiltin = effectiveCurrent?.let { g -> builtins.find { it == g } }
    val currentName = if (effectiveCurrent == null) "System default"
    else (currentCustom?.name ?: currentBuiltin?.name ?: "Custom")
    val currentShown = effectiveCurrent ?: IZS_DEFAULT_SCHEME

    // Whole page is ONE LazyColumn: 105 full-preview rows must virtualize
    // (an eager forEach in a scrolling Column composes + lays out every
    // row on each tap — measured seconds per scheme switch).
    var query by remember { mutableStateOf("") }
    val filtered = remember(all, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) all else all.filter { it.name.lowercase().contains(q) }
    }
    LazyColumn(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "header") { ScreenHeader("Colour scheme", onBack) }
        item(key = "blurb") {
            Text(
                "Terminal colours only. The profile list and app theme keep " +
                    "their own colours. Changes apply immediately, even to open sessions.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Desktop "Current color scheme" header: name + full preview +
        // Edit (+ Delete when the current matches a custom entry).
        item(key = "current") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Current colour scheme", style = MaterialTheme.typography.titleMedium)
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                currentName,
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f),
                            )
                            if (source == SchemeSource.SYNCED) {
                                androidx.compose.material3.TextButton(
                                    onClick = onEditCurrent,
                                    enabled = !busy,
                                ) { Text("Edit") }
                                if (currentCustom != null) {
                                    androidx.compose.material3.TextButton(
                                        onClick = ::deleteCurrent,
                                        enabled = !busy,
                                    ) {
                                        Text(
                                            "Delete",
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                            } else {
                                androidx.compose.material3.TextButton(onClick = onEditCurrent) {
                                    Text("Edit")
                                }
                            }
                        }
                        SchemeFullPreview(scheme = currentShown, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
        item(key = "follows-title") {
            Text("Colour scheme follows", style = MaterialTheme.typography.titleMedium)
        }
        item(key = "source-synced") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { commitSource(SchemeSource.SYNCED) },
            ) {
                RadioButton(
                    selected = source == SchemeSource.SYNCED,
                    onClick = { commitSource(SchemeSource.SYNCED) },
                )
                Text(
                    "Synced config",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        item(key = "source-local") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { commitSource(SchemeSource.LOCAL) },
            ) {
                RadioButton(
                    selected = source == SchemeSource.LOCAL,
                    onClick = { commitSource(SchemeSource.LOCAL) },
                )
                Text(
                    "This device only",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        item(key = "source-note") {
            Text(
                if (source == SchemeSource.LOCAL) {
                    "Uses the scheme stored on this device. The synced config is ignored."
                } else {
                    "Uses the synced config. Changes are shared with your other devices."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (source == SchemeSource.SYNCED) {
            item(key = "global-title") {
                Text("Global scheme", style = MaterialTheme.typography.titleMedium)
            }
            if (state.loaded?.needsPassphrase == true) {
                item(key = "locked-note") {
                    Text(
                        "The vault is locked. The passphrase will be asked before saving.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item(key = "search") {
                androidx.compose.material3.OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search colour schemes") },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item(key = "system-default") {
                Card(modifier = Modifier.fillMaxWidth().clickable { commitGlobal(null) }) {
                    Row(
                        Modifier.padding(12.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        androidx.compose.material3.RadioButton(
                            selected = global == null,
                            onClick = null,
                        )
                        Column(Modifier.weight(1f)) {
                            Text("System default", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "The default look.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            items(filtered, key = { it.name + it.background }) { s ->
                SchemeRow(
                    scheme = s,
                    selected = s == global,
                    onClick = { if (!busy) commitGlobal(s) },
                    badge = if (s in customs) "Custom" else null,
                )
            }
        } else {
            item(key = "device-title") {
                Text("This device's scheme", style = MaterialTheme.typography.titleMedium)
            }
            item(key = "device-search") {
                androidx.compose.material3.OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search colour schemes") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item(key = "device-default") {
                Card(modifier = Modifier.fillMaxWidth().clickable { commitLocal(null) }) {
                    Row(
                        Modifier.padding(12.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        androidx.compose.material3.RadioButton(
                            selected = localSel == null,
                            onClick = null,
                        )
                        Column(Modifier.weight(1f)) {
                            Text("System default", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Stored on this device only.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            items(filtered, key = { it.name + it.background + "-local" }) { s ->
                SchemeRow(
                    scheme = s,
                    selected = s == localSel,
                    onClick = { commitLocal(s) },
                    badge = if (s in customs) "Custom" else null,
                )
            }
        }
        msg?.let { m ->
            item(key = "msg") { Text(m, color = MaterialTheme.colorScheme.error) }
        }
        if (busy) {
            item(key = "busy") { CircularProgressIndicator() }
        }
    }

    if (showUnlock) {
        VaultUnlockDialog(
            state = state,
            onUnlocked = {
                showUnlock = false
                pendingRetry?.invoke()
                pendingRetry = null
            },
            onNoConfig = { showUnlock = false },
            onDismiss = { showUnlock = false; pendingRetry = null },
        )
    }
}
