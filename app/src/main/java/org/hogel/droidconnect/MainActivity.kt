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
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import org.hogel.droidconnect.databinding.ActivityMainBinding
import org.hogel.droidconnect.ssh.SshConnectionService
import org.hogel.droidconnect.ssh.SshKeyManager
import org.hogel.droidconnect.ui.TerminalActivity

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var keyManager: SshKeyManager
    private lateinit var prefs: SharedPreferences

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

        updatePublicKeyDisplay()

        binding.btnGenerateKey.setOnClickListener {
            if (keyManager.hasKey()) {
                Toast.makeText(this, R.string.key_already_exists, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            keyManager.generateKey()
            Toast.makeText(this, R.string.key_generated, Toast.LENGTH_SHORT).show()
            updatePublicKeyDisplay()
        }

        binding.btnCopyKey.setOnClickListener {
            val pubKey = keyManager.getPublicKey() ?: return@setOnClickListener
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("SSH Public Key", pubKey))
            Toast.makeText(this, R.string.public_key_copied, Toast.LENGTH_SHORT).show()
        }

        binding.btnConnect.setOnClickListener { startConnection() }

        binding.btnMainDisconnect.setOnClickListener { service?.shutdown() }
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
    }

    private fun saveConnectionInput() {
        prefs.edit {
            putString(KEY_HOST, binding.editHost.text.toString())
            putString(KEY_PORT, binding.editPort.text.toString())
            putString(KEY_USERNAME, binding.editUsername.text.toString())
        }
    }

    private fun updatePublicKeyDisplay() {
        val pubKey = keyManager.getPublicKey()
        if (pubKey != null) {
            binding.textPublicKey.text = pubKey
            binding.cardPublicKey.visibility = View.VISIBLE
            binding.btnCopyKey.visibility = View.VISIBLE
        } else {
            binding.textPublicKey.text = ""
            binding.cardPublicKey.visibility = View.GONE
            binding.btnCopyKey.visibility = View.GONE
        }
    }

    companion object {
        private const val PREFS_NAME = "connection"
        private const val KEY_HOST = "host"
        private const val KEY_PORT = "port"
        private const val KEY_USERNAME = "username"
    }
}
