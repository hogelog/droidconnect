package org.hogel.droidconnect.ui

import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.hogel.droidconnect.R
import org.hogel.droidconnect.databinding.ActivityTerminalBinding
import org.hogel.droidconnect.ssh.SshSession
import org.hogel.droidconnect.ssh.SshKeyManager
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalViewClient
import java.io.OutputStream
import kotlin.concurrent.thread

/**
 * Terminal UI activity.
 *
 * Architecture: Creates a dummy TerminalSession (with "sleep") to initialize
 * TerminalView and its TerminalEmulator. SSH I/O is bridged separately:
 * - SSH stdout → TerminalEmulator.append() (posted to main thread)
 * - User keyboard input → intercepted in onKeyDown/onCodePoint → SSH stdin
 */
class TerminalActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTerminalBinding
    private var sshSession: SshSession? = null
    private var sshOutputStream: OutputStream? = null
    @Volatile private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityTerminalBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val host = intent.getStringExtra(EXTRA_HOST) ?: return finish()
        val port = intent.getIntExtra(EXTRA_PORT, 22)
        val username = intent.getStringExtra(EXTRA_USERNAME) ?: return finish()

        val keyManager = SshKeyManager(this)
        val privateKey = keyManager.getPrivateKeyPem() ?: run {
            Toast.makeText(this, "No SSH key found", Toast.LENGTH_SHORT).show()
            return finish()
        }

        setupTerminalView()
        connectSsh(host, port, username, privateKey)
    }

    private fun setupTerminalView() {
        val terminalView = binding.terminalView
        terminalView.setTextSize(14)
        terminalView.setTypeface(Typeface.MONOSPACE)

        // Create a dummy TerminalSession to initialize TerminalView.
        // "sleep 86400" keeps the process alive without producing output.
        // All actual I/O goes through SSH, not this dummy process.
        val dummySession = TerminalSession(
            "/system/bin/sleep", "/",
            arrayOf("86400"),
            arrayOf("TERM=xterm-256color"),
            null,
            sessionClient
        )
        terminalView.attachSession(dummySession)
        terminalView.setTerminalViewClient(viewClient)

        // Termux's TerminalView does not set these flags itself, so requestFocus
        // silently fails and the IME has no view to deliver input to — keyboard
        // shows but typed characters go nowhere. Enable focus before requesting.
        terminalView.isFocusable = true
        terminalView.isFocusableInTouchMode = true
        terminalView.requestFocus()
        terminalView.post { showSoftKeyboard() }
    }

    private fun showSoftKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(binding.terminalView, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun toggleSoftKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.toggleSoftInput(InputMethodManager.SHOW_IMPLICIT, 0)
    }

    private fun connectSsh(host: String, port: Int, username: String, privateKey: CharArray) {
        running = true
        thread(name = "ssh-connect") {
            try {
                val ssh = SshSession(host, port, username, privateKey)
                ssh.connect()
                sshSession = ssh

                val emulator = binding.terminalView.mEmulator
                    ?: throw IllegalStateException("Emulator not initialized")
                val columns = emulator.mColumns
                val rows = emulator.mRows

                ssh.openShell(columns, rows)
                sshOutputStream = ssh.stdin

                val buffer = ByteArray(8192)
                val inputStream = ssh.stdout
                while (running) {
                    val bytesRead = inputStream.read(buffer)
                    if (bytesRead == -1) break
                    val data = buffer.copyOf(bytesRead)
                    binding.terminalView.post {
                        emulator.append(data, data.size)
                        binding.terminalView.invalidate()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "SSH error", e)
                runOnUiThread {
                    Toast.makeText(this, "${getString(R.string.connection_failed)}: ${e.message}", Toast.LENGTH_LONG).show()
                    finish()
                }
                return@thread
            }

            runOnUiThread {
                Toast.makeText(this, R.string.disconnected, Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun writeToSsh(data: ByteArray) {
        val out = sshOutputStream ?: return
        thread(name = "ssh-write") {
            try {
                out.write(data)
                out.flush()
            } catch (e: Exception) {
                Log.e(TAG, "SSH write error", e)
            }
        }
    }

    private fun writeCodePointToSsh(codePoint: Int, prependEscape: Boolean) {
        val buf = ByteArray(5)
        var pos = 0
        if (prependEscape) buf[pos++] = 27
        when {
            codePoint <= 0x7F -> buf[pos++] = codePoint.toByte()
            codePoint <= 0x7FF -> {
                buf[pos++] = (0xC0 or (codePoint shr 6)).toByte()
                buf[pos++] = (0x80 or (codePoint and 0x3F)).toByte()
            }
            codePoint <= 0xFFFF -> {
                buf[pos++] = (0xE0 or (codePoint shr 12)).toByte()
                buf[pos++] = (0x80 or ((codePoint shr 6) and 0x3F)).toByte()
                buf[pos++] = (0x80 or (codePoint and 0x3F)).toByte()
            }
            else -> {
                buf[pos++] = (0xF0 or (codePoint shr 18)).toByte()
                buf[pos++] = (0x80 or ((codePoint shr 12) and 0x3F)).toByte()
                buf[pos++] = (0x80 or ((codePoint shr 6) and 0x3F)).toByte()
                buf[pos++] = (0x80 or (codePoint and 0x3F)).toByte()
            }
        }
        writeToSsh(buf.copyOf(pos))
    }

    /** Apply Ctrl key mapping to a code point (a→1, b→2, etc.) */
    private fun applyCtrl(codePoint: Int): Int = when {
        codePoint in 'a'.code..'z'.code -> codePoint - 'a'.code + 1
        codePoint in 'A'.code..'Z'.code -> codePoint - 'A'.code + 1
        codePoint == ' '.code || codePoint == '2'.code -> 0
        codePoint == '['.code || codePoint == '3'.code -> 27
        codePoint == '\\'.code || codePoint == '4'.code -> 28
        codePoint == ']'.code || codePoint == '5'.code -> 29
        codePoint == '^'.code || codePoint == '6'.code -> 30
        codePoint == '_'.code || codePoint == '7'.code || codePoint == '/'.code -> 31
        codePoint == '8'.code -> 127
        else -> codePoint
    }

    /** Fix Bluetooth keyboard Unicode quirks. */
    private fun fixBluetoothCodePoint(codePoint: Int): Int = when (codePoint) {
        0x02DC -> 0x007E // SMALL TILDE → TILDE
        0x02CB -> 0x0060 // MODIFIER GRAVE → GRAVE
        0x02C6 -> 0x005E // MODIFIER CIRCUMFLEX → CIRCUMFLEX
        else -> codePoint
    }

    override fun onDestroy() {
        running = false
        sshSession?.disconnect()
        binding.terminalView.mTermSession?.finishIfRunning()
        super.onDestroy()
    }

    // --- TerminalSessionClient ---
    private val sessionClient = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) {
            binding.terminalView.invalidate()
        }
        override fun onTitleChanged(changedSession: TerminalSession) {}
        override fun onSessionFinished(finishedSession: TerminalSession) {}
        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {}
        override fun onPasteTextFromClipboard(session: TerminalSession?) {}
        override fun onBell(session: TerminalSession) {}
        override fun onColorsChanged(session: TerminalSession) {}
        override fun onTerminalCursorStateChange(state: Boolean) {}
        override fun getTerminalCursorStyle(): Int = TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK
        override fun logError(tag: String?, message: String?) { Log.e(tag ?: TAG, message ?: "") }
        override fun logWarn(tag: String?, message: String?) { Log.w(tag ?: TAG, message ?: "") }
        override fun logInfo(tag: String?, message: String?) { Log.i(tag ?: TAG, message ?: "") }
        override fun logDebug(tag: String?, message: String?) { Log.d(tag ?: TAG, message ?: "") }
        override fun logVerbose(tag: String?, message: String?) { Log.v(tag ?: TAG, message ?: "") }
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
            Log.e(tag ?: TAG, message, e)
        }
        override fun logStackTrace(tag: String?, e: Exception?) {
            Log.e(tag ?: TAG, "Stack trace", e)
        }
    }

    // --- TerminalViewClient ---
    // Key input is intercepted here and sent to SSH instead of the dummy TerminalSession.
    private val viewClient = object : TerminalViewClient {
        override fun onScale(scale: Float): Float = 1.0f
        override fun onSingleTapUp(e: MotionEvent?) {
            // Tapping the terminal toggles the soft keyboard so it can be
            // re-summoned after the user dismisses it with the back gesture.
            binding.terminalView.requestFocus()
            toggleSoftKeyboard()
        }
        override fun shouldBackButtonBeMappedToEscape(): Boolean = true
        override fun shouldEnforceCharBasedInput(): Boolean = true
        override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
        override fun isTerminalViewSelected(): Boolean = true
        override fun copyModeChanged(copyMode: Boolean) {}
        override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean = false
        override fun onLongPress(event: MotionEvent?): Boolean = false
        override fun readControlKey(): Boolean = false
        override fun readAltKey(): Boolean = false
        override fun readShiftKey(): Boolean = false
        override fun readFnKey(): Boolean = false
        override fun onEmulatorSet() {}

        override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean {
            val event = e ?: return false
            val emu = binding.terminalView.mEmulator ?: return false

            // Multi-character input (e.g., IME batch)
            if (event.action == KeyEvent.ACTION_MULTIPLE && keyCode == KeyEvent.KEYCODE_UNKNOWN) {
                event.characters?.let { writeToSsh(it.toByteArray(Charsets.UTF_8)) }
                return true
            }

            // Let system keys through (except back → escape)
            if (event.isSystem && keyCode != KeyEvent.KEYCODE_BACK) {
                return false
            }

            val metaState = event.metaState
            val controlDown = event.isCtrlPressed
            val leftAltDown = (metaState and KeyEvent.META_ALT_LEFT_ON) != 0
            val shiftDown = event.isShiftPressed

            var keyMod = 0
            if (controlDown) keyMod = keyMod or KeyHandler.KEYMOD_CTRL
            if (event.isAltPressed || leftAltDown) keyMod = keyMod or KeyHandler.KEYMOD_ALT
            if (shiftDown) keyMod = keyMod or KeyHandler.KEYMOD_SHIFT
            if (event.isNumLockOn) keyMod = keyMod or KeyHandler.KEYMOD_NUM_LOCK

            if (!event.isFunctionPressed) {
                val code = KeyHandler.getCode(
                    keyCode, keyMod,
                    emu.isCursorKeysApplicationMode,
                    emu.isKeypadApplicationMode
                )
                if (code != null) {
                    writeToSsh(code.toByteArray(Charsets.UTF_8))
                    return true
                }
            }

            val rightAltDown = (metaState and KeyEvent.META_ALT_RIGHT_ON) != 0
            var bitsToClear = KeyEvent.META_CTRL_MASK
            if (!rightAltDown) {
                bitsToClear = bitsToClear or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
            }
            var effectiveMetaState = metaState and bitsToClear.inv()
            if (shiftDown) effectiveMetaState = effectiveMetaState or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON

            val result = event.getUnicodeChar(effectiveMetaState)
            if (result == 0) return false

            // Skip combining accents for now
            if ((result and KeyCharacterMap.COMBINING_ACCENT) != 0) return true

            var codePoint = fixBluetoothCodePoint(result)
            if (controlDown) codePoint = applyCtrl(codePoint)

            writeCodePointToSsh(codePoint, leftAltDown)
            return true
        }

        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean {
            // All key input is handled in onKeyDown. This is a fallback for any edge cases.
            var cp = fixBluetoothCodePoint(codePoint)
            if (ctrlDown) cp = applyCtrl(cp)
            writeCodePointToSsh(cp, false)
            return true
        }

        override fun logError(tag: String?, message: String?) { Log.e(tag ?: TAG, message ?: "") }
        override fun logWarn(tag: String?, message: String?) { Log.w(tag ?: TAG, message ?: "") }
        override fun logInfo(tag: String?, message: String?) { Log.i(tag ?: TAG, message ?: "") }
        override fun logDebug(tag: String?, message: String?) { Log.d(tag ?: TAG, message ?: "") }
        override fun logVerbose(tag: String?, message: String?) { Log.v(tag ?: TAG, message ?: "") }
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
            Log.e(tag ?: TAG, message, e)
        }
        override fun logStackTrace(tag: String?, e: Exception?) {
            Log.e(tag ?: TAG, "Stack trace", e)
        }
    }

    companion object {
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_USERNAME = "username"
        private const val TAG = "TerminalActivity"
    }
}
