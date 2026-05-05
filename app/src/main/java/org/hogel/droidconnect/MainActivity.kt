package org.hogel.droidconnect

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.os.Bundle
import android.os.IBinder
import android.text.InputFilter
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import org.hogel.droidconnect.databinding.ActivityMainBinding
import org.hogel.droidconnect.shortcuts.ShortcutStore
import org.hogel.droidconnect.ssh.SshConnectionService
import org.hogel.droidconnect.ssh.SshKeyManager
import org.hogel.droidconnect.ui.ShortcutsSettingsActivity
import org.hogel.droidconnect.ui.TerminalActivity

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var keyManager: SshKeyManager
    private lateinit var prefs: SharedPreferences

    // In-memory copy of the saved tmux prefix letter (a–z). The dialog updates
    // this and saveConnectionInput persists it on pause, mirroring how the
    // other connection fields are handled.
    private var tmuxPrefix: String = DEFAULT_TMUX_PREFIX

    private var service: SshConnectionService? = null
    private var bindRegistered = false

    private val statusListener = object : SshConnectionService.StatusListener {
        override fun onSshConnected() {
            updateConnectionStatus()
        }

        override fun onSshDisconnected(error: Throwable?) {
            updateConnectionStatus()
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, ibinder: IBinder) {
            val svc = (ibinder as SshConnectionService.LocalBinder).getService()
            service = svc
            svc.addStatusListener(statusListener)
            updateConnectionStatus()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            updateConnectionStatus()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.textVersion.text = "${BuildConfig.VERSION_NAME}-${BuildConfig.GIT_SHORT_REV}"

        keyManager = SshKeyManager(this)
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        restoreConnectionInput()
        setupConnectionTargetToggle()
        setupSshKeyToggle()
        setupTmuxPrefixRow()
        setupShortcutsRow()

        updatePublicKeyDisplay()

        binding.btnGenerateKey.setOnClickListener {
            if (keyManager.hasKey()) {
                confirmOverwriteAndGenerateKey()
            } else {
                generateKey()
            }
        }

        binding.btnCopyKey.setOnClickListener {
            val pubKey = keyManager.getPublicKey() ?: return@setOnClickListener
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("SSH Public Key", pubKey))
            Toast.makeText(this, R.string.public_key_copied, Toast.LENGTH_SHORT).show()
        }

        binding.btnConnect.setOnClickListener {
            if (isSessionActive()) {
                resumeTerminal()
            } else {
                startConnection()
            }
        }

        binding.btnMainDisconnect.setOnClickListener { service?.shutdown() }
    }

    private fun isSessionActive(): Boolean = when (service?.state) {
        SshConnectionService.State.CONNECTING,
        SshConnectionService.State.CONNECTED -> true
        else -> false
    }

    private fun resumeTerminal() {
        startActivity(Intent(this, TerminalActivity::class.java))
    }

    private fun startConnection() {
        val host = binding.editHost.text.toString().trim()
        val portStr = binding.editPort.text.toString().trim()
        val username = binding.editUsername.text.toString().trim()

        if (host.isEmpty() || username.isEmpty()) return

        val port = portStr.toIntOrNull() ?: 22

        val intent = Intent(this, TerminalActivity::class.java).apply {
            putExtra(TerminalActivity.EXTRA_HOST, host)
            putExtra(TerminalActivity.EXTRA_PORT, port)
            putExtra(TerminalActivity.EXTRA_USERNAME, username)
            putExtra(TerminalActivity.EXTRA_USE_TMUX, binding.switchUseTmux.isChecked)
        }
        startActivity(intent)
    }

    override fun onStart() {
        super.onStart()
        // Bind without BIND_AUTO_CREATE: only connect if the service is already
        // running (i.e., an SSH session is active). A dormant app shows no
        // status card.
        val intent = Intent(this, SshConnectionService::class.java)
        bindRegistered = bindService(intent, serviceConnection, 0)
        updateConnectionStatus()
    }

    override fun onStop() {
        super.onStop()
        if (bindRegistered) {
            service?.removeStatusListener(statusListener)
            unbindService(serviceConnection)
            bindRegistered = false
            service = null
        }
    }

    override fun onPause() {
        super.onPause()
        saveConnectionInput()
    }

    override fun onResume() {
        super.onResume()
        updateShortcutsSummary()
    }

    private fun updateConnectionStatus() {
        val svc = service
        val state = svc?.state ?: SshConnectionService.State.IDLE
        when (state) {
            SshConnectionService.State.CONNECTING -> {
                binding.textConnectionStatus.text =
                    getString(R.string.status_connecting_to, svc?.connectionLabel ?: "")
                binding.cardConnectionStatus.visibility = View.VISIBLE
                binding.btnMainDisconnect.visibility = View.VISIBLE
            }
            SshConnectionService.State.CONNECTED -> {
                binding.textConnectionStatus.text =
                    getString(R.string.status_connected_to, svc?.connectionLabel ?: "")
                binding.cardConnectionStatus.visibility = View.VISIBLE
                binding.btnMainDisconnect.visibility = View.VISIBLE
            }
            else -> {
                binding.cardConnectionStatus.visibility = View.GONE
                binding.btnMainDisconnect.visibility = View.GONE
            }
        }
    }

    private fun restoreConnectionInput() {
        prefs.getString(KEY_HOST, null)?.let { binding.editHost.setText(it) }
        prefs.getString(KEY_PORT, null)?.let { binding.editPort.setText(it) }
        prefs.getString(KEY_USERNAME, null)?.let { binding.editUsername.setText(it) }
        binding.switchUseTmux.isChecked = prefs.getBoolean(KEY_USE_TMUX, true)
        tmuxPrefix = normalizeTmuxPrefix(prefs.getString(KEY_TMUX_PREFIX, DEFAULT_TMUX_PREFIX))
    }

    private fun saveConnectionInput() {
        prefs.edit {
            putString(KEY_HOST, binding.editHost.text.toString())
            putString(KEY_PORT, binding.editPort.text.toString())
            putString(KEY_USERNAME, binding.editUsername.text.toString())
            putBoolean(KEY_USE_TMUX, binding.switchUseTmux.isChecked)
            putString(KEY_TMUX_PREFIX, tmuxPrefix)
        }
    }

    private fun setupConnectionTargetToggle() {
        // Auto-collapse when a target was already saved; expand on first run so
        // the user sees the input fields.
        val hasSavedTarget = !prefs.getString(KEY_HOST, null).isNullOrBlank() &&
            !prefs.getString(KEY_USERNAME, null).isNullOrBlank()
        applyConnectionTargetExpanded(!hasSavedTarget)
        binding.headerConnectionTarget.setOnClickListener {
            val expanded = binding.containerConnectionTarget.visibility == View.VISIBLE
            applyConnectionTargetExpanded(!expanded)
        }
    }

    private fun applyConnectionTargetExpanded(expanded: Boolean) {
        binding.containerConnectionTarget.visibility = if (expanded) View.VISIBLE else View.GONE
        binding.textConnectionTargetSummary.visibility = if (expanded) View.GONE else View.VISIBLE
        binding.iconConnectionTargetChevron.rotation = if (expanded) 180f else 0f
        if (!expanded) {
            binding.textConnectionTargetSummary.text = buildConnectionTargetSummary()
        }
    }

    private fun buildConnectionTargetSummary(): String {
        val host = binding.editHost.text.toString().trim()
        val username = binding.editUsername.text.toString().trim()
        val port = binding.editPort.text.toString().trim().ifEmpty { "22" }
        if (host.isEmpty() || username.isEmpty()) {
            return getString(R.string.connection_target_summary_empty)
        }
        return "$username@$host:$port"
    }

    private fun setupSshKeyToggle() {
        // Auto-collapse when a key has already been generated.
        applySshKeyExpanded(!keyManager.hasKey())
        binding.headerSshKey.setOnClickListener {
            val expanded = binding.containerSshKey.visibility == View.VISIBLE
            applySshKeyExpanded(!expanded)
        }
    }

    private fun applySshKeyExpanded(expanded: Boolean) {
        binding.containerSshKey.visibility = if (expanded) View.VISIBLE else View.GONE
        binding.textSshKeySummary.visibility = if (expanded) View.GONE else View.VISIBLE
        binding.iconSshKeyChevron.rotation = if (expanded) 180f else 0f
        if (!expanded) {
            binding.textSshKeySummary.setText(
                if (keyManager.hasKey()) R.string.ssh_key_summary_generated
                else R.string.ssh_key_summary_not_generated
            )
        }
    }

    private fun setupShortcutsRow() {
        binding.headerShortcuts.setOnClickListener {
            startActivity(Intent(this, ShortcutsSettingsActivity::class.java))
        }
        updateShortcutsSummary()
    }

    private fun updateShortcutsSummary() {
        val store = ShortcutStore(this)
        val auxCount = store.loadAux().size
        val groupCount = store.loadContextGroups().size
        binding.textShortcutsSummary.text =
            getString(R.string.shortcuts_summary_format, auxCount, groupCount)
    }

    private fun setupTmuxPrefixRow() {
        binding.textTmuxPrefixValue.text = getString(R.string.tmux_prefix_value, tmuxPrefix)
        binding.rowTmuxPrefix.setOnClickListener { showTmuxPrefixDialog() }
    }

    private fun showTmuxPrefixDialog() {
        val edit = EditText(this).apply {
            setText(tmuxPrefix)
            setSelection(text.length)
            filters = arrayOf(InputFilter.LengthFilter(1))
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            isSingleLine = true
        }
        val pad = (resources.displayMetrics.density * 24).toInt()
        val container = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(edit)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.tmux_prefix)
            .setMessage(R.string.tmux_prefix_dialog_message)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                tmuxPrefix = normalizeTmuxPrefix(edit.text.toString())
                binding.textTmuxPrefixValue.text = getString(R.string.tmux_prefix_value, tmuxPrefix)
                prefs.edit { putString(KEY_TMUX_PREFIX, tmuxPrefix) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun normalizeTmuxPrefix(input: String?): String {
        val trimmed = input?.trim()?.lowercase().orEmpty()
        if (trimmed.length == 1 && trimmed[0] in 'a'..'z') return trimmed
        return DEFAULT_TMUX_PREFIX
    }

    private fun confirmOverwriteAndGenerateKey() {
        AlertDialog.Builder(this)
            .setTitle(R.string.key_overwrite_title)
            .setMessage(R.string.key_overwrite_message)
            .setPositiveButton(R.string.key_overwrite_confirm) { _, _ -> generateKey() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun generateKey() {
        keyManager.generateKey()
        Toast.makeText(this, R.string.key_generated, Toast.LENGTH_SHORT).show()
        updatePublicKeyDisplay()
    }

    private fun updatePublicKeyDisplay() {
        val hasKey = keyManager.getPublicKey() != null
        binding.btnCopyKey.visibility = if (hasKey) View.VISIBLE else View.GONE
    }

    companion object {
        private const val PREFS_NAME = "connection"
        private const val KEY_HOST = "host"
        private const val KEY_PORT = "port"
        private const val KEY_USERNAME = "username"
        private const val KEY_USE_TMUX = "use_tmux"
        private const val KEY_TMUX_PREFIX = "tmux_prefix"
        private const val DEFAULT_TMUX_PREFIX = "b"
    }
}
