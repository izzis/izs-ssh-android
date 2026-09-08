package id.web.izs.sshclient

import id.web.izs.sshclient.core.config.RawConfigStore
import id.web.izs.sshclient.core.config.TabLocation
import id.web.izs.sshclient.core.config.TabSource
import id.web.izs.sshclient.core.config.effectiveTabLocation
import id.web.izs.sshclient.core.config.ignoreEncryptedValue
import id.web.izs.sshclient.core.config.parseTabSource
import id.web.izs.sshclient.core.config.resolveTabLocation
import org.junit.Assert.*
import org.junit.Test

/**
 * Tab location rules (desktop `appearance.tabsLocation` parity, no device):
 * absent/garbage = OFF (phone keeps the list UX), explicit values honored.
 */
class TabLocationTest {

    @Test
    fun `absent key resolves to OFF`() {
        assertEquals(TabLocation.OFF, resolveTabLocation(RawConfigStore.tabsLocationRaw(emptyMap())))
        assertEquals(
            TabLocation.OFF,
            resolveTabLocation(RawConfigStore.tabsLocationRaw(mapOf("appearance" to mapOf<String, Any?>()))),
        )
    }

    @Test
    fun `all four desktop values honored`() {
        assertEquals(TabLocation.TOP, resolveTabLocation("top"))
        assertEquals(TabLocation.BOTTOM, resolveTabLocation("bottom"))
        assertEquals(TabLocation.LEFT, resolveTabLocation("left"))
        assertEquals(TabLocation.RIGHT, resolveTabLocation("right"))
    }

    @Test
    fun `garbage resolves to OFF never to a surprise location`() {
        assertEquals(TabLocation.OFF, resolveTabLocation("sideways"))
        assertEquals(TabLocation.OFF, resolveTabLocation(""))
        assertEquals(TabLocation.OFF, resolveTabLocation(null))
        assertEquals(TabLocation.OFF, resolveTabLocation("123"))
    }

    @Test
    fun `matching is case-insensitive and trimmed`() {
        assertEquals(TabLocation.TOP, resolveTabLocation("  Top "))
    }

    @Test
    fun `set then read round-trips through raw doc`() {
        val doc = linkedMapOf<String, Any?>()
        RawConfigStore.setTabsLocation(doc, TabLocation.LEFT)
        assertEquals(TabLocation.LEFT, resolveTabLocation(RawConfigStore.tabsLocationRaw(doc)))
        // Raw shape is exactly what desktop expects.
        assertEquals("left", ((doc["appearance"] as Map<*, *>)["tabsLocation"]))
    }

    @Test
    fun `off removes the key restoring absent`() {
        val doc = linkedMapOf<String, Any?>(
            "appearance" to linkedMapOf<String, Any?>("tabsLocation" to "top"),
        )
        RawConfigStore.setTabsLocation(doc, TabLocation.OFF)
        assertFalse(doc.containsKey("appearance"))
        assertEquals(TabLocation.OFF, resolveTabLocation(RawConfigStore.tabsLocationRaw(doc)))
    }

    @Test
    fun `off keeps sibling appearance keys`() {
        val doc = linkedMapOf<String, Any?>(
            "appearance" to linkedMapOf<String, Any?>(
                "tabsLocation" to "top",
                "flexTabs" to true,
            ),
        )
        RawConfigStore.setTabsLocation(doc, TabLocation.OFF)
        val app = doc["appearance"] as Map<*, *>
        assertFalse(app.containsKey("tabsLocation"))
        assertEquals(true, app["flexTabs"])
    }

    @Test
    fun `effective location honors plaintext key`() {
        val doc = linkedMapOf<String, Any?>(
            "appearance" to linkedMapOf<String, Any?>("tabsLocation" to "left"),
        )
        assertEquals(TabLocation.LEFT, effectiveTabLocation(blind = false, store = doc))
    }

    @Test
    fun `effective location is OFF on unreadable stores`() {
        // Locked encrypted shell: the live store has no readable appearance.
        val doc = linkedMapOf<String, Any?>(
            "appearance" to linkedMapOf<String, Any?>("tabsLocation" to "top"),
            "encrypted" to true,
        )
        assertEquals(TabLocation.OFF, effectiveTabLocation(blind = true, store = doc))
    }

    @Test
    fun `ignore rule is locked-not-encrypted`() {
        // Encrypted file shape alone ignores nothing: once unlocked
        // (passphrase in RAM) the decrypted store applies normally.
        assertTrue(ignoreEncryptedValue(encrypted = true, unlockRequired = true))
        assertFalse(ignoreEncryptedValue(encrypted = true, unlockRequired = false))
        assertFalse(ignoreEncryptedValue(encrypted = false, unlockRequired = false))
        assertFalse(ignoreEncryptedValue(encrypted = false, unlockRequired = true))
    }

    @Test
    fun `source parse defaults to follow`() {
        assertEquals(TabSource.FOLLOW_YAML, parseTabSource(null))
        assertEquals(TabSource.FOLLOW_YAML, parseTabSource(""))
        assertEquals(TabSource.FOLLOW_YAML, parseTabSource("follow"))
        assertEquals(TabSource.FOLLOW_YAML, parseTabSource("garbage"))
        assertEquals(TabSource.LOCAL, parseTabSource("local"))
    }

    @Test
    fun `local source wins over yaml including encrypted`() {
        val doc = linkedMapOf<String, Any?>(
            "appearance" to linkedMapOf<String, Any?>("tabsLocation" to "top"),
            "encrypted" to true,
        )
        // Desktop says top on an encrypted config (ignored), device says bottom.
        assertEquals(
            TabLocation.BOTTOM,
            effectiveTabLocation(TabSource.LOCAL, TabLocation.BOTTOM, blind = true, store = doc),
        )
        assertEquals(
            TabLocation.OFF,
            effectiveTabLocation(TabSource.LOCAL, TabLocation.OFF, blind = false, store = doc),
        )
    }

    @Test
    fun `follow source defers to yaml rules`() {
        val doc = linkedMapOf<String, Any?>(
            "appearance" to linkedMapOf<String, Any?>("tabsLocation" to "left"),
        )
        assertEquals(
            TabLocation.LEFT,
            effectiveTabLocation(TabSource.FOLLOW_YAML, TabLocation.BOTTOM, blind = false, store = doc),
        )
        assertEquals(
            TabLocation.OFF,
            effectiveTabLocation(TabSource.FOLLOW_YAML, TabLocation.BOTTOM, blind = true, store = doc),
        )
    }
}
