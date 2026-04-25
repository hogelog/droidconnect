package org.hogel.droidconnect.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
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
import android.view.GestureDetector
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
import androidx.core.content.edit
import org.hogel.droidconnect.R
import org.hogel.droidconnect.databinding.ActivityTerminalBinding
import org.hogel.droidconnect.ssh.SshConnectionService
import org.hogel.droidconnect.ssh.SshKeyManager
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalViewClient
import kotlin.math.abs

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

    // Sticky modifier state: applies to the next single key input, then resets.
    private var stickyShift = false
    private var stickyCtrl = false
    private var shiftButton: Button? = null
    private var ctrlButton: Button? = null

    private var fontSizePx = DEFAULT_FONT_SIZE_PX
    private val terminalPrefs by lazy { getSharedPreferences(PREFS_TERMINAL, Context.MODE_PRIVATE) }

    // Vertical-drag accumulator for setupTerminalScrollRouting.
    private var scrollRemainderPx = 0f
    // True between onDown and ACTION_UP/CANCEL whenever we have claimed a
    // vertical drag. Used to swallow subsequent events from `TerminalView` so
    // its own `doScroll` doesn't emit a duplicate mouse/arrow sequence to the
    // dummy pty (which the kernel then echoes back into the screen buffer).
    private var handlingScrollGesture = false
    // Set by the detector's onSingleTapUp when the gesture resolved as a tap
    // (not a scroll / long-press). Consulted at ACTION_UP to decide whether to
    // swallow the up-event and run our own keyboard toggle.
    private var tappedThisGesture = false

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
        val command = intent.getStringExtra(EXTRA_POST_CONNECT_COMMAND)?.takeIf { it.isNotBlank() }
        val useTmux = intent.getBooleanExtra(EXTRA_USE_TMUX, false)
        if (host != null && username != null) {
            val privateKey = SshKeyManager(this).getPrivateKeyPem() ?: run {
                Toast.makeText(this, "No SSH key found", Toast.LENGTH_SHORT).show()
                return finish()
            }
            pendingParams = SshConnectionService.ConnectionParams(
                host, port, username, privateKey, command, useTmux,
            )
        }

        setupTerminalView()
        setupTerminalScrollRouting()
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

        // Sends a raw byte sequence, clearing sticky modifiers (they don't
        // meaningfully combine with preset ^X shortcuts or ESC).
        fun sendRaw(bytes: ByteArray): () -> Unit = {
            writeToSsh(bytes)
            clearStickyModifiers()
        }

        val keys: List<Pair<String, () -> Unit>> = listOf(
            "ESC" to sendRaw(byteArrayOf(0x1B)),
            "TAB" to { sendKeyCode(KeyEvent.KEYCODE_TAB) },
            "^C" to sendRaw(byteArrayOf(0x03)),
            "^D" to sendRaw(byteArrayOf(0x04)),
            "^J" to sendRaw(byteArrayOf(0x0A)),
            "^R" to sendRaw(byteArrayOf(0x12)),
            "←" to { sendKeyCode(KeyEvent.KEYCODE_DPAD_LEFT) },
            "↓" to { sendKeyCode(KeyEvent.KEYCODE_DPAD_DOWN) },
            "↑" to { sendKeyCode(KeyEvent.KEYCODE_DPAD_UP) },
            "→" to { sendKeyCode(KeyEvent.KEYCODE_DPAD_RIGHT) },
        )
        for ((label, action) in keys) {
            bar.addView(makeAuxButton(label, action), auxButtonLayoutParams())
        }

        shiftButton = makeAuxButton("Shift") { setShiftSticky(!stickyShift) }
            .also { styleModifierButton(it); bar.addView(it, auxButtonLayoutParams()) }
        ctrlButton = makeAuxButton("Ctrl") { setCtrlSticky(!stickyCtrl) }
            .also { styleModifierButton(it); bar.addView(it, auxButtonLayoutParams()) }
    }

    private fun auxButtonLayoutParams(): LinearLayout.LayoutParams {
        val marginPx = dpToPx(2)
        return LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { setMargins(marginPx, marginPx, marginPx, marginPx) }
    }

    private fun makeAuxButton(label: String, action: () -> Unit): Button = Button(this).apply {
        val minWidthPx = dpToPx(44)
        val minHeightPx = dpToPx(40)
        text = label
        isAllCaps = false
        minWidth = minWidthPx
        minimumWidth = minWidthPx
        minHeight = minHeightPx
        minimumHeight = minHeightPx
        setPadding(dpToPx(8), 0, dpToPx(8), 0)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        isFocusable = false
        setOnClickListener {
            action()
            binding.terminalView.requestFocus()
        }
    }

    /**
     * Rebuild the context shortcut bar above the static aux key bar based on
     * the active tmux pane's foreground command (delivered as the OSC window
     * title once `tmux set -g set-titles on` is in effect; configured by
     * [SshSession]). If [app] doesn't match a known shortcut set the bar is
     * hidden so it doesn't take up screen space.
     */
    private fun applyAppContext(app: String?) {
        val normalized = app?.trim()?.lowercase()
        val shortcuts = contextShortcutsFor(normalized)
        val bar = binding.contextKeyBar
        bar.removeAllViews()
        if (shortcuts.isEmpty()) {
            binding.contextKeyScroll.visibility = android.view.View.GONE
            return
        }
        for ((label, action) in shortcuts) {
            bar.addView(makeAuxButton(label, action), auxButtonLayoutParams())
        }
        binding.contextKeyScroll.visibility = android.view.View.VISIBLE
    }

    private fun contextShortcutsFor(app: String?): List<Pair<String, () -> Unit>> {
        // Sends a literal UTF-8 string to the remote. Used for command stubs
        // the user can finish typing (no trailing newline).
        fun sendText(text: String): () -> Unit = {
            writeToSsh(text.toByteArray(Charsets.UTF_8))
            clearStickyModifiers()
        }
        // Sends raw bytes verbatim. Used for sending control sequences (e.g.,
        // /clear + Enter, or CSI Z for back-tab).
        fun sendBytes(bytes: ByteArray): () -> Unit = {
            writeToSsh(bytes)
            clearStickyModifiers()
        }
        return when (app) {
            // Claude Code starts a Node process; pane_current_command is most
            // commonly "node". "claude" / "claude-code" cover wrapper scripts.
            "claude", "claude-code", "node" -> listOf(
                "/clear" to sendBytes("/clear\r".toByteArray(Charsets.UTF_8)),
                "⇧Tab" to sendBytes(byteArrayOf(0x1B, '['.code.toByte(), 'Z'.code.toByte())),
            )
            "bash", "zsh", "sh", "fish" -> listOf(
                "claude-code --enable-auto-mode" to sendText("claude-code --enable-auto-mode"),
                "cd " to sendText("cd "),
            )
            else -> emptyList()
        }
    }

    private fun styleModifierButton(button: Button) {
        button.background = ContextCompat.getDrawable(this, R.drawable.bg_aux_modifier)
        ContextCompat.getColorStateList(this, R.color.aux_modifier_text)?.let {
            button.setTextColor(it)
        }
    }

    private fun setShiftSticky(on: Boolean) {
        stickyShift = on
        shiftButton?.isActivated = on
    }

    private fun setCtrlSticky(on: Boolean) {
        stickyCtrl = on
        ctrlButton?.isActivated = on
    }

    private fun clearStickyModifiers() {
        if (stickyShift) setShiftSticky(false)
        if (stickyCtrl) setCtrlSticky(false)
    }

    private fun sendKeyCode(keyCode: Int) {
        val emu = binding.terminalView.mEmulator
        val cursorApp = emu?.isCursorKeysApplicationMode == true
        val keypadApp = emu?.isKeypadApplicationMode == true
        var keyMod = 0
        if (stickyShift) keyMod = keyMod or KeyHandler.KEYMOD_SHIFT
        if (stickyCtrl) keyMod = keyMod or KeyHandler.KEYMOD_CTRL
        val code = KeyHandler.getCode(keyCode, keyMod, cursorApp, keypadApp) ?: return
        writeToSsh(code.toByteArray(Charsets.UTF_8))
        clearStickyModifiers()
    }

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density).toInt()

    private fun changeFontSize(increase: Boolean) {
        val newSize = (fontSizePx + if (increase) FONT_SIZE_STEP_PX else -FONT_SIZE_STEP_PX)
            .coerceIn(MIN_FONT_SIZE_PX, MAX_FONT_SIZE_PX)
        if (newSize == fontSizePx) return
        fontSizePx = newSize
        binding.terminalView.setTextSize(newSize)
        terminalPrefs.edit { putInt(KEY_FONT_SIZE_PX, newSize) }
        service?.let { syncWindowSize(it) }
    }

    private fun setupTerminalView() {
        val terminalView = binding.terminalView
        fontSizePx = terminalPrefs.getInt(KEY_FONT_SIZE_PX, DEFAULT_FONT_SIZE_PX)
            .coerceIn(MIN_FONT_SIZE_PX, MAX_FONT_SIZE_PX)
        terminalView.setTextSize(fontSizePx)
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

    /**
     * Route vertical swipes into bytes on the SSH stdin so scrolling works
     * inside tmux / vim / less. Without this the finger drag still feeds
     * `TerminalView.doScroll`, but every byte it emits is written to our dummy
     * "sleep 86400" [TerminalSession] and never reaches the remote shell — and
     * because that session's pty has echo on by default, bytes it writes come
     * straight back out as visible text in the screen buffer.
     *
     * Three cases, mirroring Termux's own `doScroll` dispatch:
     *
     * 1. Mouse tracking active (e.g. tmux `set -g mouse on`): emit an xterm
     *    classic mouse-wheel sequence (`\e[M<b+32><x+32><y+32>`) per
     *    [SCROLL_LINES_PER_WHEEL] rows of drag. We use the classic format
     *    instead of SGR because plain `set -g mouse on` only advertises
     *    DECSET 1000/1002 — SGR (1006) is opt-in, and if tmux is not in SGR
     *    mode it treats the `\e[<...M` bytes as literal text.
     * 2. Alt buffer active but mouse tracking off (tmux with mouse off, vim,
     *    less): emit `DPAD_UP` / `DPAD_DOWN` key codes — what Termux does
     *    natively for the same case.
     * 3. Otherwise: do nothing and let `TerminalView`'s native `mTopRow`
     *    scrollback path handle the gesture.
     *
     * Once we've claimed a vertical drag (case 1 or 2) the `OnTouchListener`
     * swallows the remaining events so `TerminalView` does not also run
     * `doScroll` into the dummy pty (which would echo the emulator-formatted
     * mouse bytes back to the screen).
     *
     * Taps are treated the same way while mouse tracking is active:
     * `TerminalView.onUp` would emit `MOUSE_LEFT_BUTTON` press/release via
     * `emulator.sendMouseEvent`, again echoing through the dummy pty as
     * `^[[<0;x;yM^[[<0;x;ym…`. We swallow the tap-up, then do
     * `requestFocus()` + `toggleSoftKeyboard()` ourselves so the user's
     * existing tap-to-toggle-keyboard behavior is preserved.
     *
     * For pinches and for taps in plain-shell mode we leave events untouched.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupTerminalScrollRouting() {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                scrollRemainderPx = 0f
                handlingScrollGesture = false
                tappedThisGesture = false
                return false
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                tappedThisGesture = true
                return false
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float,
            ): Boolean {
                // Ignore pinch gestures — font-size zoom is driven by onScale.
                if (e2.pointerCount > 1) return false
                val emu = binding.terminalView.mEmulator ?: return false
                val rows = emu.mRows
                if (rows <= 0) return false
                // Leave the plain-shell scrollback path to `TerminalView`.
                if (!emu.isMouseTrackingActive && !emu.isAlternateBufferActive) return false

                val lineHeight = binding.terminalView.height.toFloat() / rows
                if (lineHeight <= 0f) return false
                val step = lineHeight * SCROLL_LINES_PER_WHEEL
                // From this point on we own the gesture even if we end up
                // emitting zero bytes this frame — otherwise TerminalView would
                // pick up the leftover motion and scroll the dummy pty.
                handlingScrollGesture = true
                val total = distanceY + scrollRemainderPx
                val deltaWheels = (total / step).toInt()
                scrollRemainderPx = total - deltaWheels * step
                if (deltaWheels == 0) return true

                val up = deltaWheels < 0
                val repeats = abs(deltaWheels)
                when {
                    emu.isMouseTrackingActive -> {
                        val cols = emu.mColumns
                        val charWidth = if (cols > 0) binding.terminalView.width.toFloat() / cols else 0f
                        val col = (if (charWidth > 0f) (e2.x / charWidth).toInt() + 1 else 1)
                            .coerceIn(1, MOUSE_CLASSIC_COORD_MAX.coerceAtMost(cols.coerceAtLeast(1)))
                        val row = ((e2.y / lineHeight).toInt() + 1)
                            .coerceIn(1, MOUSE_CLASSIC_COORD_MAX.coerceAtMost(rows))
                        val button = if (up) WHEEL_UP_BUTTON else WHEEL_DOWN_BUTTON
                        val bytes = byteArrayOf(
                            0x1B, '['.code.toByte(), 'M'.code.toByte(),
                            (32 + button).toByte(),
                            (32 + col).toByte(),
                            (32 + row).toByte(),
                        )
                        repeat(repeats) { writeToSsh(bytes) }
                    }
                    emu.isAlternateBufferActive -> {
                        val keyCode = if (up) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN
                        val code = KeyHandler.getCode(
                            keyCode, 0,
                            emu.isCursorKeysApplicationMode,
                            emu.isKeypadApplicationMode,
                        )
                        if (code != null) {
                            val bytes = code.toByteArray(Charsets.UTF_8)
                            repeat(repeats) { writeToSsh(bytes) }
                        }
                    }
                }
                return true
            }
        })
        binding.terminalView.setOnTouchListener { _, event ->
            detector.onTouchEvent(event)
            val inMouseTracking = binding.terminalView.mEmulator?.isMouseTrackingActive == true
            val tapInMouseTracking = tappedThisGesture && inMouseTracking
            val consume = handlingScrollGesture || tapInMouseTracking
            when (event.action) {
                MotionEvent.ACTION_UP -> {
                    if (consume) {
                        // Replay the UP to TerminalView as a CANCEL so its
                        // gesture detector resets (clears the long-press timer)
                        // instead of being left hanging with a half-seen
                        // gesture. CANCEL also bypasses the onUp path that
                        // would otherwise emit MOUSE_LEFT_BUTTON press/release.
                        val cancel = MotionEvent.obtain(event).apply {
                            action = MotionEvent.ACTION_CANCEL
                        }
                        binding.terminalView.onTouchEvent(cancel)
                        cancel.recycle()
                    }
                    if (tapInMouseTracking) {
                        binding.terminalView.requestFocus()
                        toggleSoftKeyboard()
                    }
                    handlingScrollGesture = false
                    tappedThisGesture = false
                }
                MotionEvent.ACTION_CANCEL -> {
                    handlingScrollGesture = false
                    tappedThisGesture = false
                }
            }
            consume
        }
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

    private fun copySelectedTextToClipboard(text: String?) {
        if (text.isNullOrEmpty()) return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("droidconnect", text))
    }

    // Bytes go to SSH stdin, not the dummy TerminalSession, so we can't
    // delegate to TerminalEmulator.paste() (it writes via mSession.write).
    // Sanitize and bracket-wrap the same way it does.
    private fun pasteClipboardToSsh() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip ?: return
        if (clip.itemCount == 0) return
        val raw = clip.getItemAt(0).coerceToText(this).toString()
        if (raw.isEmpty()) return

        val sanitized = raw
            .replace(Regex("[\u001B\u0080-\u009F]"), "")
            .replace(Regex("\r?\n"), "\r")
        if (sanitized.isEmpty()) return

        val bracketed = isBracketedPasteActive()
        if (bracketed) writeToSsh(BRACKETED_PASTE_START)
        writeToSsh(sanitized.toByteArray(Charsets.UTF_8))
        if (bracketed) writeToSsh(BRACKETED_PASTE_END)
    }

    // TerminalEmulator has no public getter for DECSET 2004; reach in via
    // reflection. Submodule is pinned, so the private name is stable.
    private fun isBracketedPasteActive(): Boolean {
        val emulator = binding.terminalView.mEmulator ?: return false
        return try {
            val method = TerminalEmulator::class.java
                .getDeclaredMethod("isDecsetInternalBitSet", Int::class.javaPrimitiveType)
            method.isAccessible = true
            method.invoke(emulator, DECSET_BIT_BRACKETED_PASTE_MODE) as? Boolean == true
        } catch (_: ReflectiveOperationException) {
            false
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
        override fun onTitleChanged(changedSession: TerminalSession) {
            applyAppContext(changedSession.title)
        }
        override fun onSessionFinished(finishedSession: TerminalSession) {}
        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
            copySelectedTextToClipboard(text)
        }
        override fun onPasteTextFromClipboard(session: TerminalSession?) {
            pasteClipboardToSsh()
        }
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
        // Return value becomes TerminalView.mScaleFactor: return 1.0f to reset
        // the accumulator after stepping, or the current scale to keep
        // accumulating until the next threshold.
        override fun onScale(scale: Float): Float {
            if (scale < 0.9f || scale > 1.1f) {
                changeFontSize(scale > 1.0f)
                return 1.0f
            }
            return scale
        }
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
        // Sticky modifiers are consumed when read by the soft keyboard text path
        // (TerminalView.sendTextToTerminal / inputCodePoint). Hardware key events
        // go through our onKeyDown below, which clears sticky state explicitly.
        override fun readControlKey(): Boolean {
            val v = stickyCtrl
            if (v) setCtrlSticky(false)
            return v
        }
        override fun readAltKey(): Boolean = false
        override fun readShiftKey(): Boolean {
            val v = stickyShift
            if (v) setShiftSticky(false)
            return v
        }
        override fun readFnKey(): Boolean = false
        override fun onEmulatorSet() {}

        override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean {
            val event = e ?: return false
            val emu = binding.terminalView.mEmulator ?: return false

            // Multi-character input (e.g., IME batch)
            if (event.action == KeyEvent.ACTION_MULTIPLE && keyCode == KeyEvent.KEYCODE_UNKNOWN) {
                event.characters?.let { writeToSsh(it.toByteArray(Charsets.UTF_8)) }
                clearStickyModifiers()
                return true
            }

            // Let system keys through (except back → escape)
            if (event.isSystem && keyCode != KeyEvent.KEYCODE_BACK) {
                return false
            }

            val metaState = event.metaState
            val controlDown = event.isCtrlPressed || stickyCtrl
            val leftAltDown = (metaState and KeyEvent.META_ALT_LEFT_ON) != 0
            val shiftDown = event.isShiftPressed || stickyShift

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
                    clearStickyModifiers()
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
            if ((result and KeyCharacterMap.COMBINING_ACCENT) != 0) {
                clearStickyModifiers()
                return true
            }

            var codePoint = fixBluetoothCodePoint(result)
            if (controlDown) codePoint = applyCtrl(codePoint)

            writeCodePointToSsh(codePoint, leftAltDown)
            clearStickyModifiers()
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
        const val EXTRA_POST_CONNECT_COMMAND = "post_connect_command"
        const val EXTRA_USE_TMUX = "use_tmux"
        private const val DEFAULT_COLUMNS = 80
        private const val DEFAULT_ROWS = 24
        private const val DEFAULT_FONT_SIZE_PX = 16
        private const val MIN_FONT_SIZE_PX = 8
        private const val MAX_FONT_SIZE_PX = 80
        private const val FONT_SIZE_STEP_PX = 2
        private const val PREFS_TERMINAL = "terminal"
        private const val KEY_FONT_SIZE_PX = "font_size_px"
        private const val TAG = "TerminalActivity"

        // Mirrors termux's private DECSET_BIT_BRACKETED_PASTE_MODE (DECSET 2004).
        private const val DECSET_BIT_BRACKETED_PASTE_MODE = 1 shl 10
        private val BRACKETED_PASTE_START = byteArrayOf(0x1B, '['.code.toByte(), '2'.code.toByte(), '0'.code.toByte(), '0'.code.toByte(), '~'.code.toByte())
        private val BRACKETED_PASTE_END = byteArrayOf(0x1B, '['.code.toByte(), '2'.code.toByte(), '0'.code.toByte(), '1'.code.toByte(), '~'.code.toByte())

        // xterm mouse wheel button codes.
        private const val WHEEL_UP_BUTTON = 64
        private const val WHEEL_DOWN_BUTTON = 65
        // Classic (non-SGR) xterm mouse coords max out at (255 - 32) = 223.
        private const val MOUSE_CLASSIC_COORD_MAX = 223
        // Emit one wheel event per this many rows of finger travel. tmux's
        // default response to a wheel event is three lines, so stepping every
        // two rows works out to a comfortable ~1.5× amplification.
        private const val SCROLL_LINES_PER_WHEEL = 2f
    }
}
