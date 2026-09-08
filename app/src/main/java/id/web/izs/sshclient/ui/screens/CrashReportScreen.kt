package id.web.izs.sshclient.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Shown when the previous run crashed (see CrashLog). The user can copy the
 * stack trace and send it to the developer, then clear it and continue.
 */
@Composable
fun CrashReportScreen(
    trace: String,
    onDismissed: () -> Unit,
) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Previous run crashed", style = MaterialTheme.typography.headlineSmall)
        Text(
            "The app crashed on the last launch. Copy the report below and send " +
                "it to the developer — it tells exactly what went wrong on this phone.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Card(Modifier.fillMaxWidth().weight(1f)) {
            SelectionContainer {
                Text(
                    trace,
                    Modifier.padding(12.dp).verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                )
            }
        }
        Button(
            onClick = {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("izs SSH crash", trace))
                copied = true
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (copied) "Copied" else "Copy crash report") }
        OutlinedButton(
            onClick = onDismissed,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Clear report & continue") }
    }
}
