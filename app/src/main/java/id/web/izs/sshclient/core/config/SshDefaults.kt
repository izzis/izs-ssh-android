package id.web.izs.sshclient.core.config

/**
 * Defaults parity with tabby-ssh/src/profiles.ts:16-48.
 * The cloud omits defaults (the desktop ConfigProxy only stores non-defaults),
 * so the Domain view MUST apply defaults transiently — WITHOUT writing back to Raw.
 */
object SshDefaults {
    fun applyToProfile(p: SshProfile): SshProfile {
        val o = p.options
        return p.copy(
            options = o.copy(
                port = if (o.port <= 0) 22 else o.port,
                user = o.user.ifBlank { "root" },
                privateKeys = o.privateKeys.filter { it.isNotBlank() },
                keepaliveInterval = if (o.keepaliveInterval <= 0) 5000 else o.keepaliveInterval,
                keepaliveCountMax = if (o.keepaliveCountMax <= 0) 10 else o.keepaliveCountMax,
                readyTimeout = o.readyTimeout ?: 20000,
                // Desktop ctor parity (profiles.ts:57-60): missing algorithm
                // keys fall back to the built-in lists, transiently.
                algorithms = SshAlgorithms.TYPES.associateWith { k ->
                    o.algorithms[k] ?: SshAlgorithms.DEFAULTS.getValue(k)
                },
            ),
        )
    }

    fun quickName(user: String, host: String, port: Int): String =
        if (port == 22) "$user@$host" else "$user@$host:$port"

    /**
     * List subtitle for a profile: a stored-blank user (`user: ''` in YAML =
     * ask every time) shows the host alone; a missing `user` line (or an
     * explicit `root`) keeps `root@host`. A session-local typed answer
     * ([typedUser]) wins over both, so a connected `alice` tab reads
     * `alice@host`.
     */
    fun displayQuickName(
        displayUser: String,
        host: String,
        port: Int,
        askUsername: Boolean,
        typedUser: String? = null,
    ): String {
        typedUser?.takeIf { it.isNotBlank() }?.let { return quickName(it, host, port) }
        if (askUsername) return if (port == 22) host else "$host:$port"
        return quickName(displayUser.ifBlank { "root" }, host, port)
    }
}
