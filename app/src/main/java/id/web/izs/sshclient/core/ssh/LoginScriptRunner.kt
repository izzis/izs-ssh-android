package id.web.izs.sshclient.core.ssh

import id.web.izs.sshclient.core.config.LoginScript

/**
 * Ordered expect/send automation on shell output — desktop
 * `LoginScriptProcessor` parity (`tabby-terminal`
 * `middleware/loginScriptProcessing.ts`), pure JVM for unit tests.
 *
 * Semantics (mirrored exactly, quirks included):
 * - [runUnconditional] sends leading empty-`expect` scripts at session
 *   ready. Trailing empty-`expect` scripts after a conditional one never
 *   fire on desktop either (the feed loop skips them) — same here.
 * - [onOutput] scans the remaining queue in order per chunk: the first
 *   script whose `expect` matches (substring, or regex when `isRegex`)
 *   fires and is consumed. A non-matching `optional` script is dropped and
 *   the scan continues; the first non-matching required script stops the
 *   scan (later chunks retry it).
 * - Backslash escapes are decoded in non-regex `expect` and in `send`
 *   (`\n \r \e \t \a \b \f \v \xNN \uNNNN`, desktop `\d`-only hex quirk
 *   included; anything else keeps the char after the backslash).
 *
 * Returned sends carry NO trailing newline — the caller (`ShellSession.send`)
 * appends it, matching desktop `send + '\n'`.
 */
class LoginScriptRunner(scripts: List<LoginScript>) {

    private data class Item(
        val expect: String,
        val send: String,
        val isRegex: Boolean,
        val optional: Boolean,
    )

    private val remaining = ArrayDeque(scripts.map {
        Item(
            expect = if (it.isRegex) it.expect else unescape(it.expect),
            send = unescape(it.send),
            isRegex = it.isRegex,
            optional = it.optional,
        )
    })

    private val regexCache = mutableMapOf<String, Regex?>()

    /** Leading unconditional scripts for session-ready time. */
    fun runUnconditional(): List<String> {
        val out = mutableListOf<String>()
        while (remaining.isNotEmpty() && remaining.first().expect.isEmpty()) {
            out += remaining.removeFirst().send
        }
        return out
    }

    /** Feed one shell-output chunk; returns sends to write, in order. */
    fun onOutput(chunk: String): List<String> {
        val out = mutableListOf<String>()
        for (s in remaining.toList()) {
            if (s.expect.isEmpty()) continue
            val match = if (s.isRegex) {
                regexFor(s.expect)?.containsMatchIn(chunk) == true
            } else {
                chunk.contains(s.expect)
            }
            if (match) {
                out += s.send
                remaining.remove(s)
            } else if (s.optional) {
                remaining.remove(s)
            } else {
                break
            }
        }
        return out
    }

    /** Scripts still waiting (for tests/diagnostics). */
    fun pendingCount(): Int = remaining.size

    private fun regexFor(pattern: String): Regex? =
        regexCache.getOrPut(pattern) {
            try {
                Regex(pattern)
            } catch (_: Exception) {
                // Desktop would throw on a bad pattern; mobile treats it as
                // never-matching so one typo can't kill the session.
                null
            }
        }

    companion object {
        private val HEX_SEQ = Regex("""\\(x\d{2}|u\d{4})""")
        private val ESC_SEQ = Regex("""\\(.)""")

        fun unescape(line: String): String {
            val hexed = HEX_SEQ.replace(line) { m ->
                m.groupValues[1].substring(1).toInt(16).toChar().toString()
            }
            return ESC_SEQ.replace(hexed) { m ->
                when (m.groupValues[1]) {
                    "a" -> "\u0007"
                    "b" -> "\u0008"
                    "e" -> "\u001B"
                    "f" -> "\u000C"
                    "n" -> "\n"
                    "r" -> "\r"
                    "t" -> "\t"
                    "v" -> "\u000B"
                    else -> m.groupValues[1]
                }
            }
        }
    }
}
