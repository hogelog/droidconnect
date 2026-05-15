package org.hogel.pocketssh.debug

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.hogel.pocketssh.BuildConfig
import org.hogel.pocketssh.MainActivity
import org.hogel.pocketssh.R
import org.hogel.pocketssh.databinding.ActivityDiagDumpBinding
import org.hogel.pocketssh.learning.BigramStore
import org.hogel.pocketssh.shortcuts.ShortcutStore
import org.hogel.pocketssh.ssh.HostKeyStore
import org.hogel.pocketssh.ssh.SshKeyManager

/**
 * Debug-only diagnostic snapshot of app state. Lives in the debug source
 * set so the activity, its layout, and its supporting strings never ship
 * in the release APK.
 */
class DiagDumpActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDiagDumpBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDiagDumpBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        val dump = buildDump()
        binding.textDump.text = dump

        binding.btnCopy.setOnClickListener {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("PocketSecureShell diag", dump))
            Toast.makeText(this, R.string.diag_dump_copied, Toast.LENGTH_SHORT).show()
        }
    }

    private fun buildDump(): String = buildString {
        appendLine("[App]")
        appendLine("applicationId = ${BuildConfig.APPLICATION_ID}")
        appendLine("versionName   = ${BuildConfig.VERSION_NAME}")
        appendLine("versionCode   = ${BuildConfig.VERSION_CODE}")
        appendLine("buildType     = ${BuildConfig.BUILD_TYPE}")
        appendLine("gitShortRev   = ${BuildConfig.GIT_SHORT_REV}")
        appendLine()

        appendLine("[Device]")
        appendLine("manufacturer  = ${Build.MANUFACTURER}")
        appendLine("model         = ${Build.MODEL}")
        appendLine("device        = ${Build.DEVICE}")
        appendLine("sdk           = ${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})")
        appendLine()

        val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        appendLine("[Connection prefs]")
        appendLine("host          = ${prefs.getString(MainActivity.KEY_HOST, "")}")
        appendLine("port          = ${prefs.getString(MainActivity.KEY_PORT, "")}")
        appendLine("username      = ${prefs.getString(MainActivity.KEY_USERNAME, "")}")
        appendLine("use_tmux      = ${prefs.getBoolean(MainActivity.KEY_USE_TMUX, true)}")
        appendLine("tmux_prefix   = ${prefs.getString(MainActivity.KEY_TMUX_PREFIX, "")}")
        appendLine()

        appendLine("[SSH key]")
        appendLine("hasKey        = ${SshKeyManager().hasKey()}")
        appendLine()

        appendLine("[Host keys]")
        appendLine("count         = ${HostKeyStore(this@DiagDumpActivity).list().size}")
        appendLine()

        appendLine("[Shortcuts]")
        appendLine("groups        = ${ShortcutStore(this@DiagDumpActivity).loadContextGroups().size}")
        appendLine()

        appendLine("[Bigram contexts]")
        val summaries = BigramStore(this@DiagDumpActivity).contextSummaries()
        if (summaries.isEmpty()) {
            appendLine("(none)")
        } else {
            summaries.forEach { appendLine("${it.context} = ${it.count}") }
        }
    }
}
