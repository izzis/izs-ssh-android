package id.web.izs.sshclient.ui.screens

import android.app.Activity
import android.content.ClipData
import android.graphics.Rect
import android.util.Log
import android.view.ViewTreeObserver
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.BackHandler
import id.web.izs.sshclient.BuildConfig
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PowerOff
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.PasswordVisualTransformation
import id.web.izs.sshclient.ui.AuthPrompt
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import id.web.izs.sshclient.core.config.TabLocation
import id.web.izs.sshclient.core.config.RawConfigStore
import id.web.izs.sshclient.core.config.effectiveTabLocation
import id.web.izs.sshclient.core.config.ignoreEncryptedValue
import id.web.izs.sshclient.core.config.parseSchemeSource
import id.web.izs.sshclient.core.config.parseSchemeJson
import id.web.izs.sshclient.core.config.parseTabSource
import id.web.izs.sshclient.core.config.resolveActiveScheme
import id.web.izs.sshclient.core.config.isFallbackScheme
import id.web.izs.sshclient.core.config.IZS_DEFAULT_LIGHT_SCHEME
import id.web.izs.sshclient.core.config.resolveTabLocation
import id.web.izs.sshclient.core.config.resolveTerminalFont
import id.web.izs.sshclient.core.config.schemeColorArgb
import id.web.izs.sshclient.core.ssh.SftpTransferManager
import id.web.izs.sshclient.core.ssh.SshConnector
import id.web.izs.sshclient.core.term.TerminalInput
import id.web.izs.sshclient.core.term.KeyStep
import id.web.izs.sshclient.core.term.loadKeyLayout
import id.web.izs.sshclient.core.term.stepBytes
import id.web.izs.sshclient.ui.AppState
import id.web.izs.sshclient.ui.SshSessionViewModel
import id.web.izs.sshclient.ui.rememberAppDarkTheme
import id.web.izs.sshclient.ui.components.SessionTabDrawerContent
import id.web.izs.sshclient.ui.components.SessionTabStrip
import id.web.izs.sshclient.ui.components.TabDrawerFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * A real interactive SSH shell: xterm-256color PTY + VT100 emulator grid.
 * Direct typing is primary (soft keyboard streams raw keystrokes, so vim
 * and htop work); the old command box stays as an option via the toggle.
 *
 * Multi-session: the PTY lives in [SshSessionViewModel] (survives rotation
 * and navigation). This composable only observes the handle. Back never
 * closes the connection — only the explicit disconnect control does.
 *
 * Lazy unlock: a locked vault prompts for the passphrase before connecting,
 * then connects automatically.
 */
@Composable
fun TerminalScreen(
    state: AppState,
    sessionViewModel: SshSessionViewModel,
    sessionId: String,
    onBack: () -> Unit,
    onOpenSession: (String) -> Unit = {},
    onNewTab: () -> Unit = {},
    onOpenProfile: (String) -> Unit = {},
    onSettings: () -> Unit = {},
    onCloseTab: (String) -> Unit = {},
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboard = LocalClipboard.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val handle = remember(sessionId) { sessionViewModel.get(sessionId) }
    if (handle == null) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Session closed", color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back") }
        }
        return
    }
    val status by handle.status.collectAsState()
    val stage by handle.stage.collectAsState()
    val failed by handle.failed.collectAsState()
    val hostKeyPrompt by handle.hostKeyPrompt.collectAsState()
    // Desktop prompt-password parity: auth failure (wrong or missing
    // password) offers `Password for user@host` instead of a dead-end card.
    val authPrompt by handle.authPrompt.collectAsState()
    val pwSavePending by handle.passwordSavePending.collectAsState()
    val emuVersion by handle.version.collectAsState()
    // Shell presence as OBSERVABLE state: branching on the plain
    // `handle.shell` field subscribes to nothing, so the branch group can
    // keep evaluating a stale null forever (green dot + Disconnected row +
    // dead Reconnect on a perfectly live session). hasShell emits on every
    // assignment, forcing a fresh read.
    val hasShell by handle.hasShell.collectAsState()
    // Background-transfer presence (the SFTP "dot"): observing the
    // session's manager subscribes this screen, so the slim indicator
    // below appears while transfers run — even with the sheet dismissed
    // and even on a sibling tab's screen for its own transfers.
    val sftpMgr = remember(sessionId) { sessionViewModel.sftpOf(sessionId) }
    val sftpRows by sftpMgr.transfers.collectAsState()
    val sftpRunning = remember(sftpRows) {
        sftpRows.filter { it.status == SftpTransferManager.Status.RUNNING }
    }
    // Tab chrome (desktop appearance.tabsLocation parity): absent key (or an
    // encrypted config, which Android ignores) = OFF = no tab UI at all —
    // unless Settings > Window says this device has its own setting, which
    // wins over YAML. Read every composition (cheap pref reads): returning
    // from Settings must apply without reopening the session.
    val tabLoc = effectiveTabLocation(
        parseTabSource(state.disk.tabSource),
        resolveTabLocation(state.disk.localTabLocation.ifBlank { null }),
        ignoreEncryptedValue(
            state.loaded?.domain?.encrypted == true,
            state.loaded?.unlockRequired == true,
        ),
        state.loaded?.store ?: emptyMap(),
    )
    // Registry read subscribes this scope: strip/drawer follow open/close.
    val tabs = sessionViewModel.ordered()
    // IME dock height (px): declared up here so the outer Column pads the
    // WHOLE content (grid + strip + extra keys), not just the grid.
    val dockDensity = LocalDensity.current
    var dockPx by remember { mutableFloatStateOf(0f) }
    val liveProfiles = remember(state.loaded) { state.displayProfiles() }
    fun tabTitleOf(h: id.web.izs.sshclient.ui.SshSessionHandle): String =
        liveProfiles.find { it.id == h.profileId }?.name ?: h.profileSnapshot.name
    var closeTarget by remember { mutableStateOf<String?>(null) }
    // New-tab mode (Settings > Window, device-only): "sheet" opens the
    // quick-pick bottom sheet over this session, "list" goes home.
    var showNewTabSheet by remember { mutableStateOf(false) }
    fun handleNewTab() {
        if (state.disk.newTabMode ==
            id.web.izs.sshclient.data.local.ConfigDisk.MODE_NEW_TAB_SHEET
        ) {
            showNewTabSheet = true
        } else {
            onNewTab()
        }
    }
    // Custom side drawer (not M3): a plain boolean, no direction hacks.
    // Strip scroll is hoisted to the VM (see tabStripScrollPx): each tab is
    // its own destination, so a strip-local state would reset left on switch.
    var drawerOpen by remember { mutableStateOf(false) }
    val stripScroll = remember { ScrollState(sessionViewModel.tabStripScrollPx) }
    LaunchedEffect(stripScroll.value) { sessionViewModel.tabStripScrollPx = stripScroll.value }
    // This screen becoming visible = tab focused: selection + dot clear.
    LaunchedEffect(sessionId) { sessionViewModel.select(sessionId) }
    val profile = remember(state.loaded, handle.profileId) {
        state.displayProfiles().find { it.id == handle.profileId } ?: handle.profileSnapshot
    }
    val locked = state.loaded?.needsPassphrase == true

    val emulator = handle.emulator
    // Scrollback pref (Settings > Terminal): applied live on every
    // composition — deliberately impure, the setter only trims and never
    // triggers recomposition, so there is no loop risk.
    emulator.maxHistory = state.disk.terminalScrollback
    // Color scheme (Settings > Color scheme, profile Colours tab): source
    // priority (synced YAML vs this device) + per-profile override. Applied
    // in an effect (not bare composition like maxHistory above): setPalette
    // also resets the pen, and a bare call would race multi-chunk escape
    // sequences on every version-bump recomposition. Restarting only on
    // scheme change (data-class equals) makes returning from Settings
    // repaint the LIVE session — no reconnect needed (setPalette remaps old
    // cells too, so the whole screen follows the switch, not just new
    // output). The device JSON parses only when its string changes.
    val schemeSource = parseSchemeSource(state.disk.colorSchemeSource)
    val deviceScheme = remember(state.disk.localColorSchemeJson) {
        parseSchemeJson(state.disk.localColorSchemeJson)
    }
    val resolvedScheme = resolveActiveScheme(
        profile.terminalColorScheme,
        state.loaded?.domain?.terminalColorScheme,
        schemeSource,
        deviceScheme,
    )
    // Light-mode default: when NO scheme is set anywhere (system
    // default), the live terminal follows the app theme like the
    // Appearance preview does — light bg in light mode. Any explicit
    // scheme (profile/global/device) wins untouched.
    val displayScheme =
        if (
            !rememberAppDarkTheme(state.themeMode) && isFallbackScheme(
                profile.terminalColorScheme,
                state.loaded?.domain?.terminalColorScheme,
                schemeSource,
                deviceScheme,
            )
        ) {
            IZS_DEFAULT_LIGHT_SCHEME
        } else {
            resolvedScheme
        }
    LaunchedEffect(displayScheme) { emulator.setPalette(displayScheme) }
    // Stage follows the scheme background full-bleed (was pure black):
    // with few rows the grid no longer seams against a black page above
    // and the app surface below. The top bar keeps the themed surface.
    val stageBg = remember(displayScheme) {
        schemeColorArgb(displayScheme.background)?.let { Color(it) } ?: Color.Black
    }
    // Terminal font (`terminal.font` YAML, Settings > Appearance): resolved
    // live so returning from Settings applies instantly. SYSTEM renders
    // the system monospace; SOURCE_CODE_PRO the bundled file. The cell
    // metrics and the canvas share this family, so switching re-measures.
    val termFont = resolveTerminalFont(
        RawConfigStore.terminalFontName(state.loaded?.store ?: emptyMap()),
    )
    val fontFamily = rememberTerminalFontFamily(termFont)
    // Cursor shape + blink (`terminal.cursor`/`cursorBlink` YAML, Settings >
    // Appearance): read live so returning from Settings applies instantly.
    val termCursor = RawConfigStore.terminalCursor(state.loaded?.store ?: emptyMap())
    val termBlink = RawConfigStore.terminalCursorBlink(state.loaded?.store ?: emptyMap())
    // First fit per session is instant; later ones are settle-debounced
    // (see the refit below) so the keyboard animation never reflows.
    var sizedOnce by remember(sessionId) { mutableStateOf(false) }
    // The live socket lives in the handle (rotation-safe). Composition reads
    // go through [hasShell] (observable); event handlers read handle.shell
    // directly (fresh at event time). Never branch composition on the plain
    // field — see hasShell above.
    var boxMode by remember { mutableStateOf(false) }
    var showKeys by remember { mutableStateOf(true) }
    var showMenu by remember { mutableStateOf(false) }
    // Editable extra-keys bar (Settings > Terminal > Extra keys): reloaded
    // on every resume so edits apply without reopening the session.
    var keyLayout by remember { mutableStateOf(loadKeyLayout(state.disk.extraKeysJson)) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) keyLayout = loadKeyLayout(state.disk.extraKeysJson)
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    var boxInput by remember { mutableStateOf("") }
    // Hidden pipe state (state-based field, predictions off, Termux-style):
    // display-only — bytes reach the shell as platform events (see
    // SshInputPipe), so this never needs to match the remote line.
    val pipeState = rememberTextFieldState("")
    var fontSp by remember { mutableStateOf(state.disk.terminalFontSp) }
    var ctrlSticky by remember { mutableStateOf(false) }
    var altSticky by remember { mutableStateOf(false) }
    var copiedMsg by remember { mutableStateOf<String?>(null) }
    var showUnlock by remember { mutableStateOf(false) }
    var showCloseConfirm by remember { mutableStateOf(false) }
    // Deferred vault save of a typed password that connected while locked.
    var pendingPasswordSave by remember { mutableStateOf(false) }
    // The VM raises passwordSavePending only when the save hit a locked
    // vault: route through unlock; otherwise store straight away.
    LaunchedEffect(pwSavePending) {
        if (pwSavePending != null && !pendingPasswordSave) {
            if (state.loaded?.needsPassphrase == true) {
                pendingPasswordSave = true
                showUnlock = true
            } else {
                sessionViewModel.savePendingPassword(sessionId, state)
            }
        }
    }
    // SFTP sheet visibility only — the transfers themselves are owned by
    // the session's SftpTransferManager, so hiding this sheet (back,
    // dismiss, tab switch) never touches a running transfer.
    var showSftp by remember { mutableStateOf(false) }
    var pendingConnect by remember { mutableStateOf(false) }
    // Like the web textarea reset: a submitted line clears the pipe and
    // restarts IME state (composing) with the keyboard staying open.
    val view = LocalView.current
    val imm = remember(context) { context.getSystemService(InputMethodManager::class.java) }
    fun resetImeLine() {
        pipeState.edit { replace(0, length, "") }
        try { imm.restartInput(view) } catch (_: Exception) { }
    }
    fun setFont(v: Float) {
        val c = v.coerceIn(8f, 24f)
        fontSp = c
        state.disk.terminalFontSp = c
    }

    fun doConnect() {
        sessionViewModel.connect(sessionId, state, context.cacheDir)
    }

    fun doConnectWithPassword(password: String, remember: Boolean) {
        sessionViewModel.connectWithPassword(sessionId, state, context.cacheDir, password, remember)
    }

    fun sendRaw(text: String) {
        val s = handle.shell ?: return
        scope.launch {
            try {
                withContext(Dispatchers.IO) { s.sendRaw(text) }
            } catch (e: Exception) {
                sessionViewModel.markSendFailed(sessionId, "Send failed: ${e.message}")
            }
        }
    }

    /**
     * Termux-like sticky modifiers: typed text goes through CTRL/ALT, then
     * the stickies reset (special-key buttons bypass + reset as well).
     */
    fun sendCooked(text: String) {
        if (text.isEmpty()) return
        sendRaw(TerminalInput.applySticky(text, ctrlSticky, altSticky))
        ctrlSticky = false
        altSticky = false
    }

    /**
     * Extra-key sequences bypass sticky modifiers (CTRL+arrow would corrupt
     * the escape sequence) but still consume them. Sticky applies to
     * keyboard-typed text only.
     */
    fun sendSpecial(seq: String) {
        sendRaw(seq)
        ctrlSticky = false
        altSticky = false
    }

    /**
     * Staged delivery: a single packet behaves exactly like sendSpecial;
     * multiple packets go out separately with the Settings > Terminal step
     * delay between them, clearing the stickies like any extra-key send.
     * Declared before [sendKeySteps]: local functions must precede use.
     */
    fun sendChunks(s: SshConnector.ShellSession, chunks: List<String>) {
        if (chunks.size == 1) {
            sendSpecial(chunks[0])
            return
        }
        val gapMs = state.disk.macroStepDelayMs
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    for ((i, c) in chunks.withIndex()) {
                        if (i > 0 && gapMs > 0) delay(gapMs)
                        s.sendRaw(c)
                    }
                }
                ctrlSticky = false
                altSticky = false
            } catch (e: Exception) {
                sessionViewModel.markSendFailed(sessionId, "Send failed: ${e.message}")
            }
        }
    }

    /**
     * Single funnel for every extra-key/menu payload, as explicit step
     * bytes. Bytes go out staged with a settle delay so each part registers
     * in order (vim `:q!`: ESC, then text, then Enter). A submitted line
     * (CR/LF) resets IME state. UP/DOWN is just ESC[A/B — the shell owns
     * the recalled line and the IME never models it (Termux: backspace
     * sends DEL unconditionally, so any length deletes). The bar tap
     * steals focus onto the button, so hand it straight back.
     */
    fun sendKeySteps(steps: List<KeyStep>) {
        val s = handle.shell ?: return
        val byteSteps = steps.map(::stepBytes).filter { it.isNotEmpty() }
        if (byteSteps.isEmpty()) return
        var submitted = false
        for (b in byteSteps) {
            if (b.any { it == '\r' || it == '\n' }) submitted = true
        }
        if (submitted) resetImeLine()
        sendChunks(s, byteSteps)
        focusRequester.requestFocus()
        keyboard?.show()
    }

    fun acceptHostKey(remember: Boolean) {
        sessionViewModel.acceptHostKey(sessionId, remember, state, context.cacheDir)
    }

    /** Back never closes: the session survives in the registry. */
    fun goBack() {
        onBack()
    }

    /** Explicit disconnect: close the socket, free the slot, leave. */
    fun doDisconnectAndBack() {
        sessionViewModel.close(sessionId)
        onBack()
    }

    /** Abort an in-flight connect (never surfaces as a failure) and leave. */
    fun cancelConnect() {
        sessionViewModel.cancelConnect(sessionId)
        sessionViewModel.close(sessionId)
        onBack()
    }

    /**
     * [alwaysConfirm] is for the status dot: a 32dp invisible tap target
     * next to the title is a mis-tap hazard, and an instant silent kill
     * reads exactly like the phantom-Disconnected bug (green dot frozen +
     * dead Reconnect). The dot always asks; the power button honors
     * warnOnClose like desktop.
     */
    fun requestDisconnect(alwaysConfirm: Boolean = false) {
        // Desktop parity (sshTab): per-profile warnOnClose wins, otherwise
        // the global Settings > SSH toggle (default off). Guards an ACTIVE
        // session only — failed/connecting/closed states close at once.
        val warn = profile.options.warnOnClose
            ?: state.loaded?.domain?.ssh?.warnOnClose
            ?: false
        if (status == "connected" && (alwaysConfirm || warn)) {
            showCloseConfirm = true
        } else {
            doDisconnectAndBack()
        }
    }

    /** Tab × : same warnOnClose gate as disconnect, then the caller closes. */
    fun requestTabClose(sid: String) {
        val target = tabs.find { it.sessionId == sid }
        val live = target?.let { t -> liveProfiles.find { it.id == t.profileId } }
        val warn = live?.options?.warnOnClose
            ?: target?.profileSnapshot?.options?.warnOnClose
            ?: state.loaded?.domain?.ssh?.warnOnClose
            ?: false
        if (warn && target?.isConnected == true) {
            closeTarget = sid
        } else {
            onCloseTab(sid)
        }
    }

    LaunchedEffect(sessionId, state.loaded) {
        if (handle.shell == null && failed == null && hostKeyPrompt == null && !handle.connecting) {
            if (locked) {
                pendingConnect = true
                showUnlock = true
            } else {
                doConnect()
            }
        }
    }
    // No DisposableEffect close: rotation and navigation must NOT kill the
    // PTY. Cleanup happens in SshSessionViewModel.onCleared() (process death)
    // or explicit disconnect above.
    BackHandler { onBack() }
    // Drawer-open press closes the drawer first: registered after the
    // generic handler, so it wins (LIFO) with home as the fallback.
    if (tabLoc.isDrawer) {
        BackHandler(enabled = drawerOpen) { drawerOpen = false }
    }

    // Side tab drawer (left/right): custom frame keeps the screen LTR — no
    // whole-screen RTL mirror. Other modes render the Column directly.
    TabDrawerFrame(
        side = tabLoc,
        open = drawerOpen && tabLoc.isDrawer,
        onClose = { drawerOpen = false },
        drawer = {
            SessionTabDrawerContent(
                sessions = tabs,
                selectedId = sessionId,
                titleOf = ::tabTitleOf,
                onSelect = { sid ->
                    drawerOpen = false
                    if (sid != sessionId) onOpenSession(sid)
                },
                onCloseRequest = ::requestTabClose,
                onNew = {
                    drawerOpen = false
                    handleNewTab()
                },
                onHome = {
                    drawerOpen = false
                    onBack()
                },
                onSettings = {
                    drawerOpen = false
                    onSettings()
                },
            )
        },
    ) {
    // Stage stays full-bleed scheme background, but the top bar now matches every other
    // page (themed surface, back arrow + title) — the slate strip is gone.
    // Back keeps the session alive; the status dot disconnects.
    // Whole-column dock: on edge-to-edge devices adjustResize no longer
    // shrinks the window, so a grid-only pad leaves the bar under the IME.
    Column(
        Modifier.fillMaxSize().background(stageBg)
            .padding(bottom = with(dockDensity) { dockPx.toDp() }),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(end = 12.dp, top = 4.dp, bottom = 4.dp),
        ) {
            if (tabLoc.isDrawer) {
                // Drawer mode: the arrow becomes the hamburger that opens
                // the tab drawer on the YAML side. System Back still = home.
                IconButton(onClick = { drawerOpen = true }) {
                    Icon(Icons.Filled.Menu, contentDescription = "Open tabs")
                }
            } else {
                IconButton(onClick = { onBack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back (session stays alive)")
                }
            }            // Status dot sits on the NAME row so user@host below gets the
            // full width (green = connected, amber = connecting, red =
            // disconnected; tap to disconnect).
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        profile.name,
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    val dot = when (status) {
                        "connected" -> Color(0xFF4CAF50)
                        "connecting…" -> Color(0xFFFFC107)
                        else -> MaterialTheme.colorScheme.error
                    }
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(32.dp).clickable(
                            onClick = { requestDisconnect(alwaysConfirm = true) },
                            onClickLabel = "Disconnect",
                        ),
                    ) {
                        Box(Modifier.size(12.dp).background(dot, CircleShape))
                    }
                }
                Text(
                    "${profile.options.user}@${profile.options.host}:${profile.options.port}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            // Explicit disconnect (the status dot does the same): a power
            // icon reads as "kill this session", unlike the ambiguous dot.
            // Back (arrow / system) never disconnects — session stays alive.
            IconButton(onClick = { requestDisconnect() }) {
                Icon(
                    Icons.Filled.PowerOff,
                    contentDescription = "Disconnect",
                    tint = if (status == "connected") MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { boxMode = !boxMode }) {
                Icon(
                    if (boxMode) Icons.Filled.Terminal else Icons.Filled.Keyboard,
                    contentDescription = if (boxMode) "Direct typing mode" else "Command box mode",
                )
            }
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Terminal options")
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("SFTP") },
                        enabled = hasShell,
                        onClick = {
                            showMenu = false
                            showSftp = true
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Font - (now ${fontSp.toInt()}sp)") },
                        onClick = {
                            showMenu = false
                            setFont(fontSp - 1f)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Font + (now ${fontSp.toInt()}sp)") },
                        onClick = {
                            showMenu = false
                            setFont(fontSp + 1f)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(if (showKeys) "Hide extra keys" else "Show extra keys") },
                        onClick = {
                            showMenu = false
                            showKeys = !showKeys
                        },
                    )
                }
            }
        }
        // tabsLocation=top: strip under the header, above everything else.
        if (tabLoc == TabLocation.TOP) {
            SessionTabStrip(
                sessions = tabs,
                selectedId = sessionId,
                titleOf = ::tabTitleOf,
                onSelect = { if (it != sessionId) onOpenSession(it) },
                onCloseRequest = ::requestTabClose,
                onNew = ::handleNewTab,
                scroll = stripScroll,
            )
        }
        if (copiedMsg != null) {
            Text(
                copiedMsg!!,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
            )
        }
        // Slim tappable transfer row: visible only while THIS session has
        // running transfers. Tap reopens the sheet; it never stops anything.
        if (sftpRunning.isNotEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth()
                    .clickable { showSftp = true }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        Modifier.size(8.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape),
                    )
                    val label = if (sftpRunning.size == 1) {
                        val t = sftpRunning[0]
                        val arrow = if (t.direction == SftpTransferManager.Direction.DOWNLOAD) "↓" else "↑"
                        val pct = if (t.total > 0) " · ${(100 * t.done / t.total).toInt()}%" else ""
                        "$arrow ${t.name}$pct"
                    } else {
                        "${sftpRunning.size} transfers running"
                    }
                    Text(
                        label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (sftpRunning.size == 1) {
                    val t = sftpRunning[0]
                    if (t.total > 0) {
                        LinearProgressIndicator(
                            progress = { t.done.toFloat() / t.total.toFloat() },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
        // Copy confirmations clear themselves; the next copy re-arms.
        LaunchedEffect(copiedMsg) {
            if (copiedMsg != null) {
                delay(2500)
                copiedMsg = null
            }
        }
        if (failed != null) {
            Card(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(failed!!, color = MaterialTheme.colorScheme.error)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            if (state.loaded?.needsPassphrase == true) {
                                pendingConnect = true
                                showUnlock = true
                            } else {
                                doConnect()
                            }
                        }) { Text("Retry") }
                        OutlinedButton(onClick = { doDisconnectAndBack() }) { Text("Close") }
                    }
                }
            }
        } else if (!hasShell && hostKeyPrompt == null) {
            if (status == "connecting…") {
                // Loading row: live step text instead of a spinner (the user sees
                // what is actually happening), Cancel pinned at the far right.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                ) {
                    Text(
                        stage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = { cancelConnect() }) { Text("Cancel") }
                }
            } else {
                // Unexpected disconnect (network loss, background kill): kept
                // in the registry (red dot) for reconnect.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                ) {
                    Text(
                        "Disconnected",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = {
                        if (state.loaded?.needsPassphrase == true) {
                            pendingConnect = true
                            showUnlock = true
                        } else {
                            doConnect()
                        }
                    }) { Text("Reconnect") }
                    OutlinedButton(onClick = { doDisconnectAndBack() }) { Text("Close") }
                }
            }
        }
        // Keyboard dock:
        // (1) visualViewport equivalent — decorView.getWindowVisibleDisplayFrame
        //     is the rect that is ACTUALLY visible, immune to SwiftKey's
        //     over-claimed inset when its window is only as tall as the keys.
        //     Trusted (zero shave) when notably smaller than the IME inset.
        // (2) Skip redundant sets.
        // (3) 500ms re-assert safety net while open.
        // (4) Small settle wait in the hot path (no fit-spam: our refit hits
        //     the network via window-change, so no 150/400ms fit retries).
        // No fixed shave: one constant can never fit both an over-claiming
        // keyboard and an accurate one (44dp buried accurate keyboards).
        val imeBottomPx = WindowInsets.ime.getBottom(dockDensity).toFloat()
        val activity = LocalContext.current as? Activity
        var visKbPx by remember { mutableFloatStateOf(0f) }
        DisposableEffect(activity) {
            val decor = activity?.window?.decorView
            if (decor == null) return@DisposableEffect onDispose {}
            val rect = Rect()
            val lis = ViewTreeObserver.OnGlobalLayoutListener {
                decor.getWindowVisibleDisplayFrame(rect)
                val screenH = decor.resources.displayMetrics.heightPixels.toFloat()
                val occluded = screenH - rect.bottom.toFloat()
                // 15% threshold filters the nav bar; below it = no keyboard.
                visKbPx = if (occluded > screenH * 0.15f) occluded else 0f
            }
            decor.viewTreeObserver.addOnGlobalLayoutListener(lis)
            onDispose { decor.viewTreeObserver.removeOnGlobalLayoutListener(lis) }
        }
        LaunchedEffect(imeBottomPx, visKbPx) {
            // Visible rect notably smaller than claimed inset = true keys.
            val useVis = visKbPx > 0f && visKbPx < imeBottomPx - with(dockDensity) { 10.dp.toPx() }
            val target = (if (useVis) visKbPx else imeBottomPx).coerceAtLeast(0f)
            if (target != dockPx) {
                delay(10)
                dockPx = target
                Log.d("ImeDock", "ime=$imeBottomPx vis=$visKbPx useVis=$useVis dock=$dockPx")
            }
            // Safety net: re-assert the dock height periodically.
            while (true) {
                delay(500)
                val t = (if (useVis) visKbPx else imeBottomPx).coerceAtLeast(0f)
                if (t != dockPx) {
                    dockPx = t
                    Log.d("ImeDock", "re-assert ime=$imeBottomPx vis=$visKbPx dock=$t")
                }
            }
        }
        BoxWithConstraints(
            modifier = Modifier.weight(1f).fillMaxWidth()
                .background(stageBg)
                .pointerInput(tabLoc, drawerOpen) {
                    // Edge-band fling opens the side drawer. NEVER consumes:
                    // taps, scrollback scrolls and selection drags keep
                    // working untouched. Only the system Back edge (~32dp on
                    // each side) is reserved — the old 25%..75% middle band
                    // forced opens from the middle of the screen. A fast
                    // horizontal fling (not a slow scroll) in the drawer's
                    // opening direction triggers it: rightward for LEFT,
                    // leftward for RIGHT.
                    if (!tabLoc.isDrawer || drawerOpen) return@pointerInput
                    val rightward = tabLoc == TabLocation.LEFT
                    val edgePx = with(density) { 32.dp.toPx() }
                    val minX = edgePx
                    val maxX = size.width - edgePx
                    val vMin = with(density) { 500.dp.toPx() }
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (down.position.x < minX || down.position.x > maxX) return@awaitEachGesture
                        val tracker = VelocityTracker()
                        var totalX = 0f
                        var totalY = 0f
                        var up = false
                        while (!up) {
                            val ev = awaitPointerEvent()
                            val c = ev.changes.firstOrNull { it.id == down.id }
                            if (c == null || !c.pressed) {
                                up = true
                            } else {
                                totalX += c.position.x - c.previousPosition.x
                                totalY += c.position.y - c.previousPosition.y
                                tracker.addPosition(c.uptimeMillis, c.position)
                                if (ev.changes.all { !it.pressed }) up = true
                            }
                        }
                        val v = try { tracker.calculateVelocity() } catch (_: Exception) {
                            return@awaitEachGesture
                        }
                        val okDir = if (rightward) v.x > vMin && totalX > 0f
                        else v.x < -vMin && totalX < 0f
                        if (okDir && abs(totalX) > abs(totalY) * 1.5f) drawerOpen = true
                    }
                },
        ) {
            // Explicit tick read: the emulator mutates in place, so the tick
            // is what invalidates this scope (belt & suspenders next to key()).
            val tick = emuVersion
            val density = LocalDensity.current
            val (charW, lineH) = rememberTerminalCell(fontSp, fontFamily)
            val availW = with(density) { maxWidth.toPx() }
            val availH = with(density) { maxHeight.toPx() }
            // Screen-protector edges: keep the outer columns visible.
            val sidePad = 6.dp
            val sidePadPx = with(density) { sidePad.toPx() }
            val wantCols = ((availW - sidePadPx * 2) / charW).toInt().coerceIn(20, 256)
            val wantRows = (availH / lineH).toInt().coerceIn(8, 64)

            suspend fun applySize(c: Int, r: Int) {
                val t0 = if (BuildConfig.DEBUG) System.nanoTime() else 0L
                emulator.resize(c, r)
                handle.bumpVersion()
                try {
                    withContext(Dispatchers.IO) {
                        handle.shell?.resize(c, r, (c * charW).toInt(), (r * lineH).toInt())
                    }
                } catch (_: Exception) { }
                if (BuildConfig.DEBUG) {
                    Log.d("TvPerf", "resize ${c}x$r ms=${(System.nanoTime() - t0) / 1_000_000.0}")
                }
            }
            // Refit at SETTLE, never per-frame. Sizes stream every animation
            // frame while the keyboard slides; reflowing per frame (buffer
            // rebuild + full Canvas redraw + window-change packet) pegged
            // the CPU and froze scrolling until settle. Layout (dock height,
            // viewport, scroll) still tracks live — only the expensive
            // reflow waits for 150ms quiet.
            val wantSize = wantCols to wantRows
            val latestWant by rememberUpdatedState(wantSize)
            LaunchedEffect(wantSize) {
                if (sizedOnce) delay(150)
                val (c, r) = latestWant
                if (c == emulator.cols && r == emulator.rows) {
                    sizedOnce = true
                    return@LaunchedEffect
                }
                applySize(c, r)
                sizedOnce = true
            }
            LaunchedEffect(status) {
                // Fresh shells start at 80x24: correct the server at once.
                // Keyed on status (shell itself is not observable): fires on
                // connect and on rotate-while-connected via refit above.
                if (status != "connected") return@LaunchedEffect
                val s = handle.shell ?: return@LaunchedEffect
                try {
                    withContext(Dispatchers.IO) {
                        s.resize(emulator.cols, emulator.rows, (emulator.cols * charW).toInt(), (emulator.rows * lineH).toInt())
                    }
                } catch (_: Exception) { }
            }
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    TerminalView(
                        emulator = emulator,
                        version = tick,
                        fontSp = fontSp,
                        cell = charW to lineH,
                        fontFamily = fontFamily,
                        cursor = termCursor,
                        cursorBlink = termBlink,
                        onTap = {
                            if (!boxMode && handle.shell != null) {
                                focusRequester.requestFocus()
                                keyboard?.show()
                            }
                        },
                        onCopySelection = { text ->
                            scope.launch {
                                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("terminal", text)))
                            }
                            copiedMsg = "Selection copied"
                        },
                        onPasteSelection = { text ->
                            // Bytes go straight out; the pipe is display-only
                            // (see SshInputPipe), so there is nothing to keep
                            // in sync here.
                            // The Paste tap steals focus onto the button, so
                            // hand it straight back — otherwise typing and
                            // backspace need an extra terminal tap first.
                            if (handle.shell != null) {
                                if (!boxMode) {
                                    focusRequester.requestFocus()
                                    keyboard?.show()
                                }
                                sendRaw(text)
                            }
                        },
                        sidePadPx = sidePadPx,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                // tabsLocation=bottom: strip between the grid and the extra
                // keys — a layout sibling, so the grid shrinks instead of
                // sliding behind it.
                if (tabLoc == TabLocation.BOTTOM) {
                    SessionTabStrip(
                        sessions = tabs,
                        selectedId = sessionId,
                        titleOf = ::tabTitleOf,
                        onSelect = { if (it != sessionId) onOpenSession(it) },
                        onCloseRequest = ::requestTabClose,
                        onNew = ::handleNewTab,
                        scroll = stripScroll,
                    )
                }
                // Docked extra-keys bar (user-editable layout, same composable
                // as the editor preview): layout sibling below the grid, so
                // the grid can never slide behind it.
                if (!boxMode && showKeys) {
                    ExtraKeysBar(
                        layout = keyLayout,
                        enabled = hasShell,
                        ctrlActive = ctrlSticky,
                        altActive = altSticky,
                        onSendSteps = { sendKeySteps(it) },
                        onToggleCtrl = { ctrlSticky = !ctrlSticky },
                        onToggleAlt = { altSticky = !altSticky },
                    )
                }
                if (boxMode) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RectangleShape,
                        color = Color.Black,
                    ) {
                        Column {
                            HorizontalDivider(thickness = 1.dp, color = Color.White.copy(alpha = 0.1f))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                            ) {
                        OutlinedTextField(
                            value = boxInput,
                            onValueChange = { boxInput = it },
                            label = { Text("$ ") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            enabled = hasShell,
                        )
                        IconButton(
                            enabled = hasShell && boxInput.isNotBlank(),
                            onClick = {
                                val line = boxInput
                                boxInput = ""
                                scope.launch {
                                    try {
                                        withContext(Dispatchers.IO) { handle.shell?.send(line) }
                                    } catch (e: Exception) {
                                        sessionViewModel.markSendFailed(sessionId, "Send failed: ${e.message}")
                                    }
                                }
                            },
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                            }
                        }
                    }
                }
            }
        }
        }
        if (!boxMode) {
            // Hidden pipe: a 1dp state-based field under the platform
            // interceptor (SshInputPipe) — Termux's connection, not the
            // state's: the IME-facing buffer stays empty, commits and
            // deletes reach the shell as events. Enter (Done action or
            // committed newline) sends CR like a terminal. Hardware keys
            // never become connection calls (handled in onKeyEvent below):
            // the two paths are mutually exclusive per press.
            val pipeInterceptor = remember {
                SshInputInterceptor(
                    view,
                    onCommitText = { text, submitted ->
                        if (text.isNotEmpty()) sendCooked(text)
                        if (submitted) resetImeLine()
                    },
                    onDelete = { n -> repeat(n) { sendRaw(DEL) } },
                    onForwardDelete = { n -> repeat(n) { sendRaw(FWD) } },
                    onSpecial = { seq -> sendSpecial(seq) },
                    onEditorAction = {
                        sendRaw(CR)
                        resetImeLine()
                    },
                )
            }
            InterceptPlatformTextInput(pipeInterceptor) {
                BasicTextField(
                    state = pipeState,
                    keyboardOptions = KeyboardOptions(
                        // Plain text here: the interceptor forces TYPE_NULL
                        // (Termux default — no strip, no composing tricks, no
                        // password-manager overlay, no number row) and keeps
                        // the Done action. Trade-off: gesture/swipe typing is
                        // off in this invisible pipe; tap-typing unaffected.
                        keyboardType = KeyboardType.Text,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier.size(1.dp).focusRequester(focusRequester)
                        .onKeyEvent { ev ->
                            // Hardware keys never become connection calls:
                            // field them here (Termux: onKeyDown). Printable
                            // keys go cooked like typed text; the two paths
                            // (this + the pipe) are mutually exclusive.
                            if (ev.type != KeyEventType.KeyDown) false
                            else when {
                                ev.key == Key.Backspace -> {
                                    sendRaw(DEL)
                                    true
                                }
                                ev.key == Key.Enter || ev.key == Key.NumPadEnter -> {
                                    sendRaw(CR)
                                    resetImeLine()
                                    true
                                }
                                else -> ev.nativeKeyEvent.getUnicodeChar().takeIf { it != 0 }?.let { uni ->
                                    sendCooked(uni.toChar().toString())
                                    true
                                } ?: false
                            }
                    },
                )
            }
        }
    }

    if (showSftp) {
        SftpSheet(
            sessionViewModel = sessionViewModel,
            sessionId = sessionId,
            onDismiss = { showSftp = false },
        )
    }

    if (showCloseConfirm) {        AlertDialog(
            onDismissRequest = { showCloseConfirm = false },
            title = { Text("Disconnect?") },
            text = { Text("“${profile.name}” is still connected.") },
            confirmButton = {
                TextButton(
                    onClick = { showCloseConfirm = false; doDisconnectAndBack() },
                ) { Text("Disconnect", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showCloseConfirm = false }) { Text("Cancel") }
            },
        )
    }

    // Desktop hostKeyPromptModal parity: unknown vs changed (MITM warning +
    // previous fingerprint), three actions: remember / once / disconnect.
    hostKeyPrompt?.let { prompt ->
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Host key verification") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (prompt.mismatched) {
                        Text(
                            "Warning: the host key of ${prompt.host}:${prompt.port} has " +
                                "CHANGED since your last visit. This could be a " +
                                "man-in-the-middle attack — or the server was reinstalled.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "Last known host key fingerprint",
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Text(
                            (prompt.previousDigest ?: "").let { "SHA256:$it" },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        Text(
                            "First connection to ${prompt.host}:${prompt.port}. " +
                                "Check the fingerprint with the server administrator " +
                                "before accepting.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "Current host key fingerprint",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            prompt.keyType,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (prompt.mismatched) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary,
                        )
                    }
                    Text(
                        "SHA256:${prompt.digest}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                // Desktop stacks all three actions full-width (no side-by-side
                // confirm/dismiss row with its wide gap).
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { acceptHostKey(true) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = if (prompt.mismatched) ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                        ) else ButtonDefaults.buttonColors(),
                    ) { Text("Accept and remember key") }
                    OutlinedButton(
                        onClick = { acceptHostKey(false) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Accept just this once") }
                    TextButton(
                        onClick = {
                            sessionViewModel.rejectHostKey(sessionId)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Disconnect", color = MaterialTheme.colorScheme.error) }
                }
            },
            dismissButton = null,
        )
        } // Column — full-bleed stage
    } // TabDrawerFrame

    // Tab × with warnOnClose: confirm, then the caller closes + navigates.
    if (closeTarget != null) {
        val targetTitle = tabs.find { it.sessionId == closeTarget }?.let(::tabTitleOf)
        AlertDialog(
            onDismissRequest = { closeTarget = null },
            title = { Text("Close tab?") },
            text = { Text("Close \"${targetTitle ?: "this tab"}\"? The connection drops.") },
            confirmButton = {
                TextButton(onClick = {
                    val t = closeTarget
                    closeTarget = null
                    if (t != null) onCloseTab(t)
                }) { Text("Close") }
            },
            dismissButton = {
                TextButton(onClick = { closeTarget = null }) { Text("Cancel") }
            },
        )
    }

    if (showNewTabSheet) {
        NewTabSheet(
            state = state,
            onPick = { pid ->
                showNewTabSheet = false
                onOpenProfile(pid)
            },
            onDismiss = { showNewTabSheet = false },
        )
    }

    if (showUnlock) {
        VaultUnlockDialog(
            state = state,
            onUnlocked = {
                showUnlock = false
                if (pendingPasswordSave) {
                    pendingPasswordSave = false
                    sessionViewModel.savePendingPassword(sessionId, state)
                } else if (pendingConnect) {
                    pendingConnect = false
                    doConnect()
                }
            },
            onNoConfig = {
                showUnlock = false
                pendingConnect = false
                if (pendingPasswordSave) {
                    // Save unlock dismissed: the session is already
                    // connected — the typed password just stays session-only.
                    pendingPasswordSave = false
                    sessionViewModel.savePendingPassword(sessionId, state)
                } else {
                    sessionViewModel.close(sessionId)
                    onBack()
                }
            },
            onDismiss = {
                showUnlock = false
                pendingConnect = false
                if (pendingPasswordSave) {
                    pendingPasswordSave = false
                    sessionViewModel.savePendingPassword(sessionId, state)
                } else {
                    sessionViewModel.close(sessionId)
                    onBack()
                }
            },
            dismissible = true,
        )
    }

    // Desktop prompt-password modal parity (`Password for user@host`).
    authPrompt?.let { prompt ->
        PasswordPromptDialog(
            prompt = prompt,
            vaultPresent = state.loaded?.domain?.vault != null,
            locked = state.loaded?.needsPassphrase == true,
            onConnect = { pw, remember -> doConnectWithPassword(pw, remember) },
            onCancel = { sessionViewModel.cancelAuthPrompt(sessionId, state) },
        )
    }
}

/**
 * Desktop prompt-password modal parity: `Password for user@host` with the
 * stored password pre-filled and a remember checkbox. Connect retries once
 * with the typed password; success + remember stores it (vault, or the
 * profile literal without one), a failed retry forgets the wrong stored
 * password (desktop total-failure parity). Cancel lands on the error card.
 */
@Composable
private fun PasswordPromptDialog(
    prompt: AuthPrompt,
    vaultPresent: Boolean,
    locked: Boolean,
    onConnect: (password: String, remember: Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    var password by remember(prompt) { mutableStateOf(prompt.prefill ?: "") }
    var remember by remember(prompt) { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Password for ${prompt.user}@${prompt.host}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(prompt.error, color = MaterialTheme.colorScheme.error)
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = remember, onCheckedChange = { remember = it })
                    Text("Remember password", modifier = Modifier.weight(1f))
                }
                Text(
                    when {
                        !vaultPresent -> "Stored in the profile on success."
                        locked -> "Vault is locked — you'll unlock after connecting to save."
                        else -> "Saved to the vault on success."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(
                enabled = password.isNotEmpty(),
                onClick = { onConnect(password, remember) },
            ) { Text("Connect") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

// ASCII-safe escape constants (never raw control bytes in source).
private val DEL = 127.toChar().toString()
private val CR = 13.toChar().toString()

/** Forward (rightward) delete: the DEL preset's twin for after-cursor cuts. */
private val FWD = 27.toChar().toString() + "[3~"
