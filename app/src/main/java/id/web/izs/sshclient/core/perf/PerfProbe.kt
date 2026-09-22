package id.web.izs.sshclient.core.perf

/**
 * Zero-overhead timing hook for save-path profiling (pure JVM, no Android
 * dependency so vault/tink/yaml units stay JVM-testable).
 *
 * [listener] is null by default: [measure] then runs [block] directly.
 * Debug builds attach a `Log.d` listener in MainActivity; unit benchmarks
 * attach their own. Tags: `vault.pbkdf2`, `vault.aes.*`, `tink.*`,
 * `yaml.*`, `disk.*`.
 */
object PerfProbe {
    var listener: ((tag: String, ms: Long) -> Unit)? = null

    inline fun <T> measure(tag: String, block: () -> T): T {
        val l = listener ?: return block()
        val start = System.nanoTime()
        try {
            return block()
        } finally {
            l(tag, (System.nanoTime() - start) / 1_000_000)
        }
    }
}
