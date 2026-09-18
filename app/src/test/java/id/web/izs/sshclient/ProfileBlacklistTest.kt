package id.web.izs.sshclient

import id.web.izs.sshclient.core.config.RawConfigStore
import org.junit.Assert.*
import org.junit.Test

/**
 * Profile `profileBlacklist` (Hide) raw-helper tests (desktop parity):
 * a synced root id list; hidden rows are filtered by the home tree and
 * the new-tab picker, never deleted. (Duplicate opens the editor in copy
 * mode instead of writing directly, so it has no raw helper to test —
 * desktop newProfile parity.)
 */
class ProfileBlacklistTest {

    private val yaml = """
        version: 8
        profiles:
          - id: ssh:11111111-1111-1111-1111-111111111111
            type: ssh
            name: prod-web
            group: g-servers
            options:
              host: 10.0.0.5
              user: deploy
          - id: ssh:22222222-2222-2222-2222-222222222222
            type: ssh
            name: lone
            options:
              host: example.com
        groups:
          - id: g-servers
            name: Servers
    """.trimIndent()

    @Test
    fun `blacklist hide and show round-trip`() {
        val doc = RawConfigStore.loadRaw(yaml)
        assertTrue(RawConfigStore.profileBlacklistOf(doc).isEmpty())
        RawConfigStore.setProfileHiddenEntry(doc, "ssh:11111111-1111-1111-1111-111111111111", true)
        assertEquals(
            setOf("ssh:11111111-1111-1111-1111-111111111111"),
            RawConfigStore.profileBlacklistOf(doc),
        )
        // Hide is idempotent, unknown ids kept verbatim.
        RawConfigStore.setProfileHiddenEntry(doc, "ssh:11111111-1111-1111-1111-111111111111", true)
        RawConfigStore.setProfileHiddenEntry(doc, "ssh:from-desktop", true)
        assertEquals(2, RawConfigStore.profileBlacklistOf(doc).size)
        RawConfigStore.setProfileHiddenEntry(doc, "ssh:11111111-1111-1111-1111-111111111111", false)
        assertEquals(setOf("ssh:from-desktop"), RawConfigStore.profileBlacklistOf(doc))
    }

    @Test
    fun `blacklist survives dump and reload`() {
        val doc = RawConfigStore.loadRaw(yaml)
        RawConfigStore.setProfileHiddenEntry(doc, "ssh:22222222-2222-2222-2222-222222222222", true)
        val reloaded = RawConfigStore.loadRaw(RawConfigStore.dumpRaw(doc))
        assertEquals(
            setOf("ssh:22222222-2222-2222-2222-222222222222"),
            RawConfigStore.profileBlacklistOf(reloaded),
        )
        // Profiles themselves are untouched by hiding.
        assertEquals(2, ((reloaded["profiles"] as? List<*>) ?: emptyList<Any>()).size)
    }
}
