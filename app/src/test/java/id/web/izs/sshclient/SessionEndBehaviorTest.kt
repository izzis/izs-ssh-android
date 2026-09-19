package id.web.izs.sshclient

import id.web.izs.sshclient.core.config.SshOptions
import id.web.izs.sshclient.core.config.SshProfile
import id.web.izs.sshclient.ui.SshSessionHandle
import id.web.izs.sshclient.ui.shouldDestroyOnSessionEnd
import org.junit.Assert.*
import org.junit.Test

/**
 * behaviorOnSessionEnd tab-lifecycle rules (Tabby base/connectableTerminalTab
 * + sshTab parity): close always destroys; auto destroys only on explicit
 * exit (Ctrl+D tail or a submitted `exit`); keep/reconnect never destroy
 * here (offer / redial instead). The screen + ViewModel act on this; the
 * function itself stays pure for tests.
 */
class SessionEndBehaviorTest {
    private val ctrlD = 4.toChar()

    @Test
    fun `close always destroys whatever the input tail says`() {
        assertTrue(shouldDestroyOnSessionEnd("close", ""))
        assertTrue(shouldDestroyOnSessionEnd("close", "exit\r"))
        assertTrue(shouldDestroyOnSessionEnd("close", "ls\r"))
    }

    @Test
    fun `auto keeps the tab on ordinary deaths`() {
        assertFalse(shouldDestroyOnSessionEnd("auto", ""))
        assertFalse(shouldDestroyOnSessionEnd("auto", "ls -la\r"))
        // Typed but never submitted: no trailing CR, not an exit.
        assertFalse(shouldDestroyOnSessionEnd("auto", "ls -la\rexit"))
    }

    @Test
    fun `auto destroys on a submitted exit`() {
        assertTrue(shouldDestroyOnSessionEnd("auto", "exit\r"))
        assertTrue(shouldDestroyOnSessionEnd("auto", "cd /tmp\rps aux\rexit\r"))
    }

    @Test
    fun `auto destroys on ctrl-D`() {
        assertTrue(shouldDestroyOnSessionEnd("auto", "$ctrlD"))
        assertTrue(shouldDestroyOnSessionEnd("auto", "ls\r$ctrlD"))
    }

    @Test
    fun `keep and reconnect never destroy here`() {
        assertFalse(shouldDestroyOnSessionEnd("keep", "exit\r"))
        assertFalse(shouldDestroyOnSessionEnd("reconnect", "exit\r"))
        assertFalse(shouldDestroyOnSessionEnd("keep", ""))
        assertFalse(shouldDestroyOnSessionEnd("reconnect", "$ctrlD"))
    }

    @Test
    fun `reconnect offer is off by default and toggles`() {
        val h = SshSessionHandle(
            sessionId = "s",
            profileId = "p",
            profileSnapshot = SshProfile(id = "p", name = "n", options = SshOptions(host = "h")),
        )
        assertFalse(h.reconnectOffer.value)
        h.setReconnectOffer(true)
        assertTrue(h.reconnectOffer.value)
        h.setReconnectOffer(false)
        assertFalse(h.reconnectOffer.value)
    }
}
