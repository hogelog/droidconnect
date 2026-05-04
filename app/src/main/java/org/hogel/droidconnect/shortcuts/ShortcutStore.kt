package org.hogel.droidconnect.shortcuts

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/**
 * SharedPreferences-backed persistence for [Shortcut]s shown above the soft
 * keyboard in the terminal. Two lists are stored:
 *
 *  - the always-on "aux" bar
 *  - context groups, each keyed by one or more lowercased foreground command
 *    names that route from the active tmux pane's OSC title
 *
 * Defaults are hydrated lazily on first read so a fresh install ships with the
 * same shortcut set we used to hardcode in `TerminalActivity`.
 */
class ShortcutStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadAux(): List<Shortcut> {
        val raw = prefs.getString(KEY_AUX, null) ?: return defaultAux()
        return runCatching { parseShortcutArray(JSONArray(raw)) }.getOrElse { defaultAux() }
    }

    fun saveAux(shortcuts: List<Shortcut>) {
        prefs.edit { putString(KEY_AUX, encodeShortcutArray(shortcuts).toString()) }
    }

    fun loadContextGroups(): List<ContextGroup> {
        val raw = prefs.getString(KEY_CONTEXT, null) ?: return defaultContextGroups()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val contexts = parseStringArray(obj.optJSONArray("contexts"))
                    val items = parseShortcutArray(obj.optJSONArray("shortcuts") ?: JSONArray())
                    add(ContextGroup(contexts, items))
                }
            }
        }.getOrElse { defaultContextGroups() }
    }

    fun saveContextGroups(groups: List<ContextGroup>) {
        val arr = JSONArray()
        for (group in groups) {
            val obj = JSONObject()
            val ctxArr = JSONArray()
            for (c in group.contexts) ctxArr.put(c)
            obj.put("contexts", ctxArr)
            obj.put("shortcuts", encodeShortcutArray(group.shortcuts))
            arr.put(obj)
        }
        prefs.edit { putString(KEY_CONTEXT, arr.toString()) }
    }

    /** Drop both stored lists so the next read returns the bundled defaults. */
    fun resetToDefaults() {
        prefs.edit {
            remove(KEY_AUX)
            remove(KEY_CONTEXT)
        }
    }

    /**
     * Resolve the shortcut set for a given foreground command name. Matching is
     * case-insensitive and exact against any of a group's [ContextGroup.contexts]
     * entries; first match wins.
     */
    fun shortcutsForContext(app: String?): List<Shortcut> {
        val key = app?.trim()?.lowercase().orEmpty()
        if (key.isEmpty()) return emptyList()
        for (group in loadContextGroups()) {
            if (group.contexts.any { it.equals(key, ignoreCase = true) }) {
                return group.shortcuts
            }
        }
        return emptyList()
    }

    companion object {
        private const val PREFS_NAME = "shortcuts"
        private const val KEY_AUX = "aux"
        private const val KEY_CONTEXT = "context_groups"

        fun defaultAux(): List<Shortcut> = listOf(
            Shortcut("ESC", "\\e"),
            Shortcut("TAB", "{TAB}"),
            Shortcut("^L", "^L"),
            Shortcut("^R", "^R"),
            Shortcut("↓", "{DOWN}"),
            Shortcut("↑", "{UP}"),
            Shortcut("^C", "^C"),
            Shortcut("^D", "^D"),
        )

        fun defaultContextGroups(): List<ContextGroup> = listOf(
            ContextGroup(
                contexts = listOf("claude", "node"),
                shortcuts = listOf(
                    Shortcut("/", "/"),
                    Shortcut("/clear", "/clear"),
                    Shortcut("⇧Tab", "{S-TAB}"),
                    Shortcut("^J", "^J"),
                ),
            ),
            ContextGroup(
                contexts = listOf("bash", "zsh", "sh", "fish"),
                shortcuts = listOf(
                    Shortcut("claude", "claude"),
                    Shortcut("cd ", "cd "),
                ),
            ),
        )

        private fun parseShortcutArray(arr: JSONArray): List<Shortcut> = buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val label = obj.optString("label").orEmpty()
                val payload = obj.optString("payload").orEmpty()
                if (label.isNotEmpty()) add(Shortcut(label, payload))
            }
        }

        private fun encodeShortcutArray(shortcuts: List<Shortcut>): JSONArray {
            val arr = JSONArray()
            for (s in shortcuts) {
                arr.put(JSONObject().put("label", s.label).put("payload", s.payload))
            }
            return arr
        }

        private fun parseStringArray(arr: JSONArray?): List<String> = buildList {
            if (arr == null) return@buildList
            for (i in 0 until arr.length()) {
                val s = arr.optString(i)
                if (s.isNotEmpty()) add(s)
            }
        }
    }
}
