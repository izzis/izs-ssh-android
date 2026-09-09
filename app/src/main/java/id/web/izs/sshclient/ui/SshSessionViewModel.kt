package id.web.izs.sshclient.ui

import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.web.izs.sshclient.core.config.KnownHostEntry
import id.web.izs.sshclient.core.config.SshProfile
import id.web.izs.sshclient.core.ssh.SshConnector
import id.web.izs.sshclient.core.ssh.UnknownHostKeyException
import id.web.izs.sshclient.core.ssh.transportKeyOf
import id.web.izs.sshclient.core.term.TerminalEmulator
import id.web.izs.sshclient.data.local.ConfigDisk
import net.schmizz.sshj.SSHClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
 * sessions. Cap defaults to 5, hard max 8 ([ConfigDisk.MAX_SESSIONS_HARD_MAX]).
 */
class SessionLimitReached(val max: Int) : IllegalStateException("Session limit reached ($max)")

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

    fun setStatus(v: String) { _status.value = v }
    fun setStage(v: String) { _stage.value = v }
    fun setFailed(v: String?) { _failed.value = v }
    fun setPrompt(v: UnknownHostKeyException?) { _hostKeyPrompt.value = v }
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

    /** One pooled TCP connection shared by tabs ([transportKeyOf]). */
    private class PooledTransport(val client: SSHClient, var refs: Int = 1)

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
    }

    /**
     * Open (or re-open) the shell for [sessionId]. Safe to call from
     * LaunchedEffect on every composition: guards double-fire while a connect
     * is already in flight and no-ops when already connected.
     */
    fun connect(sessionId: String, appState: AppState, cacheDir: File) {
        val h = _sessions[sessionId] ?: return
        if (h.connecting || (h.shell != null && h.isConnected)) return
        // Retry path clears the previous failure; first connect starts clean.
        h.connecting = true
        h.setStage("Starting…")
        h.connectJob = viewModelScope.launch {
            h.setFailed(null)
            h.setStatus("connecting…")
            try {
                val profile = appState.displayProfiles().find { it.id == h.profileId }
                    ?: h.profileSnapshot
                h.profileSnapshot = profile
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
                            password = appState.passwordFor(profile),
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
                h.shell = sess
                h.setStatus("connected")
                h.collectJob?.cancel()
                h.collectJob = viewModelScope.launch {
                    sess.output.collect { chunk ->
                        h.emulator.feed(chunk)
                        h.bumpVersion()
                        noteOutput(sessionId)
                    }
                }
            } catch (e: UnknownHostKeyException) {
                h.setPrompt(e)
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
            if (alive) {
                val h = _sessions[sessionId] ?: return@launch
                // Shell identity (not just client): a late callback must not
                // kill a NEW shell opened by Reconnect on the same transport.
                if (h.failed.value != null || h.shell !== deadShell) return@launch
                val s = h.shell
                h.shell = null
                if (s != null) viewModelScope.launch {
                    withContext(Dispatchers.IO) { closeShellQuietly(s) }
                }
                h.setFailed("Session ended")
                h.setStatus("disconnected")
                return@launch
            }
            for (h in _sessions.values.toList()) {
                val s = h.shell ?: continue
                if (s.client !== deadClient || h.failed.value != null) continue
                h.shell = null
                viewModelScope.launch {
                    withContext(Dispatchers.IO) { closeShellQuietly(s) }
                }
                h.setFailed("Session ended")
                h.setStatus("disconnected")
            }
        }
    }

    override fun onCleared() {
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
        // Direct teardown: every pooled client is disconnected at most once
        // more here (harmless), so no socket can leak past the ViewModel.
        val leftovers = synchronized(poolGuard) {
            transports.values.toList().also { transports.clear() }
        }
        for (p in leftovers) disconnectQuietly(p.client)
    }

    companion object {
        const val DEFAULT_MAX_SESSIONS = 5
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
