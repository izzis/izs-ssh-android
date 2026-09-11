package id.web.izs.sshclient

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import id.web.izs.sshclient.core.sync.SyncRepository
import id.web.izs.sshclient.core.sync.TabbySyncApi
import id.web.izs.sshclient.core.session.SessionService
import id.web.izs.sshclient.data.local.ConfigDisk
import id.web.izs.sshclient.data.local.CrashLog
import id.web.izs.sshclient.ui.AppForeground
import id.web.izs.sshclient.ui.AppState
import id.web.izs.sshclient.ui.AppViewModel
import id.web.izs.sshclient.ui.resolveAppPalette
import id.web.izs.sshclient.ui.rememberAppDarkTheme
import id.web.izs.sshclient.ui.SessionLimitReached
import id.web.izs.sshclient.ui.SshSessionViewModel
import id.web.izs.sshclient.ui.screens.AppearanceSettingsScreen
import id.web.izs.sshclient.ui.screens.AboutScreen
import id.web.izs.sshclient.ui.screens.ConfigFileScreen
import id.web.izs.sshclient.ui.screens.ConfigSyncScreen
import id.web.izs.sshclient.ui.screens.ColorSchemeEditorScreen
import id.web.izs.sshclient.ui.screens.ColorSchemeSettingsScreen
import id.web.izs.sshclient.ui.screens.KeyboardLayoutScreen
import id.web.izs.sshclient.core.config.IZS_DEFAULT_SCHEME
import id.web.izs.sshclient.core.config.SchemeSource
import id.web.izs.sshclient.core.config.parseSchemeJson
import id.web.izs.sshclient.core.config.parseSchemeSource
import id.web.izs.sshclient.core.config.toJsonString
import id.web.izs.sshclient.ui.screens.CrashReportScreen
import id.web.izs.sshclient.ui.screens.ProfileEditScreen
import id.web.izs.sshclient.ui.screens.ProfileListScreen
import id.web.izs.sshclient.ui.screens.TerminalScreen
import id.web.izs.sshclient.ui.screens.TerminalSettingsScreen
import id.web.izs.sshclient.ui.screens.SettingsScreen
import id.web.izs.sshclient.ui.screens.SshSettingsScreen
import id.web.izs.sshclient.ui.screens.VaultSettingsScreen
import id.web.izs.sshclient.ui.screens.VaultUnlockDialog
import id.web.izs.sshclient.ui.screens.WindowSettingsScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * v1 flow: home is ALWAYS the profile list, on top
 * of a seeded empty local config when fresh — no setup gate. Sync connects
 * later from Settings > Config Sync; terminal is a real PTY shell.
 * Settings mirrors the desktop sidebar: Config Sync, SSH, Vault, Terminal,
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

/** Process foreground pump for [AppForeground] (no lifecycle-process dep). */
private object ForegroundPump : Application.ActivityLifecycleCallbacks {
    override fun onActivityStarted(activity: Activity) = AppForeground.onActivityStarted()
    override fun onActivityStopped(activity: Activity) = AppForeground.onActivityStopped()
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}

class MainActivity : ComponentActivity() {
    // Rotation-safe: the vault passphrase lives in AppState (RAM-only by
    // design) and must survive Activity recreation — never re-ask it.
    private val appHolder: AppViewModel by viewModels()
    // Multi-session registry: PTYs survive rotation + navigation here.
    // RAM-only like the passphrase; process death clears all sessions.
    private val sshHolder: SshSessionViewModel by viewModels()

    /**
     * Notification action target (SessionService "Disconnect all", singleTop
     * so this reuses the live instance): close every session — the registry
     * mirror then reports empty and the service stands itself down. Open
     * screens show the existing "Session closed" card.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNotificationIntent(intent)
    }

    private fun handleNotificationIntent(intent: Intent?) {
        if (intent?.action != ACTION_DISCONNECT_ALL) return
        for (h in sshHolder.ordered()) sshHolder.close(h.sessionId)
    }

    /**
     * Re-mirror on every resume: granting notifications from settings (or
     * the system dialog) does not re-post a notice that an OEM suppressed
     * while ungranted — this heals that and any other drift, silently
     * (same id, ongoing: an update, never a re-alert).
     */
    override fun onResume() {
        super.onResume()
        SessionService.refresh(
            applicationContext,
            sshHolder.connectedInfos().map { it.label },
        )
    }

    companion object {
        /** SessionService notification action: close every session. */
        const val ACTION_DISCONNECT_ALL = "id.web.izs.sshclient.DISCONNECT_ALL"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Debug-only tooling: the last-crash recorder must never run in release builds.
        if (BuildConfig.DEBUG)         CrashLog.install(this)
        // Background survival: pump the process foreground counter (no extra
        // deps) and mirror the session registry into SessionService. The
        // ViewModel never touches Context — both lambdas do.
        registerActivityLifecycleCallbacks(ForegroundPump)
        sshHolder.serviceSync = { infos ->
            SessionService.refresh(applicationContext, infos.map { it.label })
        }
        sshHolder.lostListener = { label ->
            // The FGS notification needs no permission, but this one-shot
            // notice does (API 33+). Ungranted = silent skip; the in-app
            // error card + auto-retry remain the fallback. Never prompt here.
            val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (granted) SessionService.notifyLost(this, label)
        }
        // A missing notification with live sessions must be visible, not
        // silent: Toast the start failure (catch runs on any thread).
        // Application context only — a static hook must never hold the
        // Activity past destroy.
        val appCtx = applicationContext
        SessionService.onError = { msg ->
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(
                    appCtx, "Background guard failed: $msg", android.widget.Toast.LENGTH_LONG,
                ).show()
            }
        }
        handleNotificationIntent(intent)
        setContent {
            val appState = remember {
                appHolder.state ?: run {
                    val disk = ConfigDisk(this@MainActivity)
                    AppState(disk, SyncRepository(disk, TabbySyncApi()), appHolder.viewModelScope)
                        .also {
                            appHolder.state = it
                            it.themeMode = disk.appTheme
                            it.paletteName = disk.appPalette
                        }
                }
            }
            // App-chrome theme (Settings > Appearance, device-only): read
            // as state so a change re-themes live. Dark preserves the
            // previous always-dark look.
            val darkTheme = rememberAppDarkTheme(appState.themeMode)
            val palette = resolveAppPalette(appState.paletteName)
            MaterialTheme(
                colorScheme = if (darkTheme) palette.dark else palette.light,
            ) {
                var boot by remember { mutableStateOf<Boot>(Boot.Loading) }
                var rebootCounter by remember { mutableStateOf(0) }

                LaunchedEffect(rebootCounter) {
                    boot = Boot.Loading
                    val ready = withContext(Dispatchers.IO) {
                        val ok = appState.bootLoad()
                        val crash = if (BuildConfig.DEBUG) CrashLog.read(this@MainActivity) else null
                        when {
                            crash != null -> Boot.Ready("crash", crash)
                            !ok || appState.loaded == null ->
                                Boot.Failed(appState.error ?: "Load failed")
                            // Home is ALWAYS the profile list.
                            // A fresh install owns a seeded empty config, so
                            // profiles can be added without Config Sync; sync
                            // connects later from Settings > Config Sync.
                            else -> Boot.Ready("profiles")
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
                            is Boot.Ready -> {
                                // Foreground auto-sync (desktop autoSync + tabby-android
                                // AutoSyncService parity): while the app is open, check
                                // cloud metadata every 60s; download only when
                                // modified_at changed. Default OFF (disk.auto).
                                // Silent + non-blocking: toast on update, never a
                                // modal (RAM sessions are unaffected by reloads).
                                AutoSyncTicker(appState)
                                AppNav(
                                    appState = appState,
                                    sessionViewModel = sshHolder,
                                    startRoute = b.startRoute,
                                    crashTrace = b.crashTrace,
                                    onCrashDismissed = {
                                        if (BuildConfig.DEBUG) CrashLog.clear(this@MainActivity)
                                        rebootCounter++
                                    },
                                    // Explicit exit from home: close every session
                                    // (sockets tear down off-Main inside close()),
                                    // then finish — onCleared covers anything left.
                                    onExitApp = {
                                        for (h in sshHolder.ordered()) sshHolder.close(h.sessionId)
                                        finish()
                                    },
                                )
                            }
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
            Text("Reset and restart")
        }
    }
}

/** Foreground auto-sync poll interval (desktop autoSync parity: 60s). */
private const val AUTO_SYNC_INTERVAL_MS = 60_000L

/**
 * Silent foreground poll: metadata check only, download on real change.
 * Skips when backgrounded, busy (boot/unlock/refresh), unconfigured, or
 * auto-sync off — so an idle tick costs nothing (no network, no crypto).
 * On update the state refreshes and a toast shows; a locked result never
 * pops a modal here (the unlock dialog appears naturally on the profile
 * list, and active terminal sessions keep running untouched).
 */
@Composable
private fun AutoSyncTicker(appState: AppState) {
    val appCtx = LocalContext.current.applicationContext
    LaunchedEffect(Unit) {
        while (true) {
            delay(AUTO_SYNC_INTERVAL_MS)
            if (!AppForeground.isForeground) continue
            if (appState.loading) continue
            if (!appState.disk.auto) continue
            val name = try {
                appState.repo.autoSyncTick()
            } catch (_: Exception) {
                // Silent: network/auth errors surface on the sync screen.
                null
            } ?: continue
            appState.refresh {
                val locked = appState.loaded?.unlockRequired == true
                Toast.makeText(
                    appCtx,
                    if (locked) "Config \"$name\" updated — passphrase needed"
                    else "Config \"$name\" updated",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }
}

@Composable
private fun AppNav(
    appState: AppState,
    sessionViewModel: SshSessionViewModel,
    startRoute: String,
    crashTrace: String?,
    onCrashDismissed: () -> Unit,
    onExitApp: () -> Unit,
) {
    val nav = rememberNavController()
    var limitError by remember { mutableStateOf<String?>(null) }
    // Tab navigation stays shallow: every session hop pops back to the home
    // list first, so the stack never grows ssh/A → ssh/B → ssh/C. Back from
    // any terminal = home (tab switching happens in the strip/drawer).
    // popBackStack-first: it is synchronous and reports failure, while a
    // bare navigate+popUpTo can silently drop when a transition is already
    // in flight — stranding a terminal destination over a removed handle
    // (the phantom-Disconnected shape: stale green + null shell).
    fun goHome() {
        if (!nav.popBackStack("profiles", inclusive = false)) {
            nav.navigate("profiles") { launchSingleTop = true }
        }
    }
    fun openSessionShallow(sid: String) {
        nav.navigate("ssh/$sid") {
            popUpTo("profiles")
            launchSingleTop = true
        }
    }
    fun openProfile(profileId: String) {
        val profile = appState.displayProfiles().find { it.id == profileId } ?: return
        try {
            // Every tap opens a NEW tab (desktop parity); connection sharing
            // is decided inside connect() from reuseSession.
            val sid = sessionViewModel.create(profile, appState.disk.maxSessions)
            // Desktop launchProfile parity: record recents at launch (not on
            // connect success). Cap-blocked taps throw above, so never recorded.
            val maxRecent = id.web.izs.sshclient.core.config.RawConfigStore.showRecentProfiles(
                appState.loaded?.store ?: emptyMap(),
            )
            appState.disk.recentProfileIds = id.web.izs.sshclient.data.local.recordRecent(
                appState.disk.recentProfileIds, profileId, maxRecent,
            )
            // Shallow: sheet picks from ssh/A must land on ssh/B without
            // stacking ssh/A -> ssh/B (Back = home, tab switching via
            // strip/drawer/Active list). From home this is a no-op pop.
            nav.navigate("ssh/$sid") {
                popUpTo("profiles")
                launchSingleTop = true
            }
        } catch (e: SessionLimitReached) {
            limitError = "Session limit is ${e.max}. Close a session first."
        }
    }
    // Tab × on the current screen: close, then land on the newest remaining
    // tab (or home when none). Closing a background tab needs no navigation.
    fun closeTab(tid: String, currentId: String) {
        sessionViewModel.close(tid)
        if (tid == currentId) {
            val next = sessionViewModel.ordered().lastOrNull()
            if (next != null) openSessionShallow(next.sessionId) else goHome()
        }
    }
    // Explicit exit from home is wired at the call site (needs the
    // Activity's finish()): see onExitApp above.
    fun exitApp() = onExitApp()
    NavHost(navController = nav, startDestination = startRoute) {
        composable("crash") {
            CrashReportScreen(trace = crashTrace ?: "", onDismissed = onCrashDismissed)
        }
        composable("profiles") {
            ProfileListScreen(
                appState,
                sessionViewModel,
                onOpen = { id -> openProfile(id) },
                onOpenSession = { sid -> openSessionShallow(sid) },
                onEdit = { id -> nav.navigate("edit/$id") },
                onAdd = { nav.navigate("edit/new") },
                onSettings = { nav.navigate("settings") },
                onExit = { exitApp() },
            )
            // Lazy-unlock parity: the non-dismissible dialog shows ONLY when the
            // listing itself is blocked (locked encrypted shell). A locked
            // plaintext-with-blob config lists fine; the passphrase is asked
            // at point of use (show-password, secret edit, first connect).
            // onNoConfig is a no-op: a local config always exists (seeded).
            if (appState.loaded?.unlockRequired == true) {
                VaultUnlockDialog(
                    appState,
                    onUnlocked = { },
                    onNoConfig = { },
                    dismissible = false,
                )
            }
        }
        composable(
            "ssh/{id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) { back ->
            val sid = back.arguments?.getString("id") ?: ""
            TerminalScreen(
                appState,
                sessionViewModel,
                sid,
                onBack = { goHome() },
                onOpenSession = { openSessionShallow(it) },
                onNewTab = { goHome() },
                onOpenProfile = { pid -> openProfile(pid) },
                onSettings = { nav.navigate("settings") },
                onCloseTab = { closeTab(it, sid) },
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
        composable("settings/terminal") {
            TerminalSettingsScreen(
                appState,
                onEditKeys = { nav.navigate("settings/keyboard") },
                onBack = { nav.popBackStack() },
            )
        }
        composable("settings/keyboard") {
            KeyboardLayoutScreen(appState) { nav.popBackStack() }
        }
        composable("settings/appearance") {
            AppearanceSettingsScreen(appState) { nav.popBackStack() }
        }
        composable("settings/colors") {
            ColorSchemeSettingsScreen(
                appState,
                onEditCurrent = { nav.navigate("settings/colors/edit") },
                onBack = { nav.popBackStack() },
            )
        }
        composable("settings/colors/edit") {
            // Desktop model: edit the CURRENT scheme (rename + Save upserts
            // a custom entry by name — no separate "new" flow). Device mode
            // edits the local copy (instant pref, no YAML).
            val isLocal = parseSchemeSource(appState.disk.colorSchemeSource) == SchemeSource.LOCAL
            val deviceCurrent = parseSchemeJson(appState.disk.localColorSchemeJson)
            val global = appState.loaded?.domain?.terminalColorScheme
            val customs = appState.loaded?.domain?.customColorSchemes ?: emptyList()
            ColorSchemeEditorScreen(
                appState,
                initial = if (isLocal) (deviceCurrent ?: IZS_DEFAULT_SCHEME)
                else (global ?: IZS_DEFAULT_SCHEME),
                showDelete = !isLocal && customs.any { it == global },
                onBack = { nav.popBackStack() },
                deviceMode = isLocal,
                onDeviceSave = { s ->
                    appState.disk.localColorSchemeJson = s.toJsonString()
                },
            )
        }
        composable("settings/window") {
            WindowSettingsScreen(appState) { nav.popBackStack() }
        }
        // About: version + feedback + license attributions.
        composable("settings/about") {
            AboutScreen { nav.popBackStack() }
        }
    }
    // Global: cap-blocked taps from home AND the terminal quick-pick sheet
    // surface here (previously home-only, silent from the sheet).
    if (limitError != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { limitError = null },
            title = { Text("Session limit reached") },
            text = { Text(limitError!!) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { limitError = null }) {
                    Text("OK")
                }
            },
        )
    }
}
