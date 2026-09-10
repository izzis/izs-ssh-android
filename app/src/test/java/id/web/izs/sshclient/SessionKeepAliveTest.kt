package id.web.izs.sshclient

import id.web.izs.sshclient.core.config.SshOptions
import id.web.izs.sshclient.core.config.SshProfile
import id.web.izs.sshclient.core.session.expandedLinesText
import id.web.izs.sshclient.core.session.sessionsSummaryText
import id.web.izs.sshclient.core.session.sessionsTitleText
import id.web.izs.sshclient.ui.ConnectedInfo
import id.web.izs.sshclient.ui.SshSessionViewModel
import id.web.izs.sshclient.ui.sessionLabel
import id.web.izs.sshclient.ui.shouldAutoRetry
import org.junit.Assert.*
import org.junit.Test

/**
 * Background-survival rules (SessionService plan, no device): the mirrored
 * connected list is exact (count + oldest-first labels), the label falls
 * back sanely, the single auto-retry gate only opens for a previously-live
 * session with nothing pending, and the notification text builders stay
 * accurate. The Service/Notification/WakeLock objects themselves need a
 * device (manual test).
 */
class SessionKeepAliveTest {

    private fun profile(id: String, user: String = "root", host: String = "example.com") =
        SshProfile(id = id, name = "p-$id", options = SshOptions(host = host, user = user))

    @Test
    fun `label is user at host`() {
        assertEquals("deploy@db.internal", sessionLabel("deploy", "db.internal", "DB"))
    }

    @Test
    fun `blank user defaults to root`() {
        assertEquals("root@example.com", sessionLabel("", "example.com", "P"))
    }

    @Test
    fun `blank host falls back to profile name`() {
        assertEquals("DB primary", sessionLabel("deploy", "", "DB primary"))
    }

    @Test
    fun `blank host and name falls back to user`() {
        assertEquals("deploy", sessionLabel("deploy", "", ""))
    }

    @Test
    fun `retry opens for a previously live session with nothing pending`() {
        assertTrue(
            shouldAutoRetry(
                everConnected = true,
                autoRetried = false,
                needsPassphrase = false,
                hasAuthPrompt = false,
                hasHostKeyPrompt = false,
            ),
        )
    }

    @Test
    fun `retry stays shut for first-connect failures`() {
        assertFalse(
            shouldAutoRetry(
                everConnected = false,
                autoRetried = false,
                needsPassphrase = false,
                hasAuthPrompt = false,
                hasHostKeyPrompt = false,
            ),
        )
    }

    @Test
    fun `retry fires exactly once per death`() {
        assertFalse(
            shouldAutoRetry(
                everConnected = true,
                autoRetried = true,
                needsPassphrase = false,
                hasAuthPrompt = false,
                hasHostKeyPrompt = false,
            ),
        )
    }

    @Test
    fun `retry waits while any UI answer is pending`() {
        assertFalse(
            shouldAutoRetry(true, false, needsPassphrase = true, hasAuthPrompt = false, hasHostKeyPrompt = false),
        )
        assertFalse(
            shouldAutoRetry(true, false, needsPassphrase = false, hasAuthPrompt = true, hasHostKeyPrompt = false),
        )
        assertFalse(
            shouldAutoRetry(true, false, needsPassphrase = false, hasAuthPrompt = false, hasHostKeyPrompt = true),
        )
    }

    @Test
    fun `mirror reports the exact connected list oldest first`() {
        val vm = SshSessionViewModel()
        val first = vm.create(profile("a", user = "deploy", host = "one.example"))
        // createdAt has ms resolution: separate the births so "oldest
        // first" is deterministic (SnapshotStateMap order is unspecified).
        Thread.sleep(10)
        val second = vm.create(profile("b", user = "root", host = "two.example"))
        vm.get(first)!!.setStatus("connected")
        vm.get(second)!!.setStatus("connected")
        // markSendFailed on a third handle is a public transition that
        // re-mirrors without disturbing the two live ones.
        val third = vm.create(profile("c"))
        var mirrored: List<ConnectedInfo>? = null
        vm.serviceSync = { mirrored = it }
        vm.markSendFailed(third, "boom")
        assertEquals(
            listOf(
                ConnectedInfo(first, "deploy@one.example"),
                ConnectedInfo(second, "root@two.example"),
            ),
            mirrored,
        )
    }

    @Test
    fun `mirror drops closed sessions`() {
        val vm = SshSessionViewModel()
        val sid = vm.create(profile("a"))
        vm.get(sid)!!.setStatus("connected")
        var mirrored: List<ConnectedInfo>? = null
        vm.serviceSync = { mirrored = it }
        vm.close(sid)
        assertEquals(emptyList<ConnectedInfo>(), mirrored)
    }

    @Test
    fun `title counts exactly with singular`() {
        assertEquals("1 SSH session active", sessionsTitleText(1))
        assertEquals("3 SSH sessions active", sessionsTitleText(3))
    }

    @Test
    fun `summary shows two then a remainder`() {
        assertEquals("", sessionsSummaryText(emptyList()))
        assertEquals("a@x", sessionsSummaryText(listOf("a@x")))
        assertEquals("a@x, b@y", sessionsSummaryText(listOf("a@x", "b@y")))
        assertEquals("a@x, b@y +2 more", sessionsSummaryText(listOf("a@x", "b@y", "c@z", "d@w")))
    }

    @Test
    fun `expanded lines cap at five with remainder`() {
        val labels = (1..8).map { "u$it@h$it" }
        val lines = expandedLinesText(labels)
        assertEquals(6, lines.size)
        assertEquals("+3 more", lines.last())
        assertEquals(listOf("a@x", "b@y"), expandedLinesText(listOf("a@x", "b@y")))
    }
}
