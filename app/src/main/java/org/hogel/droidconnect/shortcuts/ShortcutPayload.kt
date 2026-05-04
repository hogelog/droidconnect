package org.hogel.droidconnect.shortcuts

import android.view.KeyEvent

/**
 * One step produced by parsing a shortcut payload.
 *
 * - [SendBytes]: write the bytes verbatim to the SSH stdin.
 * - [SendKey]: dispatch a hardware-style key event so the caller can route it
 *   through Termux's [com.termux.terminal.KeyHandler], which honours the
 *   emulator's cursor-keys application mode (DECCKM). This matters for the
 *   arrow keys, which switch between `ESC [ A` and `ESC O A` depending on
 *   whether the remote app put the terminal in application cursor mode.
 */
sealed class ShortcutAction {
    data class SendBytes(val bytes: ByteArray) : ShortcutAction() {
        override fun equals(other: Any?): Boolean =
            other is SendBytes && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = bytes.contentHashCode()
    }

    data class SendKey(val keyCode: Int, val keyMod: Int = 0) : ShortcutAction()
}

/**
 * Parse a shortcut payload string into an ordered list of [ShortcutAction]s.
 *
 * Recognised escapes (anywhere in the string):
 *   `\e`             ESC (0x1B)
 *   `\t` `\r` `\n`   tab / CR / LF bytes
 *   `\\`             literal backslash
 *   `\xNN`           one byte expressed in hex (NN = two hex digits)
 *   `^A`–`^Z`        Ctrl-A..Ctrl-Z (0x01..0x1A)
 *   `^[` `^\` `^]` `^^` `^_`  the corresponding C0 control codes
 *
 * Recognised key tokens (route through KeyHandler so DECCKM/keypad modes are
 * honoured):
 *   `{UP}` `{DOWN}` `{LEFT}` `{RIGHT}` `{TAB}` `{S-TAB}`
 *
 * Anything else is sent verbatim as UTF-8.
 *
 * Unknown `\X` and `{...}` tokens fall through and are emitted as the literal
 * source text so a user typo never silently swallows characters.
 */
fun parseShortcutActions(payload: String): List<ShortcutAction> {
    val actions = mutableListOf<ShortcutAction>()
    val literal = StringBuilder()

    fun flushLiteral() {
        if (literal.isNotEmpty()) {
            actions += ShortcutAction.SendBytes(literal.toString().toByteArray(Charsets.UTF_8))
            literal.clear()
        }
    }

    fun pushBytes(vararg b: Int) {
        flushLiteral()
        actions += ShortcutAction.SendBytes(ByteArray(b.size) { i -> b[i].toByte() })
    }

    var i = 0
    while (i < payload.length) {
        val c = payload[i]
        when {
            c == '\\' && i + 1 < payload.length -> {
                when (val next = payload[i + 1]) {
                    'e', 'E' -> { pushBytes(0x1B); i += 2 }
                    't' -> { pushBytes(0x09); i += 2 }
                    'r' -> { pushBytes(0x0D); i += 2 }
                    'n' -> { pushBytes(0x0A); i += 2 }
                    '\\' -> { literal.append('\\'); i += 2 }
                    'x', 'X' -> {
                        val hex = payload.substring(i + 2, (i + 4).coerceAtMost(payload.length))
                        val byte = if (hex.length == 2) hex.toIntOrNull(16) else null
                        if (byte != null) {
                            pushBytes(byte and 0xFF)
                            i += 4
                        } else {
                            literal.append(c); literal.append(next); i += 2
                        }
                    }
                    else -> { literal.append(c); literal.append(next); i += 2 }
                }
            }
            c == '^' && i + 1 < payload.length -> {
                val ctrl = ctrlByteFor(payload[i + 1])
                if (ctrl != null) {
                    pushBytes(ctrl); i += 2
                } else {
                    literal.append(c); i += 1
                }
            }
            c == '{' -> {
                val end = payload.indexOf('}', i + 1)
                val token = if (end > i) payload.substring(i + 1, end) else null
                val action = token?.let { keyTokenAction(it) }
                if (action != null) {
                    flushLiteral()
                    actions += action
                    i = end + 1
                } else {
                    literal.append(c); i += 1
                }
            }
            else -> { literal.append(c); i += 1 }
        }
    }
    flushLiteral()
    return actions
}

private fun ctrlByteFor(ch: Char): Int? = when (ch) {
    in 'a'..'z' -> ch.code - 'a'.code + 1
    in 'A'..'Z' -> ch.code - 'A'.code + 1
    '[' -> 0x1B
    '\\' -> 0x1C
    ']' -> 0x1D
    '^' -> 0x1E
    '_' -> 0x1F
    '?' -> 0x7F
    else -> null
}

private fun keyTokenAction(token: String): ShortcutAction? = when (token.uppercase()) {
    "UP" -> ShortcutAction.SendKey(KeyEvent.KEYCODE_DPAD_UP)
    "DOWN" -> ShortcutAction.SendKey(KeyEvent.KEYCODE_DPAD_DOWN)
    "LEFT" -> ShortcutAction.SendKey(KeyEvent.KEYCODE_DPAD_LEFT)
    "RIGHT" -> ShortcutAction.SendKey(KeyEvent.KEYCODE_DPAD_RIGHT)
    "TAB" -> ShortcutAction.SendKey(KeyEvent.KEYCODE_TAB)
    // Back-tab. KeyHandler emits CSI Z when KEYCODE_TAB is paired with SHIFT.
    "S-TAB", "SHIFT-TAB" -> ShortcutAction.SendKey(
        KeyEvent.KEYCODE_TAB,
        com.termux.terminal.KeyHandler.KEYMOD_SHIFT,
    )
    else -> null
}
