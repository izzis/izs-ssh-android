package id.web.izs.sshclient.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Placeholder for settings sections scheduled for the next update
 * (Appearance, Color scheme, Window). The section stays visible in the list
 * so the desktop-like structure is complete, but opens this notice.
 */
@Composable
fun PlaceholderSettingScreen(
    title: String,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenHeader(title, onBack)
        Text(
            "Available in a future update. Syncing is unaffected: these values " +
                "stay inside the raw document and round-trip losslessly.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
