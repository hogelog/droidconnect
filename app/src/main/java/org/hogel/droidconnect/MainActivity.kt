package org.hogel.droidconnect

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.hogel.droidconnect.databinding.ActivityMainBinding
import org.hogel.droidconnect.ssh.SshKeyManager
import org.hogel.droidconnect.ui.TerminalActivity

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var keyManager: SshKeyManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        keyManager = SshKeyManager(this)

        // Show existing public key if available
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

        binding.btnConnect.setOnClickListener {
            val host = binding.editHost.text.toString().trim()
            val portStr = binding.editPort.text.toString().trim()
            val username = binding.editUsername.text.toString().trim()

            if (host.isEmpty() || username.isEmpty()) return@setOnClickListener

            val port = portStr.toIntOrNull() ?: 22

            val intent = Intent(this, TerminalActivity::class.java).apply {
                putExtra(TerminalActivity.EXTRA_HOST, host)
                putExtra(TerminalActivity.EXTRA_PORT, port)
                putExtra(TerminalActivity.EXTRA_USERNAME, username)
            }
            startActivity(intent)
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
}
