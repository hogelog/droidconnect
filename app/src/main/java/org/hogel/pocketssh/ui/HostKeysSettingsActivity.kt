package org.hogel.pocketssh.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import org.hogel.pocketssh.R
import org.hogel.pocketssh.databinding.ActivityHostKeysSettingsBinding
import org.hogel.pocketssh.ssh.HostKeyEntry
import org.hogel.pocketssh.ssh.HostKeyStore
import org.hogel.pocketssh.ssh.sha256HostKeyFingerprint

/**
 * Lists every TOFU-accepted server host key and lets the user forget them
 * individually. Without this, a legitimately rotated server key forces the
 * user to clear all app data, losing every other stored TOFU record.
 */
class HostKeysSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHostKeysSettingsBinding
    private lateinit var store: HostKeyStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHostKeysSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        store = HostKeyStore(this)
    }

    override fun onResume() {
        super.onResume()
        renderHostKeys()
    }

    private fun renderHostKeys() {
        val list = binding.listHostKeys
        list.removeAllViews()
        val entries = store.list().sortedWith(compareBy({ it.host }, { it.port }))
        if (entries.isEmpty()) {
            list.addView(makeEmptyView())
            return
        }
        for (entry in entries) {
            list.addView(makeRow(entry))
        }
    }

    private fun makeRow(entry: HostKeyEntry): View {
        val row = LayoutInflater.from(this)
            .inflate(R.layout.row_host_key, binding.listHostKeys, false)
        row.findViewById<TextView>(R.id.text_host_key_endpoint).text =
            "${entry.host}:${entry.port}"
        row.findViewById<TextView>(R.id.text_host_key_algorithm).text = entry.algorithm
        row.findViewById<TextView>(R.id.text_host_key_fingerprint).text =
            sha256HostKeyFingerprint(entry.key)
        row.findViewById<MaterialButton>(R.id.btn_forget_host_key).setOnClickListener {
            confirmForget(entry)
        }
        return row
    }

    private fun confirmForget(entry: HostKeyEntry) {
        AlertDialog.Builder(this)
            .setTitle(R.string.host_keys_forget_title)
            .setMessage(
                getString(
                    R.string.host_keys_forget_message,
                    entry.host,
                    entry.port,
                ),
            )
            .setPositiveButton(R.string.host_keys_forget) { _, _ ->
                store.delete(entry.host, entry.port)
                renderHostKeys()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun makeEmptyView(): View {
        val tv = TextView(this).apply {
            text = getString(R.string.host_keys_empty)
            textAlignment = View.TEXT_ALIGNMENT_TEXT_START
            alpha = 0.6f
            setTextAppearance(android.R.style.TextAppearance_Material_Body2)
        }
        val pad = (resources.displayMetrics.density * 8).toInt()
        tv.setPadding(pad, pad, pad, pad)
        tv.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        return tv
    }
}
