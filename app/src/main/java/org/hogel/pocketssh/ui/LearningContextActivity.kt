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
import org.hogel.pocketssh.databinding.ActivityLearningContextBinding
import org.hogel.pocketssh.learning.BigramStore

/**
 * Per-context detail screen for [LearningSettingsActivity]. Lists every
 * bigram stored under the selected foreground command context and lets the
 * user forget them one at a time. Finishes back to the parent when the last
 * row in the context is removed so the user is not left staring at an empty
 * detail screen.
 */
class LearningContextActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLearningContextBinding
    private lateinit var store: BigramStore
    private lateinit var contextName: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLearningContextBinding.inflate(layoutInflater)
        setContentView(binding.root)

        contextName = intent.getStringExtra(EXTRA_CONTEXT)
            ?: error("LearningContextActivity launched without $EXTRA_CONTEXT")

        binding.toolbar.title = contextName
        binding.toolbar.setNavigationOnClickListener { finish() }

        store = BigramStore(this)
    }

    override fun onResume() {
        super.onResume()
        renderList()
    }

    private fun renderList() {
        val list = binding.listBigrams
        list.removeAllViews()
        val rows = store.snapshotByContext(contextName)
        if (rows.isEmpty()) {
            list.addView(makeEmptyView())
            return
        }
        for (row in rows) {
            list.addView(makeRow(row))
        }
    }

    private fun makeRow(bigram: BigramStore.Bigram): View {
        val row = LayoutInflater.from(this)
            .inflate(R.layout.row_bigram, binding.listBigrams, false)
        row.findViewById<TextView>(R.id.text_bigram_prev).text = displayToken(bigram.prev)
        row.findViewById<TextView>(R.id.text_bigram_next).text = displayToken(bigram.next)
        row.findViewById<TextView>(R.id.text_bigram_count).text =
            getString(R.string.learning_bigram_count_format, bigram.count)
        row.findViewById<MaterialButton>(R.id.btn_delete_bigram).setOnClickListener {
            confirmDelete(bigram)
        }
        return row
    }

    private fun displayToken(token: String): String = when (token) {
        BigramStore.BOL -> getString(R.string.learning_token_bol)
        BigramStore.ENTER -> getString(R.string.learning_token_enter)
        else -> token
    }

    private fun confirmDelete(bigram: BigramStore.Bigram) {
        AlertDialog.Builder(this)
            .setTitle(R.string.learning_delete_title)
            .setMessage(
                getString(
                    R.string.learning_delete_message,
                    displayToken(bigram.next),
                    displayToken(bigram.prev),
                    bigram.context,
                ),
            )
            .setPositiveButton(R.string.learning_delete) { _, _ ->
                store.delete(bigram.context, bigram.prev, bigram.next)
                if (store.snapshotByContext(contextName).isEmpty()) {
                    finish()
                } else {
                    renderList()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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

    companion object {
        const val EXTRA_CONTEXT = "context"
    }
}
