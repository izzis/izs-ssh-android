package id.web.izs.sshclient

import id.web.izs.sshclient.core.config.RawConfigStore
import id.web.izs.sshclient.core.vault.SecretResolver
import id.web.izs.sshclient.core.vault.VaultCrypto
import id.web.izs.sshclient.core.vault.VaultState
import org.junit.Assert.*
import org.junit.Test

/**
 * Regression: unchecking "Encrypt config file" must never drop the vault.
 * Desktop truth — the blob is ALWAYS the secrets container; the `encrypted`
 * flag only changes the file shape (shell vs full document with inline
 * blob). The old logic treated `encrypted=false` as "no vault", which made
 * every vault content inaccessible with no passphrase prompt and no way back.
 */
class VaultStateTest {
    private val pass = "vault-pass"
    private val configJson = """{"version":1,"profiles":[{"id":"ssh:1","type":"ssh","name":"web-01","options":{"host":"10.0.0.1","port":22,"user":"root","password":"vault://pw-1","privateKeys":[]}}],"groups":[]}"""
    private val secretsJson = """[{"type":"ssh:password","key":{"user":"root","host":"10.0.0.1","port":22},"value":"s3cr3t"}]"""

    private fun shell(): LinkedHashMap<String, Any?> {
        val stored = VaultCrypto.encrypt(configJson, secretsJson, pass)
        return linkedMapOf(
            RawConfigStore.KEY_VAULT to RawConfigStore.storedVaultMap(stored),
            RawConfigStore.KEY_ENCRYPTED to true,
        )
    }

    /** The toggle-OFF document: full config + the SAME blob inline. */
    private fun offDoc(shell: LinkedHashMap<String, Any?> = shell()): LinkedHashMap<String, Any?> {
        val before = RawConfigStore.storedVault(shell)!!
        val (cfg, _) = VaultCrypto.decrypt(before, pass)
        val out = RawConfigStore.loadRaw(RawConfigStore.yamlFromJson(cfg))
        out[RawConfigStore.KEY_VAULT] = RawConfigStore.storedVaultMap(before)
        out[RawConfigStore.KEY_ENCRYPTED] = false
        return out
    }

    @Test
    fun `plaintext without vault is open`() {
        val raw = linkedMapOf<String, Any?>("version" to 1, "profiles" to emptyList<Any>())
        val v = VaultState.resolve(raw, null)
        assertFalse(v.needsPassphrase)
        assertNull(v.secrets)
    }

    @Test
    fun `shell locked needs passphrase and hides profiles`() {
        val v = VaultState.resolve(shell(), null)
        assertTrue(v.needsPassphrase)
        assertTrue(v.unlockRequired)
        assertNull(v.secrets)
        assertTrue(RawConfigStore.toDomain(v.domainDoc).profiles.isEmpty())
        assertEquals(setOf("vault", "encrypted"), v.store.keys)
    }

    @Test
    fun `shell unlocked resolves secrets and merged view`() {
        val v = VaultState.resolve(shell(), pass)
        assertFalse(v.needsPassphrase)
        val secrets = requireNotNull(v.secrets)
        assertEquals(1, secrets.size)
        assertEquals(1, RawConfigStore.toDomain(v.domainDoc).profiles.size)
        assertTrue(v.store.containsKey("profiles"))
        assertEquals(
            "s3cr3t",
            SecretResolver.findPassword(secrets, "root", "10.0.0.1", 22)?.value,
        )
    }

    @Test
    fun `shell wrong passphrase throws unless forgiven`() {
        try {
            VaultState.resolve(shell(), "wrong", forgiveStale = false)
            fail("must throw")
        } catch (e: VaultCrypto.BadDecryptException) {
            // Expected: explicit unlock surfaces Retry/Delete/Cancel.
        }
        val v = VaultState.resolve(shell(), "wrong", forgiveStale = true)
        assertTrue(v.needsPassphrase)
        assertTrue(v.stalePassphrase)
    }

    @Test
    fun `toggle-OFF doc unlocked keeps resolving secrets`() {
        val v = VaultState.resolve(offDoc(), pass)
        assertFalse(v.needsPassphrase)
        val secrets = requireNotNull(v.secrets)
        assertEquals(1, secrets.size)
        assertEquals(
            "s3cr3t",
            SecretResolver.findPassword(secrets, "root", "10.0.0.1", 22)?.value,
        )
        assertEquals(1, RawConfigStore.toDomain(v.domainDoc).profiles.size)
    }

    @Test
    fun `toggle-OFF doc locked still lists profiles and asks for passphrase`() {
        val v = VaultState.resolve(offDoc(), null)
        assertTrue(v.needsPassphrase)
        // Lazy unlock: listing works, so boot never blocks on this state.
        assertFalse(v.unlockRequired)
        assertNull(v.secrets)
        // Profiles stay visible (outer doc is plaintext); only secrets gated.
        assertEquals(1, RawConfigStore.toDomain(v.domainDoc).profiles.size)
    }

    @Test
    fun `toggle-OFF keeps blob byte-identical`() {
        val s = shell()
        val before = RawConfigStore.storedVault(s)!!
        val v = VaultState.resolve(offDoc(s), pass)
        val after = RawConfigStore.storedVault(v.store)!!
        assertEquals(before.contents, after.contents)
        assertEquals(before.keySalt, after.keySalt)
        assertEquals(before.iv, after.iv)
    }

    @Test
    fun `corrupt empty blob offers retry path`() {
        val raw = linkedMapOf<String, Any?>(
            RawConfigStore.KEY_VAULT to linkedMapOf(
                "version" to 1, "contents" to "", "keySalt" to "", "iv" to "",
            ),
            RawConfigStore.KEY_ENCRYPTED to true,
        )
        assertTrue(VaultState.resolve(raw, pass).needsPassphrase)
    }
}
