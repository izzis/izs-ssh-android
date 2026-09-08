package id.web.izs.sshclient.core.config

/**
 * A port of the desktop migration in tabby-core/src/services/config.service.ts:315-455.
 * Only the v1-relevant parts (SSH profiles + groups); other migrations are no-ops
 * but the version is still advanced so files written by Android are accepted by desktop.
 *
 * - v1: connection.privateKey -> privateKeys[]
 * - v3: ssh.connections[] -> ssh-typed profiles[] (id ssh:<uuid>)
 * - v4: id-less profiles get an id
 * - v5: group name -> group id + groups[]
 * - v7: default api.tabby.sh host dropped when token-less
 * Others (v2 terminal profiles, v6 clearServiceMessages, v8 compression)
 * stay opaque in Raw.
 */
object ConfigMigrator {
    const val LATEST_VERSION = 8

    fun migrateGroupNamesToIds(
        profiles: List<SshProfile>,
        groups: List<ProfileGroup>,
    ): Pair<List<SshProfile>, List<ProfileGroup>> {
        // Legacy profiles store the group as a NAME; desktop v5 switched to ID.
        val outGroups = groups.toMutableList()
        val outProfiles = profiles.map { p ->
            val g = p.group
            if (g.isNullOrBlank()) return@map p
            val existing = outGroups.find { it.id == g || it.name == g }
            if (existing != null) {
                if (existing.id != g) p.copy(group = existing.id) else p
            } else {
                // g is a name (not a known id) -> create a new group
                val ng = ProfileGroup(id = randomId(), name = g)
                outGroups += ng
                p.copy(group = ng.id)
            }
        }
        return outProfiles to outGroups
    }

    fun normalizeJumpHosts(profiles: List<SshProfile>): List<SshProfile> {
        // Desktop v3: jumpHost name -> profile id. Both forms are accepted at connect.
        val byName = profiles.associateBy { it.name }
        return profiles.map { p ->
            val j = p.options.jumpHost
            if (j.isNullOrBlank()) p
            else if (profiles.any { it.id == j }) p
            else {
                val target = byName[j]
                if (target != null) p.copy(options = p.options.copy(jumpHost = target.id)) else p
            }
        }
    }

    fun ensureVersion(version: Int): Int = if (version < 1) 1 else version

    private fun randomId(): String = java.util.UUID.randomUUID().toString()
}
