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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import id.web.izs.sshclient.core.config.TabLocation
import id.web.izs.sshclient.core.config.effectiveTabLocation
import id.web.izs.sshclient.core.config.ignoreEncryptedValue
import id.web.izs.sshclient.core.config.parseSchemeSource
import id.web.izs.sshclient.core.config.parseSchemeJson
import id.web.izs.sshclient.core.config.parseTabSource
import id.web.izs.sshclient.core.config.resolveActiveScheme
import id.web.izs.sshclient.core.config.resolveTabLocation
import id.web.izs.sshclient.core.config.schemeColorArgb
import id.web.izs.sshclient.core.ssh.SshConnector
import id.web.izs.sshclient.core.term.TerminalInput
import id.web.izs.sshclient.core.term.KeyStep
import id.web.izs.sshclient.core.term.loadKeyLayout
import id.web.izs.sshclient.core.term.stepBytes
import id.web.izs.sshclient.ui.AppState
import id.web.izs.sshclient.ui.SshSessionViewModel
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
    val emuVersion by handle.version.collectAsState()
    // Shell presence as OBSERVABLE state: branching on the plain
    // `handle.shell` field subscribes to nothing, so the branch group can
    // keep evaluating a stale null forever (green dot + Disconnected row +
    // dead Reconnect on a perfectly live session). hasShell emits on every
    // assignment, forcing a fresh read.
    val hasShell by handle.hasShell.collectAsState()
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
    LaunchedEffect(resolvedScheme) { emulator.setPalette(resolvedScheme) }
    // Stage follows the scheme background full-bleed (was pure black):
    // with few rows the grid no longer seams against a black page above
    // and the app surface below. The top bar keeps the themed surface.
    val stageBg = remember(resolvedScheme) {
        schemeColorArgb(resolvedScheme.background)?.let { Color(it) } ?: Color.Black
    }
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
    // Hidden field MIRROR: diff-based streaming needs the field to match the
    // shell's input buffer AND carry an explicit end-of-text cursor. A plain
    // String value leaves the IME cursor wherever it was (stale 0 after a
    // programmatic set), so typing lands mid-text and backspace misses —
    // TextFieldValue pins the cursor where the edits actually go.
    var kbText by remember { mutableStateOf(TextFieldValue("")) }
    var fontSp by remember { mutableStateOf(state.disk.terminalFontSp) }
    var ctrlSticky by remember { mutableStateOf(false) }
    var altSticky by remember { mutableStateOf(false) }
    var copiedMsg by remember { mutableStateOf<String?>(null) }
    var showUnlock by remember { mutableStateOf(false) }
    var showCloseConfirm by remember { mutableStateOf(false) }
    var pendingConnect by remember { mutableStateOf(false) }
    // WebView-based terminals clear their hidden textarea on Enter,
    // so the web view resets composing and predictions start fresh each line.
    // Compose must ask for the same explicitly — restartInput() resets the
    // IME's prediction/composing state while keeping the keyboard open.
    val view = LocalView.current
    val imm = remember(context) { context.getSystemService(InputMethodManager::class.java) }
    fun resetImeLine() {
        kbText = TextFieldValue("")
        try { imm.restartInput(view) } catch (_: Exception) { }
    }
    /** Mirror external input (paste) into the field with the cursor pinned at the end. */
    fun mirrorExternalInput(text: String) {
        val merged = kbText.text + text
        kbText = TextFieldValue(merged, TextRange(merged.length))
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
     * bytes. Same mirror invariant as paste: printable bytes join the
     * hidden field (cursor pinned at the end) so backspace diffs correctly;
     * a submitted line (CR/LF) clears it instead. Pure escape sequences
     * mirror nothing. Multiple steps go out staged with a settle delay so
     * each part registers in order (vim `:q!`: ESC, then text, then Enter).
     * The bar tap steals focus onto the button, so hand it straight back.
     */
    fun sendKeySteps(steps: List<KeyStep>) {
        val s = handle.shell ?: return
        val byteSteps = steps.map(::stepBytes).filter { it.isNotEmpty() }
        if (byteSteps.isEmpty()) return
        var submitted = false
        for (b in byteSteps) {
            if (b.any { it == '\r' || it == '\n' }) submitted = true
            else {
                val printable = b.filter { it.code >= 0x20 && it.code != 0x7F }
                if (printable.isNotEmpty()) mirrorExternalInput(printable)
            }
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
    Column(Modifier.fillMaxSize().background(stageBg)) {
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
        val dockDensity = LocalDensity.current
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
        // SwiftKey claims ~35dp more inset than its visible keys (hidden
        // toolbar slot). Shave 44.dp docks the bar onto the visible keys
        // (SwiftKey, toolbar OFF) — used only when the visible rect does
        // NOT prove a smaller true height. One constant to tune.
        val imeShavePx = with(dockDensity) { 44.dp.toPx() }
        var dockPx by remember { mutableFloatStateOf(0f) }
        LaunchedEffect(imeBottomPx, visKbPx) {
            // Visible rect notably smaller than claimed inset = true keys.
            val useVis = visKbPx > 0f && visKbPx < imeBottomPx - with(dockDensity) { 10.dp.toPx() }
            val target = (if (useVis) visKbPx else imeBottomPx - imeShavePx).coerceAtLeast(0f)
            if (target != dockPx) {
                delay(10)
                dockPx = target
                Log.d("ImeDock", "ime=$imeBottomPx vis=$visKbPx useVis=$useVis dock=$dockPx")
            }
            // Safety net: re-assert the dock height periodically.
            while (true) {
                delay(500)
                val t = (if (useVis) visKbPx else imeBottomPx - imeShavePx).coerceAtLeast(0f)
                if (t != dockPx) {
                    dockPx = t
                    Log.d("ImeDock", "re-assert ime=$imeBottomPx vis=$visKbPx dock=$t")
                }
            }
        }
        BoxWithConstraints(
            modifier = Modifier.weight(1f).fillMaxWidth()
                .background(stageBg)
                .padding(bottom = with(dockDensity) { dockPx.toDp() })
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
            val (charW, lineH) = rememberTerminalCell(fontSp)
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
                            // The hidden field mirrors the shell's input
                            // buffer for diff-based typing: a paste that
                            // bypasses it would leave backspace dead (field
                            // empty while the line has text). Mirror first so
                            // delete/continue-typing diff correctly; skip in
                            // box mode (its own field owns the buffer there).
                            // The Paste tap steals focus onto the button, so
                            // hand it straight back — otherwise typing and
                            // backspace need an extra terminal tap first.
                            if (handle.shell != null) {
                                if (!boxMode) {
                                    mirrorExternalInput(text)
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
            // Hidden field: streams soft-keyboard keystrokes raw (diff-based,
            // so backspace arrives as DEL). Enter sends CR like a terminal.
            BasicTextField(
                value = kbText,
                onValueChange = { next ->
                    val prev = kbText.text
                    val cur = next.text
                    val common = prev.commonPrefixWith(cur).length
                    val removed = prev.length - common
                    repeat(removed) { sendRaw(DEL) }
                    val added = cur.substring(common).replace(LF, CR)
                    if (added.isNotEmpty()) sendCooked(added)
                    // New line (or buffer cap): fresh IME state per line.
                    if (added.contains(CR) || cur.length > 48) resetImeLine()
                    else kbText = next
                },
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { sendRaw(CR); resetImeLine() }),
                modifier = Modifier.size(1.dp).focusRequester(focusRequester),
            )
        }
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
                if (pendingConnect) {
                    pendingConnect = false
                    doConnect()
                }
            },
            onNoConfig = {
                showUnlock = false
                pendingConnect = false
                sessionViewModel.close(sessionId)
                onBack()
            },
            onDismiss = {
                showUnlock = false
                pendingConnect = false
                sessionViewModel.close(sessionId)
                onBack()
            },
            dismissible = true,
        )
    }
}

// ASCII-safe escape constants (never raw control bytes in source).
private val DEL = 127.toChar().toString()
private val LF = 10.toChar().toString()
private val CR = 13.toChar().toString()
