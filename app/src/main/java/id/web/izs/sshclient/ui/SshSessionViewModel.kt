package id.web.izs.sshclient.ui

import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.web.izs.sshclient.core.config.KnownHostEntry
import id.web.izs.sshclient.core.config.SshProfile
import id.web.izs.sshclient.core.ssh.SshConnector
import id.web.izs.sshclient.core.ssh.SshAuthFailed
import id.web.izs.sshclient.core.ssh.SftpTransferManager
import id.web.izs.sshclient.core.ssh.UnknownHostKeyException
import id.web.izs.sshclient.core.ssh.transportKeyOf
import id.web.izs.sshclient.core.term.TerminalEmulator
import id.web.izs.sshclient.data.local.ConfigDisk
import net.schmizz.sshj.SSHClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.util.UUID

/**
 * Multi-session registry. Survives Activity recreation (rotation): PTYs live
 * here, NOT in the composable — rotating the phone must never drop the live
 * shell. TerminalScreen only observes a handle.
 *
 * Desktop parity (sshTab + sshMultiplexer): EVERY profile tap opens a NEW tab
 * (new session UUID). `reuseSession` (YAML default true) shares the TRANSPORT
 * instead — tabs of one `host:port:user:proxy…` key ride a single TCP
 * connection ([transportKeyOf]), each with its own shell channel; `false`
 * connects separately per tab (re-auth each time).
 *
 * RAM-only by design (like the vault passphrase): process death clears all
 * sessions. Cap defaults to 5, hard max 10 ([ConfigDisk.MAX_SESSIONS_HARD_MAX]).
 */
    class SessionLimitReached(val max: Int) : IllegalStateException("Session limit is $max")

/** One connected session as mirrored to SessionService (label = `user@host`). */
data class ConnectedInfo(val sessionId: String, val label: String)

/**
 * Notification label for a session: `user@host`, falling back to the
 * profile name when the host is blank (defensive import). Pure for tests.
 */
fun sessionLabel(user: String, host: String, profileName: String): String {
    val u = user.ifBlank { "root" }
    return if (host.isBlank()) profileName.ifBlank { u } else "$u@$host"
}

/**
 * Single-auto-retry gate after a transport death. Only sessions that had a
 * live shell once qualify (a first-connect failure is the user's explicit
 * tap — never retry behind their back), exactly once per death, and never
 * when a UI answer is still pending (vault passphrase, auth password,
 * host-key prompt). Pure for unit tests.
 */
fun shouldAutoRetry(
    everConnected: Boolean,
    autoRetried: Boolean,
    needsPassphrase: Boolean,
    hasAuthPrompt: Boolean,
    hasHostKeyPrompt: Boolean,
): Boolean = everConnected && !autoRetried &&
    !needsPassphrase && !hasAuthPrompt && !hasHostKeyPrompt

/**
 * Desktop `prompt-password` state: auth failed (wrong or missing password)
 * and the user is offered `Password for user@host`. Prefill is the stored
 * password when one exists (desktop pre-fills too).
 */
data class AuthPrompt(
    val user: String,
    val host: String,
    val error: String,
    val prefill: String?,
)

class SshSessionHandle(
    val sessionId: String,
    val profileId: String,
    /** Snapshot at creation for list/header display when the profile is deleted. */
    var profileSnapshot: SshProfile,
    val emulator: TerminalEmulator = TerminalEmulator(80, 24),
    val createdAt: Long = System.currentTimeMillis(),
) {
    /**
     * Shell presence as an observable flow. `shell` itself is a plain
     * @Volatile field, and a composable that branches on a plain-field read
     * subscribes to NOTHING — its branch group can keep a stale null forever
     * (green dot + Disconnected row + dead Reconnect on a live session).
     * Every assignment funnels through this setter, so the flow always
     * matches the field; UI branches on [hasShell], never on the field.
     */
    @Volatile
    var shell: SshConnector.ShellSession? = null
        set(v) {
            field = v
            _hasShell.value = v != null
        }

    private val _hasShell = MutableStateFlow(false)
    val hasShell: StateFlow<Boolean> = _hasShell.asStateFlow()

    private val _status = MutableStateFlow("connecting…")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _stage = MutableStateFlow("Starting…")
    val stage: StateFlow<String> = _stage.asStateFlow()

    private val _failed = MutableStateFlow<String?>(null)
    val failed: StateFlow<String?> = _failed.asStateFlow()

    private val _hostKeyPrompt = MutableStateFlow<UnknownHostKeyException?>(null)
    val hostKeyPrompt: StateFlow<UnknownHostKeyException?> = _hostKeyPrompt.asStateFlow()

    private val _authPrompt = MutableStateFlow<AuthPrompt?>(null)
    val authPrompt: StateFlow<AuthPrompt?> = _authPrompt.asStateFlow()

    /**
     * A typed password that connected but is not stored yet: the vault was
     * locked at save time. TerminalScreen routes it through the unlock
     * dialog, then calls [savePendingPassword].
     */
    private val _passwordSavePending = MutableStateFlow<String?>(null)
    val passwordSavePending: StateFlow<String?> = _passwordSavePending.asStateFlow()

    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version.asStateFlow()

    // Desktop BaseTabComponent.hasActivity parity: true once output lands
    // while this tab is NOT the selected one; cleared on select.
    private val _activity = MutableStateFlow(false)
    val activity: StateFlow<Boolean> = _activity.asStateFlow()

    @Volatile var extraTrust: KnownHostEntry? = null
    @Volatile var connectJob: Job? = null
    @Volatile var collectJob: Job? = null
    @Volatile var connecting: Boolean = false
    /**
     * Background-survival bookkeeping (SessionService plan): [everConnected]
     * latches once the shell first connects; [autoRetried] re-arms (false)
     * on every new connect and gates the single automatic retry after a
     * transport death — manual Retry taps never consult it.
     */
    @Volatile var everConnected: Boolean = false
    @Volatile var autoRetried: Boolean = false

    fun setStatus(v: String) { _status.value = v }
    fun setStage(v: String) { _stage.value = v }
    fun setFailed(v: String?) { _failed.value = v }
    fun setPrompt(v: UnknownHostKeyException?) { _hostKeyPrompt.value = v }
    fun setAuthPrompt(v: AuthPrompt?) { _authPrompt.value = v }
    fun setPasswordSavePending(v: String?) { _passwordSavePending.value = v }
    fun bumpVersion() { _version.value = emulator.version }
    fun setActivity(v: Boolean) { _activity.value = v }

    val isConnected: Boolean get() = _status.value == "connected"
    val isConnecting: Boolean get() = _status.value == "connecting…"
}

class SshSessionViewModel : ViewModel() {
    private val _sessions = mutableStateMapOf<String, SshSessionHandle>()
    /** Observable registry (Compose SnapshotStateMap): reads subscribe to changes. */
    val sessions: Map<String, SshSessionHandle> get() = _sessions

    fun get(sessionId: String): SshSessionHandle? = _sessions[sessionId]

    fun ordered(): List<SshSessionHandle> = _sessions.values.sortedBy { it.createdAt }

    /**
     * Shared horizontal scroll position (px) of the tab strip. Every tab is
     * its own navigation destination, so a strip-local scroll state would
     * reset to the left edge on each switch — the strip seeds from here and
     * writes back while scrolling. Plain var (never observable): no
     * recomposition on scroll frames.
     */
    @Volatile var tabStripScrollPx: Int = 0

    // Which tab the user is looking at (set by TerminalScreen on compose).
    // Drives hasActivity: output for any OTHER session lights its dot.
    private val _selectedSessionId = MutableStateFlow<String?>(null)
    val selectedSessionId: StateFlow<String?> = _selectedSessionId.asStateFlow()

    /** Focus a tab: it becomes selected and its activity dot clears. */
    fun select(sessionId: String) {
        _selectedSessionId.value = sessionId
        _sessions[sessionId]?.setActivity(false)
    }

    /**
     * Output landed for [sessionId] (called from the reader-pump collect
     * loop, which runs for background tabs too). Lights the activity dot
     * unless this is the tab on screen. Extracted for unit tests.
     */
    fun noteOutput(sessionId: String) {
        if (_selectedSessionId.value == sessionId) return
        _sessions[sessionId]?.setActivity(true)
    }

    private fun sessionLabel(h: SshSessionHandle): String {
        val o = h.profileSnapshot.options
        return sessionLabel(o.user, o.host, h.profileSnapshot.name)
    }

    /** One pooled TCP connection shared by tabs ([transportKeyOf]). */
    private class PooledTransport(val client: SSHClient, var refs: Int = 1)

    /**
     * Background-survival mirror for SessionService. MainActivity installs
     * these (it owns the Context); the ViewModel itself never touches
     * Android framework classes so it stays unit-testable.
     *
     * - [serviceSync]: the CURRENT connected list (`user@host` per session,
     *   oldest first) after every transition — the service derives its
     *   foreground state, exact count, and expanded lines from it, so the
     *   notification can never drift from the registry.
     * - [lostListener]: fired with the label when a dead session is
     *   auto-retried while the app is backgrounded (the "tap to open"
     *   notice; MainActivity checks POST_NOTIFICATIONS first).
     *
     * The last environment handed to [connect] is remembered so the
     * single auto-retry can run for background tabs too (their
     * TerminalScreen is not composed while backgrounded).
     */
    var serviceSync: ((List<ConnectedInfo>) -> Unit)? = null
    var lostListener: ((String) -> Unit)? = null
    private var lastEnv: Pair<AppState, File>? = null

    /** Exact connected snapshot for [serviceSync]. Internal for unit tests. */
    internal fun connectedInfos(): List<ConnectedInfo> =
        _sessions.values.filter { it.isConnected }
            .sortedBy { it.createdAt }
            .map { ConnectedInfo(it.sessionId, sessionLabel(it)) }

    private fun syncService() {
        serviceSync?.invoke(connectedInfos())
    }

    /**
     * Transport pool, guarded by [poolGuard] (plain synchronized: pool ops are
     * quick map ops callable from Main, IO, the reader thread, or onCleared —
     * a Mutex would need a coroutine scope that may already be dead).
     */
    private val poolGuard = Any()
    private val transports = mutableMapOf<String, PooledTransport>()

    /**
     * Every profile tap opens a NEW tab (desktop parity: tabs are never
     * reused, only transports are). Connection sharing is decided at connect
     * time from the profile's `reuseSession` flag.
     *
     * @throws SessionLimitReached when [maxSessions] slots are full.
     */
    fun create(profile: SshProfile, maxSessions: Int = DEFAULT_MAX_SESSIONS): String {
        val cap = maxSessions.coerceIn(1, ConfigDisk.MAX_SESSIONS_HARD_MAX)
        if (_sessions.size >= cap) throw SessionLimitReached(cap)
        val id = UUID.randomUUID().toString()
        _sessions[id] = SshSessionHandle(
            sessionId = id,
            profileId = profile.id,
            profileSnapshot = profile,
        )
        return id
    }

    /** Explicit tab close: free the cap slot now, tear the socket down off-Main. */
    fun close(sessionId: String) {
        val h = _sessions.remove(sessionId) ?: return
        if (_selectedSessionId.value == sessionId) _selectedSessionId.value = null
        // Disconnect aborts this session's SFTP transfers first (their
        // channels die with the transport anyway — abort surfaces Cancelled
        // with partial cleanup instead of a confusing channel error). Back /
        // dismiss never reaches here, so background transfers survive those.
        synchronized(poolGuard) { sftpManagers.remove(sessionId) }?.cancelAll()
        h.connectJob?.cancel()
        h.collectJob?.cancel()
        h.connectJob = null
        h.collectJob = null
        // Socket teardown is network IO ( stalls on a dead VPN): never on
        // Main — a blocked Main freezes the terminal mid-tap with zero
        // feedback. Slot accounting above already ran synchronously.
        val s = h.shell
        h.shell = null
        if (s != null) viewModelScope.launch {
            withContext(Dispatchers.IO) { closeShellQuietly(s) }
        }
        syncService()
    }

    fun cancelConnect(sessionId: String) {
        val h = _sessions[sessionId] ?: return
        h.connectJob?.cancel()
        h.connectJob = null
    }

    /** Unexpected I/O failure (send failed): keep the handle (red dot) for retry. */
    fun markSendFailed(sessionId: String, msg: String) {
        val h = _sessions[sessionId] ?: return
        val s = h.shell
        h.shell = null
        if (s != null) viewModelScope.launch {
            withContext(Dispatchers.IO) { closeShellQuietly(s) }
        }
        h.setFailed(msg)
        h.setStatus("disconnected")
        syncService()
    }
    /** One-shot typed password from the auth-failover prompt (+remember flag). */
    private data class PasswordAttempt(val password: String, val remember: Boolean)
    private val oneShots = mutableMapOf<String, PasswordAttempt>()

    /**
     * Desktop prompt-password parity: retry this session once with a typed
     * password. Success + remember stores it (vault, or the profile literal
     * without one); a failed retry is total failure — the wrong stored
     * password is forgotten and the session lands on the error card (never
     * an automatic re-prompt loop).
     */
    fun connectWithPassword(
        sessionId: String,
        appState: AppState,
        cacheDir: File,
        password: String,
        remember: Boolean,
    ) {
        val h = _sessions[sessionId] ?: return
        h.setAuthPrompt(null)
        h.setFailed(null)
        synchronized(poolGuard) { oneShots[sessionId] = PasswordAttempt(password, remember) }
        connect(sessionId, appState, cacheDir)
    }

    /** Prompt cancelled: same total-failure path as a failed retry. */
    fun cancelAuthPrompt(sessionId: String, appState: AppState) {
        val h = _sessions[sessionId] ?: return
        val prompt = h.authPrompt.value ?: return
        h.setAuthPrompt(null)
        h.setStatus("disconnected")
        viewModelScope.launch {
            val profile = appState.displayProfiles().find { it.id == h.profileId }
                ?: h.profileSnapshot
            finalizeAuthFailure(profile, appState)
            h.setFailed(prompt.error)
        }
        syncService()
    }

    /**
     * Desktop total-failure parity (ssh.ts: passwordStorage.deletePassword +
     * throw 'Authentication rejected'): the stored password just proved wrong
     * and is forgotten. Best-effort — a locked vault cannot be rewritten, so
     * its secret stays (it was never readable for this attempt anyway), and
     * the profile YAML itself is never touched here.
     */
    private suspend fun finalizeAuthFailure(profile: SshProfile, appState: AppState) {
        try {
            val loaded = withContext(Dispatchers.IO) { appState.repo.deletePassword(profile) }
            appState.adopt(loaded)
        } catch (_: Exception) { }
    }

    /** Post-unlock retry of a deferred vault save (TerminalScreen unlock flow). */
    fun savePendingPassword(sessionId: String, appState: AppState) {
        val h = _sessions[sessionId] ?: return
        val pending = h.passwordSavePending.value ?: return
        h.setPasswordSavePending(null)
        viewModelScope.launch {
            try {
                val profile = appState.displayProfiles().find { it.id == h.profileId }
                    ?: h.profileSnapshot
                val loaded = withContext(Dispatchers.IO) {
                    appState.repo.savePassword(profile, pending)
                }
                appState.adopt(loaded)
            } catch (_: Exception) { }
        }
    }

    /**
     * Open (or re-open) the shell for [sessionId]. Safe to call from
     * LaunchedEffect on every composition: guards double-fire while a connect
     * is already in flight and no-ops when already connected.
     */
    fun connect(sessionId: String, appState: AppState, cacheDir: File) {
        val h = _sessions[sessionId] ?: return
        if (h.connecting || (h.shell != null && h.isConnected)) return
        // Remembered for the single background auto-retry (their screen is
        // not composed while backgrounded, so the ViewModel must redial).
        lastEnv = appState to cacheDir
        // Retry path clears the previous failure; first connect starts clean.
        h.connecting = true
        h.setStage("Starting…")
        h.connectJob = viewModelScope.launch {
            h.setFailed(null)
            h.setStatus("connecting…")
            val profile = appState.displayProfiles().find { it.id == h.profileId }
                ?: h.profileSnapshot
            h.profileSnapshot = profile
            val attempt = synchronized(poolGuard) { oneShots.remove(sessionId) }
            // Only a fresh transport authenticates: an adopted (already
            // authenticated) one never tries the typed password, so a typed
            // password must never be stored for it.
            var authenticatedFresh = false
            try {
                // Live scrollback pref per emulator.
                h.emulator.maxHistory = appState.disk.terminalScrollback
                // NOTE: no setPalette here — TerminalScreen owns the single
                // apply path (LaunchedEffect on the resolved scheme): it
                // runs on mount (fresh sessions) and on every scheme change
                // (live sessions), so a second call here would only double
                // the remap work.
                val legacy = withContext(Dispatchers.IO) { readLegacyKnown(appState) }
                val conn = SshConnector()
                val tkey = transportKeyOf(profile.options)
                val shareable = profile.options.reuseSession != false
                // Pool lookup (no IO under lock): adopt a live transport, or
                // evict a dead entry (disconnected off-lock below).
                var adopted: PooledTransport? = null
                var deadClient: SSHClient? = null
                if (shareable) {
                    synchronized(poolGuard) {
                        val p = transports[tkey]
                        if (p != null) {
                            if (p.client.isConnected && p.client.isAuthenticated) {
                                p.refs++
                                adopted = p
                            } else {
                                transports.remove(tkey, p)
                                deadClient = p.client
                            }
                        }
                    }
                }
                deadClient?.let { dc ->
                    withContext(Dispatchers.IO) { disconnectQuietly(dc) }
                }
                val sess: SshConnector.ShellSession
                var sharedEntry: PooledTransport? = adopted
                // Server-side Remote-forward rejections (fresh transports
                // only) — surfaced below as an in-terminal service line,
                // desktop emitServiceMessage parity.
                var freshWarnings: List<String> = emptyList()
                if (adopted != null) {
                    h.setStage("Reusing connection…")
                    sess = try {
                        withContext(Dispatchers.IO) {
                            conn.openShellOnTransport(adopted.client, profile) { s -> h.setStage(s) }
                        }
                    } catch (e: Exception) {
                        // Adopted ref must drop on failure, or the pool keeps
                        // a phantom ref forever.
                        releaseTransport(tkey, adopted)
                        throw e
                    }
                } else {
                    val t = withContext(Dispatchers.IO) {
                        conn.connectTransport(
                            profile = profile,
                            password = attempt?.password ?: appState.passwordFor(profile),
                            // A failover-prompt password is explicit user
                            // intent: always attempt it, whatever `auth` says.
                            passwordIsTyped = attempt != null,
                            keys = appState.keysFor(profile).map {
                                SshConnector.KeyInput(pem = it.first, passphrase = it.second)
                            },
                            keyPassphrases = appState.keyPassphrases(),
                            verifyHostKeys = appState.loaded?.domain?.ssh?.verifyHostKeys ?: true,
                            knownHosts = appState.loaded?.domain?.ssh?.knownHosts ?: emptyList(),
                            legacyKnownHostLines = legacy,
                            oneTimeTrust = h.extraTrust,
                            timeoutMs = profile.options.readyTimeout ?: 20000,
                            cacheDir = cacheDir,
                            onStage = { s -> h.setStage(s) },
                        )
                    }
                    // Pool insert: a lost connect race stays solo (leak-free,
                    // just unshared — its close() disconnects its own client).
                    if (shareable) {
                        synchronized(poolGuard) {
                            if (!transports.containsKey(tkey)) {
                                PooledTransport(t.client).also {
                                    transports[tkey] = it
                                    sharedEntry = it
                                }
                            }
                        }
                    }
                    sess = try {
                        withContext(Dispatchers.IO) {
                            conn.openShellOnTransport(t.client, profile) { s -> h.setStage(s) }
                        }
                    } catch (e: Exception) {
                        // Shell open failed on a fresh transport: never leak
                        // it (pooled refs drop to zero, solo clients close).
                        val se = sharedEntry
                        if (se != null) {
                            releaseTransport(tkey, se)
                            sharedEntry = null
                        } else {
                            withContext(Dispatchers.IO) { t.close() }
                        }
                        throw e
                    }
                    sess.trustUpgrade = t.trustUpgrade
                    sess.trustUpgradeLine = t.trustUpgradeLine
                    freshWarnings = t.forwards.warnings
                    authenticatedFresh = true
                    sess.trustUpgrade?.let { entry ->
                        viewModelScope.launch {
                            try {
                                sess.trustUpgradeLine?.let { line ->
                                    appState.repo.persistTrustUpgrade(entry, line)
                                    appState.refresh()
                                }
                            } catch (_: Exception) { }
                        }
                    }
                }
                // Solo shells disconnect their own client on close (default);
                // shared ones just release the pool ref.
                val entry = sharedEntry
                if (entry != null) {
                    sess.onClosed = { releaseTransport(tkey, entry) }
                }
                sess.onDied = { onTransportDeath(sessionId, sess, sess.client) }
                // Fresh PTY: stale modes (bracketed paste, mouse) must not
                // leak from a previous session (frontend.resetTerminalModes).
                h.emulator.resetTerminalModes()
                h.shell = sess
                h.setStatus("connected")
                // A live shell re-arms the one-shot auto-retry bookkeeping.
                h.everConnected = true
                h.autoRetried = false
                if (attempt != null && attempt.remember && authenticatedFresh) {
                    // Desktop savedPassword parity: the typed password that
                    // worked is stored. A locked vault defers to the unlock
                    // dialog (TerminalScreen watches passwordSavePending).
                    try {
                        val loaded = withContext(Dispatchers.IO) {
                            appState.repo.savePassword(profile, attempt.password)
                        }
                        appState.adopt(loaded)
                    } catch (e: IllegalStateException) {
                        if ((e.message ?: "").contains("locked", ignoreCase = true)) {
                            h.setPasswordSavePending(attempt.password)
                        }
                    } catch (_: Exception) { }
                }
                h.collectJob?.cancel()
                h.collectJob = viewModelScope.launch {
                    sess.output.collect { chunk ->
                        h.emulator.feed(chunk)
                        h.bumpVersion()
                        noteOutput(sessionId)
                    }
                }
                // Remote forwards the server rejected: the session survived,
                // so say so in the terminal (output has buffer capacity —
                // tryEmit never suspends or drops here).
                if (freshWarnings.isNotEmpty()) {
                    sess.output.tryEmit(
                        freshWarnings.joinToString(
                            separator = "\r\n",
                            prefix = "\r\n",
                            postfix = "\r\n",
                        ) { "[!] $it" },
                    )
                    h.bumpVersion()
                }
            } catch (e: UnknownHostKeyException) {
                h.setPrompt(e)
                h.setStatus("disconnected")
            } catch (e: SshAuthFailed) {
                // Desktop prompt-password parity: a first failure offers the
                // password dialog; a failed RETRY is total failure — forget
                // the wrong stored password, land on the error card.
                if (attempt == null) {
                    h.setAuthPrompt(
                        AuthPrompt(
                            user = profile.options.user.ifBlank { "root" },
                            host = profile.options.host,
                            error = e.message ?: "Auth failed",
                            prefill = appState.passwordFor(profile),
                        ),
                    )
                } else {
                    finalizeAuthFailure(profile, appState)
                    h.setFailed(e.message ?: "Auth failed")
                }
                h.setStatus("disconnected")
            } catch (e: CancellationException) {
                h.setStatus("disconnected")
                throw e
            } catch (e: Exception) {
                h.setFailed(e.message ?: "Connect failed")
                h.setStatus("disconnected")
            } finally {
                h.connecting = false
                h.connectJob = null
                // Every connect outcome is a service transition (connected
                // or still disconnected) — the notification mirrors it.
                syncService()
            }
        }
    }

    /**
     * Desktop hostKeyPromptModal parity: remember persists to `ssh.knownHosts`,
     * once retries session-only. Locked vault degrades remember to once.
     */
    fun acceptHostKey(sessionId: String, remember: Boolean, appState: AppState, cacheDir: File) {
        val h = _sessions[sessionId] ?: return
        val prompt = h.hostKeyPrompt.value ?: return
        h.setPrompt(null)
        val entry = KnownHostEntry(prompt.host, prompt.port, prompt.keyType, prompt.digest)
        if (!remember) {
            h.extraTrust = entry
            connect(sessionId, appState, cacheDir)
            return
        }
        // Remember: connect FIRST with session-only trust, persist in the
        // background. The old order (persist -> refresh -> connect) blocked
        // the session on a vault rewrite + full reload (PBKDF2 + blob
        // parse, seconds on encrypted stores) — the UI sat on Disconnected
        // with Reconnect/Close before the terminal appeared. Verification
        // for THIS session uses extraTrust either way; the persisted entry
        // only matters for future sessions.
        h.extraTrust = entry
        connect(sessionId, appState, cacheDir)
        viewModelScope.launch {
            h.setFailed(null)
            try {
                withContext(Dispatchers.IO) { appState.repo.appendKnownHost(entry) }
                appState.refresh()
            } catch (_: Exception) {
                // Locked vault: YAML skipped, session trust above still connects.
            }
        }
    }

    fun rejectHostKey(sessionId: String, msg: String = "Host key rejected") {
        val h = _sessions[sessionId] ?: return
        h.setPrompt(null)
        h.setFailed(msg)
        h.setStatus("disconnected")
        syncService()
    }

    /**
     * Pool release (runs on any thread via [SshConnector.ShellSession.onClosed]).
     * The last ref disconnects the transport off the caller thread.
     */
    private fun releaseTransport(key: String, entry: PooledTransport) {
        val drop = synchronized(poolGuard) {
            entry.refs--
            if (entry.refs <= 0 && transports.remove(key, entry)) entry else null
        }
        if (drop != null) {
            viewModelScope.launch {
                withContext(Dispatchers.IO) { disconnectQuietly(drop.client) }
            }
        }
    }

    /**
     * Reader-pump death (runs on the pump thread — hop to the scope).
     * A live transport means just this channel ended (`exit`): fail this tab
     * only. A dead transport fails every tab riding it (red dots + Retry).
     * Explicit closes never reach here ([ShellSession.close] suppresses).
     */
    private fun onTransportDeath(sessionId: String, deadShell: SshConnector.ShellSession, deadClient: SSHClient) {
        viewModelScope.launch {
            val alive = try { deadClient.isConnected } catch (_: Exception) { false }
            val newlyDead = mutableListOf<SshSessionHandle>()
            if (alive) {
                val h = _sessions[sessionId]
                // Shell identity (not just client): a late callback must not
                // kill a NEW shell opened by Reconnect on the same transport.
                if (h != null && h.failed.value == null && h.shell === deadShell) {
                    val s = h.shell
                    h.shell = null
                    if (s != null) viewModelScope.launch {
                        withContext(Dispatchers.IO) { closeShellQuietly(s) }
                    }
                    h.setFailed("Session ended")
                    h.setStatus("disconnected")
                    newlyDead += h
                }
            } else {
                for (h in _sessions.values.toList()) {
                    val s = h.shell ?: continue
                    if (s.client !== deadClient || h.failed.value != null) continue
                    h.shell = null
                    viewModelScope.launch {
                        withContext(Dispatchers.IO) { closeShellQuietly(s) }
                    }
                    h.setFailed("Session ended")
                    h.setStatus("disconnected")
                    newlyDead += h
                }
            }
            syncService()
            for (h in newlyDead) maybeAutoRetry(h)
        }
    }

    /**
     * The single automatic retry after a transport death (network loss,
     * background kill). Runs for every tab — including backgrounded ones
     * whose screen is not composed — using the last connect environment.
     * Gated by [shouldAutoRetry]: exactly once per death, never when a UI
     * answer is pending. A short settle delay lets a flapping network calm
     * down; the retry aborts if the user already acted (closed, reconnected
     * manually, or a new shell appeared).
     */
    private fun maybeAutoRetry(h: SshSessionHandle) {
        val env = lastEnv ?: return
        val (appState, cacheDir) = env
        if (!shouldAutoRetry(
                everConnected = h.everConnected,
                autoRetried = h.autoRetried,
                needsPassphrase = appState.loaded?.needsPassphrase == true,
                hasAuthPrompt = h.authPrompt.value != null,
                hasHostKeyPrompt = h.hostKeyPrompt.value != null,
            )
        ) return
        h.autoRetried = true
        if (!AppForeground.isForeground) {
            lostListener?.invoke(sessionLabel(h))
        }
        viewModelScope.launch {
            delay(RETRY_SETTLE_MS)
            if (_sessions[h.sessionId] !== h) return@launch
            if (h.shell != null || h.connecting || h.failed.value == null) return@launch
            connect(h.sessionId, appState, cacheDir)
        }
    }

    /**
     * SFTP transfer host for [sessionId], created lazily. Lives in the
     * ViewModel — NOT in any composable — so transfers survive back,
     * dismiss, tab switches, and rotation; they die only on [close] (or
     * process death, like the sessions themselves).
     */
    private val sftpManagers = mutableMapOf<String, SftpTransferManager>()

    fun sftpOf(sessionId: String): SftpTransferManager = synchronized(poolGuard) {
        sftpManagers.getOrPut(sessionId) { SftpTransferManager(viewModelScope) }
    }

    override fun onCleared() {
        synchronized(poolGuard) {
            sftpManagers.values.toList().also { sftpManagers.clear() }
        }.forEach { runCatching { it.cancelAll() } }
        for ((_, h) in _sessions) {
            try { h.connectJob?.cancel() } catch (_: Exception) { }
            try { h.collectJob?.cancel() } catch (_: Exception) { }
            // Fires pool releases (their IO hops may be cancelled with the
            // scope — leftovers below cover that); solo shells disconnect now.
            // Off-thread: socket teardown can stall on a dead network.
            val s = h.shell
            h.shell = null
            if (s != null) {
                try {
                    kotlinx.coroutines.runBlocking(Dispatchers.IO) { closeShellQuietly(s) }
                } catch (_: Exception) { }
            }
        }
        _sessions.clear()
        // The registry just died (activity finish): mirror the empty list so
        // a lingering service stands down instead of guarding nothing. The
        // listener only posts a stop intent — safe with a dead scope.
        syncService()
        // Direct teardown: every pooled client is disconnected at most once
        // more here (harmless), so no socket can leak past the ViewModel.
        val leftovers = synchronized(poolGuard) {
            transports.values.toList().also { transports.clear() }
        }
        for (p in leftovers) disconnectQuietly(p.client)
    }

    companion object {
        const val DEFAULT_MAX_SESSIONS = 5
        /** Settle delay before the single auto-retry after a transport death. */
        const val RETRY_SETTLE_MS = 2000L
    }
}

private fun disconnectQuietly(client: SSHClient) {
    try { client.disconnect() } catch (_: Exception) { }
    try { client.close() } catch (_: Exception) { }
}

private fun closeShellQuietly(s: SshConnector.ShellSession) {
    try { s.close() } catch (_: Exception) { }
}

private fun readLegacyKnown(appState: AppState): List<String> = try {
    val arr = JSONArray(appState.disk.loadKnownHostsJson() ?: "[]")
    List(arr.length()) { arr.getString(it) }
} catch (_: Exception) { emptyList() }
