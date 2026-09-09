package id.web.izs.sshclient

import id.web.izs.sshclient.core.config.RawConfigStore
import org.junit.Assert.*
import org.junit.Test

/**
 * Settings > Config file > Import validation (paste full YAML, local-only).
 * [RawConfigStore.parseImport] must accept desktop exports and encrypted
 * shells, and reject everything else BEFORE anything touches disk — the
 * SyncRepository op itself needs Android storage, so only this pure piece
 * is tested here (same split as VaultManageTest).
 */
class ConfigImportTest {

    private val desktopYaml = """
        version: 1
        profiles:
          - id: ssh:11111111-1111-1111-1111-111111111111
            type: ssh
            name: prod-web
            options:
              host: 10.0.0.5
              user: deploy
          - id: ssh:22222222-2222-2222-2222-222222222222
            type: ssh
            name: db
            options:
              host: 10.0.0.6
              user: root
        groups: []
        futureKey: keep-me
    """.trimIndent()

    @Test
    fun `accepts a desktop export with profiles`() {
        val doc = RawConfigStore.parseImport(desktopYaml)
        assertEquals(2, (doc["profiles"] as List<*>).size)
        // Lossless: unknown keys survive the strict parse.
        assertEquals("keep-me", doc["futureKey"])
    }

    @Test
    fun `accepts yaml without version (repo defaults it later)`() {
        val doc = RawConfigStore.parseImport("profiles: []\n")
        assertEquals(0, (doc["profiles"] as List<*>).size)
    }

    @Test
    fun `accepts an encrypted shell without top-level profiles`() {
        val shell = """
            vault: {contents: 'e30=', keySalt: 'c2FsdA==', iv: 'aXY='}
            encrypted: true
            configSync: {}
        """.trimIndent()
        val doc = RawConfigStore.parseImport(shell)
        assertEquals(true, doc["encrypted"])
    }

    @Test
    fun `rejects encrypted flag without a vault blob or profiles`() {
        assertFailsWith("encrypted: true\nversion: 1\n")
    }

    @Test
    fun `rejects a scalar document`() {
        assertFailsWith("just a string")
    }

    @Test
    fun `rejects a list document`() {
        assertFailsWith("- a\n- b\n")
    }

    @Test
    fun `rejects broken yaml syntax`() {
        assertFailsWith("profiles: [unclosed\n  bad indent: : :\n")
    }

    @Test
    fun `rejects a mapping without profiles`() {
        assertFailsWith("version: 1\nterminal:\n  font: monospace\n")
    }

    @Test
    fun `rejects non-list profiles`() {
        assertFailsWith("version: 1\nprofiles: nope\n")
    }

    @Test
    fun `rejects blank text`() {
        assertFailsWith("   \n")
    }

    private fun assertFailsWith(text: String) {
        try {
            RawConfigStore.parseImport(text)
            fail("Expected IllegalArgumentException for: $text")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.isNotBlank())
        }
    }
}
