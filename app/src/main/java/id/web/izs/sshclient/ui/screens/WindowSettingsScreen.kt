package id.web.izs.sshclient.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import id.web.izs.sshclient.core.config.RawConfigStore
import id.web.izs.sshclient.core.config.TabLocation
import id.web.izs.sshclient.core.config.TabSource
import id.web.izs.sshclient.core.config.effectiveTabLocation
import id.web.izs.sshclient.core.config.ignoreEncryptedValue
import id.web.izs.sshclient.core.config.parseTabSource
import id.web.izs.sshclient.core.config.resolveTabLocation
import id.web.izs.sshclient.ui.AppState

/**
 * Settings > Window: tab location with an explicit source priority.
 *
 * - FOLLOW_YAML (default): the synced desktop `appearance.tabsLocation`
 *   wins. Off removes the key (absent = no tab chrome). Follow mode is a
 *   read-only mirror: the value is edited on desktop / via Config file. A
 *   locked encrypted shell is unreadable (stays Off); once unlocked the
 *   decrypted value applies normally.
 * - LOCAL: this device's own setting (a local pref, never synced) wins and
 *   YAML is ignored for display — the painless path for encrypted configs
 *   and for desktop-top/phone-bottom splits without touching sync parts.
 */
@Composable
fun WindowSettingsScreen(
    state: AppState,
    onBack: () -> Unit,
) {
    val encrypted = state.loaded?.domain?.encrypted == true
    // Locked encrypted shell = unreadable = ignore the synced value; once
    // unlocked (passphrase in RAM) the decrypted store applies normally.
    val blind = ignoreEncryptedValue(encrypted, state.loaded?.unlockRequired == true)
    val store = state.loaded?.store ?: emptyMap()
    var source by remember { mutableStateOf(parseTabSource(state.disk.tabSource)) }
    var yamlLoc by remember(state.loaded) {
        mutableStateOf(resolveTabLocation(RawConfigStore.tabsLocationRaw(store)))
    }
    var localLoc by remember {
        mutableStateOf(resolveTabLocation(state.disk.localTabLocation.ifBlank { null }))
    }
    var newTabMode by remember { mutableStateOf(state.disk.newTabMode) }
    var hideHeader by remember { mutableStateOf(state.disk.hideTerminalHeader) }

    // Read live each composition so the line below never lies.
    val effective = effectiveTabLocation(source, localLoc, blind, store)

    fun commitSource(v: TabSource) {
        source = v
        state.disk.tabSource = if (v == TabSource.LOCAL) "local" else "follow"
    }

    fun commitLocal(v: TabLocation) {
        localLoc = v
        state.disk.localTabLocation = v.yamlValue
    }

    @Composable
    fun RadioRow(
        selected: Boolean,
        enabled: Boolean,
        label: String,
        onClick: () -> Unit,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
                .clickable(enabled = enabled) { onClick() }
                .padding(vertical = 4.dp),
        ) {
            RadioButton(selected = selected, enabled = enabled, onClick = onClick)
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenHeader("Window", onBack)

        Text("Tab location follows", style = MaterialTheme.typography.titleMedium)
        RadioRow(
            selected = source == TabSource.FOLLOW_YAML,
            enabled = true,
            label = "Synced config",
            onClick = { commitSource(TabSource.FOLLOW_YAML) },
        )
        RadioRow(
            selected = source == TabSource.LOCAL,
            enabled = true,
            label = "This device only",
            onClick = { commitSource(TabSource.LOCAL) },
        )
        Text(
            if (source == TabSource.LOCAL) {
                "Uses the setting stored on this device. The synced config is ignored."
            } else {
                "Uses the synced config. Changes are shared with your other devices."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text("Tabs location", style = MaterialTheme.typography.titleMedium)
        val showingLocal = source == TabSource.LOCAL
        val current = if (showingLocal) localLoc else yamlLoc
        // Follow mode is a read-only mirror of the synced key (absent key =
        // Off default): the active value shows selected, the rest disabled.
        // Only This-device-only writes (to the local pref).
        val options = listOf(
            TabLocation.OFF to "Off. Show the profile list without tabs",
            TabLocation.TOP to "Top. Show tabs below the header",
            TabLocation.BOTTOM to "Bottom. Show tabs above the extra keys",
            TabLocation.LEFT to "Left. Show tabs in a side drawer",
            TabLocation.RIGHT to "Right. Show tabs in a side drawer",
        )
        for ((v, label) in options) {
            RadioRow(
                selected = current == v,
                enabled = showingLocal,
                label = label,
                onClick = { commitLocal(v) },
            )
        }
        if (!showingLocal) {
            Text(
                "This setting is read-only here. Change it on desktop or in Settings > Config file.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val effectiveLabel = effective.name.lowercase().replaceFirstChar { it.uppercase() }
        val effectiveSource = if (showingLocal) {
            "Using the setting on this device."
        } else if (blind) {
            "The synced config is locked. Unlock the vault to apply it."
        } else {
            "Using the synced config."
        }
        Text(
            "Currently: $effectiveLabel. $effectiveSource",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        if (!showingLocal && blind) {
            Text(
                "The encrypted config is locked. Unlock it to apply the synced setting.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "To keep different values on phone and desktop, turn off Appearance " +
                "under Settings > Config Sync > Synced parts.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("Terminal header", style = MaterialTheme.typography.titleMedium)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable {
                hideHeader = !hideHeader
                state.disk.hideTerminalHeader = hideHeader
            },
        ) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text("Hide terminal header")
                Text(
                    "Session options move to the menu on the active tab " +
                        "(or a floating menu button when tabs are off).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = hideHeader,
                onCheckedChange = { hideHeader = it; state.disk.hideTerminalHeader = it },
            )
        }
        Text(
            "Stored only on this device. It is never synced.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("New tab opens", style = MaterialTheme.typography.titleMedium)
        RadioRow(
            selected = newTabMode != id.web.izs.sshclient.data.local.ConfigDisk.MODE_NEW_TAB_SHEET,
            enabled = true,
            label = "Profile list (navigate back to home)",
            onClick = {
                newTabMode = id.web.izs.sshclient.data.local.ConfigDisk.MODE_NEW_TAB_LIST
                state.disk.newTabMode = newTabMode
            },
        )
        RadioRow(
            selected = newTabMode == id.web.izs.sshclient.data.local.ConfigDisk.MODE_NEW_TAB_SHEET,
            enabled = true,
            label = "Quick pick sheet (search over the terminal)",
            onClick = {
                newTabMode = id.web.izs.sshclient.data.local.ConfigDisk.MODE_NEW_TAB_SHEET
                state.disk.newTabMode = newTabMode
            },
        )
        Text(
            "Stored only on this device. It is never synced.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
