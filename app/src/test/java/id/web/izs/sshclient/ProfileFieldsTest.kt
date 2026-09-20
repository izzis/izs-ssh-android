package id.web.izs.sshclient

import id.web.izs.sshclient.core.config.ForwardedPort
import id.web.izs.sshclient.core.config.LoginScript
import id.web.izs.sshclient.core.config.normalizeProfileColor
import id.web.izs.sshclient.core.config.RawConfigStore
import id.web.izs.sshclient.core.config.asStringMap
import id.web.izs.sshclient.core.config.profileColorArgb
import id.web.izs.sshclient.core.config.SshAlgorithms
import id.web.izs.sshclient.core.config.SshDefaults
import id.web.izs.sshclient.core.config.SshOptions
import id.web.izs.sshclient.core.config.SshProfile
import org.junit.Assert.*
import org.junit.Test

/** Profile editor fields: parse, transient defaults, and defaults-omitting write. */
class ProfileFieldsTest {

    private fun profileMap(options: LinkedHashMap<String, Any?>) = linkedMapOf<String, Any?>(
        "version" to 1,
        "profiles" to listOf(
            linkedMapOf<String, Any?>(
                "id" to "ssh:1",
                "type" to "ssh",
                "name" to "web-01",
                "options" to options,
            ),
        ),
    )

    private fun parsed(options: LinkedHashMap<String, Any?>) =
        RawConfigStore.toDomain(profileMap(options)).profiles.single()

    @Test
    fun `explicit blank user survives the parse for the username prompt`() {
        val p = parsed(linkedMapOf("host" to "h", "user" to ""))
        assertEquals("", p.options.user)
        // ...while a missing key still means the desktop default.
        val q = parsed(linkedMapOf("host" to "h"))
        assertEquals("root", q.options.user)
    }

    @Test
    fun `null user key parses as ask-every-time like desktop falsy`() {
        // `user:` (bare) or `user: null` in YAML: SnakeYAML yields a present
        // key with a null value. Desktop ConfigProxy returns it as-is (null
        // !== undefined, so no default applies) and ssh.ts prompts — so it
        // must parse to blank, NOT to the `root` default.
        val bare = parsed(linkedMapOf("host" to "h", "user" to null))
        assertEquals("", bare.options.user)
        assertTrue(RawConfigStore.storedUserAsksEveryTime(linkedMapOf("host" to "h", "user" to null)))
    }

    @Test
    fun `real yaml separates blank null missing and explicit root`() {
        val doc = RawConfigStore.loadRaw(
            "version: 1\nprofiles:\n" +
                "  - {id: ssh:1, type: ssh, name: ask-blank, options: {host: h, user: ''}}\n" +
                "  - {id: ssh:2, type: ssh, name: ask-null, options: {host: h, user:}}\n" +
                "  - {id: ssh:3, type: ssh, name: default-root, options: {host: h}}\n" +
                "  - {id: ssh:4, type: ssh, name: explicit-root, options: {host: h, user: root}}\n",
        )
        val ps = RawConfigStore.toDomain(doc).profiles.associateBy { it.id }
        assertEquals("", ps["ssh:1"]!!.options.user)
        assertEquals("", ps["ssh:2"]!!.options.user)
        assertEquals("root", ps["ssh:3"]!!.options.user)
        assertEquals("root", ps["ssh:4"]!!.options.user)
    }

    @Test
    fun `storedUserAsksEveryTime separates absent null blank and named`() {
        // Absent key = desktop default `root` → connects directly.
        assertFalse(RawConfigStore.storedUserAsksEveryTime(linkedMapOf("host" to "h")))
        // Present-but-null and blank = falsy = ask every time.
        assertTrue(RawConfigStore.storedUserAsksEveryTime(linkedMapOf("host" to "h", "user" to null)))
        assertTrue(RawConfigStore.storedUserAsksEveryTime(linkedMapOf("host" to "h", "user" to "")))
        assertTrue(RawConfigStore.storedUserAsksEveryTime(linkedMapOf("host" to "h", "user" to "   ")))
        // Named users (including an explicit `root`) never ask.
        assertFalse(RawConfigStore.storedUserAsksEveryTime(linkedMapOf("host" to "h", "user" to "root")))
        assertFalse(RawConfigStore.storedUserAsksEveryTime(linkedMapOf("host" to "h", "user" to "deploy")))
    }

    @Test
    fun `updateProfileMap omits default root user but stores blank`() {
        // Desktop ConfigProxy parity: `root` == default → key deleted.
        val existing = linkedMapOf<String, Any?>(
            "options" to linkedMapOf<String, Any?>("host" to "h", "user" to "root"),
        )
        val rootOpts = RawConfigStore.updateProfileMap(
            existing,
            SshProfile(id = "ssh:1", name = "n", options = SshOptions(host = "h", user = "root")),
            null, null, emptyList(),
        )["options"].asStringMap()!!
        assertFalse(rootOpts.containsKey("user"))
        // Blank (ask every time) is meaningful → stored as `user: ''`.
        val askOpts = RawConfigStore.updateProfileMap(
            emptyMap(),
            SshProfile(id = "ssh:1", name = "n", options = SshOptions(host = "h", user = "")),
            null, null, emptyList(),
        )["options"].asStringMap()!!
        assertEquals("", askOpts["user"])
        // Named users round-trip verbatim.
        val namedOpts = RawConfigStore.updateProfileMap(
            emptyMap(),
            SshProfile(id = "ssh:1", name = "n", options = SshOptions(host = "h", user = "deploy")),
            null, null, emptyList(),
        )["options"].asStringMap()!!
        assertEquals("deploy", namedOpts["user"])
    }

    @Test
    fun `saved blank survives a yaml dump and reload`() {
        val out = RawConfigStore.updateProfileMap(
            emptyMap(),
            SshProfile(id = "ssh:1", name = "n", options = SshOptions(host = "h", user = "")),
            null, null, emptyList(),
        )
        val yaml = RawConfigStore.dumpRaw(linkedMapOf("version" to 1, "profiles" to listOf(out)))
        val reloaded = RawConfigStore.toDomain(RawConfigStore.loadRaw(yaml)).profiles.single()
        assertEquals("", reloaded.options.user)
    }

    @Test
    fun `ask-every-time subtitle shows the bare host, never a fake root`() {
        // `user: ''` in YAML (ask every time): the transient display `root`
        // must not leak into the list subtitle.
        assertEquals("h", SshDefaults.displayQuickName("root", "h", 22, askUsername = true))
        assertEquals("h:2222", SshDefaults.displayQuickName("root", "h", 2222, askUsername = true))
        // Missing `user` line (or explicit `root`) keeps user@host.
        assertEquals("root@h", SshDefaults.displayQuickName("root", "h", 22, askUsername = false))
        assertEquals("deploy@h:2222", SshDefaults.displayQuickName("deploy", "h", 2222, askUsername = false))
        // A session-local typed answer wins (connected tab after the prompt).
        assertEquals("alice@h", SshDefaults.displayQuickName("root", "h", 22, askUsername = true, typedUser = "alice"))
    }

    @Test
    fun `parseProfile reads the full desktop option set`() {
        val p = parsed(
            linkedMapOf(
                "host" to "h", "port" to 2222, "user" to "admin",
                "x11" to true, "skipBanner" to true, "warnOnClose" to true,
                "keepaliveInterval" to 1000, "keepaliveCountMax" to 3,
                "readyTimeout" to 5000, "reuseSession" to false,
                "proxyCommand" to "ssh -W %h:%p bastion",
                "jumpHost" to "ssh:2",
                "socksProxyHost" to "127.0.0.1", "socksProxyPort" to 1080,
                "httpProxyHost" to "proxy", "httpProxyPort" to 8080,
                "algorithms" to linkedMapOf("cipher" to listOf("aes128-ctr")),
                "forwardedPorts" to listOf(
                    linkedMapOf(
                        "type" to "Local", "host" to "127.0.0.1", "port" to 8000,
                        "targetAddress" to "127.0.0.1", "targetPort" to 80,
                        "description" to "web",
                    ),
                ),
                "scripts" to listOf(
                    linkedMapOf("expect" to "login:", "send" to "root", "isRegex" to true, "optional" to false),
                ),
            ),
        )
        val o = p.options
        assertTrue(o.x11)
        assertTrue(o.skipBanner)
        assertEquals(true, o.warnOnClose)
        assertEquals(1000L, o.keepaliveInterval)
        assertEquals(3, o.keepaliveCountMax)
        assertEquals(5000L, o.readyTimeout)
        assertFalse(o.reuseSession)
        assertEquals("ssh -W %h:%p bastion", o.proxyCommand)
        assertEquals("ssh:2", o.jumpHost)
        assertEquals(1080, o.socksProxyPort)
        assertEquals(8080, o.httpProxyPort)
        assertEquals(mapOf("cipher" to listOf("aes128-ctr")), o.algorithms)
        assertEquals(
            listOf(ForwardedPort("Local", "127.0.0.1", 8000, "127.0.0.1", 80, "web")),
            o.forwardedPorts,
        )
        assertEquals(
            listOf(LoginScript("login:", "root", isRegex = true)),
            o.scripts,
        )
    }

    @Test
    fun `defaults fill transiently and stored reuseSession false survives`() {
        val p = SshDefaults.applyToProfile(parsed(linkedMapOf("host" to "h")))
        val o = p.options
        assertEquals(SshAlgorithms.DEFAULTS, o.algorithms)
        assertEquals(5000L, o.keepaliveInterval)
        assertEquals(20000L, o.readyTimeout)
        assertTrue(o.reuseSession)
        // A stored false must NOT be forced back to true on display.
        val off = SshDefaults.applyToProfile(
            parsed(linkedMapOf("host" to "h", "reuseSession" to false)),
        )
        assertFalse(off.options.reuseSession)
    }

    @Test
    fun `behaviorOnSessionEnd parses known values defaults auto and rejects bogus`() {
        assertEquals("keep", parsed(linkedMapOf("host" to "h", "behaviorOnSessionEnd" to "keep")).options.behaviorOnSessionEnd)
        assertEquals("reconnect", parsed(linkedMapOf("host" to "h", "behaviorOnSessionEnd" to "reconnect")).options.behaviorOnSessionEnd)
        assertEquals("close", parsed(linkedMapOf("host" to "h", "behaviorOnSessionEnd" to "close")).options.behaviorOnSessionEnd)
        // Missing key = desktop default.
        assertEquals("auto", parsed(linkedMapOf("host" to "h")).options.behaviorOnSessionEnd)
        // User-editable YAML: unknown values fall back, never crash.
        assertEquals("auto", parsed(linkedMapOf("host" to "h", "behaviorOnSessionEnd" to "explode")).options.behaviorOnSessionEnd)
    }

    @Test
    fun `updateProfileMap omits auto behaviorOnSessionEnd and writes the rest`() {
        val auto = SshProfile(
            id = "ssh:1", name = "n",
            options = SshOptions(host = "h", behaviorOnSessionEnd = "auto"),
        )
        val autoOpts = RawConfigStore.updateProfileMap(emptyMap(), auto, null, null, emptyList())["options"].asStringMap()!!
        assertFalse(autoOpts.containsKey("behaviorOnSessionEnd"))
        val keep = SshProfile(
            id = "ssh:1", name = "n",
            options = SshOptions(host = "h", behaviorOnSessionEnd = "keep"),
        )
        val keepOpts = RawConfigStore.updateProfileMap(emptyMap(), keep, null, null, emptyList())["options"].asStringMap()!!
        assertEquals("keep", keepOpts["behaviorOnSessionEnd"])
    }

    @Test
    fun `updateProfileMap omits defaults and writes the rest`() {
        val existing = linkedMapOf<String, Any?>(
            "options" to linkedMapOf<String, Any?>(
                "keepaliveInterval" to 1000,
                "algorithms" to linkedMapOf("cipher" to listOf("aes128-ctr")),
                "forwardedPorts" to listOf(linkedMapOf("type" to "Local")),
            ),
        )
        val p = SshProfile(
            id = "ssh:1", name = "n",
            options = SshOptions(
                host = "h", x11 = true, keepaliveInterval = 5000,
                algorithms = SshAlgorithms.DEFAULTS,
            ),
        )
        val opts = RawConfigStore.updateProfileMap(existing, p, null, null, emptyList())["options"].asStringMap()!!
        assertEquals(true, opts["x11"])
        assertFalse(opts.containsKey("keepaliveInterval"))
        assertFalse(opts.containsKey("algorithms"))
        assertFalse(opts.containsKey("forwardedPorts"))
        assertFalse(opts.containsKey("scripts"))
        assertFalse(opts.containsKey("readyTimeout"))
        assertFalse(opts.containsKey("reuseSession"))
    }

    @Test
    fun `updateProfileMap writes forwards scripts and sorts ciphers except compression`() {
        val p = SshProfile(
            id = "ssh:1", name = "n",
            options = SshOptions(
                host = "h",
                readyTimeout = 7000,
                reuseSession = false,
                algorithms = SshAlgorithms.DEFAULTS.mapValues { (k, v) ->
                    if (k == SshAlgorithms.COMPRESSION) v else v.reversed()
                },
                forwardedPorts = listOf(ForwardedPort()),
                scripts = listOf(LoginScript("a", "b")),
            ),
        )
        val opts = RawConfigStore.updateProfileMap(emptyMap(), p, null, null, emptyList())["options"].asStringMap()!!
        assertEquals(7000L, opts["readyTimeout"])
        assertEquals(false, opts["reuseSession"])
        val algos = opts["algorithms"].asStringMap()!!
        // Desktop sorts lexicographically on save (not preference order),
        // compression untouched.
        assertEquals(
            SshAlgorithms.DEFAULTS.getValue(SshAlgorithms.CIPHER).sorted(),
            (algos[SshAlgorithms.CIPHER] as? List<*>)?.map { it.toString() },
        )
        assertEquals(
            SshAlgorithms.DEFAULTS.getValue(SshAlgorithms.COMPRESSION),
            (algos[SshAlgorithms.COMPRESSION] as? List<*>)?.map { it.toString() },
        )
        val fw = (opts["forwardedPorts"] as? List<*>)!!.filterIsInstance<Map<String, Any?>>()
        assertEquals("Local", fw.single()["type"])
        val sc = (opts["scripts"] as? List<*>)!!.filterIsInstance<Map<String, Any?>>()
        assertEquals("a", sc.single()["expect"])
    }

    @Test
    fun `inlinePasswordOf skips blanks and vault refs`() {
        assertNull(RawConfigStore.inlinePasswordOf(emptyMap()))
        assertNull(
            RawConfigStore.inlinePasswordOf(
                mapOf("options" to mapOf("password" to "  ", "user" to "u")),
            ),
        )
        assertNull(
            RawConfigStore.inlinePasswordOf(
                mapOf("options" to mapOf("password" to "vault://abc")),
            ),
        )
        val inline = RawConfigStore.inlinePasswordOf(
            mapOf("options" to mapOf("password" to "s3cr3t", "user" to "admin", "host" to "h")),
        )!!
        assertEquals("admin", inline.user)
        assertEquals("h", inline.host)
        assertEquals(22, inline.port)
        assertEquals("s3cr3t", inline.value)
    }

    @Test
    fun `withoutInlinePassword strips only the password key`() {
        val out = RawConfigStore.withoutInlinePassword(
            mapOf(
                "id" to "ssh:1",
                "options" to linkedMapOf<String, Any?>("host" to "h", "password" to "x"),
            ),
        )
        val opts = out["options"].asStringMap()!!
        assertEquals("h", opts["host"])
        assertFalse(opts.containsKey("password"))
    }

    @Test
    fun `inlineKeyPems picks PEM content only`() {
        val pem = "-----BEGIN OPENSSH PRIVATE KEY-----\nfake\n-----END OPENSSH PRIVATE KEY-----"
        val profile = mapOf(
            "options" to mapOf(
                "privateKeys" to listOf("vault://x", "/home/u/.ssh/id_rsa", pem, ""),
            ),
        )
        assertEquals(listOf(pem), RawConfigStore.inlineKeyPems(profile))
        val swapped = RawConfigStore.withPrivateKeys(profile, listOf("vault://x", "/home/u/.ssh/id_rsa", "vault://y", ""))
        assertEquals(
            listOf("vault://x", "/home/u/.ssh/id_rsa", "vault://y", ""),
            RawConfigStore.privateKeyRefs(swapped),
        )
    }

    @Test
    fun `mintProfileId matches desktop shape`() {
        val id = RawConfigStore.mintProfileId("ssh", "Web 01 / Prod!")
        assertTrue(id.matches(Regex("""ssh:custom:web-01-prod:[0-9a-f-]{36}""")))
        assertTrue(
            RawConfigStore.mintProfileId("ssh", "   ").startsWith("ssh:custom:profile:"),
        )
    }

    @Test
    fun `new profile map on an empty base carries id type name`() {        val p = SshProfile(id = "ssh:custom:x", name = "fresh", options = SshOptions(host = "h"))
        val out = RawConfigStore.updateProfileMap(emptyMap(), p, null, null, emptyList())
        assertEquals("ssh:custom:x", out["id"])
        assertEquals("ssh", out["type"])
        assertEquals("fresh", out["name"])
        val opts = out["options"].asStringMap()!!
        assertEquals("h", opts["host"])
        assertFalse(opts.containsKey("group"))
    }

    @Test
    fun `updateProfileMap writes color normalized and clears on blank`() {
        val p = SshProfile(
            id = "ssh:1", name = "n", color = "  #FF0000 ",
            options = SshOptions(host = "h"),
        )
        val written = RawConfigStore.updateProfileMap(emptyMap(), p, null, null, emptyList())
        assertEquals("#ff0000", written["color"])

        val existing = linkedMapOf<String, Any?>("color" to "#ff0000")
        val cleared = RawConfigStore.updateProfileMap(
            existing, p.copy(color = null), null, null, emptyList(),
        )
        assertFalse(cleared.containsKey("color"))
    }

    @Test
    fun `updateProfileMap preserves icon without a picker`() {
        val existing = linkedMapOf<String, Any?>("icon" to "fas fa-server")
        val kept = RawConfigStore.updateProfileMap(
            existing,
            SshProfile(id = "ssh:1", name = "n", options = SshOptions(host = "h")),
            null, null, emptyList(),
        )
        assertEquals("fas fa-server", kept["icon"])
    }

    @Test
    fun `normalizeProfileColor accepts hex only`() {
        assertEquals("#ffffff", normalizeProfileColor("#FFF"))
        assertEquals("#ff0000", normalizeProfileColor("  #FF0000 "))
        assertEquals("#80123456", normalizeProfileColor("#80123456"))
        assertNull(normalizeProfileColor(null))
        assertNull(normalizeProfileColor(""))
        assertNull(normalizeProfileColor("red"))
        assertNull(normalizeProfileColor("#gggggg"))
        assertNull(normalizeProfileColor("#12345"))
    }

    @Test
    fun `profileColorArgb parses opaque and alpha hex`() {
        assertEquals(0xFFFF0000.toInt(), profileColorArgb("#ff0000"))
        assertEquals(0x80123456.toInt(), profileColorArgb("#80123456"))
        assertNull(profileColorArgb("red"))
        assertNull(profileColorArgb(null))
    }

    @Test
    fun `global ssh warnOnClose parses with desktop false default`() {
        val bare = RawConfigStore.toDomain(profileMap(linkedMapOf("host" to "h")))
        assertFalse(bare.ssh.warnOnClose)
        val doc = profileMap(linkedMapOf("host" to "h")).apply {
            put("ssh", linkedMapOf("warnOnClose" to true))
        }
        assertTrue(RawConfigStore.toDomain(doc).ssh.warnOnClose)
    }
}
