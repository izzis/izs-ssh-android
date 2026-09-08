package id.web.izs.sshclient.core.ssh

import com.hierynomus.sshj.key.KeyAlgorithm
import com.hierynomus.sshj.key.KeyAlgorithms
import com.hierynomus.sshj.transport.cipher.BlockCiphers
import com.hierynomus.sshj.transport.cipher.ChachaPolyCiphers
import com.hierynomus.sshj.transport.cipher.GcmCiphers
import com.hierynomus.sshj.transport.kex.DHGroups
import com.hierynomus.sshj.transport.mac.Macs
import id.web.izs.sshclient.core.config.SshAlgorithms
import net.schmizz.sshj.Config
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.common.Factory
import net.schmizz.sshj.transport.cipher.Cipher
import net.schmizz.sshj.transport.compression.Compression
import net.schmizz.sshj.transport.compression.DelayedZlibCompression
import net.schmizz.sshj.transport.compression.NoneCompression
import net.schmizz.sshj.transport.compression.ZlibCompression
import net.schmizz.sshj.transport.kex.Curve25519SHA256
import net.schmizz.sshj.transport.kex.ECDHNistP
import net.schmizz.sshj.transport.kex.KeyExchange
import net.schmizz.sshj.transport.mac.MAC

/**
 * Honors the profile Ciphers tab at connect time. Desktop
 * (`ssh.ts:377-427`) filters each algorithm list to what its transport
 * (russh) supports; here each desktop wire name is resolved to an sshj
 * factory by that same wire name, and unknown names are skipped the same
 * way (e.g. `mlkem768x25519-sha256`, `ext-info-*`, `kex-strict-*` have no
 * sshj equivalent).
 *
 * A category that resolves to nothing keeps the sshj default (an empty
 * offer would break negotiation). Pure JVM except [configFor], which
 * builds a real sshj [Config] — still unit-testable (no connection made).
 */
object SshAlgorithmFactories {

    private val ciphers: Map<String, Factory.Named<Cipher>> = listOf(
        ChachaPolyCiphers.CHACHA_POLY_OPENSSH(),
        GcmCiphers.AES256GCM(),
        GcmCiphers.AES128GCM(),
        BlockCiphers.AES256CTR(),
        BlockCiphers.AES192CTR(),
        BlockCiphers.AES128CTR(),
        BlockCiphers.AES256CBC(),
        BlockCiphers.AES192CBC(),
        BlockCiphers.AES128CBC(),
    ).associateBy { it.name }

    private val kex: Map<String, Factory.Named<KeyExchange>> = listOf(
        Curve25519SHA256.Factory(),
        Curve25519SHA256.FactoryLibSsh(),
        DHGroups.Group16SHA512(),
        DHGroups.Group14SHA256(),
        DHGroups.Group14SHA1(),
        DHGroups.Group1SHA1(),
        ECDHNistP.Factory256(),
        ECDHNistP.Factory384(),
        ECDHNistP.Factory521(),
    ).associateBy { it.name }

    private val macs: Map<String, Factory.Named<MAC>> = listOf(
        Macs.HMACSHA2512Etm(),
        Macs.HMACSHA2256Etm(),
        Macs.HMACSHA2512(),
        Macs.HMACSHA2256(),
        Macs.HMACSHA1Etm(),
        Macs.HMACSHA196Etm(),
        Macs.HMACSHA1(),
        Macs.HMACSHA196(),
    ).associateBy { it.name }

    private val hostKeys: Map<String, Factory.Named<KeyAlgorithm>> = listOf(
        KeyAlgorithms.EdDSA25519(),
        KeyAlgorithms.ECDSASHANistp256(),
        KeyAlgorithms.ECDSASHANistp384(),
        KeyAlgorithms.ECDSASHANistp521(),
        KeyAlgorithms.RSASHA256(),
        KeyAlgorithms.RSASHA512(),
        KeyAlgorithms.SSHRSA(),
    ).associateBy { it.name }

    private val compressions: Map<String, Factory.Named<Compression>> = listOf(
        NoneCompression.Factory(),
        ZlibCompression.Factory(),
        DelayedZlibCompression.Factory(),
    ).associateBy { it.name }

    fun resolveCiphers(names: List<String>): List<Factory.Named<Cipher>> =
        names.mapNotNull(ciphers::get)

    fun resolveKex(names: List<String>): List<Factory.Named<KeyExchange>> =
        names.mapNotNull(kex::get)

    fun resolveMacs(names: List<String>): List<Factory.Named<MAC>> =
        names.mapNotNull(macs::get)

    fun resolveHostKeys(names: List<String>): List<Factory.Named<KeyAlgorithm>> =
        names.mapNotNull(hostKeys::get)

    fun resolveCompressions(names: List<String>): List<Factory.Named<Compression>> =
        names.mapNotNull(compressions::get)

    /** Desktop names with no sshj equivalent (skipped, like desktop filters). */
    fun skipped(algorithms: Map<String, List<String>>): Map<String, List<String>> =
        algorithms.mapValues { (type, names) ->
            val known = when (type) {
                SshAlgorithms.CIPHER -> ciphers
                SshAlgorithms.KEX -> kex
                SshAlgorithms.HMAC -> macs
                SshAlgorithms.SERVER_HOST_KEY -> hostKeys
                SshAlgorithms.COMPRESSION -> compressions
                else -> emptyMap()
            }
            names.filter { it !in known }
        }.filterValues { it.isNotEmpty() }

    /**
     * Effective server-host-key offer for a profile: resolved custom list
     * (second = true) or sshj default names (second = false). The trust
     * verifier uses this as the negotiation set for known-first ordering.
     */
    fun effectiveHostKeys(algorithms: Map<String, List<String>>): Pair<List<String>, Boolean> {
        val custom = algorithms.takeIf { it.isNotEmpty() && it != SshAlgorithms.DEFAULTS }
            ?.get(SshAlgorithms.SERVER_HOST_KEY)?.takeIf { it.isNotEmpty() }
        if (custom != null) return resolveHostKeys(custom).map { it.name } to true
        return defaultHostKeyTypes() to false
    }

    fun defaultHostKeyTypes(): List<String> =
        DefaultConfig().keyAlgorithms.map { it.name }
    /**
     * Host-key offer order with trust baked in: known types first, then the
     * rest (custom order kept for explicit lists, desktop order otherwise).
     *
     * Why here and not just the verifier: sshj's `Proposal` iterates the
     * CONFIGURED order and only uses the verifier list as a membership
     * filter (verified in bytecode) — so a verifier-side order alone never
     * changes negotiation. The config order is what the server sees.
     */
    fun orderedHostKeyNames(
        configured: List<String>,
        knownHostTypes: List<String>,
        isCustom: Boolean,
    ): List<String> {
        val known = knownHostTypes.filter { it in configured }.distinct()
        val rest = configured.filter { it !in known.toSet() }
        val tail = if (isCustom) {
            rest
        } else {
            rest.sortedBy {
                HostKeyTrust.DESKTOP_ORDER.indexOf(it).takeIf { i -> i >= 0 } ?: Int.MAX_VALUE
            }
        }
        return known + tail
    }

    /**
     * Per-connection sshj config. Host-key order always carries trust
     * (known-first, desktop order on defaults) so phone and desktop pick
     * the same server key; other categories are only touched for explicit
     * custom lists (defaults otherwise stay sshj stock).
     */
    fun configFor(
        algorithms: Map<String, List<String>>,
        knownHostTypes: List<String> = emptyList(),
    ): Config {
        val custom = algorithms.isNotEmpty() && algorithms != SshAlgorithms.DEFAULTS
        val cfg = DefaultConfig()
        if (custom) {
            resolveCiphers(algorithms[SshAlgorithms.CIPHER] ?: emptyList())
                .ifEmpty { cfg.cipherFactories }.also { cfg.cipherFactories = it }
            resolveKex(algorithms[SshAlgorithms.KEX] ?: emptyList())
                .ifEmpty { cfg.keyExchangeFactories }.also { cfg.keyExchangeFactories = it }
            resolveMacs(algorithms[SshAlgorithms.HMAC] ?: emptyList())
                .ifEmpty { cfg.macFactories }.also { cfg.macFactories = it }
            resolveCompressions(algorithms[SshAlgorithms.COMPRESSION] ?: emptyList())
                .ifEmpty { cfg.compressionFactories }.also { cfg.compressionFactories = it }
        }
        val (baseNames, isCustomHk) = effectiveHostKeys(algorithms)
        // Negotiation-safe: an order that resolves to nothing keeps stock.
        resolveHostKeys(orderedHostKeyNames(baseNames, knownHostTypes, isCustomHk))
            .ifEmpty { cfg.keyAlgorithms }.also { cfg.keyAlgorithms = it }
        return cfg
    }
}
