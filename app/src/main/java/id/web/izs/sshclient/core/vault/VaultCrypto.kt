package id.web.izs.sshclient.core.vault

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import id.web.izs.sshclient.core.config.StoredVault
import id.web.izs.sshclient.core.perf.PerfProbe

/**
 * Exact parity with tabby-core/src/services/vault.service.ts:13-94.
 *
 * - PBKDF2(passphrase UTF-8, salt 8 bytes, 100,000 iterations, HmacSHA512) -> 256-bit
 * - AES-256-CBC, IV 16 bytes, PKCS5Padding (== PKCS7 for 16-byte blocks)
 * - plaintext = JSON.stringify({config, secrets}) UTF-8
 * - contents = base64, keySalt/iv = lowercase hex, version = 1
 *
 * Named after the desktop (maybeDecrypt -> decryptIfNeeded) for clarity.
 */
object VaultCrypto {
    const val ITERATIONS = 100_000
    const val SALT_LEN = 8
    const val IV_LEN = 16
    const val VERSION = 1

    class BadDecryptException(msg: String) : Exception(msg)
    class UnsupportedVersionException(msg: String) : Exception(msg)

    fun encrypt(configJson: String, secretsJson: String, passphrase: String): StoredVault {
        val rng = SecureRandom()
        val salt = ByteArray(SALT_LEN).also { rng.nextBytes(it) }
        val iv = ByteArray(IV_LEN).also { rng.nextBytes(it) }
        val key = deriveKey(passphrase, salt)
        val plain = """{"config":$configJson,"secrets":$secretsJson}""".toByteArray(Charsets.UTF_8)
        val enc = PerfProbe.measure("vault.aes.encrypt") {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            cipher.doFinal(plain)
        }
        val stored = StoredVault(
            version = VERSION,
            contents = java.util.Base64.getEncoder().encodeToString(enc),
            keySalt = salt.toHex(),
            iv = iv.toHex(),
        )
        // Pre-warm the decrypt cache: the save tail (`decryptToLoaded`) and
        // the next edit read this exact blob back.
        PayloadCache.put(stored, passphrase, configJson to secretsJson)
        return stored
    }

    /** @return raw Pair(configJson, secretsJson). */
    fun decrypt(vault: StoredVault, passphrase: String): Pair<String, String> {
        if (vault.version != VERSION) throw UnsupportedVersionException("Unsupported vault version ${vault.version}")
        PayloadCache.get(vault, passphrase)?.let { return it }
        val salt = vault.keySalt.hexToBytes()
        val iv = vault.iv.hexToBytes()
        val enc = try {
            java.util.Base64.getMimeDecoder().decode(vault.contents)
        } catch (e: Exception) {
            throw BadDecryptException("BAD_DECRYPT: corrupt base64")
        }
        val key = deriveKey(passphrase, salt)
        val plain: ByteArray = try {
            PerfProbe.measure("vault.aes.decrypt") {
                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                cipher.doFinal(enc)
            }
        } catch (e: Exception) {
            throw BadDecryptException("BAD_DECRYPT: incorrect passphrase or corrupt vault")
        }
        val text = plain.toString(Charsets.UTF_8)
        // Payload = {"config":{...},"secrets":[...]} — split at top level without a full JSON parser.
        // A wrong passphrase yields random bytes; valid PKCS#7 padding by
        // chance (~1/256) must still surface as BadDecrypt (never leak a
        // raw require() IllegalArgumentException to callers).
        try {
            return splitVaultPayload(text).also { PayloadCache.put(vault, passphrase, it) }
        } catch (e: BadDecryptException) {
            throw e
        } catch (e: Exception) {
            throw BadDecryptException("BAD_DECRYPT: not a vault payload")
        }
    }

    /**
     * maybeDecryptConfig parity: return the payload when encrypted, else null
     * (callers then use the raw store). Throws BadDecrypt on a wrong passphrase.
     */
    fun decryptIfNeeded(encrypted: Boolean, vault: StoredVault?, passphrase: String?): Pair<String, String>? {
        if (!encrypted || vault == null) return null
        require(!passphrase.isNullOrEmpty()) { "Vault unlock cancelled" }
        return decrypt(vault, passphrase)
    }

    private fun deriveKey(passphrase: String, salt: ByteArray): ByteArray {
        // Fast path: repeated decrypts of the same vault (boot, reload,
        // point-of-use prompts) reuse the PBKDF2 output. Encrypt always
        // uses a fresh random salt so it never hits — parity unchanged
        // (same 100k/HmacSHA512 params, same bytes out).
        // RAM-only, bounded, no disk format change.
        KeyCache.get(passphrase, salt)?.let { return it }
        val raw = PerfProbe.measure("vault.pbkdf2") {
            val spec = PBEKeySpec(passphrase.toCharArray(), salt, ITERATIONS, 256)
            try {
                val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
                f.generateSecret(spec).encoded
            } finally {
                spec.clearPassword()
            }
        }
        KeyCache.put(passphrase, salt, raw)
        return raw.copyOf()
    }

    /**
     * Tiny synchronized LRU for PBKDF2 outputs (decrypt fast path only).
     * Keyed by (passphrase, saltHex); 32-byte values. Cleared via
     * [clearCache] on vault lock if callers want zero residue — eviction
     * alone is already bounded, and the passphrase itself lives in RAM
     * ([rememberedPassphrase]) for the session anyway.
     */
    private object KeyCache {
        private const val MAX_ENTRIES = 8
        private val map = object : LinkedHashMap<String, ByteArray>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>): Boolean =
                size > MAX_ENTRIES
        }

        @Synchronized
        fun get(passphrase: String, salt: ByteArray): ByteArray? =
            map[keyOf(passphrase, salt)]?.copyOf()

        @Synchronized
        fun put(passphrase: String, salt: ByteArray, key: ByteArray) {
            map[keyOf(passphrase, salt)] = key.copyOf()
        }

        @Synchronized
        fun clear() {
            map.values.forEach { it.fill(0) }
            map.clear()
        }

        private fun keyOf(passphrase: String, salt: ByteArray): String {
            val sb = StringBuilder(passphrase.length + 32)
            sb.append(passphrase.length).append(':').append(passphrase).append(':')
            for (b in salt) {
                val v = b.toInt() and 0xFF
                sb.append("0123456789abcdef"[v shr 4]).append("0123456789abcdef"[v and 0xF])
            }
            return sb.toString()
        }
    }

    /** Zero the RAM caches (call on vault lock/erase; optional hygiene). */
    fun clearCache() {
        KeyCache.clear()
        PayloadCache.clear()
    }

    /**
     * RAM-only decrypted-payload cache (no disk format change, no parity
     * change — same bytes in, same bytes out).
     *
     * Why: every encrypted edit-save does decrypt(old blob) + encrypt(new
     * blob) = 2x PBKDF2, the dominant cost at 400KB. Encrypt always mints a
     * fresh salt (desktop parity) so its PBKDF2 can never be skipped — but
     * the decrypt half hits here whenever the blob was seen before (reloads,
     * point-of-use prompts, and the `decryptToLoaded` tail of the save that
     * just wrote the blob, which [encrypt] pre-warms below).
     *
     * Safety: keyed by (passphrase, salt, iv, SHA-256(contents)) — a wrong
     * passphrase or a tampered blob can never hit; those always run the real
     * decrypt and throw BAD_DECRYPT exactly as before. Bounded (2 entries),
     * dropped on [clearCache] (vault lock).
     */
    private object PayloadCache {
        private const val MAX_ENTRIES = 2
        private val map = object : LinkedHashMap<String, Pair<String, String>>(8, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, Pair<String, String>>,
            ): Boolean = size > MAX_ENTRIES
        }

        @Synchronized
        fun get(vault: StoredVault, passphrase: String): Pair<String, String>? =
            map[keyOf(vault, passphrase)]

        @Synchronized
        fun put(vault: StoredVault, passphrase: String, payload: Pair<String, String>) {
            map[keyOf(vault, passphrase)] = payload
        }

        @Synchronized
        fun clear() = map.clear()

        private fun keyOf(vault: StoredVault, passphrase: String): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(vault.contents.toByteArray(Charsets.UTF_8))
            val sb = StringBuilder(passphrase.length + vault.keySalt.length + vault.iv.length + 140)
            sb.append(passphrase.length).append(':').append(passphrase).append(':')
            sb.append(vault.keySalt).append(':').append(vault.iv).append(':')
            for (b in hash) {
                val v = b.toInt() and 0xFF
                sb.append("0123456789abcdef"[v shr 4]).append("0123456789abcdef"[v and 0xF])
            }
            return sb.toString()
        }
    }

    /** Split {"config":X,"secrets":Y} -> (X, Y) with depth tracking. */
    internal fun splitVaultPayload(text: String): Pair<String, String> {
        val t = text.trim()
        require(t.startsWith("{") && t.endsWith("}")) { "BAD_DECRYPT: not a vault payload" }
        val ci = t.indexOf("\"config\"")
        val si = t.indexOf("\"secrets\"")
        require(ci >= 0 && si >= 0) { "BAD_DECRYPT: missing config/secrets" }
        fun extractValue(from: Int): String {
            var i = t.indexOf(':', from) + 1
            while (i < t.length && t[i].isWhitespace()) i++
            require(i < t.length) { "BAD_DECRYPT: truncated payload" }
            val start = i
            var depthBrace = 0; var depthBrack = 0; var inStr = false; var esc = false
            while (i < t.length) {
                val c = t[i]
                if (inStr) {
                    if (esc) esc = false
                    else if (c == '\\') esc = true
                    else if (c == '"') inStr = false
                } else {
                    when (c) {
                        '"' -> inStr = true
                        '{' -> depthBrace++
                        '}' -> {
                            if (depthBrace == 0 && depthBrack == 0) {
                                // end of the top-level object: only happens for the last value
                                return t.substring(start, i).trim()
                            }
                            depthBrace--
                        }
                        '[' -> depthBrack++
                        ']' -> depthBrack--
                        ',' -> if (depthBrace == 0 && depthBrack == 0) return t.substring(start, i).trim()
                    }
                }
                i++
            }
            return t.substring(start).trim().trimEnd('}')
        }
        val configJson = extractValue(ci)
        val secretsJson = extractValue(si)
        return configJson to secretsJson
    }

    private fun ByteArray.toHex(): String {
        val h = "0123456789abcdef"
        val sb = StringBuilder(size * 2)
        for (b in this) {
            sb.append(h[(b.toInt() shr 4) and 0xF]).append(h[b.toInt() and 0xF])
        }
        return sb.toString()
    }

    private fun String.hexToBytes(): ByteArray {
        val s = trim()
        require(s.length % 2 == 0 && s.isNotEmpty()) { "BAD_DECRYPT: bad hex" }
        return ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }
}
