package id.web.izs.sshclient.ui.screens

import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.CompletionInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.PlatformTextInputSession
import id.web.izs.sshclient.core.term.pipeCommitOf
import kotlinx.coroutines.awaitCancellation

/**
 * Platform input pipe for the hidden terminal field: Termux's
 * `TerminalView.onCreateInputConnection`, ported to the current
 * interception API — no deprecated calls.
 *
 * The one structural lesson from Termux: the IME-facing buffer NEVER
 * holds terminal content. It is an always-empty scratch: every commit
 * sends its bytes and clears, every backspace sends DEL key for key —
 * unconditionally. There is no mirror to adopt, no recalled line to
 * reconstruct, no prompt to measure, so backspace works after UP on a
 * command of any length. The shell owns the line; the keyboard owns
 * nothing.
 *
 * Details, all matching Termux:
 * - `inputType = TYPE_NULL` (their default; the password variation is
 *   only their Samsung fallback): no suggestion strip, no composing
 *   tricks, no password-manager overlay — char-based input. Only
 *   `NO_FULLSCREEN` is added. Urutan ini penting: the framework refills
 *   inputType from keyboard options inside createInputConnection, so the
 *   write happens AFTER, on the same object that travels to the IME.
 * - `commitText`: super first (the keyboard sees its commit applied —
 *   clearing before that is what made SwiftKey "repair" with duplicate
 *   commits), then send, then clear the scratch.
 * - `deleteSurroundingText`: that many DELs (Termux loops for Samsung's
 *   leftLength > 1), then super. Both the plain and code-point variants:
 *   GBoard may call either. This only fires while the keyboard believes
 *   there is text; on an empty scratch (always, here) keyboards send a DEL
 *   *key event* instead — see [sendKeyEvent].
 * - `sendKeyEvent`: the general soft-keyboard path, not just backspace.
 *   Termux documents it (`TerminalView.onKeyDown`): on TYPE_NULL, GBoard
 *   sends *everything* — letters included — as key events with
 *   deviceId=-1 instead of commitText (only Hacker's/OpenBoard/LG commit).
 *   So this maps what their `handleKeyCode` + `getUnicodeChar` map:
 *   arrows/ENTER/DEL/TAB/ESC/FWD-DEL to bytes, other keys to their unicode
 *   char (shift via the system kcm, like theirs), Ctrl-held letters to
 *   control codes (their `inputCodePoint` branch). Everything handled is
 *   consumed, so the invisible field never eats it. Dead-key combining
 *   accents are skipped (soft keyboards rarely send them; Termux tracks
 *   them for hardware).
 * - `finishComposingText`: send whatever composing accumulated, clear.
 * - `performEditorAction`: the Done key sends CR (Termux relies on the
 *   AOSP keyboard committing "\n" instead; our field declares Done, so
 *   the action arrives here, not as text).
 *
 * Key events (hardware backspace) never become connection calls, so the
 * screen handles those separately via onKeyEvent — the two paths are
 * mutually exclusive per press, never double.
 *
 * @param view host view the connection anchors to (a plain View, like
 *   Termux's TerminalView — never a TextView).
 * @param onCommitText committed text (already CR-folded); [submitted]
 *   marks a line submit (CR/LF inside) for the caller to reset the pipe.
 * @param onDelete chars to rub out before the cursor ([DEL] each).
 * @param onForwardDelete chars to cut after the cursor ([FWD] each).
 * @param onSpecial raw escape sequence (arrows, ESC) — bypasses sticky
 *   modifiers like any extra-key send.
 * @param onEditorAction the Done/action key: send CR, single path.
 */
class SshInputInterceptor(
    private val view: View,
    private val onCommitText: (text: String, submitted: Boolean) -> Unit,
    private val onDelete: (Int) -> Unit,
    private val onForwardDelete: (Int) -> Unit,
    private val onSpecial: (seq: String) -> Unit,
    private val onEditorAction: () -> Unit,
) : PlatformTextInputInterceptor {
    override suspend fun interceptStartInputMethod(
        request: PlatformTextInputMethodRequest,
        nextHandler: PlatformTextInputSession,
    ): Nothing {
        nextHandler.startInputMethod(
            object : PlatformTextInputMethodRequest by request {
                override fun createInputConnection(outAttributes: EditorInfo): InputConnection {
                    outAttributes.inputType = InputType.TYPE_NULL
                    outAttributes.imeOptions =
                        outAttributes.imeOptions or EditorInfo.IME_FLAG_NO_FULLSCREEN
                    // Our own connection, not the state's: nothing terminal
                    // content ever reaches the IME (see class doc).
                    return SshInputConnection(
                        view, onCommitText, onDelete, onForwardDelete, onSpecial, onEditorAction,
                    )
                }
            },
        )
        // Sessions never return: the contract is to suspend until replaced.
        awaitCancellation()
    }
}

// ASCII-safe escape introducer (never raw control bytes in source).
private const val PIPE_ESC = "\u001B"

private class SshInputConnection(
    targetView: View,
    private val onCommitText: (text: String, submitted: Boolean) -> Unit,
    private val onDelete: (Int) -> Unit,
    private val onForwardDelete: (Int) -> Unit,
    private val onSpecial: (seq: String) -> Unit,
    private val onEditorAction: () -> Unit,
) : BaseInputConnection(targetView, true) {
    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        val s = text?.toString().orEmpty()
        super.commitText(text, newCursorPosition)
        if (s.isNotEmpty()) {
            val c = pipeCommitOf(s)
            onCommitText(c.sendText, c.submitted)
        }
        editable?.clear()
        return true
    }

    override fun commitCompletion(text: CompletionInfo?): Boolean {
        // Autocomplete pick without a strip is rare; treat like a commit.
        return commitText(text?.text, 1)
    }
    // finishComposingText deliberately sends the composing remainder:
    // uncommitted composing was never sent, and the final text always
    // arrives as a commit right after (or nothing does) — either way the
    // scratch ends empty, never holding terminal content.

    override fun finishComposingText(): Boolean {
        super.finishComposingText()
        val s = editable?.toString().orEmpty()
        if (s.isNotEmpty()) {
            val c = pipeCommitOf(s)
            onCommitText(c.sendText, c.submitted)
        }
        editable?.clear()
        return true
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        if (beforeLength > 0) onDelete(beforeLength)
        if (afterLength > 0) onForwardDelete(afterLength)
        return super.deleteSurroundingText(beforeLength, afterLength)
    }

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
        if (beforeLength > 0) onDelete(beforeLength)
        if (afterLength > 0) onForwardDelete(afterLength)
        return super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
    }

    override fun sendKeyEvent(event: KeyEvent?): Boolean {
        if (event == null) return super.sendKeyEvent(null)
        // Note: no ACTION_MULTIPLE branch (Termux has one for dead-key
        // combos): both it and KeyEvent.characters are deprecated, and no
        // modern keyboard sends it — accented chars arrive as commitText.
        if (event.action != KeyEvent.ACTION_DOWN) return super.sendKeyEvent(event)
        // Termux order: special keys first (their handleKeyCode), unicode
        // after (their getUnicodeChar). UP is noise (its DOWN already
        // fired); only DOWN sends, never double.
        when (event.keyCode) {
            KeyEvent.KEYCODE_DEL -> {
                onDelete(1)
                return true
            }
            KeyEvent.KEYCODE_FORWARD_DEL -> {
                onForwardDelete(1)
                return true
            }
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_DPAD_CENTER,
            -> {
                onEditorAction()
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                onSpecial("$PIPE_ESC[A")
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                onSpecial("$PIPE_ESC[B")
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                onSpecial("$PIPE_ESC[C")
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                onSpecial("$PIPE_ESC[D")
                return true
            }
            KeyEvent.KEYCODE_ESCAPE -> {
                onSpecial(PIPE_ESC)
                return true
            }
        }
        // Printable dispatch: clear Ctrl/Alt like Termux (we apply those
        // ourselves — event-Ctrl via the control map below, sticky Alt/Ctrl
        // in the caller's cooked send), keep Shift for the kcm uppercasing.
        val effectiveMeta = event.metaState and
            (KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON).inv()
        val uni = event.getUnicodeChar(effectiveMeta)
        if (uni == 0) return super.sendKeyEvent(event)
        val cp = if (event.isCtrlPressed) ctrlOf(uni) else uni
        if (cp == '\r'.code || cp == '\n'.code) {
            onEditorAction()
        } else {
            val c = pipeCommitOf(cp.toChar().toString())
            onCommitText(c.sendText, c.submitted)
        }
        return true
    }

    /**
     * Ctrl-held letter to its C0 control code (Termux `inputCodePoint`
     * branch: Ctrl-A..Z, Ctrl-Space, Ctrl-[ etc). Anything unmapped stays
     * as-is — the shell, not us, decides what it means.
     */
    private fun ctrlOf(code: Int): Int {
        val ch = code.toChar()
        return when {
            ch in 'a'..'z' -> ch - 'a' + 1
            ch in 'A'..'Z' -> ch - 'A' + 1
            ch == ' ' || ch == '2' -> 0
            ch == '[' || ch == '3' -> 27
            ch == '\\' || ch == '4' -> 28
            ch == ']' || ch == '5' -> 29
            ch == '^' || ch == '6' -> 30
            ch == '_' || ch == '7' || ch == '/' -> 31
            ch == '8' -> 127
            else -> code
        }
    }

    override fun performEditorAction(actionCode: Int): Boolean {
        onEditorAction()
        return true
    }
}
