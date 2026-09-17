package id.web.izs.sshclient

import id.web.izs.sshclient.core.config.RawConfigStore
import id.web.izs.sshclient.core.config.asStringMap
import id.web.izs.sshclient.core.vault.VaultCrypto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import org.junit.Assert.*
import org.junit.Test

/**
 * Vault management building blocks (desktop vaultSettingsTab parity).
 * The SyncRepository ops themselves need Android storage, so the pure
 * pieces they compose are tested here: toJson, blob re-encryption
 * (change-passphrase core), and the encrypted outer-document shape.
 */
class VaultManageTest {

    @Test
    fun `toJson matches desktop JSON stringify shape`() {
        val doc = linkedMapOf<String, Any?>(
            "version" to 1,
            "name" to "srv \"a\"\nline2\t\\",
            "port" to 22,
            "ratio" to 1.5,
            "flag" to true,
            "nothing" to null,
            "list" to listOf(1, "two", null),
            "nested" to linkedMapOf("a" to 1, "b" to listOf(true)),
        )
        assertEquals(
            """{"version":1,"name":"srv \"a\"\nline2\t\\","port":22,"ratio":1.5,"flag":true,"nothing":null,"list":[1,"two",null],"nested":{"a":1,"b":[true]}}""",
            RawConfigStore.toJson(doc),
        )
    }

    @Test
    fun `toJson output parses back to the same structure`() {
        val doc = linkedMapOf<String, Any?>(
            "version" to 1,
            "profiles" to listOf(
                linkedMapOf(
                    "id" to "ssh:1",
                    "name" to "web-01",
                    "options" to linkedMapOf("host" to "10.0.0.1", "port" to 22, "user" to "root"),
                ),
            ),
            "ssh" to linkedMapOf("verifyHostKeys" to true),
        )
        val el = Json.parseToJsonElement(RawConfigStore.toJson(doc)) as JsonObject
        assertEquals(1, (el["version"] as JsonPrimitive).int)
        val opts = (((el["profiles"] as JsonArray)[0] as JsonObject)["options"] as JsonObject)
        assertEquals("10.0.0.1", (opts["host"] as JsonPrimitive).content)
        assertEquals(22, (opts["port"] as JsonPrimitive).int)
        assertEquals(true, ((el["ssh"] as JsonObject)["verifyHostKeys"] as JsonPrimitive).boolean)
    }

    @Test
    fun `change passphrase re-encrypts the same payload`() {
        val configJson = """{"version":1,"profiles":[]}"""
        val secretsJson = """[{"type":"ssh:password","key":{"user":"root"},"value":"s3cr3t"}]"""
        val old = VaultCrypto.encrypt(configJson, secretsJson, "old-pass")
        val (cfg, sec) = VaultCrypto.decrypt(old, "old-pass")
        // Fresh salt/iv like desktop encryptVault, same payload strings.
        val rotated = VaultCrypto.encrypt(cfg, sec, "new-pass")
        assertNotEquals(old.contents, rotated.contents)
        val (cfg2, sec2) = VaultCrypto.decrypt(rotated, "new-pass")
        assertEquals(configJson, cfg2)
        assertEquals(secretsJson, sec2)
        try {
            VaultCrypto.decrypt(rotated, "old-pass")
            fail("old passphrase must no longer work")
        } catch (e: VaultCrypto.BadDecryptException) {
            // Expected.
        }
    }

    @Test
    fun `same passphrase re-encrypt rotates salt but preserves payload`() {
        // Desktop writeConfigDataFromSync parity: config.save() after
        // config.load() calls encryptVault with fresh random salt/iv even
        // when the passphrase is unchanged — keySalt must rotate while the
        // decrypted payload stays byte-identical.
        val configJson = """{"version":1,"profiles":[]}"""
        val secretsJson = """[{"type":"ssh:password","key":{"user":"root"},"value":"s3cr3t"}]"""
        val downloaded = VaultCrypto.encrypt(configJson, secretsJson, "pass")
        val (cfg, sec) = VaultCrypto.decrypt(downloaded, "pass")
        val rewritten = VaultCrypto.encrypt(cfg, sec, "pass")
        assertNotEquals(downloaded.keySalt, rewritten.keySalt)
        assertNotEquals(downloaded.iv, rewritten.iv)
        assertNotEquals(downloaded.contents, rewritten.contents)
        val (cfg2, sec2) = VaultCrypto.decrypt(rewritten, "pass")
        assertEquals(configJson, cfg2)
        assertEquals(secretsJson, sec2)
    }

    @Test
    fun `encrypted outer document has exactly the desktop disk shape`() {
        val plain = linkedMapOf<String, Any?>(
            "version" to 1,
            "profiles" to listOf(linkedMapOf("id" to "ssh:1", "name" to "n")),
            "configSync" to linkedMapOf("host" to "https://x", "token" to "t"),
        )
        val payload = LinkedHashMap<String, Any?>(plain)
        payload.remove(RawConfigStore.KEY_CONFIG_SYNC)
        val stored = VaultCrypto.encrypt(RawConfigStore.toJson(payload), "[]", "pass")
        val outer = linkedMapOf<String, Any?>(
            RawConfigStore.KEY_VAULT to RawConfigStore.storedVaultMap(stored),
            RawConfigStore.KEY_ENCRYPTED to true,
            RawConfigStore.KEY_CONFIG_SYNC to plain["configSync"]!!,
        )
        assertEquals(
            setOf("vault", "encrypted", "configSync"),
            outer.keys,
        )
        // Dump -> reload keeps the blob verbatim and decryptable.
        val reloaded = RawConfigStore.loadRaw(RawConfigStore.dumpRaw(outer))
        assertTrue(RawConfigStore.isEncrypted(reloaded))
        val blob = RawConfigStore.storedVault(reloaded)!!
        assertEquals(stored.contents, blob.contents)
        assertEquals(stored.keySalt, blob.keySalt)
        assertEquals(stored.iv, blob.iv)
        val (cfg, _) = VaultCrypto.decrypt(blob, "pass")
        assertTrue(cfg.contains("\"ssh:1\""))
    }

    @Test
    fun `storedVaultMap keeps blob strings verbatim`() {        val stored = VaultCrypto.encrypt("{}", "[]", "p")
        val m = RawConfigStore.storedVaultMap(stored)
        assertEquals(stored.contents, m["contents"])
        assertEquals(stored.keySalt, m["keySalt"])
        assertEquals(stored.iv, m["iv"])
        assertEquals(1, m["version"])
    }

    @Test
    fun `setSshFlags writes flags and preserves the rest of ssh`() {
        val doc = linkedMapOf<String, Any?>(
            "version" to 1,
            "ssh" to linkedMapOf<String, Any?>(
                "verifyHostKeys" to true,
                "knownHosts" to listOf(linkedMapOf("host" to "h")),
            ),
        )
        RawConfigStore.setSshFlags(doc, verify = false, warn = true)
        val ssh = doc["ssh"].asStringMap()!!
        assertEquals(false, ssh["verifyHostKeys"])
        assertEquals(true, ssh["warnOnClose"])
        assertEquals(listOf(linkedMapOf("host" to "h")), ssh["knownHosts"])
    }

    @Test
    fun `setSshFlags creates the ssh section when absent`() {
        val doc = linkedMapOf<String, Any?>("version" to 1)
        RawConfigStore.setSshFlags(doc, verify = false, warn = false)
        val ssh = doc["ssh"].asStringMap()!!
        assertEquals(false, ssh["verifyHostKeys"])
        assertEquals(false, ssh["warnOnClose"])
    }

    @Test
    fun `ssh flags survive the vault blob round trip`() {
        val blobConfig = linkedMapOf<String, Any?>(
            "version" to 1,
            "profiles" to emptyList<Any?>(),
        )
        RawConfigStore.setSshFlags(blobConfig, verify = false, warn = true)
        val stored = VaultCrypto.encrypt(RawConfigStore.toJson(blobConfig), "[]", "pass")
        val (cfg, _) = VaultCrypto.decrypt(stored, "pass")
        val el = Json.parseToJsonElement(cfg) as JsonObject
        val ssh = el["ssh"] as JsonObject
        assertEquals(false, (ssh["verifyHostKeys"] as JsonPrimitive).boolean)
        assertEquals(true, (ssh["warnOnClose"] as JsonPrimitive).boolean)
    }
}
