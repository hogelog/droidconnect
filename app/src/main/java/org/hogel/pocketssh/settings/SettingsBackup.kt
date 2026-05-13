package org.hogel.pocketssh.settings

import android.content.Context
import androidx.core.content.edit
import org.hogel.pocketssh.MainActivity
import org.hogel.pocketssh.learning.BigramStore
import org.hogel.pocketssh.shortcuts.ShortcutStore
import org.hogel.pocketssh.ui.TerminalActivity
import org.json.JSONArray
import org.json.JSONObject

/**
 * Serializes user-configurable settings to a single JSON document so users can
 * transfer them between devices. The bundle covers connection-target defaults
 * (host/port/username, tmux toggle and prefix), the terminal font size, and
 * the entire shortcut store (context groups, including their swipe and FAB
 * payloads).
 *
 * The learned bigram counts that drive the dynamic suggestion row are
 * effectively a partial history of typed shell commands, so they are excluded
 * from exports by default. Callers opt in by passing `includeLearning = true`
 * to [export]; [import] always accepts payloads with or without the field.
 *
 * Excluded:
 *  - SSH private key — keystore-bound (TEE), can't leave the device. Users
 *    regenerate on the new device and re-authorize on servers.
 *  - Host-key TOFU records — security anchors. Re-verifying on a fresh device
 *    is the point of the trust-on-first-use model.
 */
object SettingsBackup {
    /**
     * Schema version. Bump (and add a migration branch in [import]) on any
     * non-additive change to the JSON shape.
     */
    private const val VERSION = 1

    fun export(context: Context, includeLearning: Boolean = false): String {
        val prefs = context.getSharedPreferences(
            MainActivity.PREFS_NAME, Context.MODE_PRIVATE,
        )
        val store = ShortcutStore(context)

        val connectionJson = JSONObject().apply {
            prefs.getString(MainActivity.KEY_HOST, null)?.let { put("host", it) }
            prefs.getString(MainActivity.KEY_PORT, null)?.let { put("port", it) }
            prefs.getString(MainActivity.KEY_USERNAME, null)?.let { put("username", it) }
            put("use_tmux", prefs.getBoolean(MainActivity.KEY_USE_TMUX, true))
            prefs.getString(MainActivity.KEY_TMUX_PREFIX, null)?.let { put("tmux_prefix", it) }
        }
        val groupsJson = ShortcutStore.encodeContextGroups(store.loadContextGroups())

        val terminalPrefs = context.getSharedPreferences(
            TerminalActivity.PREFS_TERMINAL, Context.MODE_PRIVATE,
        )
        val terminalJson = JSONObject().apply {
            if (terminalPrefs.contains(TerminalActivity.KEY_FONT_SIZE_PX)) {
                put("font_size_px", terminalPrefs.getInt(TerminalActivity.KEY_FONT_SIZE_PX, 0))
            }
        }

        val root = JSONObject()
            .put("version", VERSION)
            .put("connection", connectionJson)
            .put("terminal", terminalJson)
            .put("context_groups", groupsJson)
        if (includeLearning) {
            root.put("bigrams", encodeBigrams(BigramStore(context).snapshot()))
        }
        return root.toString(2)
    }

    /**
     * Apply [json] to persisted settings. Throws if the input cannot be parsed
     * or has an unsupported version. Each section is fully decoded before any
     * write so a malformed payload won't leave settings half-overwritten.
     */
    fun import(context: Context, json: String) {
        val root = JSONObject(json)
        val version = root.optInt("version", -1)
        require(version == VERSION) { "Unsupported settings version: $version" }

        val connectionObj = root.optJSONObject("connection")
        val terminalObj = root.optJSONObject("terminal")
        val groupsArr: JSONArray? = root.optJSONArray("context_groups")
        val bigramsArr: JSONArray? = root.optJSONArray("bigrams")
        // Decode upfront so a bad payload throws before we touch persisted state.
        val groups = groupsArr?.let { ShortcutStore.decodeContextGroups(it) }
        val bigrams = bigramsArr?.let { decodeBigrams(it) }

        if (connectionObj != null) {
            val prefs = context.getSharedPreferences(
                MainActivity.PREFS_NAME, Context.MODE_PRIVATE,
            )
            prefs.edit {
                if (connectionObj.has("host")) {
                    putString(MainActivity.KEY_HOST, connectionObj.getString("host"))
                }
                if (connectionObj.has("port")) {
                    putString(MainActivity.KEY_PORT, connectionObj.getString("port"))
                }
                if (connectionObj.has("username")) {
                    putString(MainActivity.KEY_USERNAME, connectionObj.getString("username"))
                }
                if (connectionObj.has("use_tmux")) {
                    putBoolean(MainActivity.KEY_USE_TMUX, connectionObj.getBoolean("use_tmux"))
                }
                if (connectionObj.has("tmux_prefix")) {
                    putString(MainActivity.KEY_TMUX_PREFIX, connectionObj.getString("tmux_prefix"))
                }
            }
        }
        if (terminalObj != null) {
            val terminalPrefs = context.getSharedPreferences(
                TerminalActivity.PREFS_TERMINAL, Context.MODE_PRIVATE,
            )
            terminalPrefs.edit {
                if (terminalObj.has("font_size_px")) {
                    putInt(TerminalActivity.KEY_FONT_SIZE_PX, terminalObj.getInt("font_size_px"))
                }
            }
        }
        if (groups != null) {
            ShortcutStore(context).saveContextGroups(groups)
        }
        if (bigrams != null) {
            BigramStore(context).replaceAll(bigrams)
        }
    }

    private fun encodeBigrams(rows: List<BigramStore.Bigram>): JSONArray {
        val arr = JSONArray()
        for (r in rows) {
            arr.put(
                JSONObject()
                    .put("context", r.context)
                    .put("prev", r.prev)
                    .put("next", r.next)
                    .put("count", r.count),
            )
        }
        return arr
    }

    private fun decodeBigrams(arr: JSONArray): List<BigramStore.Bigram> = buildList {
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            add(
                BigramStore.Bigram(
                    context = obj.getString("context"),
                    prev = obj.getString("prev"),
                    next = obj.getString("next"),
                    count = obj.getInt("count"),
                ),
            )
        }
    }
}
