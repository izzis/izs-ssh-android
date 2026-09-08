package id.web.izs.sshclient.ui.screens

import android.app.Activity
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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.web.izs.sshclient.core.ssh.SshConnector
import id.web.izs.sshclient.core.term.TerminalEmulator
import id.web.izs.sshclient.core.term.TerminalInput
import id.web.izs.sshclient.ui.AppState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * A real interactive SSH shell: xterm-256color PTY + VT100 emulator grid.
 * Direct typing is primary (soft keyboard streams raw keystrokes, so vim
 * and htop work); the old command box stays as an option via the toggle.
 *
 * Lazy unlock: a locked vault prompts for the passphrase before connecting,
 * then connects automatically. Disconnect returns straight to the list.
 */
@Composable
fun TerminalScreen(
    state: AppState,
    profileId: String,
    onBack: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboard = LocalClipboardManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val profile = remember(state.loaded, profileId) {
        state.displayProfiles().find { it.id == profileId }
    }
    val locked = state.loaded?.needsPassphrase == true

    val emulator = remember(profileId) { TerminalEmulator(80, 24) }
    // Scrollback pref (Settings > Terminal): applied live on every
    // composition — deliberately impure, the setter only trims and never
    // triggers recomposition, so there is no loop risk.
    emulator.maxHistory = state.disk.terminalScrollback
    var emuVersion by remember(profileId) { mutableStateOf(0L) }
    // First fit per session is instant; later ones are settle-debounced
    // (see the refit below) so the keyboard animation never reflows.
    var sizedOnce by remember(profileId) { mutableStateOf(false) }
    var session by remember { mutableStateOf<SshConnector.ShellSession?>(null) }
    var status by remember { mutableStateOf("connecting…") }
    var failed by remember { mutableStateOf<String?>(null) }
    var boxMode by remember { mutableStateOf(false) }
    var showKeys by remember { mutableStateOf(true) }
    var showMenu by remember { mutableStateOf(false) }
    var boxInput by remember { mutableStateOf("") }
    var kbText by remember { mutableStateOf("") }
    var fontSp by remember { mutableStateOf(state.disk.terminalFontSp) }
    var ctrlSticky by remember { mutableStateOf(false) }
    var altSticky by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    var showUnlock by remember { mutableStateOf(false) }
    var showCloseConfirm by remember { mutableStateOf(false) }
    var pendingConnect by remember { mutableStateOf(false) }
    // Guards the double-fire race (effect refires on unlock while a connect
    // is already in flight) that used to open two sessions and trip the
    // crypto provider swap.
    var connecting by remember { mutableStateOf(false) }
    // tabby-android parity: the WebView clears its hidden textarea on Enter,
    // so Chromium resets composing and predictions start fresh each line.
    // Compose must ask for the same explicitly — restartInput() resets the
    // IME's prediction/composing state while keeping the keyboard open.
    val view = LocalView.current
    val imm = remember(context) { context.getSystemService(InputMethodManager::class.java) }
    fun resetImeLine() {
        kbText = ""
        try { imm.restartInput(view) } catch (_: Exception) { }
    }

    fun readKnown(): List<String> = try {
        val arr = JSONArray(state.disk.loadKnownHostsJson() ?: "[]")
        List(arr.length()) { arr.getString(it) }
    } catch (_: Exception) { emptyList() }

    fun setFont(v: Float) {
        val c = v.coerceIn(8f, 24f)
        fontSp = c
        state.disk.terminalFontSp = c
    }

    fun sendRaw(text: String) {        val s = session ?: return
        scope.launch {
            try {
                withContext(Dispatchers.IO) { s.sendRaw(text) }
            } catch (e: Exception) {
                failed = "Send failed: ${e.message}"
                s.close()
                session = null
                status = "disconnected"
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

    fun doConnect() {
        val p = profile ?: return
        if (connecting) return
        connecting = true
        scope.launch {
            failed = null
            status = "connecting…"
            try {
                val known = withContext(Dispatchers.IO) { readKnown() }
                val sess = withContext(Dispatchers.IO) {
                    SshConnector().openShell(
                        profile = p,
                        password = state.passwordFor(p),
                        keys = state.keysFor(p).map {
                            SshConnector.KeyInput(pem = it.first, passphrase = it.second)
                        },
                        keyPassphrases = state.keyPassphrases(),
                        verifyHostKeys = state.loaded?.domain?.ssh?.verifyHostKeys ?: true,
                        knownHostLines = known,
                        timeoutMs = p.options.readyTimeout ?: 20000,
                        cacheDir = context.cacheDir,
                        onNewHostKey = { line ->
                            val cur = try {
                                JSONArray(state.disk.loadKnownHostsJson() ?: "[]")
                            } catch (_: Exception) { JSONArray() }
                            cur.put(line)
                            state.disk.saveKnownHostsJson(cur.toString())
                        },
                    )
                }
                session = sess
                status = "connected"
                scope.launch {
                    sess.output.collect { chunk ->
                        emulator.feed(chunk)
                        emuVersion = emulator.version
                    }
                }
            } catch (e: Exception) {
                failed = e.message ?: "Connect failed"
                status = "disconnected"
            } finally {
                connecting = false
            }
        }
    }

    fun doDisconnect() {
        session?.close()
        session = null
        status = "disconnected"
    }

    fun goBack() {
        doDisconnect()
        onBack()
    }

    fun requestClose() {
        // Desktop parity (sshTab): per-profile warnOnClose wins, otherwise
        // the global Settings > SSH toggle (default off). Guards an ACTIVE
        // session only — failed/connecting/closed states close at once.
        val warn = profile?.options?.warnOnClose
            ?: state.loaded?.domain?.ssh?.warnOnClose
            ?: false
        if (warn && status == "connected") {
            showCloseConfirm = true
        } else {
            goBack()
        }
    }

    LaunchedEffect(profileId, state.loaded) {
        if (profile != null && session == null && failed == null && !connecting) {
            if (locked) {
                pendingConnect = true
                showUnlock = true
            } else {
                doConnect()
            }
        }
    }
    DisposableEffect(profileId) {
        onDispose { session?.close() }
    }
    BackHandler { requestClose() }

    if (profile == null) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Profile not found", color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back") }
        }
        return
    }

    // Stage stays full-bleed black, but the top bar now matches every other
    // page (themed surface, back arrow + title) — the slate strip is gone.
    // goBack() still disconnects first; a session picker comes with the
    // multi-session update.
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(end = 12.dp, top = 4.dp, bottom = 4.dp),
        ) {
            IconButton(onClick = { requestClose() }) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
            }
            // Status dot sits on the NAME row so user@host below gets the
            // full width (green = connected, amber = connecting, red =
            // disconnected; tap to disconnect, same as the back arrow).
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
                            onClick = { requestClose() },
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
            IconButton(onClick = {
                clipboard.setText(AnnotatedString(emulator.plainText()))
                copied = true
            }) {
                Icon(Icons.Filled.ContentCopy, contentDescription = "Copy screen")
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
        if (copied) {
            Text(
                "Screen copied",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
            )
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
                        OutlinedButton(onClick = { goBack() }) { Text("Close") }
                    }
                }
            }
        } else if (session == null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(12.dp),
            ) {
                CircularProgressIndicator()
                OutlinedButton(onClick = { goBack() }) { Text("Close") }
            }
        }
        // Keyboard dock imitating tabby-android (terminal.component.ts):
        // (1) visualViewport equivalent — decorView.getWindowVisibleDisplayFrame
        //     is the rect that is ACTUALLY visible, immune to SwiftKey's
        //     over-claimed inset when its window is only as tall as the keys.
        //     Trusted (zero shave) when notably smaller than the IME inset.
        // (2) Skip redundant sets (like kb-spacer's height check).
        // (3) 500ms re-assert safety net while open (extraBarInterval).
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
            // Safety net ala tabby-android extraBarInterval: re-assert.
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
                .background(Color.Black)
                .padding(bottom = with(dockDensity) { dockPx.toDp() }),
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
                emuVersion = emulator.version
                session?.resize(c, r, (c * charW).toInt(), (r * lineH).toInt())
                if (BuildConfig.DEBUG) {
                    Log.d("TvPerf", "resize ${c}x$r ms=${(System.nanoTime() - t0) / 1_000_000.0}")
                }
            }
            // Refit at SETTLE, never per-frame. Sizes stream every animation
            // frame while the keyboard slides; reflowing per frame (buffer
            // rebuild + full Canvas redraw + window-change packet) pegged
            // the CPU and froze scrolling until settle. Layout (dock height,
            // viewport, scroll) still tracks live — only the expensive
            // reflow waits for 150ms quiet, like tabby-android's fit retries.
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
            LaunchedEffect(session) {
                // Fresh shells start at 80x24: correct the server at once.
                val s = session ?: return@LaunchedEffect
                s.resize(emulator.cols, emulator.rows, (emulator.cols * charW).toInt(), (emulator.rows * lineH).toInt())
            }
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    TerminalView(
                        emulator = emulator,
                        version = tick,
                        fontSp = fontSp,
                        cell = charW to lineH,
                        onTap = {
                            if (!boxMode && session != null) {
                                focusRequester.requestFocus()
                                keyboard?.show()
                            }
                        },
                        sidePadPx = sidePadPx,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                // Docked key bars (Termux/tabby-android .extra-keyboard):
                // layout siblings below the grid (NOT overlays), so the grid
                // can never slide behind them — no reserve math needed. Pure
                // black melts the bar into the keyboard's dead zone above it.
                if (!boxMode && showKeys) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RectangleShape,
                        color = Color.Black,
                    ) {
                        Column {
                            HorizontalDivider(thickness = 1.dp, color = Color.White.copy(alpha = 0.1f))
                            Column(
                                Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            ExtraKeyBtn("ESC", false, session != null, Modifier.weight(1f)) { sendSpecial(ESC) }
                            ExtraKeyBtn("/", false, session != null, Modifier.weight(1f)) { sendSpecial("/") }
                            ExtraKeyBtn("-", false, session != null, Modifier.weight(1f)) { sendSpecial("-") }
                            ExtraKeyBtn("HOME", false, session != null, Modifier.weight(1f)) { sendSpecial(ESC + "[H") }
                            ExtraKeyBtn("UP", false, session != null, Modifier.weight(1f)) { sendSpecial(ESC + "[A") }
                            ExtraKeyBtn("END", false, session != null, Modifier.weight(1f)) { sendSpecial(ESC + "[F") }
                            ExtraKeyBtn("PGUP", false, session != null, Modifier.weight(1f)) { sendSpecial(ESC + "[5~") }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            ExtraKeyBtn("TAB", false, session != null, Modifier.weight(1f)) { sendSpecial(TAB) }
                            ExtraKeyBtn("CTRL", ctrlSticky, session != null, Modifier.weight(1f)) {
                                ctrlSticky = !ctrlSticky
                            }
                            ExtraKeyBtn("ALT", altSticky, session != null, Modifier.weight(1f)) {
                                altSticky = !altSticky
                            }
                            ExtraKeyBtn("LEFT", false, session != null, Modifier.weight(1f)) { sendSpecial(ESC + "[D") }
                            ExtraKeyBtn("DOWN", false, session != null, Modifier.weight(1f)) { sendSpecial(ESC + "[B") }
                            ExtraKeyBtn("RIGHT", false, session != null, Modifier.weight(1f)) { sendSpecial(ESC + "[C") }
                            ExtraKeyBtn("PGDN", false, session != null, Modifier.weight(1f)) { sendSpecial(ESC + "[6~") }
                        }
                    }
                }
            }
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
                            enabled = session != null,
                        )
                        IconButton(
                            enabled = session != null && boxInput.isNotBlank(),
                            onClick = {
                                val line = boxInput
                                boxInput = ""
                                scope.launch {
                                    try {
                                        withContext(Dispatchers.IO) { session?.send(line) }
                                    } catch (e: Exception) {
                                        failed = "Send failed: ${e.message}"
                                        doDisconnect()
                                    }
                                }
                            },
                            ) {
                                Icon(Icons.Filled.Send, contentDescription = "Send")
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
                    val common = kbText.commonPrefixWith(next).length
                    val removed = kbText.length - common
                    repeat(removed) { sendRaw(DEL) }
                    val added = next.substring(common).replace(LF, CR)
                    if (added.isNotEmpty()) sendCooked(added)
                    // New line (or buffer cap): fresh IME state per line.
                    if (added.contains(CR) || next.length > 48) resetImeLine()
                    else kbText = next
                },
                keyboardOptions = KeyboardOptions(autoCorrect = false, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { sendRaw(CR); resetImeLine() }),
                modifier = Modifier.size(1.dp).focusRequester(focusRequester),
            )
        }
    }

    if (showCloseConfirm) {
        AlertDialog(
            onDismissRequest = { showCloseConfirm = false },
            title = { Text("Disconnect?") },
            text = { Text("“${profile?.name}” is still connected.") },
            confirmButton = {
                TextButton(
                    onClick = { showCloseConfirm = false; goBack() },
                ) { Text("Disconnect", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showCloseConfirm = false }) { Text("Cancel") }
            },
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
                onBack()
            },
            onDismiss = {
                showUnlock = false
                pendingConnect = false
                onBack()
            },
            dismissible = true,
        )
    }
}

private val DEL = 127.toChar().toString()
private val LF = 10.toChar().toString()
private val CR = 13.toChar().toString()

/** Compact Termux-like extra key; highlighted while its sticky is active. */
@Composable
private fun ExtraKeyBtn(
    label: String,
    active: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onTap: () -> Unit,
) {
    OutlinedButton(
        onClick = onTap,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 2.dp,
            vertical = 8.dp,
        ),
        colors = if (active) {
            ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        } else {
            ButtonDefaults.outlinedButtonColors()
        },
    ) {
        Text(label, fontSize = 11.sp)
    }
}

// ASCII-safe escape constants (never raw control bytes in source).
private val ESC = 27.toChar().toString()
private val TAB = 9.toChar().toString()
