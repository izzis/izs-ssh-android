package id.web.izs.sshclient.core.ssh

import id.web.izs.sshclient.core.config.SshProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import net.schmizz.keepalive.KeepAliveProvider
import net.schmizz.keepalive.KeepAliveRunner
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.File
import java.security.PublicKey
import java.security.Security
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.KeyAgreement
import kotlin.concurrent.thread

/**
 * Koneksi SSH v1 via sshj 0.40.0 (aktif maintained, ed25519 + KEX modern).
 *
 * Honored profile options: host/port/user/auth (+vault secrets), readyTimeout,
 * keepaliveInterval + keepaliveCountMax (SSH_MSG_IGNORE heartbeats, the
 * transport drops after countMax unanswered — ServerAliveCountMax parity),
 * login scripts (LoginScriptRunner), custom algorithms (per-connection sshj
 * config; desktop defaults take the plain path).
 * - agent auth: not supported on Android (no ssh-agent) -> clear message
 * - jumpHost / proxyCommand / http proxy: parsed + preserved, but connect
 *   shows a not-supported message (never silently ignored)
 * - socksProxy: connects through the SOCKS proxy (desktop newSocksProxy
 *   parity, default port 1080) via a proxy SocketFactory
 * - forwardedPorts: Local + Remote open at connect (desktop addPortForward
 *   parity — Local bind failure aborts the connect, Remote rejection warns
 *   and continues); Dynamic is desktop-only with a clear message.
 *   x11 stays saved-for-desktop; skipBanner is honored (auth-banner
 *   service line, suppressed when set).
 * - reuseSession: honored via transport sharing ([connectTransport] once per
 *   [transportKeyOf], [openShellOnTransport] per tab — desktop multiplexer
 *   parity). Every profile tap opens a new tab; true shares the TCP
 *   connection (no re-auth), false connects separately per tab
 * - Host-key verification: desktop `ssh.knownHosts` trust (prompt on
 *   unknown/changed, known-first negotiation so phone and desktop pick the
 *   same server key). Trust lives in the YAML as the single source.
 */

/**
 * Authentication failure (wrong/missing password or key). Typed so the UI
 * can offer the desktop `prompt-password` failover (password dialog) instead
 * of a dead-end error card — never the raw sshj "Exhausted available
 * authentication methods" text (see [friendlyAuthError]).
 */
class SshAuthFailed(message: String) : IllegalStateException(message)

/**
 * User-facing auth-failure reason: sshj's raw "Exhausted available
 * authentication methods" means the server rejected everything we tried.
 */
fun friendlyAuthError(lastErr: String): String {
    val e = lastErr.trim()
    if (e.isEmpty()) return "server rejected the credentials"
    if (e.contains("Exhausted", ignoreCase = true)) return "wrong password or key"
    return e
}

class SshConnector {

    data class KeyInput(val pem: String, val passphrase: String? = null)

    /**
     * An authenticated transport (TCP + auth, no channel yet). Multiplexing
     * shares one of these across tabs; each tab opens its own shell channel
     * via [openShellOnTransport]. Call [close] to tear the connection down.
     */
    class ConnectedTransport internal constructor(
        val client: SSHClient,
        var trustUpgrade: id.web.izs.sshclient.core.config.KnownHostEntry? = null,
        var trustUpgradeLine: String? = null,
        val forwards: StartedForwards = StartedForwards.empty(),
        /** Raw server text from the USERAUTH_BANNER packet (null when the
         *  server sent none). Displayed unless the profile skips it. */
        val authBanner: String? = null,
    ) {
        fun close() {
            try { forwards.close() } catch (_: Exception) { }
            try { client.disconnect() } catch (_: Exception) { }
            try { client.close() } catch (_: Exception) { }
        }
    }

    /**
     * A persistent interactive shell (a real connection, not an exec probe).
     * Output chunks stream into [output]; the reader pump ends when the
     * session closes. Call [close] when leaving the screen.
     *
     * Multiplexing: several shells may ride one [client]. [onClosed] lets the
     * owner (session registry) refcount the shared transport — null keeps the
     * legacy behavior (close() also disconnects the client). [onDied] fires
     * once when the reader pump ends WITHOUT an explicit close (network drop
     * or server-side end); it runs on the reader thread, so hop threads.
     */
    class ShellSession internal constructor(
        val client: SSHClient,
        private val session: Session,
        private val shell: Session.Shell,
        val output: MutableSharedFlow<String>,
    ) {
        /**
         * Legacy prefs trust that matched this session: the caller persists
         * it to YAML (self-healing upgrade) and retires the legacy line.
         * Null when trust came from YAML entries or one-time acceptance.
         */
        internal var trustUpgrade: id.web.izs.sshclient.core.config.KnownHostEntry? = null
        /** Legacy prefs line that produced [trustUpgrade] — retired on persist. */
        internal var trustUpgradeLine: String? = null
        internal var onClosed: (() -> Unit)? = null
        internal var onDied: (() -> Unit)? = null
        private val closeState = AtomicBoolean(false)
        suspend fun send(line: String) = withContext(Dispatchers.IO) {
            shell.outputStream.write((line + "\n").toByteArray(Charsets.UTF_8))
            shell.outputStream.flush()
        }

        /** Raw keystrokes (Esc, arrows, Ctrl-C…) — no newline appended. */
        suspend fun sendRaw(text: String) = withContext(Dispatchers.IO) {
            shell.outputStream.write(text.toByteArray(Charsets.UTF_8))
            shell.outputStream.flush()
        }

        /**
         * Window-change request (RFC 4254 §6.7): tell the server the pty is
         * now cols×rows. Best-effort — failures are swallowed because the
         * local grid keeps working regardless (scroll-follow safety net).
         */
        suspend fun resize(cols: Int, rows: Int, widthPx: Int, heightPx: Int) =
            withContext(Dispatchers.IO) {
                try {
                    shell.changeWindowDimensions(cols, rows, widthPx, heightPx)
                } catch (_: Exception) { }
            }

        fun close() {
            if (!closeState.compareAndSet(false, true)) return
            if (onClosed != null) {
                // Shared transport (desktop SSHShellSession.destroy parity,
                // shell.ts:92-99): NEVER close the channel here — some servers
                // tear the whole connection down on channel close, killing
                // sibling tabs. Just release the pool ref; the last release
                // disconnects the transport and the server cleans up. The
                // abandoned remote shell lingers until then, exactly like
                // desktop (whose kill() is a no-op and never closes either).
                try { onClosed?.invoke() } catch (_: Exception) { }
                return
            }
            // Solo: nothing is shared — full teardown.
            try { session.close() } catch (_: Exception) { }
            // The reader pump may already have fired onDied just before this;
            // the owner guards via registry lookup, so both orders are safe.
            try { client.disconnect() } catch (_: Exception) { }
            try { client.close() } catch (_: Exception) { }
        }

        /** Reader-pump callback: unexpected end (drop or server-side end). */
        internal fun notifyDied() {
            if (!closeState.get()) {
                try { onDied?.invoke() } catch (_: Exception) { }
            }
        }
    }

    /**
     * Open a persistent PTY shell: connect + auth, then one shell channel.
     * Same guards and auth order as before (password, then keys one by one
     * with key-passphrase candidates). Throws IllegalStateException with a
     * user-facing message when the connection or auth fails.
     *
     * Multiplexing uses the split below directly ([connectTransport] once per
     * transport, [openShellOnTransport] once per tab).
     *
     * @param onStage live step text for the connecting UI (called from IO —
     * the caller hops threads). English-only, user-facing.
     */
    suspend fun openShell(
        profile: SshProfile,
        password: String?,
        passwordIsTyped: Boolean = false,
        keys: List<KeyInput>,
        keyPassphrases: List<String> = emptyList(),
        verifyHostKeys: Boolean,
        knownHosts: List<id.web.izs.sshclient.core.config.KnownHostEntry>,
        legacyKnownHostLines: List<String> = emptyList(),
        oneTimeTrust: id.web.izs.sshclient.core.config.KnownHostEntry? = null,
        timeoutMs: Long,
        cacheDir: File,
        onStage: (String) -> Unit = {},
    ): ShellSession = withContext(Dispatchers.IO) {
        val t = connectTransport(
            profile = profile,
            password = password,
            passwordIsTyped = passwordIsTyped,
            keys = keys,
            keyPassphrases = keyPassphrases,
            verifyHostKeys = verifyHostKeys,
            knownHosts = knownHosts,
            legacyKnownHostLines = legacyKnownHostLines,
            oneTimeTrust = oneTimeTrust,
            timeoutMs = timeoutMs,
            cacheDir = cacheDir,
            onStage = onStage,
        )
        try {
            openShellOnTransport(t.client, profile, onStage).also {
                it.trustUpgrade = t.trustUpgrade
                it.trustUpgradeLine = t.trustUpgradeLine
            }
        } catch (e: Exception) {
            t.close()
            throw e
        }
    }

    /**
     * Transport half of [openShell]: TCP connect + host-key verification +
     * auth + keepalive. No channel is opened — the caller opens one shell per
     * tab via [openShellOnTransport] and eventually tears down via
     * [ConnectedTransport.close]. On failure the client is torn down here, so
     * success always transfers ownership out.
     */
    suspend fun connectTransport(
        profile: SshProfile,
        password: String?,
        /**
         * True when [password] was just typed into the auth-failover prompt
         * (not read from storage). Explicit user intent always gets attempted
         * — desktop prompt-password parity — even under an auth selection
         * that would otherwise skip the password method.
         */
        passwordIsTyped: Boolean = false,
        keys: List<KeyInput>,
        keyPassphrases: List<String> = emptyList(),
        verifyHostKeys: Boolean,
        knownHosts: List<id.web.izs.sshclient.core.config.KnownHostEntry>,
        legacyKnownHostLines: List<String> = emptyList(),
        oneTimeTrust: id.web.izs.sshclient.core.config.KnownHostEntry? = null,
        timeoutMs: Long,
        cacheDir: File,
        onStage: (String) -> Unit = {},
    ): ConnectedTransport = withContext(Dispatchers.IO) {
        val o = profile.options
        if (!o.jumpHost.isNullOrBlank() || !o.proxyCommand.isNullOrBlank() ||
            !o.httpProxyHost.isNullOrBlank()
        ) {
            throw IllegalStateException("This profile uses a jump host, proxy command, or HTTP proxy, which is not supported on this device yet.")
        }
        if (o.host.isBlank()) throw IllegalStateException("Enter a host name or IP address.")
        val port = if (o.port > 0) o.port else 22
        val user = o.user.ifBlank { "root" }
        // SOCKS proxy (desktop socksProxy parity): the whole transport —
        // handshake, auth, shells, forwards — rides the proxy socket.
        val socksProxy = SocksProxy.resolveAddress(o.socksProxyHost, o.socksProxyPort)
        // Ciphers tab + host-key trust order: the per-connection config
        // carries custom algorithm lists AND the known-first/desktop-order
        // host-key offer (the config order is what the server sees — the
        // verifier list alone is only a membership filter in sshj).
        val client = SSHClient(
            SshAlgorithmFactories.configFor(
                o.algorithms,
                HostKeyTrust.knownTypes(knownHosts, o.host, port),
            ).apply {
                // The provider MUST be chosen here: SSHClient freezes it
                // into the connection at construction, so assigning it
                // afterwards (on client.transport.config) silently keeps
                // the DefaultConfig HEARTBEAT instead. KEEP_ALIVE is a
                // KeepAliveRunner: SSH_MSG_IGNORE heartbeats plus the
                // desktop keepaliveCountMax watchdog (drops the transport
                // after that many unanswered heartbeats).
                keepAliveProvider = KeepAliveProvider.KEEP_ALIVE
            },
        )
        if (socksProxy != null) {
            onStage("Connecting via SOCKS proxy ${socksProxy.hostString}:${socksProxy.port}...")
            client.socketFactory = SocksProxy.socketFactory(socksProxy)
        }
        // (Keepalive provider is fixed at construction above.)
        try {
            withTimeout(timeoutMs.coerceIn(5_000, 120_000)) {
                onStage("Preparing secure connection...")
                ensureProvider()
                val (offeredHostKeys, hostKeysCustom) =
                    SshAlgorithmFactories.effectiveHostKeys(o.algorithms)
                val verifier =                 TrustVerifier(
                    o.host, port, verifyHostKeys, knownHosts,
                    legacyKnownHostLines, oneTimeTrust,
                    offeredHostKeys, hostKeysCustom,
                )
                client.addHostKeyVerifier(verifier)
                onStage("Connecting to $user@${o.host}:$port...")
                try {
                    client.connect(o.host, port)
                } catch (e: UnknownHostKeyException) {
                    throw e
                } catch (e: Exception) {
                    // A rejected verification surfaces here as a transport
                    // failure — translate it back to the user's decision.
                    verifier.rejection?.let { throw it }
                    throw IllegalStateException("Connect failed: ${e.message ?: e::class.simpleName}")
                }
                var lastErr = ""
                // Desktop auth-selection parity (ssh.ts init():163-262): an
                // explicit `auth` restricts the methods tried — never silent
                // Auto. Agent has no counterpart on Android (no ssh-agent).
                // Keyboard-interactive has no transport here: it narrows to
                // password only (desktop never tries keys for it either) and
                // relies on the password failover prompt — no KI flow needed.
                if (o.auth == "agent") {
                    throw IllegalStateException("Agent auth is not available on this device — select Password or Private Key.")
                }
                val tryPassword = passwordIsTyped || o.auth.isNullOrBlank() ||
                    o.auth == "password" || o.auth == "keyboardInteractive"
                val tryKeys = o.auth.isNullOrBlank() || o.auth == "publicKey"
                if (tryPassword && !password.isNullOrBlank()) {
                    onStage("Checking password...")
                    try {
                        client.authPassword(user, password)
                    } catch (e: Exception) {
                        lastErr = e.message ?: "password auth failed"
                    }
                }
                if (!client.isAuthenticated && tryKeys) {
                    val usable = keys.filter { it.pem.isNotBlank() }
                    for ((i, k) in usable.withIndex()) {
                        onStage("Trying key ${i + 1} of ${usable.size}...")
                        if (tryKeyAuth(client, user, k.pem, listOf(k.passphrase) + keyPassphrases, cacheDir)) break
                        else lastErr = "publickey auth failed"
                    }
                }
                if (!client.isAuthenticated) {
                    if (o.auth == "publicKey" && keys.none { it.pem.isNotBlank() }) {
                        throw SshAuthFailed("No private key saved for this profile")
                    }
                    if ((o.auth == "password" || o.auth == "keyboardInteractive") && password.isNullOrBlank()) {
                        throw SshAuthFailed("No saved password for this profile")
                    }
                    if (password.isNullOrBlank() && keys.isEmpty()) {
                        throw SshAuthFailed(
                            "No saved password or key for this profile",
                        )
                    }
                    throw SshAuthFailed("Auth failed: ${friendlyAuthError(lastErr)}")
                }
                try {
                    val keepAlive = client.connection.keepAlive
                    keepAlive.keepAliveInterval = (o.keepaliveInterval / 1000).toInt().coerceAtLeast(1)
                    (keepAlive as? KeepAliveRunner)?.maxAliveCount = o.keepaliveCountMax.coerceAtLeast(1)
                    if (!keepAlive.isAlive) keepAlive.start()
                } catch (_: Exception) {
                    // Best-effort: a dead keepalive must never fail the session.
                }
                // Auth banner (USERAUTH_BANNER packet, e.g. /etc/issue.net):
                // captured for the optional service-line display. Best
                // effort — a dead banner must never fail the session.
                val authBanner: String? = try {
                    client.userAuth.banner.takeIf { it.isNotBlank() }
                } catch (_: Exception) { null }
                // Port forwarding opens here — once per transport, so shared
                // tabs reuse the same forwards (desktop multiplexer parity).
                // Local bind failure / Dynamic rows throw and abort the
                // connect; Remote rejections only warn (see PortForwarding).
                val startedForwards =
                    client.startPortForwards(resolveForwardSpecs(o.forwardedPorts), onStage)
                return@withTimeout ConnectedTransport(
                    client,
                    verifier.trustUpgrade,
                    verifier.trustUpgradeLine,
                    startedForwards,
                    authBanner,
                )
            }
        } catch (e: Exception) {
            try { client.disconnect() } catch (_: Exception) { }
            try { client.close() } catch (_: Exception) { }
            throw e
        }
    }

    /**
     * Channel half of [openShell]: one PTY shell over an already-authenticated
     * [client] (own fresh transport or a multiplex-shared one). Reader pump +
     * login scripts included. The channel close never touches the transport —
     * [ShellSession.close] (plus the owner's hooks) owns that decision.
     */
    suspend fun openShellOnTransport(
        client: SSHClient,
        profile: SshProfile,
        onStage: (String) -> Unit = {},
    ): ShellSession = withContext(Dispatchers.IO) {
        val o = profile.options
        onStage("Opening shell...")
        val session = client.startSession()
        try {
            // xterm-256color (not dumb): fullscreen apps gate colors
            // and cursor addressing on TERM.
            session.allocatePTY("xterm-256color", 80, 24, 0, 0, emptyMap())
            val shell = session.startShell()
            val flow = MutableSharedFlow<String>(extraBufferCapacity = 512)
            val sess = ShellSession(client, session, shell, flow)
            // Login tab: unconditional scripts at session ready, then
            // per-chunk expect/send automation (LoginScriptRunner).
            val scriptRunner = o.scripts.takeIf { it.isNotEmpty() }?.let { LoginScriptRunner(it) }
            if (scriptRunner != null) onStage("Running login scripts...")
            scriptRunner?.runUnconditional()?.forEach { sess.send(it) }
            startReaderPump(shell, flow, scriptRunner, sess)
            return@withContext sess
        } catch (e: Exception) {
            try { session.close() } catch (_: Exception) { }
            throw e
        }
    }

    /** Reader pump: char-based emits (multi-byte UTF-8 never splits) + death hook. */
    private fun startReaderPump(
        shell: Session.Shell,
        flow: MutableSharedFlow<String>,
        scriptRunner: LoginScriptRunner?,
        sess: ShellSession,
    ) {
        thread(isDaemon = true, name = "ssh-shell-reader") {
            try {
                // Char-based (not byte chunks): multi-byte UTF-8
                // box-drawing chars never split across emits.
                val reader = shell.inputStream.reader(Charsets.UTF_8)
                val buf = CharArray(4096)
                while (true) {
                    val n = reader.read(buf)
                    if (n < 0) break
                    if (n > 0) {
                        val chunk = String(buf, 0, n)
                        flow.tryEmit(chunk)
                        // The runner is touched only on this
                        // thread; sends block briefly via
                        // runBlocking (rare and tiny).
                        scriptRunner?.onOutput(chunk)?.forEach { runBlocking { sess.send(it) } }
                    }
                }
            } catch (_: Exception) {
                // Session closed -> pump ends.
            } finally {
                sess.notifyDied()
            }
        }
    }

    companion object {
        private val cryptoReady = AtomicBoolean(false)

        /**
         * Android ships a stripped "BC" provider without X25519/XDH, while sshj
         * pins provider "BC" for key exchange. sshj's own registration silently
         * no-ops on Android (Security.addProvider refuses a duplicate name), so
         * curve25519 KEX dies with "no such algorithm X25519". Swap in the full
         * BouncyCastle from our bcprov dependency: it is a superset of what
         * Android's BC offered, and TLS itself runs on Conscrypt, not BC.
         *
         * Synchronized: two concurrent first-connects must never interleave
         * inside the remove/insert window (the second would see no BC at all).
         */
        @Synchronized
        fun ensureProvider() {
            if (cryptoReady.getAndSet(true)) return
            try {
                KeyAgreement.getInstance("X25519", BouncyCastleProvider.PROVIDER_NAME)
                return
            } catch (_: Exception) {
                // Provider "BC" cannot do X25519 -> replace it below.
            }
            try {
                Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
            } catch (_: Exception) {
            }
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        }
    }

    /**
     * Public-key auth for one PEM against passphrase candidates in order
     * (none first, then each saved key-passphrase). A wrong candidate only
     * fails locally while loading the key — the server sees one attempt.
     */
    private fun tryKeyAuth(
        client: SSHClient,
        user: String,
        pem: String,
        passphrases: List<String?>,
        cacheDir: File,
    ): Boolean {
        val tried = mutableSetOf<String?>()
        // Always try passphrase-less first (plain keys are the common case).
        for (pp in listOf(null) + passphrases) {
            if (!tried.add(pp)) continue
            val tmp = writeTempKey(cacheDir, pem)
            try {
                // loadKeys(path, char[]) handles encrypted keys; null passphrase -> plain key
                val kp = if (pp != null) client.loadKeys(tmp.absolutePath, pp.toCharArray())
                else client.loadKeys(tmp.absolutePath)
                client.authPublickey(user, kp)
                if (client.isAuthenticated) return true
            } catch (_: Exception) {
                // Wrong passphrase or unloadable key -> next candidate.
            } finally {
                tmp.delete()
            }
        }
        return false
    }

    private fun writeTempKey(cacheDir: File, pem: String): File {
        val dir = File(cacheDir, "sshkeys").also { it.mkdirs() }
        val f = File.createTempFile("key", ".pem", dir)
        f.writeText(pem)
        f.setReadable(false, false)
        f.setReadable(true, true)
        f.setWritable(false, false)
        f.setWritable(true, true)
        return f
    }

    /**
     * Desktop host-key verification parity (`ssh.ts:verifyHostKey` +
     * `hostKeyPromptModal`): unknown/changed keys are NEVER auto-trusted.
     * Rejection is recorded (the transport surfaces a generic failure, so
     * `openShell` translates it back to [UnknownHostKeyException]) while
     * negotiation order comes from [HostKeyTrust.findExistingAlgorithms] —
     * known types first, desktop order on defaults (phone and desktop
     * then pick the same server key).
     */
    private class TrustVerifier(
        private val host: String,
        private val port: Int,
        private val enabled: Boolean,
        private val entries: List<id.web.izs.sshclient.core.config.KnownHostEntry>,
        private val legacyLines: List<String>,
        private val oneTimeTrust: id.web.izs.sshclient.core.config.KnownHostEntry?,
        private val offeredHostKeys: List<String>,
        private val hostKeysCustom: Boolean,
    ) : HostKeyVerifier {
        var rejection: UnknownHostKeyException? = null
            private set
        var trustUpgrade: id.web.izs.sshclient.core.config.KnownHostEntry? = null
            private set
        var trustUpgradeLine: String? = null
            private set

        override fun verify(hostname: String?, p: Int, key: PublicKey?): Boolean {
            if (!enabled || key == null) return true
            val type = HostKeyTrust.wireTypeOf(key)
            val digest = HostKeyTrust.digestOf(key)
            val norm = HostKeyTrust.normalizeDigest(digest)
            if (oneTimeTrust != null &&
                oneTimeTrust.host == host && oneTimeTrust.port == port &&
                HostKeyTrust.normalizeDigest(oneTimeTrust.digest) == norm
            ) {
                return true
            }
            return when (
                val v = HostKeyTrust.decide(
                    entries, legacyLines, host, port, type, digest,
                    HostKeyTrust.x509B64Of(key), port,
                )
            ) {
                HostKeyTrust.Verdict.Known -> true
                is HostKeyTrust.Verdict.LegacyHit -> {
                    trustUpgrade = v.upgrade
                    trustUpgradeLine = v.line
                    true
                }
                HostKeyTrust.Verdict.Unknown -> {
                    rejection = UnknownHostKeyException(host, port, type, digest, false, null)
                    false
                }
                is HostKeyTrust.Verdict.Mismatched -> {
                    rejection = UnknownHostKeyException(host, port, type, digest, true, v.previousDigest)
                    false
                }
            }
        }

        override fun findExistingAlgorithms(hostname: String?, port: Int): MutableList<String> =
            HostKeyTrust.findExistingAlgorithms(
                entries, host, port, offeredHostKeys, hostKeysCustom,
            ).toMutableList()
    }
}

/**
 * Desktop multiplexer-key parity (`sshMultiplexer.service.ts`): transports
 * are shared per `host:port:user:proxy…` (desktop appends the recursive
 * `$jumpchain`; jump profiles can't connect here yet, so the jump ID itself
 * is enough to never share across them). Forward rules join the key too —
 * desktop doesn't include them (a reused session silently skips the second
 * profile's forwards); splitting is the honest choice, documented in
 * ARCHITECTURE.md. Port/user are normalized exactly like
 * [SshConnector.connectTransport] resolves them.
 *
 * Pure JVM — unit-tested.
 */
fun transportKeyOf(o: id.web.izs.sshclient.core.config.SshOptions): String {
    val port = if (o.port > 0) o.port else 22
    val user = o.user.ifBlank { "root" }
    val forwards = o.forwardedPorts.joinToString(";") {
        "${it.type}/${it.host.ifBlank { "127.0.0.1" }}/${it.port}/" +
            "${it.targetAddress.ifBlank { "127.0.0.1" }}/${it.targetPort}"
    }
    return listOf(
        o.host,
        port.toString(),
        user,
        o.proxyCommand ?: "",
        o.jumpHost ?: "",
        o.socksProxyHost ?: "",
        (o.socksProxyPort ?: 0).toString(),
        o.httpProxyHost ?: "",
        (o.httpProxyPort ?: 0).toString(),
        forwards,
    ).joinToString(":")
}
