package id.web.izs.sshclient.core.ssh

import id.web.izs.sshclient.core.config.KnownHostEntry
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64

/**
 * Host-key trust decisions against the desktop `ssh.knownHosts` list —
 * the single source of trust (read offline from the local YAML cache).
 *
 * Desktop parity notes (`ssh.ts:verifyHostKey`, `hostKeyPromptModal`):
 * - Digest is sha256 over the SSH **wire blob** (NOT X.509 `key.encoded`),
 *   base64 WITH padding like Node crypto. Comparisons strip padding.
 * - Matching is exact per {host, port, type}; any stored entry for the
 *   host+port with a different digest is a MISMATCH (MITM-or-rotation).
 * - Negotiation order comes from [findExistingAlgorithms]: known types for
 *   the host first (verified against sshj's `KeyExchanger` bytecode — the first non-empty verifier list becomes
 *   THE negotiated host-key list), otherwise the desktop preference order
 *   so fresh trusts coincide with desktop's pick (ecdsa first, NOT sshj's
 *   ed25519-first default).
 *
 * Legacy prefs lines (`$host|javaAlgo|x509b64`, no port, X.509 blob) can't
 * yield a wire digest directly, so they are matched blob-for-blob and
 * self-heal: a hit auto-trusts (today's behavior) and produces an upgrade
 * entry the caller persists to YAML, retiring the legacy line.
 *
 * Pure JVM (sshj is a JVM dependency) — fully unit-tested.
 */
object HostKeyTrust {

    /** Fresh-trust order so phone and desktop pick the same server key. */
    val DESKTOP_ORDER = listOf(
        "ecdsa-sha2-nistp256",
        "ecdsa-sha2-nistp384",
        "ecdsa-sha2-nistp521",
        "ssh-ed25519",
        "rsa-sha2-256",
        "rsa-sha2-512",
        "ssh-rsa",
    )

    /** SHA-256 over the SSH wire blob, base64 with padding (desktop format). */
    fun digestOf(key: PublicKey): String {
        val buf = Buffer.PlainBuffer()
        buf.putPublicKey(key)
        val sum = MessageDigest.getInstance("SHA-256").digest(buf.compactData)
        return Base64.getEncoder().encodeToString(sum)
    }

    /** SSH wire type name (`ssh-ed25519`, `ecdsa-sha2-nistp256`, …). */
    fun wireTypeOf(key: PublicKey): String = KeyType.fromKey(key).toString()

    fun x509B64Of(key: PublicKey): String =
        Base64.getEncoder().encodeToString(key.encoded)

    fun normalizeDigest(b64: String): String = b64.trim().trimEnd('=')

    sealed interface Verdict {
        data object Known : Verdict
        data object Unknown : Verdict
        data class Mismatched(val previousDigest: String) : Verdict
        data class LegacyHit(val line: String, val upgrade: KnownHostEntry) : Verdict
    }

    /**
     * @param blobB64 X.509 base64 of the presented key (legacy matching only).
     * @param upgradePort port to stamp on a legacy upgrade (legacy lines
     *   carry no port — the live connection port is the honest value).
     */
    fun decide(
        entries: List<KnownHostEntry>,
        legacyLines: List<String>,
        host: String,
        port: Int,
        keyType: String,
        digest: String,
        blobB64: String,
        upgradePort: Int = port,
    ): Verdict {
        val norm = normalizeDigest(digest)
        val forHost = entries.filter { it.host == host && it.port == port }
        if (forHost.any { normalizeDigest(it.digest) == norm }) return Verdict.Known
        if (forHost.isNotEmpty()) {
            val prev = forHost.firstOrNull { it.type == keyType } ?: forHost.first()
            return Verdict.Mismatched(prev.digest)
        }
        val hit = legacyLines.firstOrNull { line ->
            val parts = line.split("|", limit = 3)
            parts.size == 3 && parts[0] == host && parts[2] == blobB64
        }
        if (hit != null) {
            return Verdict.LegacyHit(
                hit,
                KnownHostEntry(host, upgradePort, keyType, digest),
            )
        }
        return Verdict.Unknown
    }

    fun knownTypes(entries: List<KnownHostEntry>, host: String, port: Int): List<String> =
        entries.filter { it.host == host && it.port == port }
            .map { it.type }.filter { it.isNotBlank() }.distinct()

    /**
     * sshj `HostKeyVerifier.findExistingAlgorithms` implementation: known
     * types first (in offer order), else the desktop order when on defaults
     * or the custom list itself (an explicit user choice wins; returning it
     * equals sshj's no-hint behavior for that set).
     */
    fun findExistingAlgorithms(
        entries: List<KnownHostEntry>,
        host: String,
        port: Int,
        configured: List<String>,
        isCustom: Boolean,
    ): List<String> {
        val known = knownTypes(entries, host, port).filter { it in configured }
        if (known.isNotEmpty()) return configured.filter { it in known.toSet() }
        return if (isCustom) configured else DESKTOP_ORDER.filter { it in configured }
    }
}

/**
 * Thrown (from inside `openShell`, translated from a rejected verification)
 * when the user must decide: new key ([mismatched] false) or changed key.
 * Carries everything the trust dialog shows — no extra lookup needed.
 */
class UnknownHostKeyException(
    val host: String,
    val port: Int,
    val keyType: String,
    val digest: String,
    val mismatched: Boolean,
    val previousDigest: String?,
) : IllegalStateException(
    if (mismatched) "Host key changed for $host" else "Unknown host key for $host",
)
