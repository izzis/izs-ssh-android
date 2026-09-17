package id.web.izs.sshclient

import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.AesGcmParameters
import id.web.izs.sshclient.data.local.TinkKvStore
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * TinkKvStore contract (JVM): the AEAD is injected, so a generated key
 * replaces the Keystore — no Robolectric. Covers type-exact round-trips,
 * batch editor semantics, cross-instance persistence, and corrupt/wrong-key
 * quarantine (never crash, never half-read).
 */
class TinkKvStoreTest {
    private fun freshAead(): Aead {
        AeadConfig.register()
        val params = AesGcmParameters.builder()
            .setKeySizeBytes(32)
            .setIvSizeBytes(12)
            .setTagSizeBytes(16)
            .setVariant(AesGcmParameters.Variant.NO_PREFIX)
            .build()
        val handle = KeysetHandle.newBuilder()
            .addEntry(KeysetHandle.generateEntryFromParameters(params).withFixedId(1).makePrimary())
            .build()
        return handle.getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    private fun tempFile(): File =
        Files.createTempDirectory("tinktest").toFile().resolve("store.enc")

    @Test
    fun `round trip all types with defaults for absent`() {
        val s = TinkKvStore(freshAead(), tempFile())
        assertEquals("d", s.getString("k", "d"))
        assertNull(s.getString("k", null))
        assertTrue(s.getBoolean("b", true))
        assertEquals(7, s.getInt("i", 7))
        assertEquals(9L, s.getLong("l", 9L))
        assertEquals(1.5f, s.getFloat("f", 1.5f))
        s.putString("k", "v")
        s.putBoolean("b", false)
        s.putInt("i", 5)
        s.putLong("l", 5000L)
        s.putFloat("f", 14f)
        assertEquals("v", s.getString("k", "d"))
        assertFalse(s.getBoolean("b", true))
        assertEquals(5, s.getInt("i", 0))
        assertEquals(5000L, s.getLong("l", 0L))
        assertEquals(14f, s.getFloat("f", 0f))
    }

    @Test
    fun `type mismatch returns the default`() {
        val s = TinkKvStore(freshAead(), tempFile())
        s.putString("k", "v")
        assertEquals(42, s.getInt("k", 42))
        assertEquals(42L, s.getLong("k", 42L))
        assertTrue(s.getBoolean("k", true))
        s.putInt("i", 3)
        assertEquals("d", s.getString("i", "d"))
    }

    @Test
    fun `null string removes and remove vararg contains work`() {
        val s = TinkKvStore(freshAead(), tempFile())
        s.putString("a", "1")
        s.putString("b", "2")
        assertTrue(s.contains("a"))
        s.putString("a", null)
        assertFalse(s.contains("a"))
        s.remove("b", "missing")
        assertFalse(s.contains("b"))
        assertFalse(s.contains("missing"))
    }

    @Test
    fun `editor batches into one sealed write`() {
        val f = tempFile()
        val s = TinkKvStore(freshAead(), f)
        s.edit().putString("a", "1").putInt("n", 2).remove("gone").apply()
        assertEquals("1", s.getString("a", null))
        assertEquals(2, s.getInt("n", 0))
        assertTrue(f.exists())
    }

    @Test
    fun `values persist across instances on the same file and key`() {
        val f = tempFile()
        val aead = freshAead()
        TinkKvStore(aead, f).also {
            it.putString("yaml", "version: 1")
            it.putLong("stamp", 123L)
        }
        val reopened = TinkKvStore(aead, f)
        assertEquals("version: 1", reopened.getString("yaml", null))
        assertEquals(123L, reopened.getLong("stamp", 0L))
    }

    @Test
    fun `corrupt blob quarantines and starts empty`() {
        val f = tempFile()
        val aead = freshAead()
        TinkKvStore(aead, f).putString("k", "v")
        f.writeBytes("garbage-not-a-sealed-blob".toByteArray())
        val reopened = TinkKvStore(aead, f)
        assertNull(reopened.getString("k", null))
        assertEquals(1, f.parentFile!!.listFiles { _, name -> name.contains(".corrupt.") }!!.size)
    }

    @Test
    fun `wrong key never crashes and reads empty`() {
        val f = tempFile()
        TinkKvStore(freshAead(), f).putString("k", "v")
        val reopened = TinkKvStore(freshAead(), f)
        assertNull(reopened.getString("k", null))
        // The unreadable blob is quarantined, not left to fail every boot.
        assertFalse(f.exists())
    }
}
