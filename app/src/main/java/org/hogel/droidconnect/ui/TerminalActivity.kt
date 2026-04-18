package org.hogel.droidconnect.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.util.TypedValue
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.hogel.droidconnect.R
import org.hogel.droidconnect.databinding.ActivityTerminalBinding
import org.hogel.droidconnect.ssh.SshConnectionService
import org.hogel.droidconnect.ssh.SshKeyManager
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalViewClient

/**
 * Terminal UI activity.
 *
 * The SSH session lives in [SshConnectionService] (a foreground service), not
 * here, so the connection survives backgrounding and process death of the UI.
 * This activity binds to the service to feed SSH stdout into the
 * TerminalEmulator and forward keyboard input to ssh stdin.
 */
class TerminalActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTerminalBinding

    private var service: SshConnectionService? = null
    private var bound = false

    private var pendingParams: SshConnectionService.ConnectionParams? = null

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ ->
        // The service is allowed to start even if the permission was denied;
        // the system simply suppresses the notification UI in that case.
        startAndBindService()
    }

    private val outputListener: (ByteArray) -> Unit = ::handleSshOutput

    private fun handleSshOutput(data: ByteArray) {
        val emulator = binding.terminalView.mEmulator ?: return
        emulator.append(data, data.size)
        binding.terminalView.invalidate()
    }

    private val statusListener = object : SshConnectionService.StatusListener {
        override fun onSshConnected() {
            service?.let { syncWindowSize(it) }
        }

        override fun onSshDisconnected(error: Throwable?) {
            val message = if (error != null) {
                "${getString(R.string.connection_failed)}: ${error.message}"
            } else {
                getString(R.string.disconnected)
            }
            Toast.makeText(this@TerminalActivity, message, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, ibinder: IBinder) {
            val svc = (ibinder as SshConnectionService.LocalBinder).getService()
            service = svc
            bound = true
            svc.attachOutputListener(outputListener)
            svc.addStatusListener(statusListener)
            val params = pendingParams
            when {
                svc.state == SshConnectionService.State.IDLE && params != null -> {
                    val emulator = binding.terminalView.mEmulator
                    val cols = emulator?.mColumns ?: DEFAULT_COLUMNS
                    val rows = emulator?.mRows ?: DEFAULT_ROWS
                    svc.connect(params, cols, rows)
                }
                svc.state == SshConnectionService.State.IDLE -> {
                    // Resumed from notification but the service is no longer connected.
                    Toast.makeText(this@TerminalActivity, R.string.disconnected, Toast.LENGTH_SHORT).show()
                    finish()
                }
                else -> syncWindowSize(svc)
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityTerminalBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val host = intent.getStringExtra(EXTRA_HOST)
        val username = intent.getStringExtra(EXTRA_USERNAME)
        val port = intent.getIntExtra(EXTRA_PORT, 22)
        if (host != null && username != null) {
            val privateKey = SshKeyManager(this).getPrivateKeyPem() ?: run {
                Toast.makeText(this, "No SSH key found", Toast.LENGTH_SHORT).show()
                return finish()
            }
            pendingParams = SshConnectionService.ConnectionParams(host, port, username, privateKey)
        }

        setupTerminalView()
        setupAuxKeyBar()

        binding.btnDisconnect.setOnClickListener {
            service?.shutdown()
            finish()
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startAndBindService()
        }
    }

    private fun startAndBindService() {
        val serviceIntent = Intent(this, SshConnectionService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun syncWindowSize(svc: SshConnectionService) {
        val emulator = binding.terminalView.mEmulator ?: return
        val cols = emulator.mColumns
        val rows = emulator.mRows
        if (cols > 0 && rows > 0) svc.resizeWindow(cols, rows)
    }

    private fun setupAuxKeyBar() {
        val bar = binding.auxKeyBar
        val keys: List<Pair<String, () -> Unit>> = listOf(
            "ESC" to { writeToSsh(byteArrayOf(0x1B)) },
            "TAB" to { writeToSsh(byteArrayOf(0x09)) },
            "^C" to { writeToSsh(byteArrayOf(0x03)) },
            "^D" to { writeToSsh(byteArrayOf(0x04)) },
            "^J" to { writeToSsh(byteArrayOf(0x0A)) },
            "←" to { sendKeyCode(KeyEvent.KEYCODE_DPAD_LEFT) },
            "↓" to { sendKeyCode(KeyEvent.KEYCODE_DPAD_DOWN) },
            "↑" to { sendKeyCode(KeyEvent.KEYCODE_DPAD_UP) },
            "→" to { sendKeyCode(KeyEvent.KEYCODE_DPAD_RIGHT) },
        )
        val marginPx = dpToPx(2)
        val minWidthPx = dpToPx(44)
        val minHeightPx = dpToPx(40)
        for ((label, action) in keys) {
            val button = Button(this).apply {
                text = label
                isAllCaps = false
                minWidth = minWidthPx
                minimumWidth = minWidthPx
                minHeight = minHeightPx
                minimumHeight = minHeightPx
                setPadding(dpToPx(8), 0, dpToPx(8), 0)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                isFocusable = false
                setOnClickListener {
                    action()
                    binding.terminalView.requestFocus()
                }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(marginPx, marginPx, marginPx, marginPx) }
            bar.addView(button, lp)
        }
    }

    private fun sendKeyCode(keyCode: Int) {
        val emu = binding.terminalView.mEmulator
        val cursorApp = emu?.isCursorKeysApplicationMode == true
        val keypadApp = emu?.isKeypadApplicationMode == true
        val code = KeyHandler.getCode(keyCode, 0, cursorApp, keypadApp) ?: return
        writeToSsh(code.toByteArray(Charsets.UTF_8))
    }

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density).toInt()

    private fun setupTerminalView() {
        val terminalView = binding.terminalView
        terminalView.setTextSize(14)
        terminalView.setTypeface(Typeface.MONOSPACE)

        // Create a dummy TerminalSession to initialize TerminalView.
        // "sleep 86400" keeps the process alive without producing output.
        // All actual I/O goes through SSH, not this dummy process.
        // Termux's TerminalSession uses `args` as the full argv (args[0] becomes
        // argv[0]). Android's /system/bin/sleep is a toybox multicall binary that
        // dispatches on argv[0], so we must pass "sleep" as argv[0].
        val dummySession = TerminalSession(
            "/system/bin/sleep", "/",
            arrayOf("sleep", "86400"),
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

    private fun writeToSsh(data: ByteArray) {
        service?.writeToSsh(data)
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
        if (bound) {
            service?.detachOutputListener()
            service?.removeStatusListener(statusListener)
            unbindService(serviceConnection)
            bound = false
            service = null
        }
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
        // Route TerminalView.onCreateInputConnection() into its
        // TYPE_CLASS_TEXT | TYPE_TEXT_VARIATION_NORMAL branch so the IME can
        // run composition (Japanese kana-to-kanji) and show word suggestions.
        override fun isTerminalViewSelected(): Boolean = false
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
        private const val DEFAULT_COLUMNS = 80
        private const val DEFAULT_ROWS = 24
        private const val TAG = "TerminalActivity"
    }
}
