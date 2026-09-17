package id.web.izs.sshclient

import id.web.izs.sshclient.core.config.SshOptions
import id.web.izs.sshclient.core.config.SshProfile
import id.web.izs.sshclient.ui.SshSessionViewModel
import id.web.izs.sshclient.ui.UsernamePrompt
import id.web.izs.sshclient.ui.normalizeUsername
import id.web.izs.sshclient.ui.shouldAutoRetry
import org.junit.Assert.*
import org.junit.Test

/**
 * Username-prompt rules (desktop `Username for host` parity, no device):
 * surrounding whitespace is never part of a login name, cancel lands on
 * the error card with the cause stated, and a pending username answer
 * blocks the background auto-retry.
 */
class UsernamePromptTest {

    private fun profile() = SshProfile(
        id = "u",
        name = "p-u",
        options = SshOptions(host = "example.com", user = "root"),
    )

    @Test
    fun `typed username is trimmed`() {
        assertEquals("deploy", normalizeUsername("  deploy  "))
    }

    @Test
    fun `blank username answers nothing`() {
        assertNull(normalizeUsername(""))
        assertNull(normalizeUsername("   "))
    }

    @Test
    fun `cancel lands on the error card`() {
        val vm = SshSessionViewModel()
        val sid = vm.create(profile())
        vm.get(sid)?.setUsernamePrompt(UsernamePrompt("example.com"))
        vm.cancelUsernamePrompt(sid)
        val h = vm.get(sid)!!
        assertNull(h.usernamePrompt.value)
        assertEquals("disconnected", h.status.value)
        assertEquals("Username required", h.failed.value)
    }

    @Test
    fun `cancel with no prompt is a no-op`() {
        val vm = SshSessionViewModel()
        val sid = vm.create(profile())
        vm.cancelUsernamePrompt(sid)
        val h = vm.get(sid)!!
        assertNull(h.failed.value)
    }

    @Test
    fun `retry waits for a pending username answer`() {
        assertFalse(
            shouldAutoRetry(
                everConnected = true,
                autoRetried = false,
                needsPassphrase = false,
                hasAuthPrompt = false,
                hasHostKeyPrompt = false,
                hasUsernamePrompt = true,
            ),
        )
    }
}
