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
    /** Null = desktop default (no explicit timeout in YAML). */
    val readyTimeout: Long? = null,
    val jumpHost: String? = null,
    val agentForward: Boolean = false,
    val x11: Boolean = false,
    val skipBanner: Boolean = false,
    /** Null = desktop default (falls back to the global ssh.warnOnClose). */
    val warnOnClose: Boolean? = null,
    val proxyCommand: String? = null,
    val socksProxyHost: String? = null,
    val socksProxyPort: Int? = null,
    val httpProxyHost: String? = null,
    val httpProxyPort: Int? = null,
    val reuseSession: Boolean = true,
    /**
     * Empty = desktop defaults (filled transiently by SshDefaults, never
     * written to YAML — the cloud omits defaults by design).
     * Keys: cipher, kex, hmac, serverHostKey, compression.
     */
    val algorithms: Map<String, List<String>> = emptyMap(),
    val forwardedPorts: List<ForwardedPort> = emptyList(),
    val scripts: List<LoginScript> = emptyList(),
)

/** Desktop ForwardedPortConfig parity (tabby-ssh/src/api/interfaces.ts). */
data class ForwardedPort(
    /** Local | Remote | Dynamic (Dynamic hides target = SOCKS proxy). */
    val type: String = "Local",
    /** Listen interface + port. */
    val host: String = "127.0.0.1",
    val port: Int = 8000,
    /** Destination (unused for Dynamic). */
    val targetAddress: String = "127.0.0.1",
    val targetPort: Int = 80,
    val description: String = "",
)

/** Desktop LoginScript parity (tabby-terminal loginScriptProcessing.ts). */
data class LoginScript(
    val expect: String = "",
    val send: String = "",
    val isRegex: Boolean = false,
    val optional: Boolean = false,
)

/**
 * Default algorithm lists, verbatim from tabby-ssh/src/algorithms.ts
 * defaultAlgorithms (russh-supported subset, kex-strict appended).
 * Desktop sorts every list on save EXCEPT compression.
 */
object SshAlgorithms {
    const val KEX = "kex"
    const val SERVER_HOST_KEY = "serverHostKey"
    const val CIPHER = "cipher"
    const val HMAC = "hmac"
    const val COMPRESSION = "compression"

    val TYPES = listOf(CIPHER, KEX, HMAC, SERVER_HOST_KEY, COMPRESSION)

    val DEFAULTS: Map<String, List<String>> = mapOf(
        KEX to listOf(
            "mlkem768x25519-sha256",
            "curve25519-sha256",
            "curve25519-sha256@libssh.org",
            "diffie-hellman-group16-sha512",
            "diffie-hellman-group14-sha256",
            "ext-info-c",
            "ext-info-s",
            "kex-strict-c-v00@openssh.com",
            "kex-strict-s-v00@openssh.com",
        ),
        SERVER_HOST_KEY to listOf(
            "ssh-ed25519",
            "ecdsa-sha2-nistp256",
            "ecdsa-sha2-nistp521",
            "rsa-sha2-256",
            "rsa-sha2-512",
            "ssh-rsa",
        ),
        CIPHER to listOf(
            "chacha20-poly1305@openssh.com",
            "aes256-gcm@openssh.com",
            "aes256-ctr",
            "aes192-ctr",
            "aes128-ctr",
        ),
        HMAC to listOf(
            "hmac-sha2-512-etm@openssh.com",
            "hmac-sha2-256-etm@openssh.com",
            "hmac-sha2-512",
            "hmac-sha2-256",
            "hmac-sha1-etm@openssh.com",
            "hmac-sha1",
        ),
        COMPRESSION to listOf("none"),
    )
}

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
