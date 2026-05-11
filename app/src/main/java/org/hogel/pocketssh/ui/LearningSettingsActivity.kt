package org.hogel.pocketssh.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.hogel.pocketssh.R
import org.hogel.pocketssh.databinding.ActivityLearningSettingsBinding
import org.hogel.pocketssh.learning.BigramStore

/**
 * Manages the bigram-learning data that feeds the dynamic suggestion row.
 * Kept separate from [ShortcutsSettingsActivity] because the learning store is
 * a different subsystem (auto-collected counts) than the user-curated context
 * groups, even though both surface in the same on-screen shortcut bar.
 */
class LearningSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLearningSettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLearningSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.btnClearLearned.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.learning_clear)
                .setMessage(R.string.learning_clear_message)
                .setPositiveButton(R.string.learning_clear_confirm) { _, _ ->
                    BigramStore(this).clear()
                    Toast.makeText(
                        this,
                        R.string.learning_clear_done,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }
}
