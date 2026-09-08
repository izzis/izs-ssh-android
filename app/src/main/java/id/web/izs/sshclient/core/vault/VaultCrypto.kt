package id.web.izs.sshclient.core.vault

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import id.web.izs.sshclient.core.config.StoredVault

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
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val enc = cipher.doFinal(plain)
        return StoredVault(
            version = VERSION,
            contents = java.util.Base64.getEncoder().encodeToString(enc),
            keySalt = salt.toHex(),
            iv = iv.toHex(),
        )
    }

    /** @return raw Pair(configJson, secretsJson). */
    fun decrypt(vault: StoredVault, passphrase: String): Pair<String, String> {
        if (vault.version != VERSION) throw UnsupportedVersionException("Unsupported vault version ${vault.version}")
        val salt = vault.keySalt.hexToBytes()
        val iv = vault.iv.hexToBytes()
        val enc = try {
            java.util.Base64.getMimeDecoder().decode(vault.contents)
        } catch (e: Exception) {
            throw BadDecryptException("BAD_DECRYPT: corrupt base64")
        }
        val key = deriveKey(passphrase, salt)
        val plain: ByteArray = try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            cipher.doFinal(enc)
        } catch (e: Exception) {
            throw BadDecryptException("BAD_DECRYPT: incorrect passphrase or corrupt vault")
        }
        val text = plain.toString(Charsets.UTF_8)
        // Payload = {"config":{...},"secrets":[...]} — split at top level without a full JSON parser.
        return splitVaultPayload(text)
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
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, ITERATIONS, 256)
        return try {
            val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
            f.generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
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
