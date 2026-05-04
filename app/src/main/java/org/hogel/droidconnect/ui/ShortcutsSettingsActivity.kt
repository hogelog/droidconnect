package org.hogel.droidconnect.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import org.hogel.droidconnect.R
import org.hogel.droidconnect.databinding.ActivityShortcutsSettingsBinding
import org.hogel.droidconnect.databinding.DialogEditContextGroupBinding
import org.hogel.droidconnect.databinding.DialogEditShortcutBinding
import org.hogel.droidconnect.shortcuts.ContextGroup
import org.hogel.droidconnect.shortcuts.Shortcut
import org.hogel.droidconnect.shortcuts.ShortcutStore

/**
 * Edit screen for the two shortcut bars used by [TerminalActivity]. Reads from
 * and writes back to [ShortcutStore]; [TerminalActivity.onResume] picks up
 * changes when the user navigates back.
 *
 * The screen is a flat scroll of two sections — "Always" (the aux bar) and
 * "By context" (groups keyed by foreground command). Each row is its own
 * editable item, and reordering is supported via inline ↑ / ↓ buttons because
 * order is user-visible (the bar is laid out left-to-right in list order).
 */
class ShortcutsSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityShortcutsSettingsBinding
    private lateinit var store: ShortcutStore

    private val auxShortcuts = mutableListOf<Shortcut>()
    private val contextGroups = mutableListOf<ContextGroup>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityShortcutsSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        store = ShortcutStore(this)
        auxShortcuts += store.loadAux()
        contextGroups += store.loadContextGroups()

        renderAux()
        renderContextGroups()

        binding.btnAddAux.setOnClickListener {
            showShortcutDialog(R.string.shortcuts_add_button, Shortcut("", "")) { updated ->
                auxShortcuts += updated
                persistAux()
                renderAux()
            }
        }

        binding.btnAddContextGroup.setOnClickListener {
            showContextGroupDialog(
                R.string.shortcuts_add_context_group_button,
                ContextGroup(emptyList(), emptyList()),
            ) { updated ->
                contextGroups += updated
                persistContextGroups()
                renderContextGroups()
            }
        }

        binding.btnResetDefaults.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.shortcuts_reset_defaults)
                .setMessage(R.string.shortcuts_reset_defaults_message)
                .setPositiveButton(R.string.shortcuts_reset_defaults_confirm) { _, _ ->
                    store.resetToDefaults()
                    auxShortcuts.clear()
                    auxShortcuts += store.loadAux()
                    contextGroups.clear()
                    contextGroups += store.loadContextGroups()
                    renderAux()
                    renderContextGroups()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun persistAux() {
        store.saveAux(auxShortcuts)
    }

    private fun persistContextGroups() {
        store.saveContextGroups(contextGroups)
    }

    private fun renderAux() {
        val list = binding.listAux
        list.removeAllViews()
        if (auxShortcuts.isEmpty()) {
            list.addView(makeEmptyView(R.string.shortcuts_aux_empty))
            return
        }
        auxShortcuts.forEachIndexed { index, shortcut ->
            list.addView(
                makeShortcutRow(shortcut, index, auxShortcuts.size,
                    onMoveUp = {
                        swap(auxShortcuts, index, index - 1)
                        persistAux(); renderAux()
                    },
                    onMoveDown = {
                        swap(auxShortcuts, index, index + 1)
                        persistAux(); renderAux()
                    },
                    onEdit = {
                        showShortcutDialog(R.string.shortcuts_edit_title, shortcut) { updated ->
                            auxShortcuts[index] = updated
                            persistAux(); renderAux()
                        }
                    },
                    onDelete = {
                        auxShortcuts.removeAt(index)
                        persistAux(); renderAux()
                    },
                ),
            )
        }
    }

    private fun renderContextGroups() {
        val list = binding.listContextGroups
        list.removeAllViews()
        if (contextGroups.isEmpty()) {
            list.addView(makeEmptyView(R.string.shortcuts_context_empty))
            return
        }
        contextGroups.forEachIndexed { groupIndex, group ->
            list.addView(makeContextGroupCard(groupIndex, group))
        }
    }

    private fun makeContextGroupCard(groupIndex: Int, group: ContextGroup): View {
        val card = LayoutInflater.from(this)
            .inflate(R.layout.row_context_group, binding.listContextGroups, false)
        val title = card.findViewById<TextView>(R.id.text_group_title)
        val groupHeaderRow = card.findViewById<View>(R.id.row_group_header)
        val itemsContainer = card.findViewById<LinearLayout>(R.id.list_group_shortcuts)
        val btnAdd = card.findViewById<MaterialButton>(R.id.btn_add_group_shortcut)
        val btnEditContexts = card.findViewById<MaterialButton>(R.id.btn_edit_contexts)
        val btnDeleteGroup = card.findViewById<MaterialButton>(R.id.btn_delete_group)
        val btnUp = card.findViewById<MaterialButton>(R.id.btn_group_up)
        val btnDown = card.findViewById<MaterialButton>(R.id.btn_group_down)

        title.text = if (group.contexts.isEmpty()) {
            getString(R.string.shortcuts_context_no_names)
        } else {
            group.contexts.joinToString(", ")
        }

        groupHeaderRow.setOnClickListener {
            showContextGroupDialog(R.string.shortcuts_edit_contexts_title, group) { updated ->
                contextGroups[groupIndex] = updated
                persistContextGroups(); renderContextGroups()
            }
        }
        btnEditContexts.setOnClickListener { groupHeaderRow.performClick() }
        btnUp.isEnabled = groupIndex > 0
        btnDown.isEnabled = groupIndex < contextGroups.size - 1
        btnUp.setOnClickListener {
            swap(contextGroups, groupIndex, groupIndex - 1)
            persistContextGroups(); renderContextGroups()
        }
        btnDown.setOnClickListener {
            swap(contextGroups, groupIndex, groupIndex + 1)
            persistContextGroups(); renderContextGroups()
        }
        btnDeleteGroup.setOnClickListener {
            contextGroups.removeAt(groupIndex)
            persistContextGroups(); renderContextGroups()
        }
        btnAdd.setOnClickListener {
            showShortcutDialog(R.string.shortcuts_add_button, Shortcut("", "")) { updated ->
                val mutable = group.shortcuts.toMutableList().apply { add(updated) }
                contextGroups[groupIndex] = group.copy(shortcuts = mutable)
                persistContextGroups(); renderContextGroups()
            }
        }

        if (group.shortcuts.isEmpty()) {
            itemsContainer.addView(makeEmptyView(R.string.shortcuts_group_empty))
        } else {
            group.shortcuts.forEachIndexed { itemIndex, shortcut ->
                itemsContainer.addView(
                    makeShortcutRow(shortcut, itemIndex, group.shortcuts.size,
                        onMoveUp = {
                            val mutable = group.shortcuts.toMutableList()
                            swap(mutable, itemIndex, itemIndex - 1)
                            contextGroups[groupIndex] = group.copy(shortcuts = mutable)
                            persistContextGroups(); renderContextGroups()
                        },
                        onMoveDown = {
                            val mutable = group.shortcuts.toMutableList()
                            swap(mutable, itemIndex, itemIndex + 1)
                            contextGroups[groupIndex] = group.copy(shortcuts = mutable)
                            persistContextGroups(); renderContextGroups()
                        },
                        onEdit = {
                            showShortcutDialog(R.string.shortcuts_edit_title, shortcut) { updated ->
                                val mutable = group.shortcuts.toMutableList()
                                mutable[itemIndex] = updated
                                contextGroups[groupIndex] = group.copy(shortcuts = mutable)
                                persistContextGroups(); renderContextGroups()
                            }
                        },
                        onDelete = {
                            val mutable = group.shortcuts.toMutableList()
                            mutable.removeAt(itemIndex)
                            contextGroups[groupIndex] = group.copy(shortcuts = mutable)
                            persistContextGroups(); renderContextGroups()
                        },
                    ),
                )
            }
        }
        return card
    }

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
            .inflate(R.layout.row_shortcut, binding.listAux, false)
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

    private fun showContextGroupDialog(
        titleRes: Int,
        initial: ContextGroup,
        onSave: (ContextGroup) -> Unit,
    ) {
        val dialogBinding = DialogEditContextGroupBinding.inflate(layoutInflater)
        dialogBinding.editContexts.setText(initial.contexts.joinToString(", "))
        AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setView(dialogBinding.root)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val contexts = parseContextsField(dialogBinding.editContexts)
                onSave(initial.copy(contexts = contexts))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun parseContextsField(field: EditText): List<String> =
        field.text.toString()
            .split(',', ' ', '\t', '\n')
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .distinct()

    private fun <T> swap(list: MutableList<T>, a: Int, b: Int) {
        if (a !in list.indices || b !in list.indices) return
        val tmp = list[a]; list[a] = list[b]; list[b] = tmp
    }
}
