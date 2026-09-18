package id.web.izs.sshclient

import id.web.izs.sshclient.core.config.RawConfigStore
import org.junit.Assert.*
import org.junit.Test

/**
 * Sync-target persistence: the reported bug was a silent no-save — Test and
 * save showed "Connected." yet the Config file kept `configSync: {}`, and a
 * download lost the retarget the same way. Cause: [RawConfigStore] nested
 * mutations went into the detached copy from `asMutableStringMap` without
 * being written back when the section already existed (the fresh-install
 * seed writes an empty `configSync`, so the present-key branch was always
 * taken and always dropped). These tests pin the parent doc — not the
 * returned copy — carries the mutation, through a dump/load round-trip.
 */
class SyncTargetTest {

    @Test
    fun `setSyncTarget fills a present-but-empty section (seed shape)`() {
        val doc = RawConfigStore.loadRaw("version: 8\nprofiles: []\nconfigSync: {}\n")
        RawConfigStore.setSyncTarget(
            doc,
            RawConfigStore.RawSyncTarget("https://sync.example.com", "SECRET", 7L),
        )
        val target = RawConfigStore.syncTargetOf(doc)
        assertEquals("https://sync.example.com", target.host)
        assertEquals("SECRET", target.token)
        assertEquals(7L, target.configId)
        // And it survives the disk round-trip, not just the RAM doc.
        val reloaded = RawConfigStore.loadRaw(RawConfigStore.dumpRaw(doc))
        assertEquals("https://sync.example.com", RawConfigStore.syncTargetOf(reloaded).host)
    }

    @Test
    fun `setSyncTarget creates a missing section`() {
        val doc = RawConfigStore.loadRaw("version: 8\nprofiles: []\n")
        RawConfigStore.setSyncTarget(
            doc,
            RawConfigStore.RawSyncTarget("https://sync.example.com", "SECRET", -1L),
        )
        val target = RawConfigStore.syncTargetOf(doc)
        assertEquals("https://sync.example.com", target.host)
        assertEquals("SECRET", target.token)
        assertEquals(-1L, target.configId)
    }

    @Test
    fun `setSyncTarget overwrites stale values in place`() {
        val doc = RawConfigStore.loadRaw(
            "version: 8\nprofiles: []\nconfigSync: {host: http://old, token: OLD, configID: 1}\n",
        )
        RawConfigStore.setSyncTarget(
            doc,
            RawConfigStore.RawSyncTarget("https://sync.example.com", "NEW", 2L),
        )
        val target = RawConfigStore.syncTargetOf(doc)
        assertEquals("https://sync.example.com", target.host)
        assertEquals("NEW", target.token)
        assertEquals(2L, target.configId)
    }

    @Test
    fun `retargetSyncSection points a merged doc at the downloaded config`() {
        // Remote docs carry their own (or no) sync section; after a download
        // the local doc must point at the cloud config it came from.
        val merged = RawConfigStore.loadRaw(
            "version: 8\nprofiles: []\nconfigSync: {host: http://other, token: OTHER}\n",
        )
        RawConfigStore.retargetSyncSection(merged, "https://sync.example.com", "SECRET", 7L)
        val target = RawConfigStore.syncTargetOf(merged)
        assertEquals("https://sync.example.com", target.host)
        assertEquals("SECRET", target.token)
        assertEquals(7L, target.configId)
        val reloaded = RawConfigStore.loadRaw(RawConfigStore.dumpRaw(merged))
        assertEquals(7L, RawConfigStore.syncTargetOf(reloaded).configId)
    }
}
