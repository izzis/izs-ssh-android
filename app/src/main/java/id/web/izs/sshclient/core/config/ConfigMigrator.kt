package id.web.izs.sshclient.core.config

/**
 * Read-tolerance helpers for legacy shapes (transient domain view only —
 * never rewritten to disk, never version-gated).
 *
 * Android authors v8 documents exclusively (LATEST_VERSION), so no v1-v7
 * migration exists here by design; the desktop migrator then stays quiet
 * when a file moves phone -> PC. The two functions below only keep OLD
 * pasted/imported docs readable:
 * - migrateGroupNamesToIds: pre-v5 name-based groups -> ids (v8 docs already
 *   use ids, so this is a no-op for them).
 * - normalizeJumpHosts: pre-v3 jumpHost names -> profile ids (same no-op rule).
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

    /**
     * Stamp for documents Android authors (seed / import default / local
     * normalization): the desktop LATEST. Android already writes v8 shapes
     * (plural privateKeys, id-based groups, no ssh.connections), so a fresh
     * stamp is honest — and the desktop migrator then stays quiet when the
     * file moves phone -> PC.
     */
    fun ensureVersion(version: Int): Int = if (version < LATEST_VERSION) LATEST_VERSION else version

    private fun randomId(): String = java.util.UUID.randomUUID().toString()
}
