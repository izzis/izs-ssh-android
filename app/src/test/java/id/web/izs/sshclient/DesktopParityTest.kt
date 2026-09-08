package id.web.izs.sshclient

import id.web.izs.sshclient.core.config.RawConfigStore
import id.web.izs.sshclient.core.config.VaultSecret
import id.web.izs.sshclient.core.vault.SecretResolver
import org.junit.Assert.*
import org.junit.Test

/**
 * Desktop-parity regression tests for encrypted-config handling:
 * v4 id mint format, all-types listing, encrypt-source rule, and
 * vault.getSecret matching (exact, then host-nulled — never fuzzy).
 */
class DesktopParityTest {

    @Test
    fun `id-less profile gets desktop v4 id format`() {
        val doc = linkedMapOf<String, Any?>(
            "version" to 1,
            "profiles" to listOf(mapOf("name" to "legacy", "options" to mapOf("host" to "h"))),
            "groups" to emptyList<Any>(),
        )
        val p = RawConfigStore.toDomain(doc).profiles.single()
        assertTrue(p.id.startsWith("ssh:custom:"))
        assertEquals("legacy", p.name)
    }

    @Test
    fun `non-ssh profiles parse as listable skeletons`() {
        val doc = linkedMapOf<String, Any?>(
            "version" to 1,
            "profiles" to listOf(
                mapOf("id" to "ssh:1", "type" to "ssh", "name" to "web", "group" to "g",
                    "options" to mapOf("host" to "h")),
                mapOf("id" to "serial:1", "type" to "serial", "name" to "console", "group" to "g"),
            ),
            "groups" to listOf(mapOf("id" to "g", "name" to "G")),
        )
        val domain = RawConfigStore.toDomain(doc)
        assertEquals(2, domain.profiles.size)
        val serial = domain.profiles.find { it.type == "serial" }!!
        assertEquals("console", serial.name)
        assertEquals("g", serial.group)
    }

    @Test
    fun `encrypt source is the live store, never the stripped shell`() {
        val blobConfig = linkedMapOf<String, Any?>(
            "version" to 1,
            "profiles" to listOf(mapOf("id" to "ssh:1")),
        )
        // Encrypted shell outer: source must be the blob config (profiles survive).
        val shell = linkedMapOf<String, Any?>(
            "vault" to linkedMapOf("contents" to "x"),
            "encrypted" to true,
            "configSync" to linkedMapOf("host" to "h"),
        )
        val fromShell = RawConfigStore.encryptSource(shell, blobConfig)
        assertEquals(listOf(mapOf("id" to "ssh:1")), fromShell["profiles"])
        assertFalse(fromShell.containsKey("vault"))
        // Plaintext outer: source is outer minus vault/encrypted/configSync.
        val plain = linkedMapOf<String, Any?>(
            "version" to 1,
            "profiles" to listOf(mapOf("id" to "ssh:2")),
            "vault" to linkedMapOf("contents" to "old"),
            "configSync" to linkedMapOf("host" to "h"),
        )
        val fromPlain = RawConfigStore.encryptSource(plain, emptyMap())
        assertEquals(listOf(mapOf("id" to "ssh:2")), fromPlain["profiles"])
        assertEquals(setOf("version", "profiles"), fromPlain.keys)
    }

    @Test
    fun `password matching is exact then host-nulled, never fuzzy`() {
        val secrets = listOf(
            VaultSecret("ssh:password", mapOf("user" to "root", "host" to "a", "port" to "22"), "pw-a"),
            VaultSecret("ssh:password", mapOf("user" to "root"), "pw-default"),
            VaultSecret("ssh:password", mapOf("user" to "other", "host" to "b", "port" to "22"), "pw-b"),
        )
        assertEquals("pw-a", SecretResolver.findPassword(secrets, "root", "a", 22)?.value)
        // Unknown host falls back to the host-less default for the SAME user...
        assertEquals("pw-default", SecretResolver.findPassword(secrets, "root", "zz", 22)?.value)
        // ...but never to another user's secret on a known host.
        assertNull(SecretResolver.findPassword(secrets, "nobody", "b", 22))
        assertNull(SecretResolver.findPassword(secrets, "other", "zz", 22))
    }
}
