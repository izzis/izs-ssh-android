package id.web.izs.sshclient.core.vault

import id.web.izs.sshclient.core.config.RawConfigStore
import id.web.izs.sshclient.core.config.VaultSecret

/**
 * Pure vault-state resolution (desktop vault.service.ts + config.service.ts parity).
 *
 * Desktop truth: when a vault blob is present it is ALWAYS the secrets
 * container, regardless of the `encrypted` file-shape flag. The flag only
 * decides whether the file on disk is the {vault, encrypted, configSync}
 * shell (toggle ON) or the full document with the blob inline (toggle OFF).
 * Unchecking "Encrypt config file" must therefore never drop the secrets:
 * locked or unlocked, `vault://` references keep resolving against the blob,
 * and the passphrase is still required whenever the app opens the vault
 * (edit-profile show-password, first connect).
 *
 * The previous mobile logic treated `encrypted=false` as "no vault"
 * (secrets=null, no prompt) — unchecking the toggle made every vault
 * content inaccessible, with no way back except re-enabling encryption.
 */
object VaultState {

    data class View(
        /** Document the domain view (profiles/groups) is parsed from. */
        val domainDoc: LinkedHashMap<String, Any?>,
        /** Plaintext secrets (RAM only). Null when locked or vault-less. */
        val secrets: List<VaultSecret>?,
        /** True when a vault exists but its secrets are unavailable. */
        val needsPassphrase: Boolean,
        /** Live store for the Config file tab (desktop readRaw parity). */
        val store: LinkedHashMap<String, Any?>,
        /**
         * True only when the listing itself is unusable without the
         * passphrase (locked encrypted shell). A locked plaintext-with-blob
         * config lists fine — the passphrase is asked lazily at point of
         * use (show-password, secret edit, first connect), never at boot.
         */
        val unlockRequired: Boolean = false,
        /** Caller must forget its remembered passphrase (went stale). */
        val stalePassphrase: Boolean = false,
    )

    /**
     * @param passphrase remembered session passphrase, null when locked.
     * @param forgiveStale when true, a wrong remembered passphrase locks
     * instead of throwing (desktop vault.decrypt catch -> forgetPassphrase).
     * @throws VaultCrypto.BadDecryptException on a wrong passphrase when
     * not forgiving (explicit unlock attempts surface Retry/Delete/Cancel).
     */
    fun resolve(
        raw: LinkedHashMap<String, Any?>,
        passphrase: String?,
        forgiveStale: Boolean = false,
    ): View {
        val encrypted = RawConfigStore.isEncrypted(raw)
        val vault = RawConfigStore.storedVault(raw)
        if (vault == null || vault.contents.isBlank()) {
            // No vault: plaintext. A present-but-empty blob is corrupt —
            // offer the Retry/Delete path instead of silently ignoring it.
            val corrupt = vault != null
            return View(raw, null, needsPassphrase = corrupt, raw, unlockRequired = corrupt && encrypted)
        }
        if (passphrase == null) {
            // Locked: the blob stays the container; profiles remain visible
            // when the outer doc is plaintext, secret access is gated.
            return View(raw, null, needsPassphrase = true, raw, unlockRequired = encrypted)
        }
        val (configJson, secretsJson) = try {
            VaultCrypto.decrypt(vault, passphrase)
        } catch (e: VaultCrypto.BadDecryptException) {
            if (!forgiveStale) throw e
            return View(raw, null, needsPassphrase = true, raw, stalePassphrase = true, unlockRequired = encrypted)
        }
        val secrets = parseSecretsJson(secretsJson)
        if (!encrypted) {
            // Plaintext with a blob (toggle OFF): the outer doc already is
            // the full config; secrets resolve against the inline blob.
            return View(raw, secrets, needsPassphrase = false, raw)
        }
        // Encrypted shell: domain + live store come from the decrypted blob
        // config merged with the outer vault/configSync.
        val decryptedRaw = RawConfigStore.loadRaw(RawConfigStore.yamlFromJson(configJson))
        val merged = LinkedHashMap<String, Any?>(decryptedRaw)
        merged[RawConfigStore.KEY_VAULT] = mapOf(
            "version" to vault.version,
            "contents" to vault.contents,
            "keySalt" to vault.keySalt,
            "iv" to vault.iv,
        )
        merged[RawConfigStore.KEY_ENCRYPTED] = true
        (raw[RawConfigStore.KEY_CONFIG_SYNC] as? Map<String, Any?>)?.let {
            merged[RawConfigStore.KEY_CONFIG_SYNC] = it
        }
        return View(merged, secrets, needsPassphrase = false, merged)
    }

    fun parseSecretsJson(secretsJson: String): List<VaultSecret> {        val t = secretsJson.trim()
        if (t.isEmpty() || t == "null" || t == "[]") return emptyList()
        return try {
            val arr = kotlinx.serialization.json.Json.parseToJsonElement(t)
                as? kotlinx.serialization.json.JsonArray ?: return emptyList()
            arr.mapNotNull { e ->
                try {
                    val o = e as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                    val keyObj = o["key"] as? kotlinx.serialization.json.JsonObject
                    VaultSecret(
                        type = o["type"]?.toString()?.trim('"') ?: "",
                        key = keyObj?.mapValues { (_, v) ->
                            v.toString().trim('"').takeIf { it != "null" }
                        } ?: emptyMap(),
                        value = o["value"]?.toString()?.trim('"') ?: "",
                    )
                } catch (_: Exception) { null }
            }
        } catch (_: Exception) { emptyList() }
    }

    /**
     * Inverse of [parseSecretsJson]: secrets edited in the profile editor
     * are re-serialized for the vault payload (desktop JSON.stringify parity
     * via [RawConfigStore.toJson]). Key values stay strings; the resolver
     * treats a missing port as 22, so byte-shape may differ from desktop
     * for hand-made blobs without changing resolution.
     */
    fun secretsToJson(secrets: List<VaultSecret>): String =
        RawConfigStore.toJson(
            secrets.map { s ->
                linkedMapOf<String, Any?>(
                    "type" to s.type,
                    "key" to LinkedHashMap(s.key),
                    "value" to s.value,
                )
            },
        )
}
