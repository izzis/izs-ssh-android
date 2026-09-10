package id.web.izs.sshclient.core.term

/**
 * What one committed chunk of the hidden input pipe stands for. The pipe
 * is display-blind (predictions off, Termux-style): every commit and every
 * platform delete reaches the shell as an event, never derived from field
 * state — history recall, cursor moves, and stale content can't corrupt
 * bytes. Pure function (JVM-testable); the platform connection wrapper in
 * the terminal screen sends what this returns.
 *
 * @param sendText text to send (LF already folded to CR, like Enter).
 * @param submitted true when the chunk carries CR/LF: the line is done,
 *   the caller resets the pipe.
 */
data class PipeCommit(val sendText: String, val submitted: Boolean)

/** Split a committed chunk into sendable text + submit flag. Pure. */
fun pipeCommitOf(text: String): PipeCommit {
    if (text.isEmpty()) return PipeCommit("", false)
    val submitted = text.any { it == '\r' || it == '\n' }
    return PipeCommit(text.replace('\n', '\r'), submitted)
}
