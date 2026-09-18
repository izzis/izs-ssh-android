package id.web.izs.sshclient

import id.web.izs.sshclient.core.sync.AutoSyncAction
import id.web.izs.sshclient.core.sync.decideAutoSync
import id.web.izs.sshclient.core.sync.syncContentHash
import org.junit.Assert.*
import org.junit.Test

/**
 * Auto-sync direction decision: download when only the server moved, upload
 * when only the local YAML moved, pause with a conflict when both moved —
 * never silently overwrite either side.
 */
class AutoSyncDecisionTest {

    @Test
    fun `clean stays clean`() {
        assertEquals(AutoSyncAction.CLEAN, decideAutoSync(false, false))
    }

    @Test
    fun `remote-only downloads`() {
        assertEquals(AutoSyncAction.DOWNLOAD, decideAutoSync(false, true))
    }

    @Test
    fun `local-only uploads`() {
        assertEquals(AutoSyncAction.UPLOAD, decideAutoSync(true, false))
    }

    @Test
    fun `both dirty is a conflict, never an overwrite`() {
        assertEquals(AutoSyncAction.CONFLICT, decideAutoSync(true, true))
    }

    @Test
    fun `hash is stable and sensitive`() {
        val a = "version: 8\nprofiles: []\n"
        assertEquals(syncContentHash(a), syncContentHash(a))
        assertNotEquals(syncContentHash(a), syncContentHash("$a# touched\n"))
        assertEquals(64, syncContentHash(a).length)
    }
}
