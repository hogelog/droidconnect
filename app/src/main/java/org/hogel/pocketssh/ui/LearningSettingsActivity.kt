package org.hogel.pocketssh.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.card.MaterialCardView
import org.hogel.pocketssh.R
import org.hogel.pocketssh.databinding.ActivityLearningSettingsBinding
import org.hogel.pocketssh.learning.BigramStore

/**
 * Top level of the bigram-learning settings flow. Lists every foreground
 * command context that has accumulated suggestions and drills into a
 * per-context detail screen ([LearningContextActivity]) where individual
 * bigrams can be forgotten. The bulk "Clear learned suggestions" action
 * stays here because it spans every context.
 */
class LearningSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLearningSettingsBinding
    private lateinit var store: BigramStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLearningSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        store = BigramStore(this)

        binding.btnClearLearned.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.learning_clear)
                .setMessage(R.string.learning_clear_message)
                .setPositiveButton(R.string.learning_clear_confirm) { _, _ ->
                    store.clear()
                    Toast.makeText(
                        this,
                        R.string.learning_clear_done,
                        Toast.LENGTH_SHORT,
                    ).show()
                    renderContexts()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        renderContexts()
    }

    private fun renderContexts() {
        val list = binding.listLearningContexts
        list.removeAllViews()
        val summaries = store.contextSummaries()
        if (summaries.isEmpty()) {
            list.addView(makeEmptyView())
            return
        }
        for (summary in summaries) {
            list.addView(makeContextRow(summary))
        }
    }

    private fun makeContextRow(summary: BigramStore.ContextSummary): View {
        val row = LayoutInflater.from(this)
            .inflate(R.layout.row_learning_context, binding.listLearningContexts, false)
        row.findViewById<TextView>(R.id.text_context_name).text = summary.context
        row.findViewById<TextView>(R.id.text_context_count).text = resources.getQuantityString(
            R.plurals.learning_context_count_format,
            summary.count,
            summary.count,
        )
        (row as MaterialCardView).setOnClickListener {
            startActivity(
                Intent(this, LearningContextActivity::class.java).putExtra(
                    LearningContextActivity.EXTRA_CONTEXT,
                    summary.context,
                ),
            )
        }
        return row
    }

    private fun makeEmptyView(): View {
        val tv = TextView(this).apply {
            text = getString(R.string.learning_empty)
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
