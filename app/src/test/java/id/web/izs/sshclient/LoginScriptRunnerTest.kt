package id.web.izs.sshclient

import id.web.izs.sshclient.core.config.LoginScript
import id.web.izs.sshclient.core.ssh.LoginScriptRunner
import org.junit.Assert.*
import org.junit.Test

class LoginScriptRunnerTest {

    @Test
    fun `unconditional scripts run first at session ready`() {
        val r = LoginScriptRunner(
            listOf(
                LoginScript(expect = "", send = "echo hi"),
                LoginScript(expect = "$", send = "tmux attach"),
            ),
        )
        assertEquals(listOf("echo hi"), r.runUnconditional())
        assertEquals(1, r.pendingCount())
        assertEquals(listOf("tmux attach"), r.onOutput("user@host:~$ "))
        assertEquals(0, r.pendingCount())
    }

    @Test
    fun `non-optional mismatch blocks until later output`() {
        val r = LoginScriptRunner(
            listOf(LoginScript(expect = "password:", send = "s3cr3t")),
        )
        assertTrue(r.runUnconditional().isEmpty())
        assertTrue(r.onOutput("login banner").isEmpty())
        assertEquals(1, r.pendingCount())
        assertEquals(listOf("s3cr3t"), r.onOutput("Password: password:"))
    }

    @Test
    fun `optional mismatch is skipped and scan continues`() {
        val r = LoginScriptRunner(
            listOf(
                LoginScript(expect = "banner", send = "x", optional = true),
                LoginScript(expect = "$", send = "ls"),
            ),
        )
        assertEquals(listOf("ls"), r.onOutput("user@host:~$ "))
        assertEquals(0, r.pendingCount())
    }

    @Test
    fun `regex expect matches patterns`() {
        val r = LoginScriptRunner(
            listOf(LoginScript(expect = "pass(word|phrase):", send = "s", isRegex = true)),
        )
        assertEquals(listOf("s"), r.onOutput("enter passphrase:"))
    }

    @Test
    fun `bad regex never matches instead of throwing`() {
        val r = LoginScriptRunner(
            listOf(LoginScript(expect = "([", send = "s", isRegex = true)),
        )
        assertTrue(r.onOutput("anything ([ here").isEmpty())
        assertEquals(1, r.pendingCount())
    }

    @Test
    fun `matched scripts fire once only`() {
        val r = LoginScriptRunner(
            listOf(LoginScript(expect = "$", send = "ls")),
        )
        assertEquals(listOf("ls"), r.onOutput("$ "))
        assertTrue(r.onOutput("$ ").isEmpty())
    }

    @Test
    fun `unescape decodes escapes in expect and send`() {
        assertEquals("\n", LoginScriptRunner.unescape("\\n"))
        assertEquals("\u001B", LoginScriptRunner.unescape("\\e"))
        assertEquals("A", LoginScriptRunner.unescape("\\x41"))
        assertEquals("A", LoginScriptRunner.unescape("\\u0041"))
        assertEquals("\\", LoginScriptRunner.unescape("\\\\"))
        assertEquals("q", LoginScriptRunner.unescape("\\q"))
        val r = LoginScriptRunner(
            listOf(LoginScript(expect = "login\\n", send = "u\\ts")),
        )
        assertEquals(listOf("u\ts"), r.onOutput("login\n"))
    }
}
