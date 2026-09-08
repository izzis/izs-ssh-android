package id.web.izs.sshclient

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.viewModelScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import id.web.izs.sshclient.core.sync.SyncRepository
import id.web.izs.sshclient.core.sync.TabbySyncApi
import id.web.izs.sshclient.data.local.ConfigDisk
import id.web.izs.sshclient.data.local.CrashLog
import id.web.izs.sshclient.ui.AppState
import id.web.izs.sshclient.ui.AppViewModel
import id.web.izs.sshclient.ui.IzsDarkColors
import id.web.izs.sshclient.ui.screens.ConfigFileScreen
import id.web.izs.sshclient.ui.screens.ConfigSyncScreen
import id.web.izs.sshclient.ui.screens.PlaceholderSettingScreen
import id.web.izs.sshclient.ui.screens.CrashReportScreen
import id.web.izs.sshclient.ui.screens.ProfileEditScreen
import id.web.izs.sshclient.ui.screens.ProfileListScreen
import id.web.izs.sshclient.ui.screens.TerminalScreen
import id.web.izs.sshclient.ui.screens.SettingsScreen
import id.web.izs.sshclient.ui.screens.SshSettingsScreen
import id.web.izs.sshclient.ui.screens.SyncSetupScreen
import id.web.izs.sshclient.ui.screens.VaultSettingsScreen
import id.web.izs.sshclient.ui.screens.VaultUnlockDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * v1 flow (desktop parity):
 * setup (host+token) -> sync (connection + cloud configs + options)
 * -> profiles (folders + search) -> terminal (real PTY shell).
 * Settings mirrors the desktop sidebar: Config Sync, SSH, Vault,
 * Config file (+ Appearance/Color scheme/Window placeholders).
 *
 * Boot is hardened: the start destination is computed BEFORE the NavHost is
 * composed (no post-compose navigation), load failures show a recovery screen
 * instead of crashing, and a previous-run crash shows the Crash Report screen.
 */
sealed interface Boot {
    data object Loading : Boot
    data class Ready(val startRoute: String, val crashTrace: String? = null) : Boot
    data class Failed(val message: String) : Boot
}

class MainActivity : ComponentActivity() {
    // Rotation-safe: the vault passphrase lives in AppState (RAM-only by
    // design) and must survive Activity recreation — never re-ask it.
    private val appHolder: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Debug-only tooling: the last-crash recorder must never run in release builds.
        if (BuildConfig.DEBUG)         CrashLog.install(this)
        setContent {
            MaterialTheme(colorScheme = IzsDarkColors) {
                val appState = remember {
                    appHolder.state ?: run {
                        val disk = ConfigDisk(this@MainActivity)
                        AppState(disk, SyncRepository(disk, TabbySyncApi()), appHolder.viewModelScope)
                            .also { appHolder.state = it }
                    }
                }
                var boot by remember { mutableStateOf<Boot>(Boot.Loading) }
                var rebootCounter by remember { mutableStateOf(0) }

                LaunchedEffect(rebootCounter) {
                    boot = Boot.Loading
                    val ready = withContext(Dispatchers.IO) {
                        val ok = appState.bootLoad()
                        val crash = if (BuildConfig.DEBUG) CrashLog.read(this@MainActivity) else null
                        val disk = appState.disk
                        when {
                            crash != null -> Boot.Ready("crash", crash)
                            !ok || appState.loaded == null ->
                                Boot.Failed(appState.error ?: "Load failed")
                            appState.loaded!!.needsPassphrase -> Boot.Ready("profiles")
                            disk.loadYaml() != null -> Boot.Ready("profiles")
                            !disk.host.isNullOrBlank() && !disk.token.isNullOrBlank() ->
                                Boot.Ready("sync")
                            else -> Boot.Ready("setup")
                        }
                    }
                    boot = ready
                }

                Scaffold { pad ->
                    Box(Modifier.fillMaxSize().padding(pad)) {
                        when (val b = boot) {
                            is Boot.Loading ->
                                CircularProgressIndicator(Modifier.align(Alignment.Center))
                            is Boot.Failed ->
                                BootFailedScreen(
                                    message = b.message,
                                    onRetry = { rebootCounter++ },
                                    onReset = {
                                        appState.disk.clearYaml()
                                        appState.repo.forgetPassphrase()
                                        rebootCounter++
                                    },
                                )
                            is Boot.Ready ->
                                AppNav(
                                    appState = appState,
                                    startRoute = b.startRoute,
                                    crashTrace = b.crashTrace,
                                    onCrashDismissed = {
                                        if (BuildConfig.DEBUG) CrashLog.clear(this@MainActivity)
                                        rebootCounter++
                                    },
                                )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BootFailedScreen(
    message: String,
    onRetry: () -> Unit,
    onReset: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Could not load local data", style = MaterialTheme.typography.headlineSmall)
        Text(message, style = MaterialTheme.typography.bodyMedium)
        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("Retry") }
        OutlinedButton(onClick = onReset, modifier = Modifier.fillMaxWidth()) {
            Text("Reset local data & restart")
        }
    }
}

@Composable
private fun AppNav(
    appState: AppState,
    startRoute: String,
    crashTrace: String?,
    onCrashDismissed: () -> Unit,
) {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = startRoute) {
        composable("crash") {
            CrashReportScreen(trace = crashTrace ?: "", onDismissed = onCrashDismissed)
        }
        composable("setup") {
            SyncSetupScreen(appState) {
                nav.navigate("sync") { popUpTo("setup") { inclusive = true } }
            }
        }
        composable("sync") {
            // First run (from setup) or Settings > Config Sync: after a download
            // the flow continues to the profile list (unlock dialog auto-shows
            // when encrypted); from Settings it just pops back.
            ConfigSyncScreen(
                appState,
                onDownloaded = {
                    if (nav.previousBackStackEntry?.destination?.route == "settings") {
                        nav.popBackStack()
                    } else {
                        nav.navigate("profiles") { popUpTo("sync") { inclusive = true } }
                    }
                },
                onBack = { nav.popBackStack() },
            )
        }
        composable("profiles") {
            ProfileListScreen(
                appState,
                onOpen = { id -> nav.navigate("ssh/$id") },
                onEdit = { id -> nav.navigate("edit/$id") },
                onSettings = { nav.navigate("settings") },
            )
            // Lazy-unlock parity: the non-dismissible dialog shows ONLY when the
            // listing itself is blocked (locked encrypted shell). A locked
            // plaintext-with-blob config lists fine; the passphrase is asked
            // at point of use (show-password, secret edit, first connect).
            if (appState.loaded?.unlockRequired == true) {
                VaultUnlockDialog(
                    appState,
                    onUnlocked = { },
                    onNoConfig = {
                        nav.navigate("setup") { popUpTo("profiles") { inclusive = true } }
                    },
                    dismissible = false,
                )
            }
        }
        composable(
            "ssh/{id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) { back ->
            TerminalScreen(
                appState,
                back.arguments?.getString("id") ?: "",
                onBack = { nav.popBackStack() },
            )
        }
        composable(
            "edit/{id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) { back ->
            ProfileEditScreen(
                appState,
                back.arguments?.getString("id") ?: "",
                onBack = { nav.popBackStack() },
            )
        }
        composable("settings") {
            SettingsScreen(
                appState,
                onSection = { nav.navigate("settings/$it") },
                onBack = { nav.popBackStack() },
            )
        }
        composable("settings/sync") {
            ConfigSyncScreen(
                appState,
                onDownloaded = { nav.popBackStack() },
                onBack = { nav.popBackStack() },
            )
        }
        composable("settings/ssh") {
            SshSettingsScreen(appState) { nav.popBackStack() }
        }
        composable("settings/vault") {
            VaultSettingsScreen(appState) { nav.popBackStack() }
        }
        composable("settings/configfile") {
            ConfigFileScreen(appState) { nav.popBackStack() }
        }
        composable("settings/appearance") {
            PlaceholderSettingScreen("Appearance") { nav.popBackStack() }
        }
        composable("settings/colors") {
            PlaceholderSettingScreen("Color scheme") { nav.popBackStack() }
        }
        composable("settings/window") {
            PlaceholderSettingScreen("Window") { nav.popBackStack() }
        }
    }
}
