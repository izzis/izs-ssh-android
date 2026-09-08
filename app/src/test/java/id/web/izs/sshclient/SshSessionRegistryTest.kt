package id.web.izs.sshclient

import id.web.izs.sshclient.core.config.SshOptions
import id.web.izs.sshclient.core.ssh.transportKeyOf
import id.web.izs.sshclient.core.config.SshProfile
import id.web.izs.sshclient.ui.SessionLimitReached
import id.web.izs.sshclient.ui.SshSessionViewModel
import org.junit.Assert.*
import org.junit.Test

/**
 * Multi-session registry rules (desktop tab parity, no device): every tap
 * opens a NEW tab, the cap is enforced, close reclaims the slot, and the
 * transport key matches the desktop multiplexer format. Connection sharing
 * itself needs a live server (manual device test).
 */
class SshSessionRegistryTest {

    private fun profile(id: String, reuse: Boolean = true) = SshProfile(
        id = id,
        name = "p-$id",
        options = SshOptions(host = "example.com", user = "root", reuseSession = reuse),
    )

    @Test
    fun `create returns a uuid keyed handle`() {
        val vm = SshSessionViewModel()
        val sid = vm.create(profile("a"))
        assertEquals(sid, vm.get(sid)?.sessionId)
        assertEquals("a", vm.get(sid)?.profileId)
        assertEquals(1, vm.ordered().size)
    }

    @Test
    fun `every tap opens a new tab even with reuseSession true`() {
        val vm = SshSessionViewModel()
        val first = vm.create(profile("a", reuse = true))
        val second = vm.create(profile("a", reuse = true))
        assertNotEquals(first, second)
        assertEquals(2, vm.ordered().size)
    }

    @Test
    fun `reuseSession false also opens a new tab per tap`() {
        val vm = SshSessionViewModel()
        val first = vm.create(profile("a", reuse = false))
        val second = vm.create(profile("a", reuse = false))
        assertNotEquals(first, second)
        assertEquals(2, vm.ordered().size)
    }

    @Test
    fun `cap blocks and close reclaims the slot`() {
        val vm = SshSessionViewModel()
        vm.create(profile("a"), maxSessions = 2)
        vm.create(profile("b"), maxSessions = 2)
        try {
            vm.create(profile("c"), maxSessions = 2)
            fail("must throw at cap")
        } catch (e: SessionLimitReached) {
            assertEquals(2, e.max)
        }
        vm.close(vm.ordered().first().sessionId)
        // Slot freed: creating again works.
        vm.create(profile("c"), maxSessions = 2)
        assertEquals(2, vm.ordered().size)
    }

    @Test
    fun `close unknown id and get unknown id are safe no-ops`() {
        val vm = SshSessionViewModel()
        vm.close("nope")
        assertNull(vm.get("nope"))
        assertTrue(vm.ordered().isEmpty())
    }

    @Test
    fun `transport key matches desktop multiplexer format`() {
        val base = SshOptions(host = "example.com", port = 22, user = "root")
        assertEquals("example.com:22:root:::0::0", transportKeyOf(base))
        // Port/user normalize exactly like connectTransport resolves them.
        assertEquals(
            transportKeyOf(base),
            transportKeyOf(base.copy(port = 0, user = "")),
        )
        // Identity splits transports; proxy fields participate.
        assertNotEquals(transportKeyOf(base), transportKeyOf(base.copy(user = "al")))
        assertNotEquals(transportKeyOf(base), transportKeyOf(base.copy(port = 2222)))
        assertNotEquals(
            transportKeyOf(base),
            transportKeyOf(base.copy(proxyCommand = "ssh -W %h:%p jump")),
        )
    }
}
