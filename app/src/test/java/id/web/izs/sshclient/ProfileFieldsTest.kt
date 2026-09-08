package id.web.izs.sshclient

import id.web.izs.sshclient.core.config.ForwardedPort
import id.web.izs.sshclient.core.config.LoginScript
import id.web.izs.sshclient.core.config.RawConfigStore
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
        @Suppress("UNCHECKED_CAST")
        val opts = RawConfigStore.updateProfileMap(existing, p, null, null, emptyList())["options"] as Map<String, Any?>
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
        @Suppress("UNCHECKED_CAST")
        val opts = RawConfigStore.updateProfileMap(emptyMap(), p, null, null, emptyList())["options"] as Map<String, Any?>
        assertEquals(7000L, opts["readyTimeout"])
        assertEquals(false, opts["reuseSession"])
        @Suppress("UNCHECKED_CAST")
        val algos = opts["algorithms"] as Map<String, List<String>>
        // Desktop sorts lexicographically on save (not preference order),
        // compression untouched.
        assertEquals(
            SshAlgorithms.DEFAULTS.getValue(SshAlgorithms.CIPHER).sorted(),
            algos[SshAlgorithms.CIPHER],
        )
        assertEquals(
            SshAlgorithms.DEFAULTS.getValue(SshAlgorithms.COMPRESSION),
            algos[SshAlgorithms.COMPRESSION],
        )
        @Suppress("UNCHECKED_CAST")
        val fw = opts["forwardedPorts"] as List<Map<String, Any?>>
        assertEquals("Local", fw.single()["type"])
        @Suppress("UNCHECKED_CAST")
        val sc = opts["scripts"] as List<Map<String, Any?>>
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
        @Suppress("UNCHECKED_CAST")
        val opts = out["options"] as Map<String, Any?>
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
    fun `new profile map on an empty base carries id type name`() {
        val p = SshProfile(id = "ssh:custom:x", name = "fresh", options = SshOptions(host = "h"))
        val out = RawConfigStore.updateProfileMap(emptyMap(), p, null, null, emptyList())
        assertEquals("ssh:custom:x", out["id"])
        assertEquals("ssh", out["type"])
        assertEquals("fresh", out["name"])
        @Suppress("UNCHECKED_CAST")
        val opts = out["options"] as Map<String, Any?>
        assertEquals("h", opts["host"])
        assertFalse(opts.containsKey("group"))
    }
}
