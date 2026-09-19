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
    const val KEY_PROFILE_BLACKLIST = "profileBlacklist"
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

    fun loadRaw(yamlStr: String): LinkedHashMap<String, Any?> {
        if (yamlStr.isBlank()) return linkedMapOf(KEY_VERSION to ConfigMigrator.LATEST_VERSION)
        val loaded = yaml().load<Any>(yamlStr)
        return when (loaded) {
            // `is Map<*, *>` is fully checkable; asMutableStringMap copies
            // with string keys (dynamic YAML maps: keys are strings by construction).
            is Map<*, *> -> loaded.asMutableStringMap()
                ?: linkedMapOf(KEY_VERSION to ConfigMigrator.LATEST_VERSION)
            else -> linkedMapOf(KEY_VERSION to ConfigMigrator.LATEST_VERSION)
        }
    }

    fun dumpRaw(doc: Map<String, Any?>): String = yaml().dump(doc)

    /**
     * Strict parse for Settings > Config file > Import (pasted full YAML).
     * Unlike [loadRaw] (forgiving, for disk), this REJECTS anything that is
     * not a Tabby config so a bad paste never wipes the local config:
     * blank text, YAML syntax errors, a non-mapping top level, a missing or
     * non-list `profiles`. Encrypted shells (`encrypted: true` + vault blob)
     * are accepted — the passphrase is asked lazily after import, same as a
     * downloaded encrypted config.
     *
     * @throws IllegalArgumentException with a UI-ready message.
     */
    fun parseImport(text: String): LinkedHashMap<String, Any?> {
        require(text.isNotBlank()) { "Paste a YAML config first" }
        val loaded: Any? = try {
            yaml().load<Any>(text)
        } catch (e: Exception) {
            val first = e.message?.lineSequence()?.firstOrNull()?.take(160)
            throw IllegalArgumentException("Invalid YAML${if (first.isNullOrBlank()) "" else ": $first"}")
        }
        val doc: LinkedHashMap<String, Any?> = when (loaded) {
            is Map<*, *> -> loaded.asMutableStringMap()
                ?: throw IllegalArgumentException("Not a Tabby config (top level must be a mapping)")
            else -> throw IllegalArgumentException("Not a Tabby config (top level must be a mapping)")
        }
        if (isEncrypted(doc) && storedVault(doc) != null) return doc
        val profiles = doc[KEY_PROFILES]
            ?: throw IllegalArgumentException("Not a Tabby config (missing 'profiles' list)")
        require(profiles is List<*>) { "Not a Tabby config ('profiles' must be a list)" }
        return doc
    }

    /**
     * `terminal.showRecentProfiles` read with desktop-default fallback.
     * Negative garbage coerces to 0 (disabled); non-numeric to the default.
     * Read from [SyncRepository.Loaded.store] (decrypted merged view when
     * unlocked, outer raw otherwise) — never from the defaulted Domain view.
     */
    fun showRecentProfiles(doc: Map<String, Any?>): Int {
        val n = ((doc[KEY_TERMINAL].asStringMap())?.get("showRecentProfiles") as? Number)
            ?.toInt() ?: return DEFAULT_SHOW_RECENT_PROFILES
        return n.coerceAtLeast(0)
    }

    /**
     * Explicit user set (stepper): creates the `terminal` map when absent.
     * Like desktop (`ngModelChange=config.save()`), an explicit set is
     * persisted even when it equals the default.
     */
    fun setShowRecentProfiles(doc: MutableMap<String, Any?>, v: Int) {
        val term = LinkedHashMap(
            (doc[KEY_TERMINAL].asStringMap()) ?: emptyMap(),
        )
        term["showRecentProfiles"] = v.coerceIn(0, MAX_SHOW_RECENT_PROFILES)
        doc[KEY_TERMINAL] = term
    }

    /**
     * Global `terminal.colorScheme` read (desktop TerminalConfigProvider
     * parity). Null when absent/unparseable — render falls back to
     * [IZS_DEFAULT_SCHEME]. `lightColorScheme` is ignored (dark-only app).
     */
    fun terminalColorSchemeRaw(doc: Map<String, Any?>): TerminalColorScheme? =
        parseTerminalColorScheme((doc[KEY_TERMINAL].asStringMap())?.get("colorScheme"))

    /**
     * Explicit user set: writes the full scheme object (desktop stores
     * objects inline, never name refs). Null REMOVES the key — and the
     * `terminal` map itself when left empty — restoring absent = default.
     */
    fun setTerminalColorScheme(doc: MutableMap<String, Any?>, scheme: TerminalColorScheme?) {
        val term = LinkedHashMap(
            (doc[KEY_TERMINAL].asStringMap()) ?: emptyMap(),
        )
        if (scheme == null) term.remove("colorScheme") else term["colorScheme"] = scheme.toRawMap()
        if (term.isEmpty()) doc.remove(KEY_TERMINAL) else doc[KEY_TERMINAL] = term
    }

    /**
     * `terminal.customColorSchemes` read (desktop parity). Unparseable
     * entries are skipped, never fatal.
     */
    fun customColorSchemesRaw(doc: Map<String, Any?>): List<TerminalColorScheme> =
        (((doc[KEY_TERMINAL].asStringMap())?.get("customColorSchemes") as? List<*>)
            ?: emptyList<Any>()).mapNotNull { parseTerminalColorScheme(it) }

    /** Explicit user set (custom scheme editor). Empty list removes the key. */
    fun setCustomColorSchemes(doc: MutableMap<String, Any?>, schemes: List<TerminalColorScheme>) {
        val term = LinkedHashMap(
            (doc[KEY_TERMINAL].asStringMap()) ?: emptyMap(),
        )
        if (schemes.isEmpty()) term.remove("customColorSchemes")
        else term["customColorSchemes"] = schemes.map { it.toRawMap() }
        if (term.isEmpty()) doc.remove(KEY_TERMINAL) else doc[KEY_TERMINAL] = term
    }

    /**
     * `terminal.font` read (desktop parity). Null when absent/non-string —
     * absent means the desktop default; resolve with [resolveTerminalFont].
     */
    fun terminalFontName(doc: Map<String, Any?>): String? =
        (doc[KEY_TERMINAL].asStringMap())?.get("font") as? String

    /**
     * Explicit user set (Appearance > Font). Null REMOVES the key — and the
     * `terminal` map itself when left empty — restoring absent = default.
     */
    fun setTerminalFont(doc: MutableMap<String, Any?>, name: String?) {
        val term = LinkedHashMap(
            (doc[KEY_TERMINAL].asStringMap()) ?: emptyMap(),
        )
        if (name == null) term.remove("font") else term["font"] = name
        if (term.isEmpty()) doc.remove(KEY_TERMINAL) else doc[KEY_TERMINAL] = term
    }

    /**
     * `terminal.cursor` read with desktop-default fallback (block).
     * Unknown garbage resolves to BLOCK via [parseTerminalCursor].
     */
    fun terminalCursor(doc: Map<String, Any?>): TerminalCursor =
        parseTerminalCursor((doc[KEY_TERMINAL].asStringMap())?.get("cursor") as? String)

    /**
     * Explicit user set (Appearance > Cursor): persisted even when it
     * equals the default (showRecentProfiles parity).
     */
    fun setTerminalCursor(doc: MutableMap<String, Any?>, cursor: TerminalCursor) {
        val term = LinkedHashMap(
            (doc[KEY_TERMINAL].asStringMap()) ?: emptyMap(),
        )
        term["cursor"] = terminalCursorYamlName(cursor)
        doc[KEY_TERMINAL] = term
    }

    /**
     * `terminal.cursorBlink` read with desktop-default fallback (true).
     * Non-boolean garbage falls back to the default, never fatal.
     */
    fun terminalCursorBlink(doc: Map<String, Any?>): Boolean =
        (doc[KEY_TERMINAL].asStringMap())?.get("cursorBlink") as? Boolean ?: true

    /** Explicit user set (Appearance > Cursor blink). */
    fun setTerminalCursorBlink(doc: MutableMap<String, Any?>, blink: Boolean) {
        val term = LinkedHashMap(
            (doc[KEY_TERMINAL].asStringMap()) ?: emptyMap(),
        )
        term["cursorBlink"] = blink
        doc[KEY_TERMINAL] = term
    }

    /**
     * Clipboard parity (tabby-terminal/src/config.ts + terminalSettingsTab
     * Clipboard section). `copyOnSelect`/`copyAsHTML` are intentionally NOT
     * synced.
     *
     * Desktop defaults: bracketedPaste=true, warnOnMultilinePaste=true,
     * replaceNewlinesWithSpacesOnPaste=false, trimWhitespaceOnPaste=true.
     * Absent key = default (minimal YAML). Setters follow desktop
     * ConfigProxy parity: writing a value equal to the default REMOVES the
     * key — and the `terminal` map itself when left empty.
     */
    const val DEFAULT_BRACKETED_PASTE = true
    const val DEFAULT_WARN_ON_MULTILINE_PASTE = true
    const val DEFAULT_REPLACE_NEWLINES_WITH_SPACES_ON_PASTE = false
    const val DEFAULT_TRIM_WHITESPACE_ON_PASTE = true

    private fun terminalMap(doc: Map<String, Any?>): Map<String, Any?>? =
        doc[KEY_TERMINAL].asStringMap()

    private fun mutableTerminalMap(doc: MutableMap<String, Any?>): LinkedHashMap<String, Any?> =
        LinkedHashMap(terminalMap(doc) ?: emptyMap())

    private fun putTerminalKey(doc: MutableMap<String, Any?>, key: String, value: Any?, isDefault: Boolean) {
        val term = mutableTerminalMap(doc)
        if (isDefault) term.remove(key) else term[key] = value
        if (term.isEmpty()) doc.remove(KEY_TERMINAL) else doc[KEY_TERMINAL] = term
    }

    fun terminalBracketedPaste(doc: Map<String, Any?>): Boolean =
        terminalMap(doc)?.get("bracketedPaste") as? Boolean ?: DEFAULT_BRACKETED_PASTE

    fun setTerminalBracketedPaste(doc: MutableMap<String, Any?>, v: Boolean) =
        putTerminalKey(doc, "bracketedPaste", v, v == DEFAULT_BRACKETED_PASTE)

    fun terminalWarnOnMultilinePaste(doc: Map<String, Any?>): Boolean =
        terminalMap(doc)?.get("warnOnMultilinePaste") as? Boolean ?: DEFAULT_WARN_ON_MULTILINE_PASTE

    fun setTerminalWarnOnMultilinePaste(doc: MutableMap<String, Any?>, v: Boolean) =
        putTerminalKey(doc, "warnOnMultilinePaste", v, v == DEFAULT_WARN_ON_MULTILINE_PASTE)

    fun terminalReplaceNewlinesWithSpacesOnPaste(doc: Map<String, Any?>): Boolean =
        terminalMap(doc)?.get("replaceNewlinesWithSpacesOnPaste") as? Boolean
            ?: DEFAULT_REPLACE_NEWLINES_WITH_SPACES_ON_PASTE

    fun setTerminalReplaceNewlinesWithSpacesOnPaste(doc: MutableMap<String, Any?>, v: Boolean) =
        putTerminalKey(
            doc, "replaceNewlinesWithSpacesOnPaste", v,
            v == DEFAULT_REPLACE_NEWLINES_WITH_SPACES_ON_PASTE,
        )

    fun terminalTrimWhitespaceOnPaste(doc: Map<String, Any?>): Boolean =
        terminalMap(doc)?.get("trimWhitespaceOnPaste") as? Boolean ?: DEFAULT_TRIM_WHITESPACE_ON_PASTE

    fun setTerminalTrimWhitespaceOnPaste(doc: MutableMap<String, Any?>, v: Boolean) =
        putTerminalKey(doc, "trimWhitespaceOnPaste", v, v == DEFAULT_TRIM_WHITESPACE_ON_PASTE)

    /**
     * `appearance.tabsLocation` read WITHOUT desktop-default fallback.
     * Desktop resolves absent → `top` (configDefaults.yaml); on the phone
     * absent means OFF (the current list-based UX, no tab chrome) — a
     * deliberate phone default, documented in ARCHITECTURE.md. Only an
     * explicitly synced value turns the tab UI on. Returns the raw string
     * (or null when absent/non-string); use [resolveTabLocation] to map it.
     * Read from [SyncRepository.Loaded.store], like [showRecentProfiles].
     */
    fun tabsLocationRaw(doc: Map<String, Any?>): String? =
        (doc[KEY_APPEARANCE].asStringMap())?.get("tabsLocation") as? String

    /**
     * Explicit user set (Window settings): writes the desktop-owned
     * `appearance.tabsLocation` key. `null` (Off) REMOVES the key — and the
     * `appearance` map itself when left empty — restoring absent = Off.
     */
    fun setTabsLocation(doc: MutableMap<String, Any?>, v: TabLocation?) {
        val app = LinkedHashMap(
            (doc[KEY_APPEARANCE].asStringMap()) ?: emptyMap(),
        )
        if (v == null || v == TabLocation.OFF) app.remove("tabsLocation")
        else app["tabsLocation"] = v.yamlValue
        if (app.isEmpty()) doc.remove(KEY_APPEARANCE) else doc[KEY_APPEARANCE] = app
    }

    /**
     * Parse a vault-blob config JSON object into a raw document (decrypt path).
     * Inverse of [toJson]; shared with the sync layer so tests exercise it.
     */
    fun jsonToRaw(configJson: String): LinkedHashMap<String, Any?> {
        val el = kotlinx.serialization.json.Json.parseToJsonElement(configJson)
        val map = jsonElementToJava(el).asStringMap() ?: emptyMap()
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

    fun storedVault(doc: Map<String, Any?>): StoredVault? {
        val v = doc[KEY_VAULT].asStringMap() ?: return null
        return StoredVault(
            version = (v["version"] as? Number)?.toInt() ?: 1,
            contents = v["contents"]?.toString() ?: "",
            keySalt = v["keySalt"]?.toString() ?: "",
            iv = v["iv"]?.toString() ?: "",
        )
    }

    fun toDomain(doc: Map<String, Any?>): TabbyConfig {
        val version = (doc[KEY_VERSION] as? Number)?.toInt() ?: ConfigMigrator.LATEST_VERSION
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
        val sshMap = doc[KEY_SSH].asStringMap()
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
        val csMap = doc[KEY_CONFIG_SYNC].asStringMap()
        val parts = csMap?.get("parts").asStringMap()
        val configSync = ConfigSync(
            host = csMap?.get("host")?.toString(),
            token = csMap?.get("token")?.toString(),
            configID = (csMap?.get("configID") as? Number)?.toLong(),
            auto = csMap?.get("auto") as? Boolean ?: false,
            partsHotkeys = parts?.get("hotkeys") as? Boolean ?: true,
            partsAppearance = parts?.get("appearance") as? Boolean ?: true,
            partsVault = parts?.get("vault") as? Boolean ?: true,
        )
        return TabbyConfig(
            version, profiles, groups, ssh, configSync, vault, encrypted,
            terminalColorScheme = terminalColorSchemeRaw(doc),
            customColorSchemes = customColorSchemesRaw(doc),
        )
    }

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
                terminalColorScheme = parseTerminalColorScheme(m["terminalColorScheme"]),
                options = SshOptions(),
            )
        }
        val name = m["name"]?.toString() ?: id
        val o = (m["options"].asStringMap()) ?: emptyMap()
        return SshProfile(
            id = id,
            type = type,
            name = name,
            group = m["group"]?.toString(),
            icon = m["icon"]?.toString(),
            color = m["color"]?.toString(),
            disableDynamicTitle = m["disableDynamicTitle"] as? Boolean,
            terminalColorScheme = parseTerminalColorScheme(m["terminalColorScheme"]),
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
                behaviorOnSessionEnd = (o["behaviorOnSessionEnd"]?.toString()
                    ?.takeIf { it == "keep" || it == "reconnect" || it == "close" }
                    ?: "auto"),
                algorithms = parseAlgorithms(o["algorithms"]),
                forwardedPorts = parseForwardedPorts(o["forwardedPorts"]),
                scripts = parseLoginScripts(o["scripts"]),
            ),
        )
    }

    private fun parseAlgorithms(raw: Any?): Map<String, List<String>> {
        val m = raw.asStringMap() ?: return emptyMap()
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
     * - version defaults to LATEST when missing (a desktop-uploaded remote
     *   always carries one after the desktop's first save).
     */
    fun mergeDownload(
        remoteRaw: LinkedHashMap<String, Any?>,
        localRaw: LinkedHashMap<String, Any?>,
        parts: Map<String, Boolean>,
    ): LinkedHashMap<String, Any?> {
        val doc = LinkedHashMap<String, Any?>(remoteRaw)
        (localRaw[KEY_CONFIG_SYNC].asStringMap())?.let {
            doc[KEY_CONFIG_SYNC] = it
        } ?: doc.remove(KEY_CONFIG_SYNC)
        if (!isEncrypted(doc)) {
            for (part in OPTIONAL_PARTS) {
                if (parts[part] == false && localRaw.containsKey(part)) {
                    doc[part] = localRaw[part]
                }
            }
        }
        if (!doc.containsKey(KEY_VERSION)) doc[KEY_VERSION] = ConfigMigrator.LATEST_VERSION
        return doc
    }

    /**
     * Rename a group entry (desktop parity: groups carry only
     * id/name/parentGroupId). Unknown keys on the entry are preserved
     * (lossless). No-op when the id is absent. Blank-name rejection lives
     * in the repo layer, not here.
     */
    fun renameGroupEntry(doc: MutableMap<String, Any?>, id: String, name: String) {
        val groups = (doc[KEY_GROUPS] as? List<*>) ?: return
        doc[KEY_GROUPS] = groups.map { e ->
            val m = e.asMutableStringMap() ?: return@map e
            if (m["id"]?.toString() == id) m["name"] = name.trim()
            m
        }
    }

    /**
     * Delete a group entry — desktop `deleteProfileGroup(group,
     * {deleteProfiles:false})` parity: member profiles are ungrouped (never
     * deleted) and child groups rise to top level. No-op when absent.
     */
    fun deleteGroupEntry(doc: MutableMap<String, Any?>, id: String) {
        val groups = (doc[KEY_GROUPS] as? List<*>) ?: emptyList<Any>()
        var removed = false
        doc[KEY_GROUPS] = groups.mapNotNull { e ->
            val m = e.asMutableStringMap() ?: return@mapNotNull e
            if (m["id"]?.toString() == id) {
                removed = true
                null
            } else {
                if (m["parentGroupId"]?.toString() == id) m.remove("parentGroupId")
                m
            }
        }
        if (!removed) return
        val profiles = (doc[KEY_PROFILES] as? List<*>) ?: return
        doc[KEY_PROFILES] = profiles.map { p ->
            val m = p.asMutableStringMap() ?: return@map p
            if (m["group"]?.toString() == id) m.remove("group")
            m
        }
    }

    /**
     * Reparent a group (desktop nested-group parity: the tree already
     * renders parentGroupId, this makes it settable). parentId null/blank =
     * top level. Returns false (no write) when the id is absent, the parent
     * is unknown/self, or the move would cycle (group under its own
     * descendant) — the UI filters those out, this is the backstop.
     */
    fun moveGroupEntry(doc: MutableMap<String, Any?>, id: String, parentId: String?): Boolean {
        val groups = (doc[KEY_GROUPS] as? List<*>) ?: return false
        val ids = groups.mapNotNull { (it as? Map<*, *>)?.get("id")?.toString() }.toSet()
        if (id !in ids) return false
        val parent = parentId?.takeIf { it.isNotBlank() }
        if (parent != null) {
            if (parent == id || parent !in ids) return false
            val parentOf = groups.mapNotNull { e ->
                val m = e as? Map<*, *> ?: return@mapNotNull null
                m["id"]?.toString() to m["parentGroupId"]?.toString()
            }.toMap()
            var cursor: String? = parent
            val seen = mutableSetOf<String>()
            while (cursor != null && seen.add(cursor)) {
                if (cursor == id) return false
                cursor = parentOf[cursor]?.takeIf { it.isNotBlank() }
            }
        }
        doc[KEY_GROUPS] = groups.map { e ->
            val m = e.asMutableStringMap() ?: return@map e
            if (m["id"]?.toString() == id) {
                if (parent == null) m.remove("parentGroupId") else m["parentGroupId"] = parent
            }
            m
        }
        // In-place (desktop writeProfileGroup parity): a moved group keeps
        // its YAML slot — only brand-new groups append at the bottom.
        return true
    }

    /**
     * Hidden-profile ids (desktop `profileBlacklist` parity: a synced root
     * list, honored by the new-tab selector). Missing/malformed = empty.
     */
    fun profileBlacklistOf(doc: Map<String, Any?>): Set<String> =
        ((doc[KEY_PROFILE_BLACKLIST] as? List<*>) ?: emptyList<Any>())
            .mapNotNull { it?.toString()?.takeIf { s -> s.isNotBlank() } }
            .toSet()

    /**
     * Hide/show a profile. The key persists (even empty — desktop-shape
     * parity) once touched; unknown ids are kept verbatim (lossless).
     */
    fun setProfileHiddenEntry(doc: MutableMap<String, Any?>, id: String, hidden: Boolean) {
        val ids = profileBlacklistOf(doc).toMutableList()
        if (hidden) {
            if (id !in ids) ids += id
        } else {
            ids.removeAll { it == id }
        }
        doc[KEY_PROFILE_BLACKLIST] = ids
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
        // Per-profile scheme override (desktop parity): a selected scheme is
        // written as a full inline object; "use global" (null) removes the
        // key. Unlike identity `color` above there is no normalization —
        // the picker only produces valid objects.
        if (p.terminalColorScheme != null) out["terminalColorScheme"] = p.terminalColorScheme.toRawMap()
        else out.remove("terminalColorScheme")
        val o = p.options
        val opts = LinkedHashMap<String, Any?>((existing["options"].asStringMap()) ?: emptyMap())
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
        // Desktop key, non-default-only like the rest: auto profiles keep a
        // clean YAML, and the value syncs to desktop verbatim.
        if (o.behaviorOnSessionEnd != d.behaviorOnSessionEnd) opts["behaviorOnSessionEnd"] = o.behaviorOnSessionEnd
        else opts.remove("behaviorOnSessionEnd")
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
     * Sets `ssh.verifyHostKeys` / `ssh.warnOnClose`, preserving the rest of
     * the ssh section (knownHosts etc.). Shared by the plaintext local edit
     * and the encrypted vault-blob edit so both write the identical desktop
     * ssh-section shape. Mutates [doc] in place, like [appendKnownHost].
     */
    fun setSshFlags(doc: LinkedHashMap<String, Any?>, verify: Boolean, warn: Boolean) {
        val ssh = LinkedHashMap(
            (doc[KEY_SSH] as? Map<*, *>)?.entries?.associate { (k, v) -> k.toString() to v }
                ?: emptyMap(),
        )
        ssh["verifyHostKeys"] = verify
        ssh["warnOnClose"] = warn
        doc[KEY_SSH] = ssh
    }

    /**
     * Index of a profile in a raw profiles list. Id-less legacy profiles get
     * session-minted ids, so fall back to name+type+connection params for
     * those (`:custom:` ids only). Returns -1 when absent.
     */
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
            val o = map["options"].asStringMap()
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
     * Sync-target half of the `configSync` section (host/token/configID).
     * Lives in the outer RAW document — readable while the vault is locked,
     * exactly like desktop (config.service keeps configSync outside the
     * encrypted blob). Single source of truth; RAM mirrors it after load.
     */
    data class RawSyncTarget(
        val host: String?,
        val token: String?,
        val configId: Long,
    )

    fun syncTargetOf(doc: Map<String, Any?>): RawSyncTarget {
        // `is Map<*, *>` (not `as? Map<String, Any?>`): fully checkable, so
        // no unchecked-cast warning and no @Suppress needed.
        val cs = doc[KEY_CONFIG_SYNC]
        if (cs !is Map<*, *>) return RawSyncTarget(null, null, -1L)
        return RawSyncTarget(
            host = cs["host"]?.toString(),
            token = cs["token"]?.toString(),
            configId = (cs["configID"] as? Number)?.toLong() ?: -1L,
        )
    }

    fun setSyncTarget(doc: LinkedHashMap<String, Any?>, target: RawSyncTarget) {
        val cs = (doc[KEY_CONFIG_SYNC].asMutableStringMap())
            ?: linkedMapOf<String, Any?>()
        if (target.host == null) cs.remove("host") else cs["host"] = target.host
        if (target.token == null) cs.remove("token") else cs["token"] = target.token
        if (target.configId < 0) cs.remove("configID") else cs["configID"] = target.configId
        // asMutableStringMap returns a detached copy, so the mutated section
        // must be written back — otherwise a present-but-empty `configSync`
        // (fresh-install seed) silently swallows the save.
        doc[KEY_CONFIG_SYNC] = cs
    }

    /**
     * Point a merged/downloaded doc at the cloud config it came from
     * (download retarget). Pure for unit tests; same write-back rule as
     * [setSyncTarget].
     */
    fun retargetSyncSection(doc: LinkedHashMap<String, Any?>, host: String, token: String, configId: Long) {
        val cs = (doc[KEY_CONFIG_SYNC].asMutableStringMap())
            ?: linkedMapOf<String, Any?>()
        cs["host"] = host
        cs["token"] = token
        cs["configID"] = configId
        doc[KEY_CONFIG_SYNC] = cs
    }

    /**
     * Cleartext policy for Config Sync (pure, unit-tested).
     *
     * `https://` is always allowed. `http://` is allowed ONLY for local
     * targets: loopback, RFC 1918 private ranges, link-local, well-known
     * local suffixes (.local/.lan/.home/.internal) and single-label LAN
     * names. Anything else (in particular public http://) is refused so a
     * Bearer token + executable YAML payload never cross the open internet
     * in cleartext. Enforced by [TabbySyncApi] on every request.
     *
     * Note: this lives in code (not network_security_config.xml) because
     * Android network-security-config cannot express IP CIDR ranges.
     */
    fun isSyncHostAllowed(raw: String): Boolean {
        val h = raw.trim()
        if (h.startsWith("https://", ignoreCase = true)) return true
        if (!h.startsWith("http://", ignoreCase = true)) return false
        var authority = h.substringAfter("://").substringBefore('/').trim()
        if (authority.isEmpty()) return false
        authority = authority.substringAfterLast('@')
        val hostOnly = if (authority.startsWith("[")) {
            authority.substringAfter('[').substringBefore(']')
        } else if (authority.count { it == ':' } > 1) {
            authority // bare IPv6 literal, brackets omitted
        } else {
            authority.substringBefore(':')
        }
        val host = hostOnly.trim().trimEnd('.').lowercase()
        if (host.isEmpty()) return false
        if (host == "localhost" || host == "::1") return true
        val numericIp = host.all { it.isDigit() || it == '.' }
        if (numericIp) {
            if (host.startsWith("127.") || host.startsWith("10.") ||
                host.startsWith("192.168.") || host.startsWith("169.254.")
            ) return true
            val parts = host.split('.')
            if (parts.size == 4) {
                val second = parts[1].toIntOrNull()
                if (parts[0].toIntOrNull() == 172 && second != null && second in 16..31) return true
            }
            return false
        }
        if (host.startsWith("fe80:") || host == "fe80") return true
        // IPv6 unique-local (fc00::/7) — only for real IPv6 literals.
        if (':' in host) {
            val firstHextet = host.substringBefore(':')
            if (firstHextet.length == 4 &&
                (firstHextet.startsWith("fc") || firstHextet.startsWith("fd"))
            ) return true
        }
        if (host.endsWith(".local") || host.endsWith(".lan") ||
            host.endsWith(".home") || host.endsWith(".internal")
        ) return true
        // Single-label names (e.g. http://nas:5000) resolve on the LAN.
        if (!host.contains('.') && !host.contains(':')) return true
        return false
    }

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
    fun inlinePasswordOf(profile: Map<String, Any?>): InlinePassword? {
        val o = profile["options"].asStringMap() ?: return null
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
    fun withoutInlinePassword(profile: Map<String, Any?>): LinkedHashMap<String, Any?> {
        val out = LinkedHashMap<String, Any?>(profile)
        val o = LinkedHashMap<String, Any?>(profile["options"].asStringMap() ?: emptyMap())
        o.remove("password")
        out["options"] = o
        return out
    }

    /** All `options.privateKeys` entries as strings (refs, PEM, or paths). */
    fun privateKeyRefs(profile: Map<String, Any?>): List<String> {
        val o = profile["options"].asStringMap() ?: return emptyList()
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
    fun withPrivateKeys(profile: Map<String, Any?>, refs: List<String>): LinkedHashMap<String, Any?> {
        val out = LinkedHashMap<String, Any?>(profile)
        val o = LinkedHashMap<String, Any?>(profile["options"].asStringMap() ?: emptyMap())
        o["privateKeys"] = refs.toList()
        out["options"] = o
        return out
    }
}

/**
 * Warning-free view of a dynamic YAML/JSON map.
 *
 * SnakeYAML parses mappings into `Map<*, *>`; casting straight to
 * `Map<String, Any?>` is unchecked (generics are erased) and used to need
 * `@Suppress("UNCHECKED_CAST")` at every call site. Casting to `Map<*, *>`
 * instead is fully checkable, so no warning is produced — keys are mapped
 * with `toString()` (they are strings by construction; this mirrors what
 * [RawConfigStore.loadRaw] already did for the generic branch).
 */
internal fun Any?.asStringMap(): Map<String, Any?>? =
    (this as? Map<*, *>)?.entries?.associate { (k, v) -> k.toString() to v }

/** Mutable copy of [asStringMap] for the read-modify-write call sites. */
internal fun Any?.asMutableStringMap(): LinkedHashMap<String, Any?>? =
    asStringMap()?.let { LinkedHashMap(it) }
