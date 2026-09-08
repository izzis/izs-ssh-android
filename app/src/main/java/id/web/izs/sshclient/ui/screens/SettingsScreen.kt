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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import id.web.izs.sshclient.ui.AppState

/**
 * Desktop-style settings: a section list (like the desktop settings sidebar)
 * opening one sub-screen per section.
 *
 * Included now: Config Sync (full cloud management), SSH, Vault (simplified),
 * Terminal (font, scrollback, extra keys), Config file. Appearance / Color scheme / Window are placeholders for the
 * next update. Hotkeys and Plugins are intentionally skipped on mobile
 * (no hardware keyboard assumption, no plugin runtime on Android), and
 * Profiles & Connections lives on the home page.
 */
data class SettingSection(
    val route: String,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val comingSoon: Boolean = false,
)

val SETTING_SECTIONS = listOf(
    SettingSection(
        "sync", "Config Sync", "Host, cloud configs, auto-sync",
        Icons.Filled.Sync,
    ),
    SettingSection(
        "ssh", "SSH", "Host key verification, close warning",
        Icons.Filled.VpnKey,
    ),
    SettingSection(
        "vault", "Vault", "Master passphrase, encryption, erase",
        Icons.Filled.Lock,
    ),
    SettingSection(
        "configfile", "Config file", "View the raw synced YAML",
        Icons.Filled.Description,
    ),
    SettingSection(
        "terminal", "Terminal", "Font size, scrollback, extra keys",
        Icons.Filled.Terminal,
    ),
    SettingSection(
        "appearance", "Appearance", "Next update",
        Icons.Filled.Style, comingSoon = true,
    ),
    SettingSection(
        "colors", "Color scheme", "Next update",
        Icons.Filled.Palette, comingSoon = true,
    ),
    SettingSection(
        "window", "Window", "Next update",
        Icons.Filled.AspectRatio, comingSoon = true,
    ),
)

@Composable
fun SettingsScreen(
    state: AppState,
    onSection: (route: String) -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenHeader("Settings", onBack)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(SETTING_SECTIONS, key = { it.route }) { s ->
                Card(modifier = Modifier.fillMaxWidth().clickable { onSection(s.route) }) {
                    Row(
                        Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(s.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f)) {
                            Text(s.title, style = MaterialTheme.typography.titleMedium)
                            Text(
                                s.subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(Icons.Filled.ChevronRight, contentDescription = null)
                    }
                }
            }
        }
        if (!state.disk.isEncryptedStorage) {
            Text(
                "Note: device keystore unavailable, local storage is unencrypted.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
