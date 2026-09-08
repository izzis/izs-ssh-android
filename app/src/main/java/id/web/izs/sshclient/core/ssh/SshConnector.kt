package id.web.izs.sshclient.core.ssh

import id.web.izs.sshclient.core.config.SshProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import net.schmizz.keepalive.KeepAliveProvider
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
 * keepaliveInterval (SSH_MSG_IGNORE heartbeats; countMax stored-only),
 * login scripts (LoginScriptRunner), custom algorithms (per-connection sshj
 * config; desktop defaults take the plain path).
 * - agent auth: not supported on Android (no ssh-agent) -> clear message
 * - jumpHost / proxyCommand / socks-http proxy: parsed + preserved,
 *   but v1 connect shows a "scheduled for v2" message (never silently ignored)
 * - forwardedPorts / x11 / skipBanner / reuseSession: saved for desktop,
 *   not applied on mobile yet
 * - Host-key verification: desktop `ssh.knownHosts` trust (prompt on
 *   unknown/changed, known-first negotiation so phone and desktop pick the
 *   same server key). Trust lives in the YAML as the single source.
 */
class SshConnector {

    data class KeyInput(val pem: String, val passphrase: String? = null)

    /**
     * A persistent interactive shell (a real connection, not an exec probe).
     * Output chunks stream into [output]; the reader pump ends when the
     * session closes. Call [close] when leaving the screen.
     */
    class ShellSession internal constructor(
        private val client: SSHClient,
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
            try { session.close() } catch (_: Exception) { }
            try { client.disconnect() } catch (_: Exception) { }
            try { client.close() } catch (_: Exception) { }
        }
    }

    /**
     * Open a persistent PTY shell. Same guards and auth order as
     * [testConnect] (password, then keys one by one with key-passphrase
     * candidates). Throws IllegalStateException with a user-facing message
     * when the connection or auth fails.
     *
     * @param onStage live step text for the connecting UI (called from IO —
     * the caller hops threads). English-only, user-facing.
     */
    suspend fun openShell(
        profile: SshProfile,
        password: String?,
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
        val o = profile.options
        if (!o.jumpHost.isNullOrBlank() || !o.proxyCommand.isNullOrBlank() ||
            !o.socksProxyHost.isNullOrBlank() || !o.httpProxyHost.isNullOrBlank()
        ) {
            throw IllegalStateException("This profile uses jumpHost/proxy (scheduled for v2)")
        }
        if (o.host.isBlank()) throw IllegalStateException("Empty host")
        val port = if (o.port > 0) o.port else 22
        val user = o.user.ifBlank { "root" }
        // Ciphers tab + host-key trust order: the per-connection config
        // carries custom algorithm lists AND the known-first/desktop-order
        // host-key offer (the config order is what the server sees — the
        // verifier list alone is only a membership filter in sshj).
        val client = SSHClient(
            SshAlgorithmFactories.configFor(
                o.algorithms,
                HostKeyTrust.knownTypes(knownHosts, o.host, port),
            ),
        )
        // Advanced tab: keepalive heartbeats (SSH_MSG_IGNORE, universally
        // safe). The desktop countMax has no sshj equivalent and stays
        // stored-only; the interval is honored (ms -> s, min 1).
        client.transport.config.keepAliveProvider = KeepAliveProvider.KEEP_ALIVE
        try {
            withTimeout(timeoutMs.coerceIn(5_000, 120_000)) {
                onStage("Preparing crypto…")
                ensureProvider()
                val (offeredHostKeys, hostKeysCustom) =
                    SshAlgorithmFactories.effectiveHostKeys(o.algorithms)
                val verifier =                 TrustVerifier(
                    o.host, port, verifyHostKeys, knownHosts,
                    legacyKnownHostLines, oneTimeTrust,
                    offeredHostKeys, hostKeysCustom,
                )
                client.addHostKeyVerifier(verifier)
                onStage("Connecting to $user@${o.host}:$port…")
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
                if (!password.isNullOrBlank()) {
                    onStage("Authenticating with password…")
                    try {
                        client.authPassword(user, password)
                    } catch (e: Exception) {
                        lastErr = e.message ?: "password auth failed"
                    }
                }
                if (!client.isAuthenticated) {
                    val usable = keys.filter { it.pem.isNotBlank() }
                    for ((i, k) in usable.withIndex()) {
                        onStage("Trying private key ${i + 1} of ${usable.size}…")
                        if (tryKeyAuth(client, user, k.pem, listOf(k.passphrase) + keyPassphrases, cacheDir)) break
                        else lastErr = "publickey auth failed"
                    }
                }
                if (!client.isAuthenticated) {
                    if (password.isNullOrBlank() && keys.isEmpty()) {
                        throw IllegalStateException(
                            "Profile needs ${o.auth ?: "credentials"} but no password/key is available from the vault",
                        )
                    }
                    throw IllegalStateException("Auth failed: $lastErr")
                }
                try {
                    val keepAlive = client.connection.keepAlive
                    keepAlive.keepAliveInterval = (o.keepaliveInterval / 1000).toInt().coerceAtLeast(1)
                    if (!keepAlive.isAlive) keepAlive.start()
                } catch (_: Exception) {
                    // Best-effort: a dead keepalive must never fail the session.
                }
                onStage("Opening shell…")
                val session = client.startSession()
                try {
                    // xterm-256color (not dumb): fullscreen apps gate colors
                    // and cursor addressing on TERM.
                    session.allocatePTY("xterm-256color", 80, 24, 0, 0, emptyMap())
                    val shell = session.startShell()
                    val flow = MutableSharedFlow<String>(extraBufferCapacity = 512)
                    val sess = ShellSession(client, session, shell, flow)
                    sess.trustUpgrade = verifier.trustUpgrade
                    sess.trustUpgradeLine = verifier.trustUpgradeLine
                    // Login tab: unconditional scripts at session ready, then
                    // per-chunk expect/send automation (LoginScriptRunner).
                    val scriptRunner = o.scripts.takeIf { it.isNotEmpty() }?.let { LoginScriptRunner(it) }
                    if (scriptRunner != null) onStage("Running login scripts…")
                    scriptRunner?.runUnconditional()?.forEach { sess.send(it) }
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
                        }
                    }
                    return@withTimeout sess
                } catch (e: Exception) {
                    try { session.close() } catch (_: Exception) { }
                    throw e
                }
            }
        } catch (e: Exception) {
            try { client.disconnect() } catch (_: Exception) { }
            try { client.close() } catch (_: Exception) { }
            throw e
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
