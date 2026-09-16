package id.web.izs.sshclient.ui.screens

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Consistent sub-screen top bar (the Settings pattern): back arrow + title.
 * Screens using this must NOT add a bottom "Back" button. Home (profile
 * list) and Terminal keep their own headers.
 *
 * When a settings save is in flight (encrypted vault re-encrypt, large YAML),
 * pass [busy] = true to show a small spinner at the trailing end instead
 * of inserting a full-width progress row inside the scrolling content (which
 * jitters the layout). The header row itself is intended to be sticky — call
 * sites should keep it outside their scrolling column (see TerminalSettings).
 */
@Composable
fun ScreenHeader(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.weight(1f),
        )
        if (busy) {
            CircularProgressIndicator(
                strokeWidth = 2.dp,
                modifier = Modifier.padding(end = 12.dp).size(20.dp),
            )
        }
    }
}
