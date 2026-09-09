package id.web.izs.sshclient.core.config

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml

/**
 * The Raw layer — parity with desktop readConfigDataForSync / writeConfigDataFromSync.
 *
 * Lossless rules (agreed):
 * - Uploads are read from the RAW on-disk document (never from the defaulted Domain view).
 * - The Domain view (profiles/groups) is UI/connect only, with transient defaults.
 * - Unknown root keys + unknown option keys are preserved opaquely.
 * - The vault blob is never reformatted (contents/keySalt/iv strings verbatim).
 */
object RawConfigStore {
    const val KEY_VERSION = "version"
    const val KEY_PROFILES = "profiles"
    const val KEY_GROUPS = "groups"
    const val KEY_SSH = "ssh"
    const val KEY_CONFIG_SYNC = "configSync"
    const val KEY_VAULT = "vault"
    const val KEY_ENCRYPTED = "encrypted"
    const val KEY_TERMINAL = "terminal"
    const val KEY_APPEARANCE = "appearance"

    /**
     * Desktop default for `terminal.showRecentProfiles`
     * (tabby-core configDefaults.yaml + Profiles > Advanced number input,
     * min 0, "Set to 0 to disable"). Absent key = this, never invented.
     */
    const val DEFAULT_SHOW_RECENT_PROFILES = 3

    /** UI bound for the Android stepper (desktop itself has no max). */
    const val MAX_SHOW_RECENT_PROFILES = 20

    /** Parts opsional parity desktop: hotkeys, appearance, vault. */
    val OPTIONAL_PARTS = listOf("hotkeys", "appearance", "vault")

    private fun yaml(): Yaml {
        val opts = DumperOptions().apply {
            defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
            // Never wrap the base64 vault contents (must stay single-line like desktop)
            width = 1_000_000
            indent = 2
        }
        return Yaml(opts)
    }

    @Suppress("UNCHECKED_CAST")
    fun loadRaw(yamlStr: String): LinkedHashMap<String, Any?> {
        if (yamlStr.isBlank()) return linkedMapOf(KEY_VERSION to 1)
        val loaded = yaml().load<Any>(yamlStr)
        return when (loaded) {
            is LinkedHashMap<*, *> -> loaded as LinkedHashMap<String, Any?>
            is Map<*, *> -> LinkedHashMap<String, Any?>().also { m ->
                loaded.forEach { (k, v) -> m[k.toString()] = v }
            }
            else -> linkedMapOf(KEY_VERSION to 1)
        }
    }

    fun dumpRaw(doc: Map<String, Any?>): String = yaml().dump(doc)

    /**
     * `terminal.showRecentProfiles` read with desktop-default fallback.
     * Negative garbage coerces to 0 (disabled); non-numeric to the default.
     * Read from [SyncRepository.Loaded.store] (decrypted merged view when
     * unlocked, outer raw otherwise) — never from the defaulted Domain view.
     */
    @Suppress("UNCHECKED_CAST")
    fun showRecentProfiles(doc: Map<String, Any?>): Int {
        val n = ((doc[KEY_TERMINAL] as? Map<String, Any?>)?.get("showRecentProfiles") as? Number)
            ?.toInt() ?: return DEFAULT_SHOW_RECENT_PROFILES
        return n.coerceAtLeast(0)
    }

    /**
     * Explicit user set (stepper): creates the `terminal` map when absent.
     * Like desktop (`ngModelChange=config.save()`), an explicit set is
     * persisted even when it equals the default.
     */
    @Suppress("UNCHECKED_CAST")
    fun setShowRecentProfiles(doc: MutableMap<String, Any?>, v: Int) {
        val term = LinkedHashMap(
            (doc[KEY_TERMINAL] as? Map<String, Any?>) ?: emptyMap(),
        )
        term["showRecentProfiles"] = v.coerceIn(0, MAX_SHOW_RECENT_PROFILES)
        doc[KEY_TERMINAL] = term
    }

    /**
     * `appearance.tabsLocation` read WITHOUT desktop-default fallback.
     * Desktop resolves absent → `top` (configDefaults.yaml); on the phone
     * absent means OFF (the current list-based UX, no tab chrome) — a
     * deliberate phone default, documented in ARCHITECTURE.md. Only an
     * explicitly synced value turns the tab UI on. Returns the raw string
     * (or null when absent/non-string); use [resolveTabLocation] to map it.
     * Read from [SyncRepository.Loaded.store], like [showRecentProfiles].
     */
    @Suppress("UNCHECKED_CAST")
    fun tabsLocationRaw(doc: Map<String, Any?>): String? =
        (doc[KEY_APPEARANCE] as? Map<String, Any?>)?.get("tabsLocation") as? String

    /**
     * Explicit user set (Window settings): writes the desktop-owned
     * `appearance.tabsLocation` key. `null` (Off) REMOVES the key — and the
     * `appearance` map itself when left empty — restoring absent = Off.
     */
    @Suppress("UNCHECKED_CAST")
    fun setTabsLocation(doc: MutableMap<String, Any?>, v: TabLocation?) {
        val app = LinkedHashMap(
            (doc[KEY_APPEARANCE] as? Map<String, Any?>) ?: emptyMap(),
        )
        if (v == null || v == TabLocation.OFF) app.remove("tabsLocation")
        else app["tabsLocation"] = v.yamlValue
        if (app.isEmpty()) doc.remove(KEY_APPEARANCE) else doc[KEY_APPEARANCE] = app
    }

    /**
     * Parse a vault-blob config JSON object into a raw document (decrypt path).
     * Inverse of [toJson]; shared with the sync layer so tests exercise it.
     */
    @Suppress("UNCHECKED_CAST") // dynamic YAML/JSON maps: keys are strings by construction
    fun jsonToRaw(configJson: String): LinkedHashMap<String, Any?> {
        val el = kotlinx.serialization.json.Json.parseToJsonElement(configJson)
        val map = jsonElementToJava(el) as? Map<String, Any?> ?: emptyMap()
        return loadRaw(dumpRaw(LinkedHashMap(map)))
    }

    private fun jsonElementToJava(el: JsonElement): Any? = when (el) {
        is JsonObject ->
            LinkedHashMap<String, Any?>().also { m ->
                el.forEach { (k, v) -> m[k] = jsonElementToJava(v) }
            }
        is JsonArray -> el.map { jsonElementToJava(it) }
        is JsonPrimitive ->
            if (el.isString) el.content
            else el.content.toLongOrNull() ?: el.content.toDoubleOrNull()
                ?: when (el.content) {
                    "true" -> true
                    "false" -> false
                    "null" -> null
                    else -> el.content
                }
    }

    /**
     * Serialize a raw document to JSON (desktop JSON.stringify parity).
     * Used for the vault blob payload: key order preserved, no whitespace.
     * Inverse of the JSON half of SyncRepository.jsonObjectToYamlish.
     */
    fun toJson(value: Any?): String = buildString { appendJson(value) }

    private fun StringBuilder.appendJson(value: Any?) {
        when (value) {
            null -> append("null")
            is String -> appendJsonString(value)
            is Boolean -> append(value.toString())
            is Number -> append(value.toString())
            is Map<*, *> -> {
                append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) append(',')
                    first = false
                    appendJsonString(k.toString())
                    append(':')
                    appendJson(v)
                }
                append('}')
            }
            is Iterable<*> -> {
                append('[')
                var first = true
                for (e in value) {
                    if (!first) append(',')
                    first = false
                    appendJson(e)
                }
                append(']')
            }
            else -> appendJsonString(value.toString())
        }
    }

    private fun StringBuilder.appendJsonString(s: String) {
        append('"')
        for (c in s) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else ->
                    if (c < ' ') append("\\u%04x".format(c.code))
                    else append(c)
            }
        }
        append('"')
    }

    /** Verbatim vault map for the outer document (never reformat the blob). */
    fun storedVaultMap(stored: StoredVault): LinkedHashMap<String, Any?> = linkedMapOf(
        "version" to stored.version,
        "contents" to stored.contents,
        "keySalt" to stored.keySalt,
        "iv" to stored.iv,
    )

    /**
     * Desktop maybeEncryptConfig parity: the encryption source is the live
     * store — the decrypted blob config when the outer doc is the encrypted
     * shell, else the outer doc minus vault/encrypted/configSync.
     * Encrypting the stripped shell itself would wipe the blob (data loss).
     */
    fun encryptSource(
        outer: LinkedHashMap<String, Any?>,
        blobConfig: Map<String, Any?>,
    ): LinkedHashMap<String, Any?> =
        if (isEncrypted(outer)) LinkedHashMap(blobConfig)
        else LinkedHashMap<String, Any?>(outer).also {
            it.remove(KEY_VAULT)
            it.remove(KEY_ENCRYPTED)
            it.remove(KEY_CONFIG_SYNC)
        }

    fun isEncrypted(doc: Map<String, Any?>): Boolean = doc[KEY_ENCRYPTED] as? Boolean ?: false

    @Suppress("UNCHECKED_CAST")
    fun storedVault(doc: Map<String, Any?>): StoredVault? {
        val v = doc[KEY_VAULT] as? Map<String, Any?> ?: return null
        return StoredVault(
            version = (v["version"] as? Number)?.toInt() ?: 1,
            contents = v["contents"]?.toString() ?: "",
            keySalt = v["keySalt"]?.toString() ?: "",
            iv = v["iv"]?.toString() ?: "",
        )
    }

    @Suppress("UNCHECKED_CAST")
    fun toDomain(doc: Map<String, Any?>): TabbyConfig {
        val version = (doc[KEY_VERSION] as? Number)?.toInt() ?: 1
        val encrypted = isEncrypted(doc)
        val vault = storedVault(doc)
        val profiles = ((doc[KEY_PROFILES] as? List<*>) ?: emptyList<Any>())
            .filterIsInstance<Map<String, Any?>>()
            .mapNotNull { parseProfile(it) }
        val groups = ((doc[KEY_GROUPS] as? List<*>) ?: emptyList<Any>())
            .filterIsInstance<Map<String, Any?>>()
            .mapNotNull {
                val id = it["id"]?.toString() ?: return@mapNotNull null
                val name = it["name"]?.toString() ?: return@mapNotNull null
                ProfileGroup(id = id, name = name, parentGroupId = it["parentGroupId"]?.toString())
            }
        val sshMap = doc[KEY_SSH] as? Map<String, Any?>
        val ssh = SshGlobals(
            knownHosts = (sshMap?.get("knownHosts") as? List<*>)?.mapNotNull { entry ->
                (entry as? Map<*, *>)?.let { m ->
                    val host = m["host"]?.toString() ?: return@mapNotNull null
                    KnownHostEntry(
                        host = host,
                        port = (m["port"] as? Number)?.toInt() ?: 22,
                        type = m["type"]?.toString() ?: "",
                        digest = m["digest"]?.toString() ?: "",
                    )
                }
            } ?: emptyList(),
            verifyHostKeys = sshMap?.get("verifyHostKeys") as? Boolean ?: true,
            warnOnClose = sshMap?.get("warnOnClose") as? Boolean ?: false,
        )
        val csMap = doc[KEY_CONFIG_SYNC] as? Map<String, Any?>
        val parts = csMap?.get("parts") as? Map<String, Any?>
        val configSync = ConfigSync(
            host = csMap?.get("host")?.toString(),
            token = csMap?.get("token")?.toString(),
            configID = (csMap?.get("configID") as? Number)?.toLong(),
            auto = csMap?.get("auto") as? Boolean ?: false,
            partsHotkeys = parts?.get("hotkeys") as? Boolean ?: true,
            partsAppearance = parts?.get("appearance") as? Boolean ?: true,
            partsVault = parts?.get("vault") as? Boolean ?: true,
        )
        return TabbyConfig(version, profiles, groups, ssh, configSync, vault, encrypted)
    }

    @Suppress("UNCHECKED_CAST") // dynamic YAML maps: keys are strings by construction
    private fun parseProfile(m: Map<String, Any?>): SshProfile? {
        // Desktop v4 parity (config.service.ts:390): id-less profiles get
        // `<type>:custom:<uuid>`, never dropped.
        // (Random per parse; AppState memoizes one Domain view per loaded store,
        // so ids stay stable for the session.)
        val type = m["type"]?.toString() ?: "ssh"
        val id = m["id"]?.toString() ?: "$type:custom:${java.util.UUID.randomUUID()}"
        // Desktop lists every type (ssh, serial, telnet, ...). Only SSH can
        // connect on mobile; other types resolve to a skeleton (group/folder
        // placement intact) and the UI gates connect. RAW stays lossless.
        if (type != "ssh") {
            return SshProfile(
                id = id,
                type = type,
                name = m["name"]?.toString() ?: id,
                group = m["group"]?.toString(),
                icon = m["icon"]?.toString(),
                color = m["color"]?.toString(),
                disableDynamicTitle = m["disableDynamicTitle"] as? Boolean,
                options = SshOptions(),
            )
        }
        val name = m["name"]?.toString() ?: id
        val o = (m["options"] as? Map<String, Any?>) ?: emptyMap()
        return SshProfile(
            id = id,
            type = type,
            name = name,
            group = m["group"]?.toString(),
            icon = m["icon"]?.toString(),
            color = m["color"]?.toString(),
            disableDynamicTitle = m["disableDynamicTitle"] as? Boolean,
            options = SshOptions(
                host = o["host"]?.toString() ?: "",
                port = (o["port"] as? Number)?.toInt() ?: 22,
                user = o["user"]?.toString() ?: "root",
                auth = o["auth"]?.toString(),
                password = o["password"]?.toString(),
                privateKeys = (o["privateKeys"] as? List<*>)?.map { it.toString() } ?: emptyList(),
                keepaliveInterval = (o["keepaliveInterval"] as? Number)?.toLong() ?: 5000,
                keepaliveCountMax = (o["keepaliveCountMax"] as? Number)?.toInt() ?: 10,
                readyTimeout = (o["readyTimeout"] as? Number)?.toLong(),
                jumpHost = o["jumpHost"]?.toString(),
                agentForward = o["agentForward"] as? Boolean ?: false,
                x11 = o["x11"] as? Boolean ?: false,
                skipBanner = o["skipBanner"] as? Boolean ?: false,
                warnOnClose = o["warnOnClose"] as? Boolean,
                proxyCommand = o["proxyCommand"]?.toString(),
                socksProxyHost = o["socksProxyHost"]?.toString(),
                socksProxyPort = (o["socksProxyPort"] as? Number)?.toInt(),
                httpProxyHost = o["httpProxyHost"]?.toString(),
                httpProxyPort = (o["httpProxyPort"] as? Number)?.toInt(),
                reuseSession = o["reuseSession"] as? Boolean ?: true,
                algorithms = parseAlgorithms(o["algorithms"]),
                forwardedPorts = parseForwardedPorts(o["forwardedPorts"]),
                scripts = parseLoginScripts(o["scripts"]),
            ),
        )
    }

    @Suppress("UNCHECKED_CAST") // dynamic YAML maps: keys are strings by construction
    private fun parseAlgorithms(raw: Any?): Map<String, List<String>> {
        val m = raw as? Map<String, Any?> ?: return emptyMap()
        return SshAlgorithms.TYPES.mapNotNull { k ->
            val list = (m[k] as? List<*>)?.map { it.toString() }
            if (list == null) null else k to list
        }.toMap()
    }

    private fun parseForwardedPorts(raw: Any?): List<ForwardedPort> {
        val list = raw as? List<*> ?: return emptyList()
        return list.filterIsInstance<Map<String, Any?>>().map { m ->
            ForwardedPort(
                type = m["type"]?.toString() ?: "Local",
                host = m["host"]?.toString() ?: "127.0.0.1",
                port = (m["port"] as? Number)?.toInt() ?: 8000,
                targetAddress = m["targetAddress"]?.toString() ?: "127.0.0.1",
                targetPort = (m["targetPort"] as? Number)?.toInt() ?: 80,
                description = m["description"]?.toString() ?: "",
            )
        }
    }

    private fun parseLoginScripts(raw: Any?): List<LoginScript> {
        val list = raw as? List<*> ?: return emptyList()
        return list.filterIsInstance<Map<String, Any?>>().map { m ->
            LoginScript(
                expect = m["expect"]?.toString() ?: "",
                send = m["send"]?.toString() ?: "",
                isRegex = m["isRegex"] as? Boolean ?: false,
                optional = m["optional"] as? Boolean ?: false,
            )
        }
    }

    /**
     * The upload document — parity with desktop readConfigDataForSync + parts merge.
     * - configSync is always dropped (never sent).
     * - When parts[part]==false, the part is taken from remote (never overwritten locally).
     */
    fun buildUploadDoc(
        localRaw: LinkedHashMap<String, Any?>,
        remoteRaw: LinkedHashMap<String, Any?>?,
        parts: Map<String, Boolean>,
    ): LinkedHashMap<String, Any?> {
        val doc = LinkedHashMap<String, Any?>(localRaw)
        doc.remove(KEY_CONFIG_SYNC)
        if (remoteRaw != null) {
            for (part in OPTIONAL_PARTS) {
                if (parts[part] == false && remoteRaw.containsKey(part)) {
                    doc[part] = remoteRaw[part]
                }
            }
        }
        return doc
    }

    /**
     * The download merge — parity with desktop download().
     * - data.configSync is always the local one (never from the cloud).
     * - When the remote is plaintext and parts[part]==false, the local part is kept.
     * - version defaults to 1 when missing.
     */
    @Suppress("UNCHECKED_CAST") // dynamic YAML maps: keys are strings by construction
    fun mergeDownload(
        remoteRaw: LinkedHashMap<String, Any?>,
        localRaw: LinkedHashMap<String, Any?>,
        parts: Map<String, Boolean>,
    ): LinkedHashMap<String, Any?> {
        val doc = LinkedHashMap<String, Any?>(remoteRaw)
        (localRaw[KEY_CONFIG_SYNC] as? Map<String, Any?>)?.let {
            doc[KEY_CONFIG_SYNC] = it
        } ?: doc.remove(KEY_CONFIG_SYNC)
        if (!isEncrypted(doc)) {
            for (part in OPTIONAL_PARTS) {
                if (parts[part] == false && localRaw.containsKey(part)) {
                    doc[part] = localRaw[part]
                }
            }
        }
        if (!doc.containsKey(KEY_VERSION)) doc[KEY_VERSION] = 1
        return doc
    }

    /**
     * Convert a decrypted vault JSON object -> YAML so it can be re-parsed
     * by [loadRaw] (SnakeYAML). No nested structure is lost. Shared by the
     * sync layer and the vault-state resolution so tests exercise one path.
     */
    fun yamlFromJson(configJson: String): String =
        dumpRaw(jsonToRaw(configJson))

    /**
     * Merge edited domain profile fields over the existing raw profile map.
     * Unknown top-level and option keys are preserved (lossless); only keys
     * the editor owns are overwritten.
     *
     * @param passwordField null = keep the existing key untouched;
     * "" = remove the key (desktop convention: vault-managed passwords leave
     * no plaintext field); otherwise set the literal (plaintext configs).
     * @param groupWrite null = keep; "" = remove the key (ungrouped);
     * otherwise set the value.
     */
    @Suppress("UNCHECKED_CAST")
    fun updateProfileMap(
        existing: Map<String, Any?>,
        p: SshProfile,
        passwordField: String?,
        groupWrite: String?,
        privateKeys: List<String>,
    ): LinkedHashMap<String, Any?> {
        val out = LinkedHashMap<String, Any?>(existing)
        out["id"] = p.id
        out["type"] = p.type
        out["name"] = p.name
        if (groupWrite != null) {
            if (groupWrite.isEmpty()) out.remove("group") else out["group"] = groupWrite
        }
        // Profile identity color is written from the domain object (blank
        // clears the key, defaults-omitted parity). Icon has no mobile
        // picker, so the raw copy above preserves the stored value; an
        // explicit domain value still wins (create path).
        val color = normalizeProfileColor(p.color)
        if (color != null) out["color"] = color else out.remove("color")
        if (p.icon != null) out["icon"] = p.icon
        val o = p.options
        val opts = LinkedHashMap<String, Any?>((existing["options"] as? Map<String, Any?>) ?: emptyMap())
        opts["host"] = o.host
        opts["port"] = o.port
        opts["user"] = o.user
        if (o.auth != null) opts["auth"] = o.auth else opts.remove("auth")
        if (passwordField != null) {
            if (passwordField.isEmpty()) opts.remove("password") else opts["password"] = passwordField
        }
        opts["privateKeys"] = privateKeys.toList()
        if (o.jumpHost != null) opts["jumpHost"] = o.jumpHost else opts.remove("jumpHost")
        // Desktop ConfigProxy parity: keys the editor owns are written only
        // when they differ from the built-in defaults, otherwise removed —
        // the cloud YAML stores non-defaults, defaults come from code.
        val d = SshOptions()
        if (o.keepaliveInterval != d.keepaliveInterval) opts["keepaliveInterval"] = o.keepaliveInterval
        else opts.remove("keepaliveInterval")
        if (o.keepaliveCountMax != d.keepaliveCountMax) opts["keepaliveCountMax"] = o.keepaliveCountMax
        else opts.remove("keepaliveCountMax")
        if (o.readyTimeout != null) opts["readyTimeout"] = o.readyTimeout else opts.remove("readyTimeout")
        if (o.agentForward) opts["agentForward"] = true else opts.remove("agentForward")
        if (o.x11) opts["x11"] = true else opts.remove("x11")
        if (o.skipBanner) opts["skipBanner"] = true else opts.remove("skipBanner")
        if (o.warnOnClose == true) opts["warnOnClose"] = true else opts.remove("warnOnClose")
        if (o.reuseSession != d.reuseSession) opts["reuseSession"] = o.reuseSession
        else opts.remove("reuseSession")
        if (o.proxyCommand != null) opts["proxyCommand"] = o.proxyCommand else opts.remove("proxyCommand")
        if (o.socksProxyHost != null) opts["socksProxyHost"] = o.socksProxyHost
        else opts.remove("socksProxyHost")
        if (o.socksProxyPort != null) opts["socksProxyPort"] = o.socksProxyPort
        else opts.remove("socksProxyPort")
        if (o.httpProxyHost != null) opts["httpProxyHost"] = o.httpProxyHost
        else opts.remove("httpProxyHost")
        if (o.httpProxyPort != null) opts["httpProxyPort"] = o.httpProxyPort
        else opts.remove("httpProxyPort")
        if (o.algorithms == SshAlgorithms.DEFAULTS) opts.remove("algorithms")
        else if (o.algorithms.isNotEmpty()) {
            // Desktop sorts on save except compression (profiles.ts + editor).
            val sorted = LinkedHashMap<String, Any?>()
            for (k in SshAlgorithms.TYPES) {
                val list = o.algorithms[k] ?: continue
                sorted[k] = if (k == SshAlgorithms.COMPRESSION) list.toList() else list.sorted()
            }
            opts["algorithms"] = sorted
        }
        if (o.forwardedPorts.isNotEmpty()) {
            opts["forwardedPorts"] = o.forwardedPorts.map { f ->
                linkedMapOf<String, Any?>(
                    "type" to f.type,
                    "host" to f.host,
                    "port" to f.port,
                    "targetAddress" to f.targetAddress,
                    "targetPort" to f.targetPort,
                    "description" to f.description,
                )
            }
        } else opts.remove("forwardedPorts")
        if (o.scripts.isNotEmpty()) {
            opts["scripts"] = o.scripts.map { s ->
                linkedMapOf<String, Any?>(
                    "expect" to s.expect,
                    "send" to s.send,
                    "isRegex" to s.isRegex,
                    "optional" to s.optional,
                )
            }
        } else opts.remove("scripts")
        out["options"] = opts
        return out
    }

    /**
     * Write value for a changed profile group. Real (desktop v4) group ids
     * are written back as ids; legacy name-based groups have session-minted
     * ids, so the stable NAME is written instead (a minted id would orphan
     * the profile on the next load).
     */
    fun resolveGroupWriteValue(rawGroupIds: Set<String>, newId: String, newName: String?): String =
        if (newId in rawGroupIds) newId else (newName?.takeIf { it.isNotBlank() } ?: newId)

    /**
     * Upserts a desktop-format trust entry into `ssh.knownHosts` (match on
     * host+port+type, replaces digest). The single source of trust — read
     * offline from the local cache, synced to desktop via upload.
     */
    @Suppress("UNCHECKED_CAST")
    fun appendKnownHost(doc: LinkedHashMap<String, Any?>, entry: KnownHostEntry) {
        val ssh = LinkedHashMap((doc[KEY_SSH] as? Map<*, *>)?.entries?.associate { (k, v) -> k.toString() to v } ?: emptyMap())
        val list = ((ssh["knownHosts"] as? List<*>)?.toMutableList() ?: mutableListOf())
        val idx = list.indexOfFirst { m ->
            (m as? Map<*, *>)?.let {
                it["host"]?.toString() == entry.host &&
                    ((it["port"] as? Number)?.toInt() ?: 22) == entry.port &&
                    it["type"]?.toString() == entry.type
            } == true
        }
        val map = linkedMapOf<String, Any?>(
            "host" to entry.host,
            "port" to entry.port,
            "type" to entry.type,
            "digest" to entry.digest,
        )
        if (idx >= 0) list[idx] = map else list += map
        ssh["knownHosts"] = list
        doc[KEY_SSH] = ssh
    }

    /**
     * Index of a profile in a raw profiles list. Id-less legacy profiles get
     * session-minted ids, so fall back to name+type+connection params for
     * those (`:custom:` ids only). Returns -1 when absent.
     */
    @Suppress("UNCHECKED_CAST")
    fun findProfileIndex(
        profiles: List<*>,
        profileId: String,
        name: String?,
        type: String?,
        host: String?,
        user: String?,
    ): Int {
        profiles.forEachIndexed { i, m ->
            if ((m as? Map<*, *>)?.get("id")?.toString() == profileId) return i
        }
        if (!profileId.contains(":custom:")) return -1
        profiles.forEachIndexed { i, m ->
            val map = m as? Map<*, *> ?: return@forEachIndexed
            val o = map["options"] as? Map<String, Any?>
            if (map["name"]?.toString() == name &&
                (map["type"]?.toString() ?: "ssh") == (type ?: "ssh") &&
                (o?.get("host")?.toString() ?: "") == (host ?: "") &&
                (o?.get("user")?.toString() ?: "") == (user ?: "")
            ) return i
        }
        return -1
    }

    fun normalizeHost(raw: String): String {
        val h = raw.trim().trimEnd('/')
        require(h.isNotEmpty()) { "Sync host is empty" }
        require(h.startsWith("http://") || h.startsWith("https://")) {
            "Host must be http:// or https://"
        }
        return h
    }

    fun isHttps(host: String): Boolean = host.startsWith("https://", ignoreCase = true)

    /**
     * Desktop id parity (profiles.service.ts): `<type>:custom:<slug>:<uuid>`.
     * slugify approximation: lowercase, non-alphanumerics collapse to '-'.
     */
    fun slugify(name: String): String {
        val s = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        return s.ifBlank { "profile" }
    }

    fun mintProfileId(type: String, name: String): String =
        "$type:custom:${slugify(name)}:${java.util.UUID.randomUUID()}"

    /** Inline plaintext password carrier (desktop: transient, never at rest). */
    data class InlinePassword(val user: String, val host: String, val port: Int, val value: String)

    /**
     * Returns the inline `options.password` when it is a real plaintext
     * secret (null when absent, blank, or already a vault:// ref).
     * Parse fallbacks (root/22) match parseProfile so secret keys resolve.
     */
    @Suppress("UNCHECKED_CAST")
    fun inlinePasswordOf(profile: Map<String, Any?>): InlinePassword? {
        val o = profile["options"] as? Map<String, Any?> ?: return null
        val pw = o["password"]?.toString() ?: return null
        if (pw.isBlank() || pw.startsWith("vault://")) return null
        return InlinePassword(
            user = o["user"]?.toString() ?: "root",
            host = o["host"]?.toString() ?: "",
            port = (o["port"] as? Number)?.toInt() ?: 22,
            value = pw,
        )
    }

    /** Copy of the profile map with the inline password key removed. */
    @Suppress("UNCHECKED_CAST")
    fun withoutInlinePassword(profile: Map<String, Any?>): LinkedHashMap<String, Any?> {
        val out = LinkedHashMap<String, Any?>(profile)
        val o = LinkedHashMap<String, Any?>(profile["options"] as? Map<String, Any?> ?: emptyMap())
        o.remove("password")
        out["options"] = o
        return out
    }

    /** All `options.privateKeys` entries as strings (refs, PEM, or paths). */
    @Suppress("UNCHECKED_CAST")
    fun privateKeyRefs(profile: Map<String, Any?>): List<String> {
        val o = profile["options"] as? Map<String, Any?> ?: return emptyList()
        return (o["privateKeys"] as? List<*>)?.map { it.toString() } ?: emptyList()
    }

    /**
     * Pasted PEM contents resting inline (desktop keeps keys as refs/paths,
     * never PEM in YAML). Key PATHS are intentionally NOT swept — they are
     * meaningful entries desktop resolves, not secrets.
     */
    fun inlineKeyPems(profile: Map<String, Any?>): List<String> =
        privateKeyRefs(profile).filter { !it.startsWith("vault://") && it.contains("-----BEGIN") }

    /** Copy of the profile map with the given final key-ref list. */
    @Suppress("UNCHECKED_CAST")
    fun withPrivateKeys(profile: Map<String, Any?>, refs: List<String>): LinkedHashMap<String, Any?> {
        val out = LinkedHashMap<String, Any?>(profile)
        val o = LinkedHashMap<String, Any?>(profile["options"] as? Map<String, Any?> ?: emptyMap())
        o["privateKeys"] = refs.toList()
        out["options"] = o
        return out
    }
}
