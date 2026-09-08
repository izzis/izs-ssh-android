package id.web.izs.sshclient.core.term

/**
 * Termux-like sticky CTRL/ALT applied to the next typed text (pure, tested).
 * CTRL maps letters/symbols to C0 controls (c & 0x1F); ALT prefixes ESC.
 * Special-key sequences from the extra-keys bar bypass this (sent raw).
 */
object TerminalInput {

    fun applySticky(text: String, ctrl: Boolean, alt: Boolean): String {
        if (text.isEmpty() || (!ctrl && !alt)) return text
        val out = StringBuilder()
        for (c in text) {
            var ch = c
            if (ctrl) {
                ch = when {
                    ch in 'a'..'z' -> (ch.code and 0x1F).toChar()
                    ch in 'A'..'Z' -> (ch.lowercaseChar().code and 0x1F).toChar()
                    ch == ' ' -> 0.toChar()
                    ch == '[' -> 0x1B.toChar()
                    ch == '\\' -> 0x1C.toChar()
                    ch == ']' -> 0x1D.toChar()
                    ch == '^' -> 0x1E.toChar()
                    ch == '_' -> 0x1F.toChar()
                    ch == '?' -> 0x7F.toChar()
                    else -> ch
                }
            }
            if (alt) out.append(0x1B.toChar())
            out.append(ch)
        }
        return out.toString()
    }
}
