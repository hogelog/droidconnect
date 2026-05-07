package org.hogel.pocketssh.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import android.widget.Toast
import org.hogel.pocketssh.R
import org.hogel.pocketssh.databinding.ActivityShortcutsSettingsBinding
import org.hogel.pocketssh.learning.BigramStore
import org.hogel.pocketssh.shortcuts.ContextGroup
import org.hogel.pocketssh.shortcuts.ShortcutStore

/**
 * List of [ContextGroup]s. Each row is a thin summary (name + match keys) and
 * tapping it dives into [EditContextGroupActivity] for the full editor. The
 * list is the only place where ordering / deletion of groups happens, so the
 * up / down / delete buttons live here on the row chrome rather than inside
 * the per-group editor.
 */
class ShortcutsSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityShortcutsSettingsBinding
    private lateinit var store: ShortcutStore

    private val contextGroups = mutableListOf<ContextGroup>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityShortcutsSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        store = ShortcutStore(this)

        binding.btnAddContextGroup.setOnClickListener {
            val newIndex = contextGroups.size
            contextGroups.add(ContextGroup(name = ""))
            persist()
            renderContextGroups()
            startActivity(
                Intent(this, EditContextGroupActivity::class.java)
                    .putExtra(EditContextGroupActivity.EXTRA_GROUP_INDEX, newIndex),
            )
        }

        binding.btnResetDefaults.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.shortcuts_reset_defaults)
                .setMessage(R.string.shortcuts_reset_defaults_message)
                .setPositiveButton(R.string.shortcuts_reset_defaults_confirm) { _, _ ->
                    store.resetToDefaults()
                    reload()
                    renderContextGroups()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        binding.btnClearLearned.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.shortcuts_clear_learned)
                .setMessage(R.string.shortcuts_clear_learned_message)
                .setPositiveButton(R.string.shortcuts_clear_learned_confirm) { _, _ ->
                    BigramStore(this).clear()
                    Toast.makeText(
                        this,
                        R.string.shortcuts_clear_learned_done,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-read on every resume because [EditContextGroupActivity] writes
        // back without notifying us.
        reload()
        renderContextGroups()
    }

    private fun reload() {
        contextGroups.clear()
        contextGroups += store.loadContextGroups()
    }

    private fun persist() {
        store.saveContextGroups(contextGroups)
    }

    private fun renderContextGroups() {
        val list = binding.listContextGroups
        list.removeAllViews()
        if (contextGroups.isEmpty()) {
            list.addView(makeEmptyView(R.string.shortcuts_context_empty))
            return
        }
        contextGroups.forEachIndexed { index, group ->
            list.addView(makeContextGroupRow(index, group))
        }
    }

    private fun makeContextGroupRow(index: Int, group: ContextGroup): View {
        val row = LayoutInflater.from(this)
            .inflate(R.layout.row_context_group, binding.listContextGroups, false)
        val title = row.findViewById<TextView>(R.id.text_group_title)
        val subtitle = row.findViewById<TextView>(R.id.text_group_subtitle)
        val rowBody = row.findViewById<View>(R.id.row_body)
        val btnUp = row.findViewById<MaterialButton>(R.id.btn_group_up)
        val btnDown = row.findViewById<MaterialButton>(R.id.btn_group_down)
        val btnDelete = row.findViewById<MaterialButton>(R.id.btn_delete_group)

        title.text = group.name.ifEmpty { getString(R.string.shortcuts_group_unnamed) }
        subtitle.text = buildSummary(group)

        rowBody.setOnClickListener {
            startActivity(
                Intent(this, EditContextGroupActivity::class.java)
                    .putExtra(EditContextGroupActivity.EXTRA_GROUP_INDEX, index),
            )
        }
        btnUp.isEnabled = index > 0
        btnDown.isEnabled = index < contextGroups.size - 1
        btnUp.setOnClickListener {
            swap(contextGroups, index, index - 1); persist(); renderContextGroups()
        }
        btnDown.setOnClickListener {
            swap(contextGroups, index, index + 1); persist(); renderContextGroups()
        }
        btnDelete.setOnClickListener {
            contextGroups.removeAt(index); persist(); renderContextGroups()
        }
        return row
    }

    /**
     * Build a one-line "matches when …" summary. Empty contexts and absent
     * useTmux render as the bullet only — those are the all-states groups
     * (e.g. the bundled `always` group).
     */
    private fun buildSummary(group: ContextGroup): String {
        val parts = buildList {
            if (group.contexts.isNotEmpty()) add(group.contexts.joinToString(","))
            if (group.useTmux == true) add(getString(R.string.group_summary_tmux))
        }
        if (parts.isEmpty()) return getString(R.string.group_summary_always)
        return parts.joinToString(" + ")
    }

    private fun makeEmptyView(textRes: Int): View {
        val tv = TextView(this).apply {
            text = getString(textRes)
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

    private fun <T> swap(list: MutableList<T>, a: Int, b: Int) {
        if (a !in list.indices || b !in list.indices) return
        val tmp = list[a]; list[a] = list[b]; list[b] = tmp
    }
}
