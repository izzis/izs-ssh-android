package id.web.izs.sshclient.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import id.web.izs.sshclient.core.config.RawConfigStore
import id.web.izs.sshclient.core.session.isBatteryExempt
import id.web.izs.sshclient.core.session.openBatterySettings
import id.web.izs.sshclient.ui.AppState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings > SSH (desktop SSH-tab parity, mobile-relevant subset).
 * verifyHostKeys + warnOnClose are wired: WinSCP/agent options are
 * Windows-only on desktop and meaningless on Android. Plaintext configs
 * only — on encrypted stores the ssh section lives inside the vault blob
 * (edited on desktop).
 */
@Composable
fun SshSettingsScreen(
    state: AppState,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val encrypted = state.loaded?.domain?.encrypted == true
    var verify by remember(state.loaded) {
        mutableStateOf(state.loaded?.domain?.ssh?.verifyHostKeys ?: true)
    }
    var warn by remember(state.loaded) {
        mutableStateOf(state.loaded?.domain?.ssh?.warnOnClose ?: false)
    }
    // Device-only (never synced): held only while sessions are connected.
    var keepAwake by remember { mutableStateOf(state.disk.keepAwake) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }

    // Desktop parity (ngModelChange=config.save()): toggles apply live, no
    // Save button. On failure the checkbox reverts and the error shows.
    fun saveLive(nextVerify: Boolean, nextWarn: Boolean, onError: () -> Unit) {
        scope.launch {
            busy = true
            msg = null
            try {
                withContext(Dispatchers.IO) {
                    state.repo.updateLocalRaw { raw ->
                        @Suppress("UNCHECKED_CAST")
                        val ssh = LinkedHashMap(
                            (raw[RawConfigStore.KEY_SSH] as? Map<String, Any?>) ?: emptyMap(),
                        )
                        ssh["verifyHostKeys"] = nextVerify
                        ssh["warnOnClose"] = nextWarn
                        raw[RawConfigStore.KEY_SSH] = ssh
                    }
                }
                state.refresh()
            } catch (e: Exception) {
                onError()
                msg = "Failed: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenHeader("SSH", onBack)
        if (encrypted) {
            Text(
                "This config is encrypted: SSH options live inside the vault blob " +
                    "and are edited on desktop. Mobile keeps them read-only to " +
                    "preserve upload parity.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Text("Connection", style = MaterialTheme.typography.titleMedium)
        // Whole-row tap toggles (box onCheckedChange stays null so the
        // row click is the single toggle source, same as Window settings).
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.clickable(enabled = !encrypted && !busy) {
                val old = verify
                verify = !verify
                saveLive(verify, warn) { verify = old }
            },
        ) {
            Checkbox(
                checked = verify,
                onCheckedChange = null,
                enabled = !encrypted && !busy,
            )
            Column {
                Text("Verify host keys when connecting")
                Text(
                    "New or changed keys ask first (fingerprint shown); " +
                        "off trusts everything silently.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.clickable(enabled = !encrypted && !busy) {
                val old = warn
                warn = !warn
                saveLive(verify, warn) { warn = old }
            },
        ) {
            Checkbox(
                checked = warn,
                onCheckedChange = null,
                enabled = !encrypted && !busy,
            )
            Column {
                Text("Warn when closing active connections")
                Text(
                    "Ask before disconnecting a live session. A profile with " +
                        "its own warnOnClose set still wins (desktop parity).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // Background keep-alive (device-only pref, instant — not part of the
        // synced YAML above, so it stays enabled on encrypted configs too).
        // Read every composition so the status below never lies after
        // returning from the system screen.
        Text("Background sessions", style = MaterialTheme.typography.titleMedium)
        val batteryExempt = isBatteryExempt(context)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.clickable { openBatterySettings(context) },
        ) {
            Icon(
                if (batteryExempt) Icons.Filled.BatteryFull else Icons.Filled.BatteryAlert,
                contentDescription = null,
                tint = if (batteryExempt) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.error,
            )
            Column(Modifier.weight(1f)) {
                Text("Battery use")
                Text(
                    if (batteryExempt) "Unrestricted — background sessions allowed."
                    else "Optimized — Android may kill background sessions.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.Filled.ChevronRight, contentDescription = "Open battery settings")
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.clickable {
                keepAwake = !keepAwake
                state.disk.keepAwake = keepAwake
            },
        ) {
            Checkbox(
                checked = keepAwake,
                onCheckedChange = null,
            )
            Column {
                Text("Keep CPU awake during sessions")
                Text(
                    "Held only while sessions are connected. Drains battery — " +
                        "enable for long jobs (htop, rsync). Connected " +
                        "sessions are always guarded by a foreground " +
                        "notification; this only adds a CPU wake lock on top.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        msg?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (busy) CircularProgressIndicator()
    }
}
