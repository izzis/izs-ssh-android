package id.web.izs.sshclient.core.config

import kotlinx.serialization.json.JsonObject

/**
 * The parity model for Tabby Desktop.
 *
 * Desktop references:
 * - tabby-core/src/configDefaults.yaml (root keys)
 * - tabby-ssh/src/api/interfaces.ts SSHProfileOptions
 * - tabby-ssh/src/profiles.ts defaults
 * - tabby-core/src/api/profileProvider.ts Profile/ProfileGroup
 *
 * Lossless v1 rule: fields not used yet (appearance, hotkeys,
 * terminal, etc.) are kept opaque in [RawConfig.unknown] so that
 * download -> upload never loses anything.
 */
data class TabbyConfig(
    val version: Int = 1,
    val profiles: List<SshProfile> = emptyList(),
    val groups: List<ProfileGroup> = emptyList(),
    val ssh: SshGlobals = SshGlobals(),
    val configSync: ConfigSync = ConfigSync(),
    val vault: StoredVault? = null,
    val encrypted: Boolean = false,
    /** All other root keys (hotkeys, appearance, terminal, ...) kept raw. */
    val unknown: Map<String, JsonObject?> = emptyMap(),
)

data class SshProfile(
    val id: String,
    val type: String = "ssh",
    val name: String,
    /** Group ID (not the name) — desktop v5 migration parity. */
    val group: String? = null,
    val icon: String? = null,
    val color: String? = null,
    val disableDynamicTitle: Boolean? = null,
    val options: SshOptions,
    /** Extra unmodeled raw options — preserved for the future. */
    val unknownOptions: Map<String, JsonObject?> = emptyMap(),
)

data class SshOptions(
    val host: String = "",
    val port: Int = 22,
    val user: String = "root",
    /** null | password | publicKey | agent | keyboardInteractive */
    val auth: String? = null,
    /** Null while the vault is active (desktop parity); may be a vault:// ref. */
    val password: String? = null,
    val privateKeys: List<String> = emptyList(),
    val keepaliveInterval: Long = 5000,
    val keepaliveCountMax: Int = 10,
    val readyTimeout: Long? = 20000,
    val jumpHost: String? = null,
    val agentForward: Boolean = false,
    val proxyCommand: String? = null,
    val socksProxyHost: String? = null,
    val socksProxyPort: Int? = null,
    val httpProxyHost: String? = null,
    val httpProxyPort: Int? = null,
    val reuseSession: Boolean = true,
)

data class ProfileGroup(
    val id: String,
    val name: String,
    /** Nested folders parity (desktop ProfileGroup.parentGroupId). Null = top level. */
    val parentGroupId: String? = null,
)

data class SshGlobals(
    val knownHosts: List<String> = emptyList(),
    val verifyHostKeys: Boolean = true,
)

data class ConfigSync(
    val host: String? = null,
    val token: String? = null,
    val configID: Long? = null,
    val auto: Boolean = false,
    val partsHotkeys: Boolean = true,
    val partsAppearance: Boolean = true,
    val partsVault: Boolean = true,
)

/** Desktop StoredVault parity (vault.service.ts). */
data class StoredVault(
    val version: Int = 1,
    /** base64(AES-256-CBC(JSON{config,secrets})); hex is never wrapped. */
    val contents: String = "",
    /** hex */
    val keySalt: String = "",
    /** hex */
    val iv: String = "",
)

data class VaultSecret(
    val type: String,
    val key: Map<String, String?> = emptyMap(),
    val value: String = "",
)

data class DecryptedVault(
    /** Raw decrypted config (without vault/encrypted/configSync). */
    val configRawYaml: String,
    val secrets: List<VaultSecret> = emptyList(),
)

data class RemoteConfigMeta(
    val id: Long,
    val name: String,
    val content: String = "",
    val lastUsedWithVersion: String? = null,
    val createdAt: String = "",
    val modifiedAt: String = "",
)
