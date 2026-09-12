package id.web.izs.sshclient.core.sync

import id.web.izs.sshclient.core.config.ConfigMigrator
import id.web.izs.sshclient.core.config.RawConfigStore
import id.web.izs.sshclient.core.config.RemoteConfigMeta
import id.web.izs.sshclient.core.config.StoredVault
import id.web.izs.sshclient.core.config.TabbyConfig
import id.web.izs.sshclient.core.config.SshProfile
import id.web.izs.sshclient.core.vault.SecretResolver
import id.web.izs.sshclient.core.vault.VaultCrypto
import id.web.izs.sshclient.core.vault.VaultState
import id.web.izs.sshclient.data.local.ConfigDisk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Orchestrates desktop-parity sync (configSync.service.ts) + vault (config.service.ts).
 *
 * - Disk = raw YAML (lossless). Memory = raw document + session-only passphrase.
 * - Decrypt happens ONLY on: initial load, after download, and secret resolution
 *   for connect. Upload/listing/metadata NEVER need decrypt.
 * - A freshly downloaded/imported encrypted shell is saved verbatim first,
 *   then re-encrypted once with a fresh salt/iv after the passphrase prompt
 *   (desktop writeConfigDataFromSync parity) — immediately when already
 *   unlocked, otherwise on the first unlock. Plaintext docs are never rewritten.
 * - Upload: local raw minus configSync + parts-merge from remote.
 * - Download: remote + local configSync + parts-merge when plaintext.
 *
 * The passphrase is NEVER written to disk; RAM only, for the session.
 */
class SyncRepository(
    private val disk: ConfigDisk,
    private val api: TabbySyncApi = TabbySyncApi(),
) {
    @Volatile private var rememberedPassphrase: String? = null

    /**
     * Desktop `writeConfigDataFromSync` parity flag: a freshly
     * downloaded/imported encrypted shell is saved verbatim first, then
     * re-encrypted once with a fresh salt/iv after the passphrase prompt
     * (`config.load()` -> `config.save()` on desktop). True only between
     * the verbatim save and the first successful unlock (or the immediate
     * rewrite when already unlocked). Ordinary boot unlocks never rewrite.
     */
    @Volatile private var pendingEncryptedRewrite = false

    /**
     * Pre-overwrite disk snapshot taken by [downloadIntoLocal] /
     * [importRawYaml] before the verbatim save. RAM only, never persisted.
     * [abortPendingImport] restores it so cancelling a fresh import whose
     * shell was never unlocked leaves the previous local config untouched
     * instead of erasing everything. Cleared together with the pending flag.
     */
    @Volatile private var preImportBackupYaml: String? = null

    /**
     * Pre-overwrite sync-target snapshot (host/token/configID from YAML >
     * configSync, plus the prefs stamp) taken next to [preImportBackupYaml].
     * [downloadIntoLocal] retargets at the cloud config; abort must put the
     * target back or the restored YAML and the poll target would disagree.
     * Null when the op doesn't retarget (file import keeps the local target).
     */
    @Volatile private var preImportSyncTarget: SyncTarget? = null

    /** Sync-target half of the pre-overwrite snapshot (RAM only). */
    private data class SyncTarget(
        val host: String?,
        val token: String?,
        val configId: Long,
        val lastRemoteChange: String,
    )

    /**
     * Pre-overwrite session passphrase snapshot (RAM only, like
     * [rememberedPassphrase] itself). The old passphrase is forgotten as
     * stale when the fresh blob fails to open with it — but it is still
     * valid for the backup, so [abortPendingImport] restores it: after a
     * cancelled import the previous config comes back already unlocked,
     * exactly the session state from before the download. Cleared together
     * with the pending flag.
     */
    @Volatile private var preImportPassphrase: String? = null

    fun isVaultOpen(): Boolean = rememberedPassphrase != null

    fun rememberPassphrase(p: String) { rememberedPassphrase = p }

    fun forgetPassphrase() { rememberedPassphrase = null }

    data class Loaded(
        val domain: TabbyConfig,
        /** Plaintext secrets from decrypt (RAM only). Null when locked or vault-less. */
        val secrets: List<id.web.izs.sshclient.core.config.VaultSecret>?,
        /**
         * True when a vault blob exists but its secrets are unavailable
         * (locked or corrupt) — the UI must prompt for the passphrase at
         * point of use (show-password, connect), desktop parity.
         */
        val needsPassphrase: Boolean,
        /**
         * True only when the listing itself is blocked (locked encrypted
         * shell) — drives the non-dismissible boot dialog. Locked
         * plaintext-with-blob never blocks: passphrase is asked lazily.
         */
        val unlockRequired: Boolean = false,
        /**
         * Live store parity (desktop ConfigService._store / readRaw): the
         * document the Config file tab shows — outer raw when plaintext or
         * locked, decrypted merged view when unlocked. Secrets stay inside
         * the opaque vault blob, exactly like desktop.
         */
        val store: LinkedHashMap<String, Any?>,
        /**
         * True when this state came from a freshly downloaded/imported
         * encrypted shell whose post-prompt re-encrypt is still pending.
         * The unlock UI uses it to offer "cancel the import" (restore the
         * pre-overwrite backup) instead of "erase the local config".
         */
        val pendingRewrite: Boolean = false,
    )

    suspend fun loadLocal(): Loaded = withContext(Dispatchers.IO) {
        var yamlStr = disk.loadYaml()
        if (yamlStr.isNullOrBlank()) {
            // A fresh install owns an empty local config, so the app is usable
            // (add profiles) without ever touching Config Sync. Seeded to
            // disk once — later loads see a real document.
            // Fresh installs are authored at the desktop LATEST: modern shapes
            // only (plural privateKeys, id-based groups), so the desktop
            // migrator stays quiet when the file moves phone -> PC.
            val seed = linkedMapOf<String, Any?>(
                RawConfigStore.KEY_VERSION to ConfigMigrator.LATEST_VERSION,
                RawConfigStore.KEY_PROFILES to mutableListOf<Any?>(),
                RawConfigStore.KEY_GROUPS to mutableListOf<Any?>(),
                RawConfigStore.KEY_CONFIG_SYNC to LinkedHashMap<String, Any?>(),
            )
            disk.saveYaml(RawConfigStore.dumpRaw(seed))
            yamlStr = disk.loadYaml()
        }
        val raw = if (yamlStr.isNullOrBlank()) {
            linkedMapOf<String, Any?>(
                RawConfigStore.KEY_VERSION to ConfigMigrator.LATEST_VERSION,
                RawConfigStore.KEY_PROFILES to mutableListOf<Any?>(),
                RawConfigStore.KEY_GROUPS to mutableListOf<Any?>(),
            )
        } else {
            RawConfigStore.loadRaw(yamlStr)
        }
        // Android-authored plaintext docs carry modern shapes, so stamp any
        // stale version up to LATEST (one rewrite for pre-v8 installs).
        // Encrypted shells are untouched: their outer shape
        // {vault, encrypted, configSync} carries no version by desktop parity.
        if (!RawConfigStore.isEncrypted(raw)) {
            val v = (raw[RawConfigStore.KEY_VERSION] as? Number)?.toInt() ?: 0
            if (v < ConfigMigrator.LATEST_VERSION) {
                raw[RawConfigStore.KEY_VERSION] = ConfigMigrator.LATEST_VERSION
                disk.saveYaml(RawConfigStore.dumpRaw(raw))
            }
        }
        // Alpha cleanup: a prefs-stored sync target from older builds is
        // dropped (never adopted) — YAML > configSync is the only source.
        disk.dropLegacySyncTarget()
        decryptToLoaded(raw, forgiveStaleRemembered = true)
    }

    /**
     * Persist the sync target ("Test and save"): writes YAML > configSync
     * only — the single source of truth. Never touches separate prefs.
     */
    suspend fun setSyncTarget(hostRaw: String, token: String): Loaded = withContext(Dispatchers.IO) {
        val host = RawConfigStore.normalizeHost(hostRaw)
        require(token.isNotBlank()) { "Sync token is empty" }
        val yamlStr = disk.loadYaml() ?: throw IllegalStateException("No local config")
        val raw = RawConfigStore.loadRaw(yamlStr)
        val cur = RawConfigStore.syncTargetOf(raw)
        RawConfigStore.setSyncTarget(
            raw,
            RawConfigStore.RawSyncTarget(host, token, if (cur.configId >= 0) cur.configId else -1L),
        )
        disk.saveYaml(RawConfigStore.dumpRaw(raw))
        decryptToLoaded(raw)
    }

    suspend fun unlockWithPassphrase(passphrase: String): Loaded = withContext(Dispatchers.IO) {
        val yamlStr = disk.loadYaml() ?: throw IllegalStateException("No local config")
        val raw = RawConfigStore.loadRaw(yamlStr)
        val vault = RawConfigStore.storedVault(raw)
            ?: throw IllegalStateException("Vault is not configured")
        // Throws BadDecrypt on a wrong passphrase -> UI shows Retry/Delete/Cancel.
        // Reuse the payload for a pending rewrite so the blob is decrypted once.
        val (configJson, secretsJson) = VaultCrypto.decrypt(vault, passphrase)
        rememberPassphrase(passphrase)
        // Desktop writeConfigDataFromSync parity (config.load() -> config.save()):
        // a freshly downloaded/imported encrypted shell is re-encrypted once
        // with a fresh salt/iv after the prompt. Plaintext-with-blob configs
        // and ordinary boot unlocks keep the verbatim blob (desktop
        // maybeEncryptConfig early-returns when encrypted=false; boot load()
        // never re-saves).
        if (pendingEncryptedRewrite) {
            if (RawConfigStore.isEncrypted(raw)) {
                val out = reencryptShellDoc(raw, configJson, secretsJson, passphrase)
                disk.saveYaml(RawConfigStore.dumpRaw(out))
                settlePendingRewrite()
                return@withContext decryptToLoaded(out)
            }
            // Stale flag (e.g. an encrypted download followed by a plaintext
            // import before unlock): nothing to rewrite.
            settlePendingRewrite()
        }
        // Rebuild the loaded state from the existing raw (vault stays a blob on disk)
        decryptToLoaded(raw)
    }

    suspend fun testConnection(hostRaw: String, token: String): String = withContext(Dispatchers.IO) {
        val host = RawConfigStore.normalizeHost(hostRaw)
        require(token.isNotBlank()) { "Sync token is empty" }
        api.getUser(host, token)
    }

    /**
     * Applies a mutation to the local RAW document (mobile settings toggles)
     * and persists it losslessly. Plaintext configs only: when the store is
     * encrypted the domain view comes from the vault blob, so editing the
     * outer document would neither take effect nor survive upload parity.
     */
    suspend fun updateLocalRaw(transform: (LinkedHashMap<String, Any?>) -> Unit): Loaded =
        withContext(Dispatchers.IO) {
            val yamlStr = disk.loadYaml() ?: throw IllegalStateException("No local config")
            val raw = RawConfigStore.loadRaw(yamlStr)
            require(!RawConfigStore.isEncrypted(raw)) {
                "Encrypted configs are edited on desktop (the vault blob owns these keys)"
            }
            transform(raw)
            disk.saveYaml(RawConfigStore.dumpRaw(raw))
            decryptToLoaded(raw)
        }

    /**
     * Persists an accepted host key to `ssh.knownHosts` (desktop format, the
     * single source of trust). Plaintext: local RAW edit. Encrypted shell:
     * the vault blob is rewritten — needs the passphrase in RAM, otherwise
     * throws "Vault is locked" and the caller keeps session-only trust.
     * Server upload follows the normal Upload/auto path (never on accept).
     */
    @Suppress("UNCHECKED_CAST") // dynamic YAML maps: keys are strings by construction
    suspend fun appendKnownHost(entry: id.web.izs.sshclient.core.config.KnownHostEntry): Loaded =
        withContext(Dispatchers.IO) {
            val yamlStr = disk.loadYaml() ?: throw IllegalStateException("No local config")
            val raw = RawConfigStore.loadRaw(yamlStr)
            if (!RawConfigStore.isEncrypted(raw)) {
                return@withContext updateLocalRaw { doc ->
                    RawConfigStore.appendKnownHost(doc, entry)
                }
            }
            val pass = rememberedPassphrase ?: throw IllegalStateException("Vault is locked")
            val vault = RawConfigStore.storedVault(raw)
                ?: throw IllegalStateException("Vault is not configured")
            val (configJson, secretsJson) = VaultCrypto.decrypt(vault, pass)
            val blobConfig = RawConfigStore.loadRaw(RawConfigStore.yamlFromJson(configJson))
            RawConfigStore.appendKnownHost(blobConfig, entry)
            val stored = VaultCrypto.encrypt(RawConfigStore.toJson(blobConfig), secretsJson, pass)
            val out: LinkedHashMap<String, Any?> = linkedMapOf(
                RawConfigStore.KEY_VAULT to RawConfigStore.storedVaultMap(stored),
                RawConfigStore.KEY_ENCRYPTED to true,
            )
            (raw[RawConfigStore.KEY_CONFIG_SYNC] as? Map<String, Any?>)?.let {
                out[RawConfigStore.KEY_CONFIG_SYNC] = it
            }
            disk.saveYaml(RawConfigStore.dumpRaw(out))
            decryptToLoaded(out)
        }

    /**
     * Settings > SSH on any store (desktop vault parity): flips
     * ssh.verifyHostKeys / ssh.warnOnClose. Plaintext configs delegate to
     * [updateLocalRaw]; encrypted ones are edited inside the vault blob's
     * config payload — same decrypt/edit/re-encrypt round-trip (and same
     * outer-document shape) as [appendKnownHost]. Requires an unlocked
     * vault; the UI prompts for the passphrase first. Server upload follows
     * the normal Upload/auto path (never here).
     */
    suspend fun updateEncryptedSsh(verify: Boolean, warn: Boolean): Loaded =
        withContext(Dispatchers.IO) {
            val yamlStr = disk.loadYaml() ?: throw IllegalStateException("No local config")
            val raw = RawConfigStore.loadRaw(yamlStr)
            if (!RawConfigStore.isEncrypted(raw)) {
                return@withContext updateLocalRaw { doc ->
                    RawConfigStore.setSshFlags(doc, verify, warn)
                }
            }
            val pass = rememberedPassphrase ?: throw IllegalStateException("Vault is locked")
            val vault = RawConfigStore.storedVault(raw)
                ?: throw IllegalStateException("Vault is not configured")
            val (configJson, secretsJson) = VaultCrypto.decrypt(vault, pass)
            val blobConfig = RawConfigStore.loadRaw(RawConfigStore.yamlFromJson(configJson))
            RawConfigStore.setSshFlags(blobConfig, verify, warn)
            val stored = VaultCrypto.encrypt(RawConfigStore.toJson(blobConfig), secretsJson, pass)
            val out: LinkedHashMap<String, Any?> = linkedMapOf(
                RawConfigStore.KEY_VAULT to RawConfigStore.storedVaultMap(stored),
                RawConfigStore.KEY_ENCRYPTED to true,
            )
            // `is Map<*, *>` instead of `as? Map<String, Any?>`: checkable,
            // so no unchecked-cast warning and no @Suppress needed.
            val carriedSync = raw[RawConfigStore.KEY_CONFIG_SYNC]
            if (carriedSync is Map<*, *>) {
                out[RawConfigStore.KEY_CONFIG_SYNC] = carriedSync
            }
            disk.saveYaml(RawConfigStore.dumpRaw(out))
            decryptToLoaded(out)
        }

    /**
     * Self-healing legacy upgrade: a prefs-era trust line that matched this
     * session is promoted to a YAML entry and the legacy line retired.
     * Throws when locked (caller keeps session-only trust) — same as above.
     */
    suspend fun persistTrustUpgrade(
        entry: id.web.izs.sshclient.core.config.KnownHostEntry,
        legacyLine: String,
    ): Loaded = withContext(Dispatchers.IO) {
        val loaded = appendKnownHost(entry)
        try {
            val arr = org.json.JSONArray(disk.loadKnownHostsJson() ?: "[]")
            val kept = org.json.JSONArray()
            for (i in 0 until arr.length()) {
                val s = arr.optString(i)
                if (s != legacyLine) kept.put(s)
            }
            disk.saveKnownHostsJson(kept.toString())
        } catch (_: Exception) { }
        loaded
    }

    suspend fun listRemote(hostRaw: String, token: String): List<RemoteConfigMeta> =
        withContext(Dispatchers.IO) {
            val host = RawConfigStore.normalizeHost(hostRaw)
            api.getConfigs(host, token)
        }

    /**
     * Parity with uploadAndSync: set the local configID, upload, refresh lastRemoteChange.
     * UI confirmation for remote overwrite is required when the configID changes.
     */
    suspend fun uploadAsCurrent(
        hostRaw: String,
        token: String,
        configId: Long,
        appVersion: String,
    ): String = withContext(Dispatchers.IO) {
        val host = RawConfigStore.normalizeHost(hostRaw)
        val yamlStr = disk.loadYaml() ?: throw IllegalStateException("No local config to upload")
        val localRaw = RawConfigStore.loadRaw(yamlStr)
        val parts = readParts(localRaw)
        // Fetch remote for the parts-merge (when any part is disabled)
        val remoteRaw = try {
            val remote = api.getConfig(host, token, configId)
            if (remote.content.isBlank()) null
            else RawConfigStore.loadRaw(remote.content)
        } catch (e: Exception) {
            // New/empty remote (createNewConfig result): no merge
            null
        }
        val uploadDoc = RawConfigStore.buildUploadDoc(localRaw, remoteRaw, parts)
        val content = RawConfigStore.dumpRaw(uploadDoc)
        api.updateConfig(host, token, configId, content, appVersion)
        // Desktop setConfig parity: uploadAndSync calls setConfig(cfg) BEFORE
        // upload(), so uploading to a different cloud config moves the local
        // subscription to it. Without this, autosync would poll the old ID
        // with the new stamp (spurious download / missed change). Host/token
        // already live in YAML (written by "Test and save"); only the ID
        // moves here, and only across IDs — same-ID re-upload is a no-op.
        val cur = RawConfigStore.syncTargetOf(localRaw)
        if (cur.configId != configId) {
            RawConfigStore.setSyncTarget(
                localRaw,
                RawConfigStore.RawSyncTarget(cur.host, cur.token, configId),
            )
            disk.saveYaml(RawConfigStore.dumpRaw(localRaw))
        }
        // Refresh the stamp so autosync does not treat this as a new change.
        val meta = api.getConfig(host, token, configId)
        disk.lastRemoteChange = meta.modifiedAt
        meta.modifiedAt
    }

    suspend fun createRemote(hostRaw: String, token: String, name: String): RemoteConfigMeta =
        withContext(Dispatchers.IO) {
            val host = RawConfigStore.normalizeHost(hostRaw)
            api.createConfig(host, token, name)
        }

    /**
     * Parity with downloadAndSync: GET content -> merge (local configSync preserved)
     * -> save raw -> load (may need a passphrase when encrypted).
     * UI confirmation for local overwrite is required before calling this.
     *
     * Desktop `writeConfigDataFromSync` parity: the merged document is saved
     * verbatim first. When it is an encrypted shell, the blob is then
     * re-encrypted once with a fresh salt/iv (desktop `config.save()` after
     * `config.load()`): immediately when the vault is already open with the
     * right passphrase, otherwise deferred via [pendingEncryptedRewrite]
     * until the first successful [unlockWithPassphrase]. Background callers
     * ([autoSyncTick]) therefore never prompt.
     */
    suspend fun downloadIntoLocal(
        hostRaw: String,
        token: String,
        configId: Long,
    ): Loaded = withContext(Dispatchers.IO) {
        val host = RawConfigStore.normalizeHost(hostRaw)
        val remote = api.getConfig(host, token, configId)
        require(remote.content.isNotBlank()) { "Remote config is empty (no YAML content)" }
        val remoteRaw = RawConfigStore.loadRaw(remote.content)
        val localYaml = disk.loadYaml()
        val localRaw: LinkedHashMap<String, Any?> = if (localYaml.isNullOrBlank()) {
            LinkedHashMap<String, Any?>(
                mapOf(RawConfigStore.KEY_CONFIG_SYNC to LinkedHashMap<String, Any?>()),
            )
        } else {
            RawConfigStore.loadRaw(localYaml)
        }
        val parts = readParts(localRaw)
        val merged = RawConfigStore.mergeDownload(remoteRaw, localRaw, parts)
        // Snapshot the PRE-download target first (abort restores it): the
        // merged doc below retargets at the cloud config.
        val preTarget = RawConfigStore.syncTargetOf(localRaw)
        preImportSyncTarget = SyncTarget(preTarget.host, preTarget.token, preTarget.configId, disk.lastRemoteChange)
        // Disk-persisted twin of the RAM snapshot (beta-blockers #2/#3):
        // abort must survive process death, and autosync ticks (which funnel
        // through here) need an undo even when unlocked. Skipped while a
        // previous fresh shell still awaits its first unlock, so a chained
        // overwrite can't orphan the original — abort then restores the
        // oldest generation.
        if (!pendingEncryptedRewrite && !localYaml.isNullOrBlank()) {
            disk.savePreImport(localYaml, disk.lastRemoteChange)
        }
        // Make sure the local configSync points at the freshly downloaded config
        @Suppress("UNCHECKED_CAST")
        val cs = (merged[RawConfigStore.KEY_CONFIG_SYNC] as? LinkedHashMap<String, Any?>)
            ?: linkedMapOf<String, Any?>().also { merged[RawConfigStore.KEY_CONFIG_SYNC] = it }
        cs["host"] = host
        cs["token"] = token
        cs["configID"] = configId
        disk.lastRemoteChange = remote.modifiedAt
        // Snapshot for abortPendingImport(): cancelling before the first
        // unlock must just fail the import (restore this), not erase local.
        preImportBackupYaml = localYaml
        preImportPassphrase = rememberedPassphrase
        disk.saveYaml(RawConfigStore.dumpRaw(merged))
        maybeReencryptFreshShell(merged)
    }

    /**
     * Settings > Config file > Import: replace the local config with pasted
     * full YAML. Validated strictly first ([RawConfigStore.parseImport]), so
     * a bad paste never touches disk.
     *
     * Sync-target parity with [downloadIntoLocal] (data.configSync is always
     * the local one): the local configSync section is kept, a pasted one is
     * dropped — importing someone else's host/token must never hijack sync.
     * A stale remembered passphrase is forgiven: the imported vault (if any)
     * belongs to another passphrase until the user unlocks it.
     *
     * Like [downloadIntoLocal], an imported encrypted shell is re-encrypted
     * once with a fresh salt/iv after the prompt (immediately when already
     * unlocked, otherwise on the first [unlockWithPassphrase]).
     */
    @Suppress("UNCHECKED_CAST") // dynamic YAML maps: keys are strings by construction
    suspend fun importRawYaml(text: String): Loaded = withContext(Dispatchers.IO) {
        val doc = RawConfigStore.parseImport(text)
        val localYaml = disk.loadYaml()
        val localSync = if (localYaml.isNullOrBlank()) null
        else (RawConfigStore.loadRaw(localYaml)[RawConfigStore.KEY_CONFIG_SYNC] as? Map<String, Any?>)
        if (localSync != null) doc[RawConfigStore.KEY_CONFIG_SYNC] = localSync
        else doc.remove(RawConfigStore.KEY_CONFIG_SYNC)
        if (!doc.containsKey(RawConfigStore.KEY_VERSION)) doc[RawConfigStore.KEY_VERSION] = ConfigMigrator.LATEST_VERSION
        // Snapshot for abortPendingImport(): cancelling before the first
        // unlock must just fail the import (restore this), not erase local.
        // File import never retargets sync (local configSync is kept above),
        // so snapshotting the current target makes its restore a no-op (and
        // supersedes any older one).
        preImportBackupYaml = localYaml
        if (!localYaml.isNullOrBlank()) {
            val preTarget = RawConfigStore.syncTargetOf(RawConfigStore.loadRaw(localYaml))
            preImportSyncTarget =
                SyncTarget(preTarget.host, preTarget.token, preTarget.configId, disk.lastRemoteChange)
        } else {
            preImportSyncTarget = null
        }
        preImportPassphrase = rememberedPassphrase
        // Disk twin of the snapshot (same beta-blocker rationale as in
        // downloadIntoLocal above); file import keeps the local target, so
        // only the YAML + stamp need persisting.
        if (!pendingEncryptedRewrite && !localYaml.isNullOrBlank()) {
            disk.savePreImport(localYaml, disk.lastRemoteChange)
        }
        disk.saveYaml(RawConfigStore.dumpRaw(doc))
        maybeReencryptFreshShell(doc)
    }

    /**
     * Cancel a fresh download/import whose encrypted shell was never
     * unlocked: restore the pre-overwrite disk snapshot so the failed
     * import leaves the previous local config untouched instead of
     * erasing everything. The pre-overwrite session passphrase is
     * restored too, so the previous config comes back unlocked exactly
     * as before the download — no re-typing needed.
     *
     * When no import is pending this is a no-op returning the current
     * disk state. When there was never a previous config (blank backup,
     * e.g. first download on a fresh install), the downloaded doc stays
     * on disk still locked — the user can retry the passphrase or erase it.
     */
    suspend fun abortPendingImport(): Loaded = withContext(Dispatchers.IO) {
        val ramBackup = preImportBackupYaml
        val target = preImportSyncTarget
        val pass = preImportPassphrase
        settlePendingRewrite()
        // The backup YAML carries the pre-import configSync, so restoring it
        // restores the target too. Only the prefs stamp needs explicit care.
        if (target != null) {
            disk.lastRemoteChange = target.lastRemoteChange
        }
        if (!pass.isNullOrEmpty()) rememberPassphrase(pass)
        val source = pickRestoreSource(ramBackup, disk.loadPreImportYaml())
        if (source.isNullOrBlank()) return@withContext loadLocal()
        if (ramBackup.isNullOrBlank()) {
            // RAM snapshot lost (process death): the persisted copy + its
            // stamp stand in. The session passphrase is unrestorable by
            // design (never written to disk) — the restored config simply
            // unlocks on demand.
            disk.lastRemoteChange = disk.loadPreImportStamp()
        }
        disk.saveYaml(source)
        decryptToLoaded(RawConfigStore.loadRaw(source), forgiveStaleRemembered = true)
    }

    /**
     * Explicit undo of the last download/import/autosync overwrite
     * (autosync safety net, beta-blocker #2): restores the persisted
     * pre-overwrite copy + stamp. The snapshot is kept so undo is
     * repeatable; the next overwrite supersedes it. The session passphrase
     * is unrestorable by design (never written to disk).
     */
    suspend fun restorePreImport(): Loaded = withContext(Dispatchers.IO) {
        val snap = disk.loadPreImportYaml()
            ?: throw IllegalStateException("No pre-overwrite snapshot available")
        disk.lastRemoteChange = disk.loadPreImportStamp()
        disk.saveYaml(snap)
        decryptToLoaded(RawConfigStore.loadRaw(snap), forgiveStaleRemembered = true)
    }

    suspend fun deleteRemote(hostRaw: String, token: String, configId: Long) =
        withContext(Dispatchers.IO) {
            val host = RawConfigStore.normalizeHost(hostRaw)
            api.deleteConfig(host, token, configId)
        }

    /**
     * Autosync parity: check lightweight metadata (list), download only when
     * modified_at changed. Skip while the vault is locked (never prompt in background).
     * @return config name when a download happened, else null.
     */
    suspend fun autoSyncTick(): String? = withContext(Dispatchers.IO) {
        if (!disk.auto) return@withContext null
        val localYaml = disk.loadYaml()
        if (localYaml.isNullOrBlank()) return@withContext null
        val localRaw = RawConfigStore.loadRaw(localYaml)
        // Target from YAML (single source, readable while locked).
        val target = RawConfigStore.syncTargetOf(localRaw)
        val host = target.host
        val token = target.token
        val id = target.configId
        if (host.isNullOrBlank() || token.isNullOrBlank() || id < 0) return@withContext null
        if (RawConfigStore.isEncrypted(localRaw) && !isVaultOpen()) return@withContext null
        val list = try {
            api.getConfigs(host, token)
        } catch (e: Exception) {
            return@withContext null
        }
        val sel = list.find { it.id == id } ?: return@withContext null
        if (sel.modifiedAt.isNotBlank() && sel.modifiedAt == disk.lastRemoteChange) {
            return@withContext null
        }
        downloadIntoLocal(host, token, id)
        disk.lastRemoteChange = sel.modifiedAt
        sel.name
    }

    // ---- vault management (desktop vaultSettingsTab parity) ----

    /**
     * Parity with vault.setEnabled(true): create an (initially empty) vault.
     * The plaintext config is untouched; only the vault blob is added.
     * The passphrase is remembered for the session (mobile unlock-model parity).
     *
     * Mobile sweep (desktop never rests passwords inline — keytar/vault only):
     * every inline plaintext `options.password` is moved into the new vault
     * as an ssh:password secret and stripped from the YAML, so enabling the
     * master passphrase never leaves a duplicated secret in one file.
     */
    @Suppress("UNCHECKED_CAST") // dynamic YAML maps: keys are strings by construction
    suspend fun setVaultPassphrase(passphrase: String): Loaded = withContext(Dispatchers.IO) {
        require(passphrase.isNotBlank()) { "Passphrase is empty" }
        val yamlStr = disk.loadYaml() ?: throw IllegalStateException("No local config")
        val raw = RawConfigStore.loadRaw(yamlStr)
        require(RawConfigStore.storedVault(raw) == null) { "Vault is already configured" }
        require(!RawConfigStore.isEncrypted(raw)) { "Config is already encrypted" }
        var secrets = emptyList<id.web.izs.sshclient.core.config.VaultSecret>()
        @Suppress("UNCHECKED_CAST")
        val profiles = (raw[RawConfigStore.KEY_PROFILES] as? List<*>) ?: emptyList<Any>()
        raw[RawConfigStore.KEY_PROFILES] = profiles.map { pm ->
            val map = pm as? Map<String, Any?> ?: return@map pm
            var cur: Map<String, Any?> = map
            RawConfigStore.inlinePasswordOf(cur)?.let { inline ->
                secrets = SecretResolver.upsertPassword(
                    secrets, inline.user, inline.host, inline.port, inline.value,
                )
                cur = RawConfigStore.withoutInlinePassword(cur)
            }
            // Same treatment for pasted key material (paths untouched).
            val pems = RawConfigStore.inlineKeyPems(cur)
            if (pems.isNotEmpty()) {
                val name = cur["name"]?.toString() ?: "profile"
                val refs = RawConfigStore.privateKeyRefs(cur).map { r ->
                    if (r in pems) {
                        val (next, ref) = SecretResolver.addFile(secrets, r, "migrated key ($name)")
                        secrets = next
                        ref
                    } else r
                }
                cur = RawConfigStore.withPrivateKeys(cur, refs)
            }
            cur
        }
        val stored = VaultCrypto.encrypt("{}", VaultState.secretsToJson(secrets), passphrase)
        raw[RawConfigStore.KEY_VAULT] = RawConfigStore.storedVaultMap(stored)
        disk.saveYaml(RawConfigStore.dumpRaw(raw))
        rememberPassphrase(passphrase)
        decryptToLoaded(raw)
    }

    /**
     * Parity with vaultSettingsTab.changePassphrase: re-encrypt the SAME
     * payload (config + secrets byte-identical) under a new passphrase
     * (fresh salt/iv, like desktop encryptVault). Requires an unlocked vault;
     * the UI prompts for the current passphrase first (desktop load-then-save).
     */
    suspend fun changeVaultPassphrase(newPassphrase: String): Loaded = withContext(Dispatchers.IO) {
        require(newPassphrase.isNotBlank()) { "Passphrase is empty" }
        val yamlStr = disk.loadYaml() ?: throw IllegalStateException("No local config")
        val raw = RawConfigStore.loadRaw(yamlStr)
        val vault = RawConfigStore.storedVault(raw)
            ?: throw IllegalStateException("Vault is not configured")
        val current = rememberedPassphrase
            ?: throw IllegalStateException("Vault is locked")
        val (configJson, secretsJson) = VaultCrypto.decrypt(vault, current)
        val stored = VaultCrypto.encrypt(configJson, secretsJson, newPassphrase)
        raw[RawConfigStore.KEY_VAULT] = RawConfigStore.storedVaultMap(stored)
        disk.saveYaml(RawConfigStore.dumpRaw(raw))
        rememberPassphrase(newPassphrase)
        decryptToLoaded(raw)
    }

    /**
     * Parity with vault.setEnabled(false) ("Erase the Vault").
     * Deviation (documented): desktop leaves the `encrypted` flag dangling,
     * which bricks the file; mobile restores plaintext (blob removed,
     * encryption off) so the config stays loadable.
     */
    @Suppress("UNCHECKED_CAST") // dynamic YAML maps: keys are strings by construction
    suspend fun eraseVault(): Loaded = withContext(Dispatchers.IO) {
        val yamlStr = disk.loadYaml() ?: throw IllegalStateException("No local config")
        val raw = RawConfigStore.loadRaw(yamlStr)
        if (RawConfigStore.storedVault(raw) == null) {
            throw IllegalStateException("Vault is not configured")
        }
        val out: LinkedHashMap<String, Any?>
        if (RawConfigStore.isEncrypted(raw)) {
            val current = rememberedPassphrase
                ?: throw IllegalStateException("Vault is locked")
            val vault = RawConfigStore.storedVault(raw)!!
            val (configJson, _) = VaultCrypto.decrypt(vault, current)
            out = RawConfigStore.loadRaw(RawConfigStore.yamlFromJson(configJson))
            out.remove(RawConfigStore.KEY_VAULT)
            out[RawConfigStore.KEY_ENCRYPTED] = false
            (raw[RawConfigStore.KEY_CONFIG_SYNC] as? Map<String, Any?>)?.let {
                out[RawConfigStore.KEY_CONFIG_SYNC] = it
            }
        } else {
            out = LinkedHashMap(raw)
            out.remove(RawConfigStore.KEY_VAULT)
            out.remove(RawConfigStore.KEY_ENCRYPTED)
        }
        disk.saveYaml(RawConfigStore.dumpRaw(out))
        forgetPassphrase()
        settlePendingRewrite()
        decryptToLoaded(out)
    }

    /**
     * Desktop passwordStorage.savePassword parity (prompt-password modal with
     * remember checked): persist the password that just connected. Vault
     * configs upsert the `ssh:password` secret (re-encrypting the SAME config
     * payload — profile YAML untouched); no-vault configs store the literal
     * in `options.password` (desktop keytar equivalent on a platform without
     * an OS keychain — plaintext configs already store it this way).
     */
    suspend fun savePassword(profile: SshProfile, password: String): Loaded = withContext(Dispatchers.IO) {
        require(password.isNotEmpty()) { "Password is empty" }
        val yamlStr = disk.loadYaml() ?: throw IllegalStateException("No local config")
        val raw = RawConfigStore.loadRaw(yamlStr)
        val vault = RawConfigStore.storedVault(raw)
        val ou = profile.options
        if (vault == null) {
            val out = withPlaintextPassword(raw, profile, password)
            disk.saveYaml(RawConfigStore.dumpRaw(out))
            return@withContext decryptToLoaded(out)
        }
        val pass = rememberedPassphrase ?: throw IllegalStateException("Vault is locked")
        val (configJson, secretsJson) = VaultCrypto.decrypt(vault, pass)
        val secrets = SecretResolver.upsertPassword(
            VaultState.parseSecretsJson(secretsJson), ou.user, ou.host, ou.port, password,
        )
        val out = withVaultSecrets(raw, configJson, secrets, pass)
        disk.saveYaml(RawConfigStore.dumpRaw(out))
        decryptToLoaded(out)
    }

    /**
     * Desktop passwordStorage.deletePassword parity: the password that just
     * failed authentication is forgotten (desktop drops it on total auth
     * failure). Vault configs remove the `ssh:password` secret; no-vault
     * configs drop the plaintext literal. The profile itself is untouched.
     */
    suspend fun deletePassword(profile: SshProfile): Loaded = withContext(Dispatchers.IO) {
        val yamlStr = disk.loadYaml() ?: throw IllegalStateException("No local config")
        val raw = RawConfigStore.loadRaw(yamlStr)
        val vault = RawConfigStore.storedVault(raw)
        val ou = profile.options
        if (vault == null) {
            val out = withPlaintextPassword(raw, profile, null)
            disk.saveYaml(RawConfigStore.dumpRaw(out))
            return@withContext decryptToLoaded(out)
        }
        val pass = rememberedPassphrase ?: throw IllegalStateException("Vault is locked")
        val (configJson, secretsJson) = VaultCrypto.decrypt(vault, pass)
        val secrets = SecretResolver.removePassword(
            VaultState.parseSecretsJson(secretsJson), ou.user, ou.host, ou.port,
        )
        val out = withVaultSecrets(raw, configJson, secrets, pass)
        disk.saveYaml(RawConfigStore.dumpRaw(out))
        decryptToLoaded(out)
    }

    /**
     * Set (or with null, remove) the plaintext `options.password` literal of
     * one profile. No-vault configs only — the vault path never keeps a
     * literal (desktop convention).
     */
    @Suppress("UNCHECKED_CAST") // dynamic YAML maps: keys are strings by construction
    private fun withPlaintextPassword(
        raw: LinkedHashMap<String, Any?>,
        profile: SshProfile,
        password: String?,
    ): LinkedHashMap<String, Any?> {
        val out = LinkedHashMap(raw)
        val profiles = (out[RawConfigStore.KEY_PROFILES] as? List<*>) ?: emptyList<Any>()
        val o = profile.options
        val idx = RawConfigStore.findProfileIndex(
            profiles, profile.id, profile.name, profile.type, o.host, o.user,
        )
        if (idx < 0) throw IllegalStateException("Profile not found")
        val list = profiles.toMutableList()
        val cur = list[idx] as? Map<String, Any?> ?: emptyMap()
        val upd = LinkedHashMap(cur)
        val opts = LinkedHashMap((cur["options"] as? Map<String, Any?>) ?: emptyMap())
        if (password.isNullOrEmpty()) opts.remove("password") else opts["password"] = password
        upd["options"] = opts
        list[idx] = upd
        out[RawConfigStore.KEY_PROFILES] = list
        return out
    }

    /**
     * Rewrite the vault blob with a new secret list, preserving the disk
     * shape of both modes (encrypted shell vs plaintext-with-blob, incl.
     * local configSync on the encrypted path).
     */
    @Suppress("UNCHECKED_CAST") // dynamic YAML maps: keys are strings by construction
    private fun withVaultSecrets(
        raw: LinkedHashMap<String, Any?>,
        configJson: String,
        secrets: List<id.web.izs.sshclient.core.config.VaultSecret>,
        pass: String,
    ): LinkedHashMap<String, Any?> {
        val stored = VaultCrypto.encrypt(configJson, VaultState.secretsToJson(secrets), pass)
        return if (RawConfigStore.isEncrypted(raw)) {
            linkedMapOf<String, Any?>(
                RawConfigStore.KEY_VAULT to RawConfigStore.storedVaultMap(stored),
                RawConfigStore.KEY_ENCRYPTED to true,
            ).also { m ->
                (raw[RawConfigStore.KEY_CONFIG_SYNC] as? Map<String, Any?>)?.let {
                    m[RawConfigStore.KEY_CONFIG_SYNC] = it
                }
            }
        } else {
            LinkedHashMap(raw).also { it[RawConfigStore.KEY_VAULT] = RawConfigStore.storedVaultMap(stored) }
        }
    }

    /**
     * Parity with the desktop "Encrypt config file" toggle
     * (vaultSettingsTab.toggleConfigEncrypted + maybeEncryptConfig).
     *
     * ON produces exactly the desktop disk shape: {vault, encrypted, configSync}.
     * The secrets payload is preserved byte-identical (fresh salt/iv only).
     * OFF restores the full plaintext document and keeps the vault blob
     * (desktop save() writes the merged store, blob included).
     * Both directions require an unlocked vault; the UI prompts first
     * (desktop vault.load() -> getPassphrase() modal parity).
     */
    @Suppress("UNCHECKED_CAST") // dynamic YAML maps: keys are strings by construction
    suspend fun setConfigEncrypted(encrypted: Boolean): Loaded = withContext(Dispatchers.IO) {
        val yamlStr = disk.loadYaml() ?: throw IllegalStateException("No local config")
        val raw = RawConfigStore.loadRaw(yamlStr)
        val current = rememberedPassphrase
            ?: throw IllegalStateException("Vault is locked")
        val out: LinkedHashMap<String, Any?>
        if (encrypted) {
            val vault = RawConfigStore.storedVault(raw)
                ?: throw IllegalStateException("Vault not configured")
            // Desktop maybeEncryptConfig parity: encrypt the LIVE store, never
            // the stripped outer shell (see RawConfigStore.encryptSource).
            val (configJson, secretsJson) = VaultCrypto.decrypt(vault, current)
            val blobConfig = RawConfigStore.loadRaw(RawConfigStore.yamlFromJson(configJson))
            val plain = RawConfigStore.encryptSource(raw, blobConfig)
            val stored = VaultCrypto.encrypt(RawConfigStore.toJson(plain), secretsJson, current)
            out = linkedMapOf(
                RawConfigStore.KEY_VAULT to RawConfigStore.storedVaultMap(stored),
                RawConfigStore.KEY_ENCRYPTED to true,
            )
            (raw[RawConfigStore.KEY_CONFIG_SYNC] as? Map<String, Any?>)?.let {
                out[RawConfigStore.KEY_CONFIG_SYNC] = it
            }
        } else {
            val vault = RawConfigStore.storedVault(raw)
                ?: throw IllegalStateException("Vault is not configured")
            val (configJson, _) = VaultCrypto.decrypt(vault, current)
            out = RawConfigStore.loadRaw(RawConfigStore.yamlFromJson(configJson))
            out[RawConfigStore.KEY_VAULT] = RawConfigStore.storedVaultMap(vault)
            out[RawConfigStore.KEY_ENCRYPTED] = false
            (raw[RawConfigStore.KEY_CONFIG_SYNC] as? Map<String, Any?>)?.let {
                out[RawConfigStore.KEY_CONFIG_SYNC] = it
            }
        }
        disk.saveYaml(RawConfigStore.dumpRaw(out))
        settlePendingRewrite()
        decryptToLoaded(out)
    }

    /**
     * Delete a profile. Secrets are intentionally orphaned, never deleted
     * (a saved password may serve other profiles; desktop parity is
     * profile-only removal). Shell configs require unlock (lazy-unlock:
     * the blob must be re-encrypted, secrets payload passed through
     * byte-identical).
     */
    @Suppress("UNCHECKED_CAST") // dynamic YAML maps: keys are strings by construction
    suspend fun deleteProfile(profileId: String, original: SshProfile): Loaded =
        withContext(Dispatchers.IO) {
            val yamlStr = disk.loadYaml() ?: throw IllegalStateException("No local config")
            val raw = RawConfigStore.loadRaw(yamlStr)
            val encrypted = RawConfigStore.isEncrypted(raw)

        val out: LinkedHashMap<String, Any?>
        if (encrypted) {
                val pass = rememberedPassphrase ?: throw IllegalStateException("Vault is locked")
                val vault = RawConfigStore.storedVault(raw)
                    ?: throw IllegalStateException("Vault is not configured")
                val (configJson, secretsJson) = VaultCrypto.decrypt(vault, pass)
                val blobConfig = RawConfigStore.loadRaw(RawConfigStore.yamlFromJson(configJson))
                @Suppress("UNCHECKED_CAST")
                val profiles = (blobConfig[RawConfigStore.KEY_PROFILES] as? List<*>) ?: emptyList<Any>()
                val idx = RawConfigStore.findProfileIndex(
                    profiles, profileId, original.name, original.type,
                    original.options.host, original.options.user,
                )
                if (idx < 0) throw IllegalStateException("Profile not found")
                blobConfig[RawConfigStore.KEY_PROFILES] = profiles.toMutableList().also { it.removeAt(idx) }
                val stored = VaultCrypto.encrypt(RawConfigStore.toJson(blobConfig), secretsJson, pass)
                out = linkedMapOf(
                    RawConfigStore.KEY_VAULT to RawConfigStore.storedVaultMap(stored),
                    RawConfigStore.KEY_ENCRYPTED to true,
                )
            // `is Map<*, *>` instead of `as? Map<String, Any?>`: checkable,
            // so no unchecked-cast warning and no @Suppress needed.
            val carriedSync = raw[RawConfigStore.KEY_CONFIG_SYNC]
            if (carriedSync is Map<*, *>) {
                out[RawConfigStore.KEY_CONFIG_SYNC] = carriedSync
            }
            } else {
                out = LinkedHashMap(raw)
                @Suppress("UNCHECKED_CAST")
                val profiles = (out[RawConfigStore.KEY_PROFILES] as? List<*>) ?: emptyList<Any>()
                val idx = RawConfigStore.findProfileIndex(
                    profiles, profileId, original.name, original.type,
                    original.options.host, original.options.user,
                )
                if (idx < 0) throw IllegalStateException("Profile not found")
                out[RawConfigStore.KEY_PROFILES] = profiles.toMutableList().also { it.removeAt(idx) }
            }
            disk.saveYaml(RawConfigStore.dumpRaw(out))
            decryptToLoaded(out)
        }

    /**
     * Secret intents for [updateProfile]. `password` null = untouched,
     * "" = clear, else set new. New PEMs are stored as vault file secrets
     * (vault mode) or appended as-is (plaintext mode, forwarded like desktop
     * key paths).
     */
    data class ProfileSecretEdits(
        val password: String? = null,
        val newKeyPems: List<Pair<String, String>> = emptyList(),
        val removedKeyRefs: List<String> = emptyList(),
    )

    /**
     * Profile editor save (lazy-unlock parity: the passphrase is required
     * ONLY when vault contents are actually read or written).
     *
     * - No vault: the outer document is edited directly, password inline.
     * - Plaintext + blob: non-secret edits never touch the vault (no unlock
     *   needed); secret edits decrypt, apply, and re-encrypt the blob with
     *   the same passphrase and the UNCHANGED config payload.
     * - Encrypted shell: edits apply to the decrypted blob config and the
     *   whole shell is re-encrypted (secrets payload preserved + updated).
     *
     * @param newGroupId null = keep the raw group value; "" = ungrouped.
     * @throws IllegalStateException("Vault is locked") when secrets must be
     * touched while locked — the UI prompts for the passphrase and retries.
     */
    @Suppress("UNCHECKED_CAST") // dynamic YAML maps: keys are strings by construction
    suspend fun updateProfile(
        profileId: String,
        original: SshProfile,
        updated: SshProfile,
        newGroupId: String?,
        newGroupName: String?,
        secretEdits: ProfileSecretEdits = ProfileSecretEdits(),
    ): Loaded = withContext(Dispatchers.IO) {
        val yamlStr = disk.loadYaml() ?: throw IllegalStateException("No local config")
        val raw = RawConfigStore.loadRaw(yamlStr)
        val encrypted = RawConfigStore.isEncrypted(raw)
        val vault = RawConfigStore.storedVault(raw)

        val connChanged = updated.options.user != original.options.user ||
            updated.options.host != original.options.host ||
            updated.options.port != original.options.port
        val wantsSecrets = vault != null &&
            (encrypted || secretEdits.password != null ||
                secretEdits.newKeyPems.isNotEmpty() || secretEdits.removedKeyRefs.isNotEmpty())
        val pass = rememberedPassphrase
        if (wantsSecrets && pass == null) throw IllegalStateException("Vault is locked")

        // Decrypt once when the blob must be read (shell) or rewritten.
        var secrets = emptyList<id.web.izs.sshclient.core.config.VaultSecret>()
        var blobConfigJson = ""
        if (wantsSecrets) {
            // Smart cast: wantsSecrets implies vault != null.
            val (cfg, sec) = VaultCrypto.decrypt(vault, pass!!)
            secrets = VaultState.parseSecretsJson(sec)
            blobConfigJson = cfg
        }

        // Apply secret edits against the (possibly new) connection params.
        var newSecrets = secrets
        val ou = original.options
        val nu = updated.options
        if (wantsSecrets) {
            val pw = secretEdits.password
            if (pw != null) {
                newSecrets = if (pw.isEmpty()) {
                    SecretResolver.removePassword(newSecrets, ou.user, ou.host, ou.port)
                } else {
                    SecretResolver.upsertPassword(newSecrets, nu.user, nu.host, nu.port, pw)
                }
            } else if (connChanged) {
                // Migrate the existing secret to the new params instead of
                // orphaning it (desktop leaves orphans; mobile moves it).
                SecretResolver.findPassword(newSecrets, ou.user, ou.host, ou.port)?.let { old ->
                    newSecrets = SecretResolver.removePassword(newSecrets, ou.user, ou.host, ou.port)
                    newSecrets = SecretResolver.upsertPassword(newSecrets, nu.user, nu.host, nu.port, old.value)
                }
            }
        }
        val addedRefs = mutableListOf<String>()
        if (wantsSecrets) {
            for ((pem, desc) in secretEdits.newKeyPems) {
                // Smart cast: wantsSecrets implies vault != null.
                val (next, ref) = SecretResolver.addFile(newSecrets, pem, desc)
                newSecrets = next
                addedRefs += ref
            }
            for (ref in secretEdits.removedKeyRefs) {
                newSecrets = SecretResolver.removeFile(newSecrets, ref)
            }
        }
        val secretsChanged = newSecrets != secrets

        // Final ref list: updated refs minus removed plus newly stored ones.
        // Plaintext mode appends pasted PEMs as-is (forwarded like key paths).
        val finalRefs = (updated.options.privateKeys - secretEdits.removedKeyRefs.toSet()) +
            addedRefs + secretEdits.newKeyPems.filter { vault == null }.map { it.first }

        // Password field: vault mode removes the key when a secret covers or
        // cleared it (desktop convention); plaintext mode stores the literal.
        val passwordField: String? = if (vault == null) {
            secretEdits.password
        } else if (secretEdits.password != null) {
            ""
        } else {
            null
        }

        val groupWrite: String? = when {
            newGroupId == null || newGroupId == original.group -> null
            newGroupId.isEmpty() -> ""
            else -> {
                @Suppress("UNCHECKED_CAST")
                val rawIds = ((raw[RawConfigStore.KEY_GROUPS] as? List<*>) ?: emptyList<Any>())
                    .filterIsInstance<Map<String, Any?>>()
                    .mapNotNull { it["id"]?.toString() }.toSet()
                RawConfigStore.resolveGroupWriteValue(rawIds, newGroupId, newGroupName)
            }
        }

        val out: LinkedHashMap<String, Any?>
        if (encrypted) {
            val blobConfig = RawConfigStore.loadRaw(RawConfigStore.yamlFromJson(blobConfigJson))
            @Suppress("UNCHECKED_CAST")
            val profiles = (blobConfig[RawConfigStore.KEY_PROFILES] as? List<*>) ?: emptyList<Any>()
            val idx = RawConfigStore.findProfileIndex(
                profiles, profileId, original.name, original.type,
                ou.host, ou.user,
            )
            if (idx < 0) throw IllegalStateException("Profile not found")
            val list = profiles.toMutableList()
            list[idx] = RawConfigStore.updateProfileMap(
                profiles[idx] as? Map<String, Any?> ?: emptyMap(),
                updated.copy(options = updated.options.copy(privateKeys = finalRefs)),
                passwordField, groupWrite, finalRefs,
            )
            blobConfig[RawConfigStore.KEY_PROFILES] = list
            val stored = VaultCrypto.encrypt(
                RawConfigStore.toJson(blobConfig), VaultState.secretsToJson(newSecrets), pass!!,
            )
            out = linkedMapOf(
                RawConfigStore.KEY_VAULT to RawConfigStore.storedVaultMap(stored),
                RawConfigStore.KEY_ENCRYPTED to true,
            )
            (raw[RawConfigStore.KEY_CONFIG_SYNC] as? Map<String, Any?>)?.let {
                out[RawConfigStore.KEY_CONFIG_SYNC] = it
            }
        } else {
            out = LinkedHashMap(raw)
            @Suppress("UNCHECKED_CAST")
            val profiles = (out[RawConfigStore.KEY_PROFILES] as? List<*>) ?: emptyList<Any>()
            val idx = RawConfigStore.findProfileIndex(
                profiles, profileId, original.name, original.type,
                ou.host, ou.user,
            )
            if (idx < 0) throw IllegalStateException("Profile not found")
            val list = profiles.toMutableList()
            list[idx] = RawConfigStore.updateProfileMap(
                profiles[idx] as? Map<String, Any?> ?: emptyMap(),
                updated.copy(options = updated.options.copy(privateKeys = finalRefs)),
                passwordField, groupWrite, finalRefs,
            )
            out[RawConfigStore.KEY_PROFILES] = list
            if (vault != null && secretsChanged) {
                val stored = VaultCrypto.encrypt(blobConfigJson, VaultState.secretsToJson(newSecrets), pass!!)
                out[RawConfigStore.KEY_VAULT] = RawConfigStore.storedVaultMap(stored)
            }
            disk.saveYaml(RawConfigStore.dumpRaw(out))
            return@withContext decryptToLoaded(out)
        }
        disk.saveYaml(RawConfigStore.dumpRaw(out))
        decryptToLoaded(out)
    }

    /**
     * Create a profile (mobile "New profile"). The id is always minted here
     * (`ssh:custom:<slug>:<uuid>`, desktop v4 parity) and unknown raw keys cannot
     * exist yet, so the map is built on an empty base via updateProfileMap.
     * Vault branching mirrors [updateProfile]; secrets start empty unless the
     * editor supplies a password/keys.
     *
     * @param groupId null/blank = ungrouped.
     */
    @Suppress("UNCHECKED_CAST") // dynamic YAML maps: keys are strings by construction
    suspend fun createProfile(
        profile: SshProfile,
        groupId: String?,
        groupName: String?,
        secretEdits: ProfileSecretEdits = ProfileSecretEdits(),
    ): Loaded = withContext(Dispatchers.IO) {
        val yamlStr = disk.loadYaml() ?: throw IllegalStateException("No local config")
        val raw = RawConfigStore.loadRaw(yamlStr)
        val encrypted = RawConfigStore.isEncrypted(raw)
        val vault = RawConfigStore.storedVault(raw)

        val wantsSecrets = vault != null &&
            (encrypted || secretEdits.password != null || secretEdits.newKeyPems.isNotEmpty())
        val pass = rememberedPassphrase
        if (wantsSecrets && pass == null) throw IllegalStateException("Vault is locked")

        var secrets = emptyList<id.web.izs.sshclient.core.config.VaultSecret>()
        var blobConfigJson = ""
        if (wantsSecrets) {
            // Smart cast: wantsSecrets implies vault != null.
            val (cfg, sec) = VaultCrypto.decrypt(vault, pass!!)
            secrets = VaultState.parseSecretsJson(sec)
            blobConfigJson = cfg
        }

        var newSecrets = secrets
        val nu = profile.options
        if (wantsSecrets && !secretEdits.password.isNullOrEmpty()) {
            newSecrets = SecretResolver.upsertPassword(
                newSecrets, nu.user, nu.host, nu.port, secretEdits.password,
            )
        }
        val addedRefs = mutableListOf<String>()
        if (wantsSecrets) {
            for ((pem, desc) in secretEdits.newKeyPems) {
                // Smart cast: wantsSecrets implies vault != null.
                val (next, ref) = SecretResolver.addFile(newSecrets, pem, desc)
                newSecrets = next
                addedRefs += ref
            }
        }
        val secretsChanged = newSecrets != secrets
        val finalRefs = profile.options.privateKeys + addedRefs +
            secretEdits.newKeyPems.filter { vault == null }.map { it.first }
        val passwordField: String? = if (vault == null) {
            secretEdits.password
        } else if (secretEdits.password != null) {
            ""
        } else {
            null
        }

        val groupWrite: String? = when {
            groupId.isNullOrBlank() -> null
            else -> {
                @Suppress("UNCHECKED_CAST")
                val rawIds = ((raw[RawConfigStore.KEY_GROUPS] as? List<*>) ?: emptyList<Any>())
                    .filterIsInstance<Map<String, Any?>>()
                    .mapNotNull { it["id"]?.toString() }.toSet()
                RawConfigStore.resolveGroupWriteValue(rawIds, groupId, groupName)
            }
        }

        val minted = profile.copy(id = RawConfigStore.mintProfileId("ssh", profile.name))
        val map = RawConfigStore.updateProfileMap(
            emptyMap(), minted, passwordField, groupWrite, finalRefs,
        )

        val out: LinkedHashMap<String, Any?>
        if (encrypted) {
            val blobConfig = RawConfigStore.loadRaw(RawConfigStore.yamlFromJson(blobConfigJson))
            @Suppress("UNCHECKED_CAST")
            val profiles = ((blobConfig[RawConfigStore.KEY_PROFILES] as? List<*>) ?: emptyList<Any>())
                .toMutableList()
            profiles += map
            blobConfig[RawConfigStore.KEY_PROFILES] = profiles
            val stored = VaultCrypto.encrypt(
                RawConfigStore.toJson(blobConfig), VaultState.secretsToJson(newSecrets), pass!!,
            )
            out = linkedMapOf(
                RawConfigStore.KEY_VAULT to RawConfigStore.storedVaultMap(stored),
                RawConfigStore.KEY_ENCRYPTED to true,
            )
            (raw[RawConfigStore.KEY_CONFIG_SYNC] as? Map<String, Any?>)?.let {
                out[RawConfigStore.KEY_CONFIG_SYNC] = it
            }
        } else {
            out = LinkedHashMap(raw)
            @Suppress("UNCHECKED_CAST")
            val profiles = ((out[RawConfigStore.KEY_PROFILES] as? List<*>) ?: emptyList<Any>())
                .toMutableList()
            profiles += map
            out[RawConfigStore.KEY_PROFILES] = profiles
            if (vault != null && secretsChanged) {
                val stored = VaultCrypto.encrypt(blobConfigJson, VaultState.secretsToJson(newSecrets), pass!!)
                out[RawConfigStore.KEY_VAULT] = RawConfigStore.storedVaultMap(stored)
            }
        }
        disk.saveYaml(RawConfigStore.dumpRaw(out))
        decryptToLoaded(out)
    }

    /**
     * Append a top-level group (mobile "New group" in the profile editor).
     * No secrets involved; encrypted shells still need unlock (re-encrypt).
     */
    @Suppress("UNCHECKED_CAST") // dynamic YAML maps: keys are strings by construction
    suspend fun createGroup(id: String, name: String): Loaded = withContext(Dispatchers.IO) {
        require(name.isNotBlank()) { "Group name is empty" }
        val yamlStr = disk.loadYaml() ?: throw IllegalStateException("No local config")
        val raw = RawConfigStore.loadRaw(yamlStr)
        val encrypted = RawConfigStore.isEncrypted(raw)
        val entry = linkedMapOf<String, Any?>("id" to id, "name" to name.trim())
        val out: LinkedHashMap<String, Any?>
        if (encrypted) {
            val pass = rememberedPassphrase ?: throw IllegalStateException("Vault is locked")
            val vault = RawConfigStore.storedVault(raw)
                ?: throw IllegalStateException("Vault is not configured")
            val (configJson, secretsJson) = VaultCrypto.decrypt(vault, pass)
            val blobConfig = RawConfigStore.loadRaw(RawConfigStore.yamlFromJson(configJson))
            @Suppress("UNCHECKED_CAST")
            val groups = ((blobConfig[RawConfigStore.KEY_GROUPS] as? List<*>) ?: emptyList<Any>())
                .toMutableList()
            groups += entry
            blobConfig[RawConfigStore.KEY_GROUPS] = groups
            val stored = VaultCrypto.encrypt(RawConfigStore.toJson(blobConfig), secretsJson, pass)
            out = linkedMapOf(
                RawConfigStore.KEY_VAULT to RawConfigStore.storedVaultMap(stored),
                RawConfigStore.KEY_ENCRYPTED to true,
            )
            (raw[RawConfigStore.KEY_CONFIG_SYNC] as? Map<String, Any?>)?.let {
                out[RawConfigStore.KEY_CONFIG_SYNC] = it
            }
        } else {
            out = LinkedHashMap(raw)
            @Suppress("UNCHECKED_CAST")
            val groups = ((out[RawConfigStore.KEY_GROUPS] as? List<*>) ?: emptyList<Any>())
                .toMutableList()
            groups += entry
            out[RawConfigStore.KEY_GROUPS] = groups
        }
        disk.saveYaml(RawConfigStore.dumpRaw(out))
        decryptToLoaded(out)
    }

    /**
     * Applies a `terminal`-section mutation (color scheme, custom schemes,
     * font, cursor — Settings > Color scheme / Appearance). Plaintext configs edit the outer document
     * (updateLocalRaw parity); encrypted shells edit the vault blob and
     * re-encrypt (createGroup parity) — so scheme changes work with the
     * vault unlocked, instead of the old desktop-only read-only rule.
     * Throws "Vault is locked" when the blob cannot be rewritten; callers
     * surface the unlock dialog and retry (profile-editor pendingSave parity).
     */
    @Suppress("UNCHECKED_CAST") // dynamic YAML maps: keys are strings by construction
    suspend fun updateTerminalSection(
        transform: (LinkedHashMap<String, Any?>) -> Unit,
    ): Loaded = withContext(Dispatchers.IO) {
        val yamlStr = disk.loadYaml() ?: throw IllegalStateException("No local config")
        val raw = RawConfigStore.loadRaw(yamlStr)
        if (!RawConfigStore.isEncrypted(raw)) {
            val out = LinkedHashMap(raw)
            transform(out)
            disk.saveYaml(RawConfigStore.dumpRaw(out))
            return@withContext decryptToLoaded(out)
        }
        val pass = rememberedPassphrase ?: throw IllegalStateException("Vault is locked")
        val vault = RawConfigStore.storedVault(raw)
            ?: throw IllegalStateException("Vault is not configured")
        val (configJson, secretsJson) = VaultCrypto.decrypt(vault, pass)
        val blobConfig = RawConfigStore.loadRaw(RawConfigStore.yamlFromJson(configJson))
        transform(blobConfig)
        val stored = VaultCrypto.encrypt(RawConfigStore.toJson(blobConfig), secretsJson, pass)
        val out: LinkedHashMap<String, Any?> = linkedMapOf(
            RawConfigStore.KEY_VAULT to RawConfigStore.storedVaultMap(stored),
            RawConfigStore.KEY_ENCRYPTED to true,
        )
        (raw[RawConfigStore.KEY_CONFIG_SYNC] as? Map<String, Any?>)?.let {
            out[RawConfigStore.KEY_CONFIG_SYNC] = it
        }
        disk.saveYaml(RawConfigStore.dumpRaw(out))
        // Direct Loaded (VaultState.resolve parity for the unlocked-shell
        // case): the blob was JUST decrypted above, so re-resolving `out`
        // would PBKDF2-decrypt + re-parse the same bytes for nothing
        // (measured ~2s of every scheme tap). The passphrase demonstrably
        // works (decrypt above succeeded), so stalePassphrase is false.
        val merged = LinkedHashMap<String, Any?>(blobConfig)
        merged[RawConfigStore.KEY_VAULT] = RawConfigStore.storedVaultMap(stored)
        merged[RawConfigStore.KEY_ENCRYPTED] = true
        (raw[RawConfigStore.KEY_CONFIG_SYNC] as? Map<String, Any?>)?.let {
            merged[RawConfigStore.KEY_CONFIG_SYNC] = it
        }
        Loaded(
            domain = RawConfigStore.toDomain(merged),
            secrets = VaultState.parseSecretsJson(secretsJson),
            needsPassphrase = false,
            store = merged,
        )
    }

    private fun readParts(raw: Map<String, Any?>): Map<String, Boolean> {
        @Suppress("UNCHECKED_CAST")
        val cs = raw[RawConfigStore.KEY_CONFIG_SYNC] as? Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val parts = cs?.get("parts") as? Map<String, Any?>
        // The v1 UI disk prefs win when the document has no complete configSync yet
        return mapOf(
            "hotkeys" to (parts?.get("hotkeys") as? Boolean ?: disk.partsHotkeys),
            "appearance" to (parts?.get("appearance") as? Boolean ?: disk.partsAppearance),
            "vault" to (parts?.get("vault") as? Boolean ?: disk.partsVault),
        )
    }

    /**
     * Vault-state resolution is pure ([VaultState]): a present blob is always
     * the secrets container, independent of the `encrypted` file-shape flag.
     * Only the stale-passphrase side effect (forget) stays here.
     */
    private fun decryptToLoaded(
        raw: LinkedHashMap<String, Any?>,
        forgiveStaleRemembered: Boolean = false,
    ): Loaded {
        val v = VaultState.resolve(raw, rememberedPassphrase, forgiveStaleRemembered)
        if (v.stalePassphrase) forgetPassphrase()
        return Loaded(
            domain = RawConfigStore.toDomain(v.domainDoc),
            secrets = v.secrets,
            needsPassphrase = v.needsPassphrase,
            store = v.store,
            unlockRequired = v.unlockRequired,
            pendingRewrite = pendingEncryptedRewrite,
        )
    }

    /** Clear the post-download/import pending state (and its backup) together. */
    private fun settlePendingRewrite() {
        pendingEncryptedRewrite = false
        preImportBackupYaml = null
        preImportSyncTarget = null
        preImportPassphrase = null
    }

    /**
     * Desktop `writeConfigDataFromSync` second half (`config.save()` after
     * `config.load()`): re-encrypt an encrypted shell with a fresh salt/iv,
     * preserving the exact desktop outer shape `{vault, encrypted, configSync}`.
     * Plaintext docs (including plaintext-with-blob) are never rewritten here.
     */
    @Suppress("UNCHECKED_CAST") // dynamic YAML maps: keys are strings by construction
    private fun reencryptShellDoc(
        raw: LinkedHashMap<String, Any?>,
        configJson: String,
        secretsJson: String,
        pass: String,
    ): LinkedHashMap<String, Any?> {
        val stored = VaultCrypto.encrypt(configJson, secretsJson, pass)
        return linkedMapOf<String, Any?>(
            RawConfigStore.KEY_VAULT to RawConfigStore.storedVaultMap(stored),
            RawConfigStore.KEY_ENCRYPTED to true,
        ).also { m ->
            (raw[RawConfigStore.KEY_CONFIG_SYNC] as? Map<String, Any?>)?.let {
                m[RawConfigStore.KEY_CONFIG_SYNC] = it
            }
        }
    }

    /**
     * Shared tail of [downloadIntoLocal] and [importRawYaml]: the fresh
     * document is already saved verbatim on disk. When it is an encrypted
     * shell, re-encrypt immediately if the remembered passphrase opens it
     * (desktop `save()` with no prompt needed); otherwise arm
     * [pendingEncryptedRewrite] so the first [unlockWithPassphrase] rotates
     * the salt. Never prompts — background-safe for [autoSyncTick].
     */
    private fun maybeReencryptFreshShell(doc: LinkedHashMap<String, Any?>): Loaded {
        if (!RawConfigStore.isEncrypted(doc) || RawConfigStore.storedVault(doc) == null) {
            settlePendingRewrite()
            return decryptToLoaded(doc, forgiveStaleRemembered = true)
        }
        val pass = rememberedPassphrase
        if (pass != null) {
            try {
                val vault = RawConfigStore.storedVault(doc)!!
                val (configJson, secretsJson) = VaultCrypto.decrypt(vault, pass)
                val out = reencryptShellDoc(doc, configJson, secretsJson, pass)
                disk.saveYaml(RawConfigStore.dumpRaw(out))
                settlePendingRewrite()
                return decryptToLoaded(out)
            } catch (_: Exception) {
                // Stale/wrong remembered passphrase for the new blob: stay
                // locked and rewrite on the next successful unlock instead.
            }
        }
        pendingEncryptedRewrite = true
        return decryptToLoaded(doc, forgiveStaleRemembered = true)
    }

    companion object {
        fun storedVaultOf(raw: Map<String, Any?>): StoredVault? = RawConfigStore.storedVault(raw)

        /**
         * Restore-source decision (pure, unit-tested): the RAM snapshot wins
         * (it also carries the target + session passphrase); the persisted
         * pre-overwrite copy is the process-death fallback; null means
         * nothing is pending.
         */
        fun pickRestoreSource(ramBackup: String?, diskBackup: String?): String? =
            if (!ramBackup.isNullOrBlank()) ramBackup else diskBackup?.takeIf { it.isNotBlank() }
    }
}
