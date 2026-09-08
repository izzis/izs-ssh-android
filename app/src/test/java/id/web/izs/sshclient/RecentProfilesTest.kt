package id.web.izs.sshclient

import id.web.izs.sshclient.core.config.RawConfigStore
import id.web.izs.sshclient.data.local.recordRecent
import org.junit.Assert.*
import org.junit.Test

/**
 * Recent-profiles rules (desktop parity, no device): the YAML key is read
 * with the desktop default, an explicit set persists losslessly, and the
 * local id list follows launchProfile ordering (dedup, front, cap, 0=clear).
 */
class RecentProfilesTest {

    // ---- recordRecent (pure, local id list) ----

    @Test
    fun `first tap starts the list`() {
        assertEquals(listOf("a"), recordRecent(emptyList(), "a", 3))
    }

    @Test
    fun `re-tap moves to front without duplicates`() {
        assertEquals(listOf("b", "a", "c"), recordRecent(listOf("a", "b", "c"), "b", 3))
    }

    @Test
    fun `cap drops the oldest`() {
        assertEquals(listOf("d", "a", "b"), recordRecent(listOf("a", "b", "c"), "d", 3))
    }

    @Test
    fun `max zero clears like desktop disable`() {
        assertEquals(emptyList<String>(), recordRecent(listOf("a", "b"), "c", 0))
    }

    @Test
    fun `negative max clears too`() {
        assertEquals(emptyList<String>(), recordRecent(listOf("a"), "b", -1))
    }

    // ---- showRecentProfiles (YAML key read) ----

    @Test
    fun `absent key falls back to desktop default`() {
        assertEquals(3, RawConfigStore.showRecentProfiles(linkedMapOf("version" to 1)))
    }

    @Test
    fun `present value honored`() {
        val doc = linkedMapOf<String, Any?>(
            "terminal" to linkedMapOf<String, Any?>("showRecentProfiles" to 7),
        )
        assertEquals(7, RawConfigStore.showRecentProfiles(doc))
    }

    @Test
    fun `zero means disabled not default`() {
        val doc = linkedMapOf<String, Any?>(
            "terminal" to linkedMapOf<String, Any?>("showRecentProfiles" to 0),
        )
        assertEquals(0, RawConfigStore.showRecentProfiles(doc))
    }

    @Test
    fun `negative garbage coerces to disabled`() {
        val doc = linkedMapOf<String, Any?>(
            "terminal" to linkedMapOf<String, Any?>("showRecentProfiles" to -5),
        )
        assertEquals(0, RawConfigStore.showRecentProfiles(doc))
    }

    @Test
    fun `non-numeric falls back to default`() {
        val doc = linkedMapOf<String, Any?>(
            "terminal" to linkedMapOf<String, Any?>("showRecentProfiles" to "many"),
        )
        assertEquals(3, RawConfigStore.showRecentProfiles(doc))
    }

    // ---- setShowRecentProfiles (YAML key write) ----

    @Test
    fun `explicit set creates terminal map when absent`() {
        val doc = linkedMapOf<String, Any?>("version" to 1)
        RawConfigStore.setShowRecentProfiles(doc, 5)
        assertEquals(5, RawConfigStore.showRecentProfiles(doc))
    }

    @Test
    fun `explicit set preserves sibling terminal keys`() {
        val doc = linkedMapOf<String, Any?>(
            "terminal" to linkedMapOf<String, Any?>("hideTabIndex" to true),
        )
        RawConfigStore.setShowRecentProfiles(doc, 2)
        assertEquals(2, RawConfigStore.showRecentProfiles(doc))
        @Suppress("UNCHECKED_CAST")
        assertEquals(true, (doc["terminal"] as Map<String, Any?>)["hideTabIndex"])
    }

    @Test
    fun `explicit set clamps to ui bounds`() {
        val doc = linkedMapOf<String, Any?>()
        RawConfigStore.setShowRecentProfiles(doc, 999)
        assertEquals(
            RawConfigStore.MAX_SHOW_RECENT_PROFILES,
            RawConfigStore.showRecentProfiles(doc),
        )
        RawConfigStore.setShowRecentProfiles(doc, -9)
        assertEquals(0, RawConfigStore.showRecentProfiles(doc))
    }

    @Test
    fun `explicit set survives yaml round trip losslessly`() {
        val doc = linkedMapOf<String, Any?>("version" to 1)
        RawConfigStore.setShowRecentProfiles(doc, 4)
        val reloaded = RawConfigStore.loadRaw(RawConfigStore.dumpRaw(doc))
        assertEquals(4, RawConfigStore.showRecentProfiles(reloaded))
    }
}
