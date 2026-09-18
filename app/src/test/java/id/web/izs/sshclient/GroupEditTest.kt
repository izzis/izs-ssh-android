package id.web.izs.sshclient

import id.web.izs.sshclient.core.config.RawConfigStore
import org.junit.Assert.*
import org.junit.Test

/**
 * Group rename/delete raw-helper tests (home folder pencil parity with
 * desktop deleteProfileGroup(group, {deleteProfiles:false})): rename keeps
 * unknown entry keys; delete ungroups members and lifts child groups to
 * top level instead of deleting anything else.
 */
class GroupEditTest {

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
            name: db
            group: g-nested
            options:
              host: db.internal
          - id: ssh:33333333-3333-3333-3333-333333333333
            type: ssh
            name: lone
            options:
              host: example.com
        groups:
          - id: g-servers
            name: Servers
            custom: keep-me
          - id: g-nested
            name: Nested
            parentGroupId: g-servers
          - id: g-other
            name: Other
    """.trimIndent()

    private fun groupById(doc: Map<String, Any?>, id: String): Map<String, Any?>? =
        ((doc["groups"] as? List<*>) ?: emptyList<Any>())
            .filterIsInstance<Map<String, Any?>>()
            .firstOrNull { it["id"]?.toString() == id }

    private fun profileById(doc: Map<String, Any?>, id: String): Map<String, Any?>? =
        ((doc["profiles"] as? List<*>) ?: emptyList<Any>())
            .filterIsInstance<Map<String, Any?>>()
            .firstOrNull { it["id"]?.toString() == id }

    @Test
    fun `rename updates name and trims, keeps unknown keys`() {
        val doc = RawConfigStore.loadRaw(yaml)
        RawConfigStore.renameGroupEntry(doc, "g-servers", "  Production  ")
        val g = groupById(doc, "g-servers")!!
        assertEquals("Production", g["name"])
        assertEquals("keep-me", g["custom"])
        assertEquals("Nested", groupById(doc, "g-nested")!!["name"])
        // domain view agrees
        assertEquals("Production", RawConfigStore.toDomain(doc).groups.first { it.id == "g-servers" }.name)
    }

    @Test
    fun `rename unknown id is a no-op`() {
        val doc = RawConfigStore.loadRaw(yaml)
        RawConfigStore.renameGroupEntry(doc, "g-missing", "X")
        assertEquals(3, (doc["groups"] as List<*>).size)
        assertEquals("Servers", groupById(doc, "g-servers")!!["name"])
    }

    @Test
    fun `delete ungroups members and lifts children`() {
        val doc = RawConfigStore.loadRaw(yaml)
        RawConfigStore.deleteGroupEntry(doc, "g-servers")
        // entry gone, others intact
        assertNull(groupById(doc, "g-servers"))
        assertEquals(2, (doc["groups"] as List<*>).size)
        // member ungrouped via key removal (ungrouped convention)
        val web = profileById(doc, "ssh:11111111-1111-1111-1111-111111111111")!!
        assertFalse(web.containsKey("group"))
        // child group rises to top level, keeps its own members
        val nested = groupById(doc, "g-nested")!!
        assertFalse(nested.containsKey("parentGroupId"))
        val db = profileById(doc, "ssh:22222222-2222-2222-2222-222222222222")!!
        assertEquals("g-nested", db["group"]?.toString())
        // untouched profile still grouped nowhere
        assertFalse(profileById(doc, "ssh:33333333-3333-3333-3333-333333333333")!!.containsKey("group"))
        // domain view agrees: 2 groups, prod-web ungrouped
        val domain = RawConfigStore.toDomain(doc)
        assertEquals(2, domain.groups.size)
        assertNull(domain.profiles.first { it.name == "prod-web" }.group)
        // dump + reload is stable
        val reloaded = RawConfigStore.loadRaw(RawConfigStore.dumpRaw(doc))
        assertEquals(2, (reloaded["groups"] as List<*>).size)
    }

    @Test
    fun `delete unknown id removes nothing`() {
        val doc = RawConfigStore.loadRaw(yaml)
        RawConfigStore.deleteGroupEntry(doc, "g-missing")
        assertEquals(3, (doc["groups"] as List<*>).size)
        assertEquals(3, (doc["profiles"] as List<*>).size)
        assertEquals("g-servers", profileById(doc, "ssh:11111111-1111-1111-1111-111111111111")!!["group"])
    }

    @Test
    fun `move reparents and lifts to top level`() {
        val doc = RawConfigStore.loadRaw(yaml)
        assertTrue(RawConfigStore.moveGroupEntry(doc, "g-other", "g-nested"))
        assertEquals("g-nested", groupById(doc, "g-other")!!["parentGroupId"])
        // Moved entry keeps its YAML slot (desktop writeProfileGroup
        // parity) — only brand-new groups append at the bottom.
        assertEquals(
            listOf("g-servers", "g-nested", "g-other"),
            RawConfigStore.toDomain(doc).groups.map { it.id },
        )
        assertTrue(RawConfigStore.moveGroupEntry(doc, "g-nested", null))
        assertFalse(groupById(doc, "g-nested")!!.containsKey("parentGroupId"))
        // members follow their group, nothing else touched
        assertEquals("g-nested", profileById(doc, "ssh:22222222-2222-2222-2222-222222222222")!!["group"])
        val domain = RawConfigStore.toDomain(doc)
        assertNull(domain.groups.first { it.id == "g-nested" }.parentGroupId)
    }

    @Test
    fun `move rejects self unknown and cycles`() {
        val doc = RawConfigStore.loadRaw(yaml)
        assertFalse(RawConfigStore.moveGroupEntry(doc, "g-servers", "g-servers"))
        assertFalse(RawConfigStore.moveGroupEntry(doc, "g-missing", "g-servers"))
        assertFalse(RawConfigStore.moveGroupEntry(doc, "g-other", "g-missing"))
        // g-servers is an ancestor of g-nested: nesting it under g-nested cycles
        assertFalse(RawConfigStore.moveGroupEntry(doc, "g-servers", "g-nested"))
        // rejected moves write nothing
        assertFalse(groupById(doc, "g-servers")!!.containsKey("parentGroupId"))
        assertEquals("g-servers", groupById(doc, "g-nested")!!["parentGroupId"])
    }
}
