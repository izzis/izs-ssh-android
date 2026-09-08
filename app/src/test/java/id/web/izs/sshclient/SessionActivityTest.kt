package id.web.izs.sshclient

import id.web.izs.sshclient.core.config.SshOptions
import id.web.izs.sshclient.core.config.SshProfile
import id.web.izs.sshclient.ui.SshSessionViewModel
import org.junit.Assert.*
import org.junit.Test

/**
 * Tab activity rules (desktop BaseTabComponent.hasActivity parity, no
 * device): background output lights the dot, selecting clears it.
 */
class SessionActivityTest {

    private fun profile(id: String) = SshProfile(
        id = id,
        name = "p-$id",
        options = SshOptions(host = "example.com", user = "root"),
    )

    @Test
    fun `fresh tab has no activity and no selection`() {
        val vm = SshSessionViewModel()
        val sid = vm.create(profile("a"))
        assertFalse(vm.get(sid)!!.activity.value)
        assertNull(vm.selectedSessionId.value)
    }

    @Test
    fun `output on unselected tab lights its dot only`() {
        val vm = SshSessionViewModel()
        val a = vm.create(profile("a"))
        val b = vm.create(profile("b"))
        vm.select(a)
        vm.noteOutput(b)
        assertFalse(vm.get(a)!!.activity.value)
        assertTrue(vm.get(b)!!.activity.value)
    }

    @Test
    fun `output on the selected tab stays quiet`() {
        val vm = SshSessionViewModel()
        val a = vm.create(profile("a"))
        vm.select(a)
        vm.noteOutput(a)
        assertFalse(vm.get(a)!!.activity.value)
    }

    @Test
    fun `selecting a tab clears its dot`() {
        val vm = SshSessionViewModel()
        val a = vm.create(profile("a"))
        val b = vm.create(profile("b"))
        vm.noteOutput(a)
        vm.noteOutput(b)
        assertTrue(vm.get(a)!!.activity.value)
        vm.select(a)
        assertFalse(vm.get(a)!!.activity.value)
        assertTrue(vm.get(b)!!.activity.value)
        assertEquals(a, vm.selectedSessionId.value)
    }

    @Test
    fun `closing the selected tab clears selection`() {
        val vm = SshSessionViewModel()
        val a = vm.create(profile("a"))
        vm.select(a)
        vm.close(a)
        assertNull(vm.selectedSessionId.value)
    }

    @Test
    fun `noteOutput on unknown id is a no-op`() {
        val vm = SshSessionViewModel()
        vm.noteOutput("nope") // must not throw
    }
}
