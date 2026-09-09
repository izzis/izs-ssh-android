package id.web.izs.sshclient

import id.web.izs.sshclient.core.config.RawConfigStore
import id.web.izs.sshclient.core.config.SshOptions
import id.web.izs.sshclient.core.config.SshProfile
import id.web.izs.sshclient.core.config.VaultSecret
import id.web.izs.sshclient.core.vault.SecretResolver
import id.web.izs.sshclient.core.vault.VaultState
import org.junit.Assert.*
import org.junit.Test

/** Pure building blocks of the profile editor (repo ops need Android storage). */
class SecretStoreTest {

    private fun pw(user: String, host: String?, port: Int?, value: String) = VaultSecret(
        SecretResolver.TYPE_PASSWORD,
        linkedMapOf<String, String?>("user" to user).also {
            if (host != null) it["host"] = host
            if (port != null) it["port"] = port.toString()
        },
        value,
    )

    @Test
    fun `upsertPassword adds and resolves`() {
        val next = SecretResolver.upsertPassword(emptyList(), "root", "h", 22, "pw1")
        assertEquals(1, next.size)
        assertEquals("pw1", SecretResolver.findPassword(next, "root", "h", 22)!!.value)
    }

    @Test
    fun `upsertPassword replaces in place without duplicating`() {
        val start = listOf(pw("root", "h", 22, "old"))
        val next = SecretResolver.upsertPassword(start, "root", "h", 22, "new")
        assertEquals(1, next.size)
        assertEquals("new", SecretResolver.findPassword(next, "root", "h", 22)!!.value)
    }

    @Test
    fun `upsertPassword reuses host-less default instead of duplicating`() {
        val start = listOf(pw("root", null, 22, "default"))
        val next = SecretResolver.upsertPassword(start, "root", "h", 22, "new")
        assertEquals(1, next.size)
        assertEquals("new", SecretResolver.findPassword(next, "root", "h", 22)!!.value)
    }

    @Test
    fun `removePassword drops exact match only`() {
        val start = listOf(pw("root", "h", 22, "a"), pw("root", null, 22, "b"))
        val next = SecretResolver.removePassword(start, "root", "h", 22)
        assertEquals(1, next.size)
        assertEquals("b", next[0].value)
    }

    @Test
    fun `addFile stores PEM and ref resolves back`() {
        val pem = "-----BEGIN OPENSSH PRIVATE KEY-----\nfake\n-----END OPENSSH PRIVATE KEY-----"
        val (next, ref) = SecretResolver.addFile(emptyList(), pem, "laptop key")
        assertTrue(ref.startsWith("vault://"))
        assertEquals(pem, SecretResolver.findFilePem(next, ref))
        val afterRemove = SecretResolver.removeFile(next, ref)
        assertTrue(afterRemove.isEmpty())
        assertNull(SecretResolver.findFilePem(afterRemove, ref))
    }

    @Test
    fun `fileSecrets lists vault keys with descriptions for the picker`() {
        val pem = "-----BEGIN OPENSSH PRIVATE KEY-----\nfake\n-----END OPENSSH PRIVATE KEY-----"
        val (s1, ref1) = SecretResolver.addFile(emptyList(), pem, "laptop key")
        val (s2, ref2) = SecretResolver.addFile(s1, pem, "")
        val all = s2 + pw("root", "h", 22, "pw1")
        val listed = SecretResolver.fileSecrets(all)
        assertEquals(2, listed.size)
        assertEquals(ref1, listed[0].ref)
        assertEquals("laptop key", listed[0].description)
        assertEquals(ref2, listed[1].ref)
        assertEquals("", listed[1].description)
        // Non-file secrets never leak into the key picker.
        assertTrue(listed.none { it.ref.isBlank() })
    }

    @Test
    fun `secretsToJson round-trips through parseSecretsJson`() {
        val secrets = listOf(
            pw("root", "h", 22, "s3cr3t pâss"),
            VaultSecret("file", mapOf("id" to "k1", "description" to "d"), "YmFzZQ=="),
        )
        val back = VaultState.parseSecretsJson(VaultState.secretsToJson(secrets))
        assertEquals(secrets, back)
    }

    private fun sshProfile() = SshProfile(
        id = "ssh:1", type = "ssh", name = "web-01", group = "g1",
        options = SshOptions(host = "10.0.0.1", port = 22, user = "root", auth = "password"),
    )

    @Test
    fun `updateProfileMap preserves unknown keys`() {
        val existing = linkedMapOf<String, Any?>(
            "id" to "ssh:1",
            "customDesktopKey" to "keep",
            "options" to linkedMapOf<String, Any?>(
                "host" to "old", "mysteryOpt" to 42,
            ),
        )
        val out = RawConfigStore.updateProfileMap(existing, sshProfile(), null, null, emptyList())
        assertEquals("keep", out["customDesktopKey"])
        @Suppress("UNCHECKED_CAST")
        val opts = out["options"] as Map<String, Any?>
        assertEquals("10.0.0.1", opts["host"])
        assertEquals(42, opts["mysteryOpt"])
        assertEquals("web-01", out["name"])
    }

    @Test
    fun `updateProfileMap empty password removes the key`() {
        val existing = linkedMapOf<String, Any?>(
            "options" to linkedMapOf<String, Any?>("password" to "plain"),
        )
        @Suppress("UNCHECKED_CAST")
        val opts = RawConfigStore.updateProfileMap(existing, sshProfile(), "", null, emptyList())["options"] as Map<String, Any?>
        assertFalse(opts.containsKey("password"))
    }

    @Test
    fun `resolveGroupWriteValue prefers real ids falls back to names`() {
        assertEquals("g1", RawConfigStore.resolveGroupWriteValue(setOf("g1"), "g1", "Servers"))
        // Minted legacy id is not a raw id: the stable name is written.
        assertEquals("Servers", RawConfigStore.resolveGroupWriteValue(setOf("g9"), "group:custom:x", "Servers"))
        assertEquals("other", RawConfigStore.resolveGroupWriteValue(emptySet(), "other", null))
    }

    @Test
    fun `findProfileIndex matches by id then legacy fallback`() {
        val profiles = listOf(
            linkedMapOf<String, Any?>("id" to "ssh:1", "name" to "a"),
            linkedMapOf<String, Any?>(
                "name" to "legacy", "type" to "ssh",
                "options" to linkedMapOf<String, Any?>("host" to "h", "user" to "u"),
            ),
        )
        assertEquals(0, RawConfigStore.findProfileIndex(profiles, "ssh:1", null, null, null, null))
        assertEquals(
            1,
            RawConfigStore.findProfileIndex(profiles, "ssh:custom:zzz", "legacy", "ssh", "h", "u"),
        )
        assertEquals(-1, RawConfigStore.findProfileIndex(profiles, "ssh:nope", null, null, null, null))
        // Non-minted ids never use the fuzzy fallback.
        assertEquals(-1, RawConfigStore.findProfileIndex(profiles, "ssh:2", "legacy", "ssh", "h", "u"))
    }
}
