package id.web.izs.sshclient

import id.web.izs.sshclient.core.term.isMonospaceSample
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MonoFontCheckTest {
    @Test
    fun `true monospace passes`() {
        assertTrue(isMonospaceSample(10f, listOf(10f, 10f, 10f)))
    }

    @Test
    fun `proportional system font fails`() {
        // Narrow i, wide W, short space vs digit advance: OEM face.
        assertFalse(isMonospaceSample(10f, listOf(4f, 14f, 4f)))
    }

    @Test
    fun `small jitter within tolerance passes`() {
        assertTrue(isMonospaceSample(10f, listOf(10.5f, 9.5f, 10f)))
    }

    @Test
    fun `zero measurement abstains instead of condemning`() {
        assertTrue(isMonospaceSample(0f, listOf(4f, 14f, 4f)))
        assertTrue(isMonospaceSample(10f, listOf(10f, 0f, 10f)))
    }
}
