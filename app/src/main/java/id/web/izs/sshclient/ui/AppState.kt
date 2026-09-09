package id.web.izs.sshclient.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import id.web.izs.sshclient.core.config.ConfigMigrator
import id.web.izs.sshclient.core.config.ProfileGroup
import id.web.izs.sshclient.core.config.SshDefaults
import id.web.izs.sshclient.core.config.SshProfile
import id.web.izs.sshclient.core.config.VaultSecret
import id.web.izs.sshclient.core.sync.SyncRepository
import id.web.izs.sshclient.core.vault.SavedKeyInfo
import id.web.izs.sshclient.core.vault.SecretResolver
import id.web.izs.sshclient.data.local.ConfigDisk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * v1 state holder (manual DI to keep v1 lean, no Hilt yet).
 * Single source of truth = SyncRepository.loadLocal() (lossless RAW document).
 */
class AppState(
    val disk: ConfigDisk,
    val repo: SyncRepository,
    private val scope: CoroutineScope,
) {
    var loaded by mutableStateOf<SyncRepository.Loaded?>(null)
        private set
    /**
     * App-chrome theme mode (Settings > Appearance): mirrors
     * [ConfigDisk.appTheme] as observable state so a change re-themes
     * live without an Activity restart. Initialized from disk at
     * composition start (MainActivity).
     */
    var themeMode by mutableStateOf(ConfigDisk.THEME_DARK)
    /**
     * App-chrome palette id ([ConfigDisk.appPalette] mirror): observable
     * so a palette change re-themes live. Initialized from disk in
     * MainActivity alongside [themeMode].
     */
    var paletteName by mutableStateOf(ConfigDisk.PALETTE_IZS)
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)

    fun refresh(onDone: (() -> Unit)? = null) {
        scope.launch {
            bootLoad()
            onDone?.invoke()
        }
    }

    /**
     * Adopt a freshly-written [SyncRepository.Loaded] (repo write fns return
     * it) instead of [refresh] re-loading from disk. Saves a full reload
     * cycle — on encrypted stores that is another PBKDF2 decrypt + YAML
     * parse of the whole blob, seconds on slow phones for zero new
     * information (the write fn already resolved from the written doc).
     */
    fun adopt(loaded: SyncRepository.Loaded, onDone: (() -> Unit)? = null) {
        this.loaded = loaded
        onDone?.invoke()
    }

    /** Suspend boot load used by MainActivity before the NavHost is composed. */
    suspend fun bootLoad(): Boolean {
        loading = true
        error = null
        return try {
            loaded = repo.loadLocal()
            true
        } catch (e: Exception) {
            error = e.message ?: "Load failed"
            false
        } finally {
            loading = false
        }
    }

    fun unlock(passphrase: String, onDone: (Boolean) -> Unit) {
        scope.launch(Dispatchers.Main) {
            loading = true
            error = null
            try {
                loaded = repo.unlockWithPassphrase(passphrase)
                onDone(true)
            } catch (e: Exception) {
                error = e.message ?: "Unlock failed"
                onDone(false)
            } finally {
                loading = false
            }
        }
    }

    /** Display-ready profiles: group/jumpHost migration + transient defaults. */
    fun displayProfiles(): List<SshProfile> = migrated().first

    /**
     * Display-ready groups from the SAME migration pass as [displayProfiles].
     * Memoized per [loaded] instance: the v5 migration mints random ids for
     * legacy name-based groups, so profiles and groups must come from one pass.
     */
    fun displayGroups(): List<ProfileGroup> = migrated().second

    fun groupName(groups: List<ProfileGroup>, id: String?): String =
        id?.let { gid -> groups.find { it.id == gid }?.name } ?: ""

    private var migratedCache: Pair<SyncRepository.Loaded, Pair<List<SshProfile>, List<ProfileGroup>>>? = null

    private fun migrated(): Pair<List<SshProfile>, List<ProfileGroup>> {
        val l = loaded ?: return emptyList<SshProfile>() to emptyList()
        migratedCache?.let { (cachedLoaded, result) ->
            if (cachedLoaded === l) return result
        }
        val (byGroup, groups) = ConfigMigrator.migrateGroupNamesToIds(
            l.domain.profiles, l.domain.groups,
        )
        val result = ConfigMigrator.normalizeJumpHosts(byGroup).map { SshDefaults.applyToProfile(it) } to groups
        migratedCache = l to result
        return result
    }

    private fun secrets(): List<VaultSecret> = loaded?.secrets ?: emptyList()

    /** Effective password: vault secret first, then the profile plaintext (unencrypted configs). */
    fun passwordFor(p: SshProfile): String? {
        val s = SecretResolver.findPassword(secrets(), p.options.user, p.options.host, p.options.port)
        if (s != null) return s.value
        val plain = p.options.password
        if (!plain.isNullOrBlank() && !plain.startsWith(SecretResolver.VAULT_PREFIX)) return plain
        return null
    }

    /** All effective private-key PEMs (desktop multi-key parity). */
    fun keysFor(p: SshProfile): List<Pair<String, String?>> {
        val out = mutableListOf<Pair<String, String?>>()
        for (ref in p.options.privateKeys) {
            if (ref.isBlank()) continue
            if (ref.startsWith(SecretResolver.VAULT_PREFIX)) {
                val pem = SecretResolver.findFilePem(secrets(), ref) ?: continue
                // key passphrase: look for a related ssh:key-passphrase secret when present
                out += pem to null
            } else {
                // Raw path/content in a plaintext config: forwarded as-is
                out += ref to null
            }
        }
        return out
    }

    /**
     * Vault-stored private keys (desktop vault.selectAndStoreFile parity).
     * Empty when the vault is missing or locked — callers gate on that.
     */
    fun savedKeys(): List<SavedKeyInfo> = SecretResolver.fileSecrets(secrets())

    /** Display label for an attached key ref: vault description, else a short id tail. */
    fun keyLabel(ref: String): String =
        savedKeys().find { it.ref == ref }?.description?.takeIf { it.isNotBlank() }
            ?: if (ref.startsWith(SecretResolver.VAULT_PREFIX)) "vault file • …${ref.takeLast(8)}"
            else ref.take(44)

    /**
     * All saved key-passphrase values. Desktop keys them by key hash
     * (scheme undocumented); mobile tries every saved passphrase when
     * loading an encrypted key until one works. Wrong candidates only fail
     * locally — no server contact, no lockout risk.
     */
    fun keyPassphrases(): List<String> =
        secrets().filter { it.type == SecretResolver.TYPE_PASSPHRASE }.map { it.value }
}
