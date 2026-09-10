package id.web.izs.sshclient

import id.web.izs.sshclient.core.term.pipeCommitOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PipeInputTest {
    @Test
    fun `plain text sends as-is, no submit`() {
        val c = pipeCommitOf("ls ")
        assertEquals("ls ", c.sendText)
        assertFalse(c.submitted)
    }

    @Test
    fun `newline folds to CR and marks submitted`() {
        val c = pipeCommitOf("ls\n")
        assertEquals("ls\r", c.sendText)
        assertTrue(c.submitted)
    }

    @Test
    fun `bare CR marks submitted`() {
        val c = pipeCommitOf("\r")
        assertTrue(c.submitted)
    }

    @Test
    fun `empty commits nothing`() {
        val c = pipeCommitOf("")
        assertEquals("", c.sendText)
        assertFalse(c.submitted)
    }
}
