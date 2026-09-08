package id.web.izs.sshclient.core.vault

import id.web.izs.sshclient.core.config.VaultSecret

/**
 * Parity with passwordStorage.service.ts + vault-android.service.ts:
 * - ssh:password key {user,host,port} (port defaults to 22 for matching)
 * - ssh:key-passphrase key {hash}
 * - file key {id,description} value base64(PEM); secure storage holds PEM text
 */
object SecretResolver {
    const val TYPE_PASSWORD = "ssh:password"
    const val TYPE_PASSPHRASE = "ssh:key-passphrase"
    const val TYPE_FILE = "file"
    const val VAULT_PREFIX = "vault://"

    /**
     * Parity with vault.getSecret (vault.service.ts): exact key match first,
     * then one retry with host nulled (host-less default credentials).
     * Never fuzzy-matches another host's/user's secret.
     */
    fun findPassword(secrets: List<VaultSecret>, user: String, host: String, port: Int?): VaultSecret? {
        val p = port ?: 22
        fun portOf(s: VaultSecret) = s.key["port"]?.toIntOrNull() ?: 22
        return secrets.firstOrNull {
            it.type == TYPE_PASSWORD &&
                it.key["user"] == user && it.key["host"] == host && portOf(it) == p
        } ?: secrets.firstOrNull {
            it.type == TYPE_PASSWORD &&
                it.key["user"] == user && it.key["host"] == null && portOf(it) == p
        }
    }

    private fun passwordMatches(s: VaultSecret, user: String, host: String?, port: Int?): Boolean {
        if (s.type != TYPE_PASSWORD || s.key["user"] != user) return false
        val p = port ?: 22
        if ((s.key["port"]?.toIntOrNull() ?: 22) != p) return false
        val h = host?.takeIf { it.isNotBlank() }
        return s.key["host"] == h || (h != null && s.key["host"] == null)
    }

    /**
     * Pure secret-list edits for the profile editor (desktop parity: secrets
     * live in the vault payload, profiles only carry `vault://` file refs).
     * Upsert/remove use the SAME two-tier match as [findPassword], so an
     * edited password keeps resolving for its (user, host, port).
     */
    fun upsertPassword(
        secrets: List<VaultSecret>,
        user: String,
        host: String?,
        port: Int?,
        value: String,
    ): List<VaultSecret> {
        val h = host?.takeIf { it.isNotBlank() }
        val key = linkedMapOf<String, String?>("user" to user)
        if (h != null) key["host"] = h
        if (port != null) key["port"] = port.toString()
        val idx = secrets.indexOfFirst { passwordMatches(it, user, h, port) }
        val entry = VaultSecret(TYPE_PASSWORD, key, value)
        return if (idx < 0) secrets + entry
        else secrets.toMutableList().also { it[idx] = entry }
    }

    fun removePassword(
        secrets: List<VaultSecret>,
        user: String,
        host: String?,
        port: Int?,
    ): List<VaultSecret> {
        val h = host?.takeIf { it.isNotBlank() }
        return secrets.filterNot { s ->
            s.type == TYPE_PASSWORD && s.key["user"] == user &&
                s.key["host"] == h && (s.key["port"]?.toIntOrNull() ?: 22) == (port ?: 22)
        }
    }

    /** Stores a pasted PEM as a vault file secret; returns the new list + its `vault://` ref. */
    fun addFile(
        secrets: List<VaultSecret>,
        pem: String,
        description: String,
    ): Pair<List<VaultSecret>, String> {
        val id = java.util.UUID.randomUUID().toString()
        val entry = VaultSecret(
            TYPE_FILE,
            mapOf("id" to id, "description" to description),
            pemToBase64(pem),
        )
        return (secrets + entry) to (VAULT_PREFIX + id)
    }

    fun removeFile(secrets: List<VaultSecret>, ref: String): List<VaultSecret> {
        if (!ref.startsWith(VAULT_PREFIX)) return secrets
        val id = ref.removePrefix(VAULT_PREFIX)
        return secrets.filterNot { it.type == TYPE_FILE && it.key["id"] == id }
    }

    fun findFilePem(secrets: List<VaultSecret>, ref: String): String? {
        if (!ref.startsWith(VAULT_PREFIX)) return null
        val id = ref.removePrefix(VAULT_PREFIX)
        val s = secrets.firstOrNull { it.type == TYPE_FILE && it.key["id"] == id } ?: return null
        return base64ToPem(s.value)
    }

    fun pemToBase64(pem: String): String {
        val s = pem.trim()
        if (s.isEmpty()) return ""
        if (s.contains("BEGIN") && s.contains("PRIVATE")) {
            // Already PEM: the desktop stores base64(PEM) in the vault
            return try {
                java.util.Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))
            } catch (e: Exception) { "" }
        }
        return s
    }

    fun base64ToPem(b64: String): String {
        val s = b64.trim()
        if (s.isEmpty()) return ""
        if (s.contains("BEGIN") && s.contains("PRIVATE")) return s
        if (s.contains("PuTTY-User-Key-File")) return s
        return try {
            String(java.util.Base64.getMimeDecoder().decode(s), Charsets.UTF_8)
        } catch (e: Exception) { s }
    }
}
