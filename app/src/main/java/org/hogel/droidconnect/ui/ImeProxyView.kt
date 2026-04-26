package org.hogel.droidconnect.ui

import android.content.Context
import android.text.InputType
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

/**
 * Transparent focusable View that owns the IME connection on the user's behalf.
 *
 * Termux's [com.termux.view.TerminalView] is `final` and its built-in
 * [BaseInputConnection] discards composing text without rendering it, so
 * Japanese kana-to-kanji preedit is invisible. Putting this view at the same
 * focus slot as the terminal lets us intercept [setComposingText] /
 * [commitText] / [finishComposingText] and surface the composing string to a
 * preedit overlay while still routing committed text and hardware key events
 * through the existing terminal pipeline.
 */
class ImeProxyView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Called whenever the IME's composing text changes (empty when cleared). */
    var onComposingTextChanged: (CharSequence) -> Unit = {}

    /** Called when the IME commits text (kana-to-kanji confirmation, etc.). */
    var onCommitText: (CharSequence) -> Unit = {}

    /**
     * Called when a hardware key (or a key event synthesised by the IME via
     * [InputConnection.sendKeyEvent], e.g. Enter on the soft keyboard) reaches
     * this view. Return true to mark it consumed.
     */
    var onHardwareKey: (Int, KeyEvent) -> Boolean = { _, _ -> false }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        // Background must be drawable for focus on some devices but we want it
        // visually invisible — a fully transparent colour is sufficient.
        setBackgroundColor(0x00000000)
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // TYPE_CLASS_TEXT lets the IME run composition (Japanese kana →
        // kanji), word suggestions, etc. NO_FULLSCREEN keeps the keyboard
        // docked instead of taking over the screen in landscape.
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        return ProxyInputConnection(this, true)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (onHardwareKey(keyCode, event)) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        // Most terminal-relevant work happens on key down; consume up events
        // for keys we already handled to avoid IME defaults reacting to them.
        return false
    }

    private inner class ProxyInputConnection(
        target: View,
        fullEditor: Boolean,
    ) : BaseInputConnection(target, fullEditor) {

        override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
            // Mirror to overlay first so the user sees the preedit immediately;
            // the editable bookkeeping in super is what lets the IME continue
            // editing the same composing region on the next call.
            onComposingTextChanged(text)
            return super.setComposingText(text, newCursorPosition)
        }

        override fun setComposingRegion(start: Int, end: Int): Boolean {
            // Defer to super; we don't reflect partial-region edits in the
            // overlay because the next setComposingText call (which IMEs
            // always issue right after) gives us the full new string.
            return super.setComposingRegion(start, end)
        }

        override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
            // Hand off to SSH instead of letting the editable accumulate; the
            // terminal screen is the source of truth for what the user sees.
            onCommitText(text)
            onComposingTextChanged("")
            editable?.clear()
            return true
        }

        override fun finishComposingText(): Boolean {
            // Flush whatever is still composing as if the user confirmed it,
            // then drop the overlay. Mirrors how Termux's BaseInputConnection
            // wrapper sends pending text on focus loss.
            val current = editable
            val pending = current?.toString().orEmpty()
            if (pending.isNotEmpty()) {
                onCommitText(pending)
            }
            onComposingTextChanged("")
            current?.clear()
            return super.finishComposingText()
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            // Some IMEs ask the editor to delete characters before the cursor
            // (e.g. Samsung auto-correct). Translate that into BS keystrokes
            // so the remote shell sees the same edit it would for hardware
            // backspace.
            val deleteEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL)
            repeat(beforeLength) { onHardwareKey(KeyEvent.KEYCODE_DEL, deleteEvent) }
            return super.deleteSurroundingText(beforeLength, afterLength)
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            // The soft keyboard's Enter / Backspace arrive here as synthetic
            // key events. Route them through the same hardware-key handler so
            // they reach the existing TerminalActivity logic.
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (onHardwareKey(event.keyCode, event)) return true
            }
            return super.sendKeyEvent(event)
        }
    }
}
