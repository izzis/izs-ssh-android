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
            label = "Synced config (appearance.tabsLocation)",
            onClick = { commitSource(TabSource.FOLLOW_YAML) },
        )
        RadioRow(
            selected = source == TabSource.LOCAL,
            enabled = true,
            label = "This device only (ignores synced value)",
            onClick = { commitSource(TabSource.LOCAL) },
        )

        Text("Tab location", style = MaterialTheme.typography.titleMedium)
        val showingLocal = source == TabSource.LOCAL
        val current = if (showingLocal) localLoc else yamlLoc
        // Follow mode is a read-only mirror of the synced key (absent key =
        // Off default): the active value shows selected, the rest disabled.
        // Only This-device-only writes (to the local pref).
        val options = listOf(
            TabLocation.OFF to "Off — profile list, no tabs",
            TabLocation.TOP to "Top — strip under the header",
            TabLocation.BOTTOM to "Bottom — strip above the extra keys",
            TabLocation.LEFT to "Left — drawer, hamburger opens it",
            TabLocation.RIGHT to "Right — drawer on the right",
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
                "Synced value is read-only here — change it on desktop or via Settings > Config file.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "Effective now: ${effective.name.lowercase().replaceFirstChar { it.uppercase() }} " +
                if (showingLocal) "(device setting)"
                else if (blind) "(unreadable — locked encrypted config)"
                else "(synced config)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        if (!showingLocal && blind) {
            Text(
                "Locked encrypted config: unlock to apply the synced value.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "Cloud sync: whether the synced value uploads is controlled by " +
                "Settings > Config Sync > Synced parts > appearance (off = " +
                "desktop and phone keep their own values).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
