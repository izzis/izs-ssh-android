package id.web.izs.sshclient

import id.web.izs.sshclient.core.sync.SyncRepository
import id.web.izs.sshclient.data.local.ConfigDisk
import org.junit.Assert.*
import org.junit.Test

/**
 * Config backup (.bak) building blocks — desktop saveConfig parity
 * (tabby/app/lib/config.ts writes config.yaml.backup on every save).
 * SharedPreferences itself needs Android, so the pure rotation decision
 * the wiring composes is tested here: only a genuinely older, non-blank
 * generation is preserved; everything else leaves .bak untouched.
 */
class ConfigBackupTest {

    @Test
    fun `first seed keeps no backup`() {
        assertNull(ConfigDisk.rotatedBackup(null, "version: 8\n"))
    }

    @Test
    fun `blank current never clobbers a good backup`() {
        assertNull(ConfigDisk.rotatedBackup("", "version: 8\n"))
        assertNull(ConfigDisk.rotatedBackup("   ", "version: 8\n"))
    }

    @Test
    fun `no-op rewrite leaves backup untouched`() {
        assertNull(ConfigDisk.rotatedBackup("version: 8\n", "version: 8\n"))
    }

    @Test
    fun `older generation is preserved`() {
        assertEquals("version: 8\n", ConfigDisk.rotatedBackup("version: 8\n", "version: 8\nprofiles: []\n"))
    }

    @Test
    fun `single slot rolls forward, never accumulates`() {
        // A -> B keeps A; B -> C keeps B (A is gone, like desktop's 1 file).
        val afterFirst = ConfigDisk.rotatedBackup("A", "B")
        assertEquals("A", afterFirst)
        val afterSecond = ConfigDisk.rotatedBackup("B", "C")
        assertEquals("B", afterSecond)
        assertNotEquals("A", afterSecond)
    }

    @Test
    fun `restore prefers RAM snapshot`() {
        assertEquals("ram", SyncRepository.pickRestoreSource("ram", "disk"))
    }

    @Test
    fun `restore falls back to disk when RAM is gone`() {
        assertEquals("disk", SyncRepository.pickRestoreSource(null, "disk"))
        assertEquals("disk", SyncRepository.pickRestoreSource("", "disk"))
        assertEquals("disk", SyncRepository.pickRestoreSource("  ", "disk"))
    }

    @Test
    fun `restore is null when nothing is pending`() {
        assertNull(SyncRepository.pickRestoreSource(null, null))
        assertNull(SyncRepository.pickRestoreSource("", ""))
        assertNull(SyncRepository.pickRestoreSource(null, "  "))
    }
}
