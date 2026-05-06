package org.hogel.pocketssh.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import org.hogel.pocketssh.R
import org.hogel.pocketssh.databinding.ActivityEditContextGroupBinding
import org.hogel.pocketssh.databinding.DialogEditShortcutBinding
import org.hogel.pocketssh.shortcuts.ContextGroup
import org.hogel.pocketssh.shortcuts.Shortcut
import org.hogel.pocketssh.shortcuts.ShortcutStore

/**
 * Per-group editor used by [ShortcutsSettingsActivity]. Loads the group at
 * [EXTRA_GROUP_INDEX] from [ShortcutStore], edits it in-place, and writes back
 * after every mutation so a kill-while-editing scenario keeps partial work.
 *
 * The screen is a flat scroll: name, contexts, useTmux toggle, shortcuts list,
 * swipe left, swipe right, FAB items list. Picked over a tab/expand layout so
 * the user sees the full group state at a glance.
 */
class EditContextGroupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEditContextGroupBinding
    private lateinit var store: ShortcutStore

    private var groupIndex: Int = -1
    // Mutable working copy. Persisted back after every edit; if the caller
    // killed the activity mid-edit the partial state survives.
    private var group: ContextGroup = ContextGroup(name = "")
    // Suppresses the text watchers' persist-on-change while we programmatically
    // populate the fields in onCreate.
    private var loading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditContextGroupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        store = ShortcutStore(this)
        groupIndex = intent.getIntExtra(EXTRA_GROUP_INDEX, -1)
        val groups = store.loadContextGroups()
        if (groupIndex !in groups.indices) {
            // Stale intent (e.g. user rotated the device after deleting a
            // group via another path). Bail out instead of indexing into a
            // shorter list and clobbering the wrong slot.
            finish()
            return
        }
        group = groups[groupIndex]

        loading = true
        binding.editName.setText(group.name)
        binding.editContexts.setText(group.contexts.joinToString(","))
        binding.switchUseTmux.isChecked = group.useTmux == true
        loading = false

        binding.editName.addTextChangedListener(simpleWatcher {
            group = group.copy(name = binding.editName.text.toString().trim())
            persist()
        })
        binding.editContexts.addTextChangedListener(simpleWatcher {
            group = group.copy(contexts = parseContextsField(binding.editContexts))
            persist()
        })
        binding.switchUseTmux.setOnCheckedChangeListener { _, checked ->
            // Store as null when off so the don't-care state stays absent
            // from the on-disk JSON.
            group = group.copy(useTmux = if (checked) true else null)
            persist()
        }

        binding.btnAddShortcut.setOnClickListener {
            showShortcutDialog(R.string.shortcuts_add_button, Shortcut("", "")) { added ->
                group = group.copy(shortcuts = group.shortcuts + added)
                persist(); renderShortcuts()
            }
        }
        binding.btnAddFabItem.setOnClickListener {
            showShortcutDialog(R.string.shortcuts_add_button, Shortcut("", "")) { added ->
                group = group.copy(fabItems = group.fabItems + added)
                persist(); renderFabItems()
            }
        }
        binding.rowSwipeLeft.setOnClickListener {
            showShortcutDialog(
                R.string.shortcuts_edit_title,
                group.swipeLeft ?: Shortcut("", ""),
            ) { updated ->
                group = group.copy(swipeLeft = updated.takeIf { it.label.isNotEmpty() })
                persist(); renderSwipe()
            }
        }
        binding.rowSwipeRight.setOnClickListener {
            showShortcutDialog(
                R.string.shortcuts_edit_title,
                group.swipeRight ?: Shortcut("", ""),
            ) { updated ->
                group = group.copy(swipeRight = updated.takeIf { it.label.isNotEmpty() })
                persist(); renderSwipe()
            }
        }
        binding.btnClearSwipeLeft.setOnClickListener {
            group = group.copy(swipeLeft = null); persist(); renderSwipe()
        }
        binding.btnClearSwipeRight.setOnClickListener {
            group = group.copy(swipeRight = null); persist(); renderSwipe()
        }

        renderShortcuts()
        renderSwipe()
        renderFabItems()
    }

    private fun persist() {
        if (loading) return
        val groups = store.loadContextGroups().toMutableList()
        if (groupIndex !in groups.indices) {
            // Group was deleted from another path while this editor was open.
            finish()
            return
        }
        groups[groupIndex] = group
        store.saveContextGroups(groups)
    }

    private fun renderShortcuts() {
        renderList(binding.listShortcuts, group.shortcuts, R.string.shortcuts_group_empty,
            onUpdate = { updated -> group = group.copy(shortcuts = updated) })
    }

    private fun renderFabItems() {
        renderList(binding.listFabItems, group.fabItems, R.string.shortcuts_fab_empty,
            onUpdate = { updated -> group = group.copy(fabItems = updated) })
    }

    private fun renderList(
        container: LinearLayout,
        items: List<Shortcut>,
        emptyTextRes: Int,
        onUpdate: (List<Shortcut>) -> Unit,
    ) {
        container.removeAllViews()
        if (items.isEmpty()) {
            container.addView(makeEmptyView(emptyTextRes))
            return
        }
        items.forEachIndexed { index, shortcut ->
            container.addView(
                makeShortcutRow(
                    shortcut = shortcut,
                    index = index,
                    size = items.size,
                    onMoveUp = {
                        val mutable = items.toMutableList()
                        swap(mutable, index, index - 1)
                        onUpdate(mutable); persist()
                        if (container === binding.listShortcuts) renderShortcuts() else renderFabItems()
                    },
                    onMoveDown = {
                        val mutable = items.toMutableList()
                        swap(mutable, index, index + 1)
                        onUpdate(mutable); persist()
                        if (container === binding.listShortcuts) renderShortcuts() else renderFabItems()
                    },
                    onEdit = {
                        showShortcutDialog(R.string.shortcuts_edit_title, shortcut) { updated ->
                            val mutable = items.toMutableList()
                            mutable[index] = updated
                            onUpdate(mutable); persist()
                            if (container === binding.listShortcuts) renderShortcuts() else renderFabItems()
                        }
                    },
                    onDelete = {
                        val mutable = items.toMutableList()
                        mutable.removeAt(index)
                        onUpdate(mutable); persist()
                        if (container === binding.listShortcuts) renderShortcuts() else renderFabItems()
                    },
                ),
            )
        }
    }

    private fun renderSwipe() {
        binding.textSwipeLeftValue.text =
            group.swipeLeft?.let { swipeSummary(it) } ?: getString(R.string.shortcuts_payload_empty)
        binding.textSwipeRightValue.text =
            group.swipeRight?.let { swipeSummary(it) } ?: getString(R.string.shortcuts_payload_empty)
        binding.btnClearSwipeLeft.visibility =
            if (group.swipeLeft != null) View.VISIBLE else View.GONE
        binding.btnClearSwipeRight.visibility =
            if (group.swipeRight != null) View.VISIBLE else View.GONE
    }

    private fun swipeSummary(shortcut: Shortcut): String =
        if (shortcut.payload.isEmpty()) shortcut.label
        else "${shortcut.label} (${shortcut.payload})"

    private fun makeShortcutRow(
        shortcut: Shortcut,
        index: Int,
        size: Int,
        onMoveUp: () -> Unit,
        onMoveDown: () -> Unit,
        onEdit: () -> Unit,
        onDelete: () -> Unit,
    ): View {
        val row = LayoutInflater.from(this)
            .inflate(R.layout.row_shortcut, binding.listShortcuts, false)
        row.findViewById<TextView>(R.id.text_label).text = shortcut.label
        val payloadView = row.findViewById<TextView>(R.id.text_payload)
        payloadView.text = shortcut.payload.ifEmpty { getString(R.string.shortcuts_payload_empty) }
        row.findViewById<View>(R.id.row_body).setOnClickListener { onEdit() }
        val btnUp = row.findViewById<MaterialButton>(R.id.btn_up)
        val btnDown = row.findViewById<MaterialButton>(R.id.btn_down)
        val btnDelete = row.findViewById<MaterialButton>(R.id.btn_delete)
        btnUp.isEnabled = index > 0
        btnDown.isEnabled = index < size - 1
        btnUp.setOnClickListener { onMoveUp() }
        btnDown.setOnClickListener { onMoveDown() }
        btnDelete.setOnClickListener { onDelete() }
        return row
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

    private fun showShortcutDialog(
        titleRes: Int,
        initial: Shortcut,
        onSave: (Shortcut) -> Unit,
    ) {
        val dialogBinding = DialogEditShortcutBinding.inflate(layoutInflater)
        dialogBinding.editLabel.setText(initial.label)
        dialogBinding.editPayload.setText(initial.payload)
        AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setView(dialogBinding.root)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val label = dialogBinding.editLabel.text.toString().trim()
                val payload = dialogBinding.editPayload.text.toString()
                if (label.isEmpty()) return@setPositiveButton
                onSave(Shortcut(label, payload))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun parseContextsField(field: TextInputEditText): List<String> =
        field.text.toString()
            .split(',', ' ', '\t', '\n')
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .distinct()

    private fun simpleWatcher(onChange: () -> Unit): TextWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) {
            if (!loading) onChange()
        }
    }

    private fun <T> swap(list: MutableList<T>, a: Int, b: Int) {
        if (a !in list.indices || b !in list.indices) return
        val tmp = list[a]; list[a] = list[b]; list[b] = tmp
    }

    companion object {
        const val EXTRA_GROUP_INDEX = "group_index"
    }
}
