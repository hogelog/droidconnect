package org.hogel.pocketssh.settings

import android.content.Context
import androidx.core.content.edit
import org.hogel.pocketssh.MainActivity
import org.hogel.pocketssh.shortcuts.ShortcutStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * Serializes user-configurable settings to a single JSON document so users can
 * transfer them between devices. The bundle covers connection-target defaults
 * (host/port/username, tmux toggle and prefix) and the entire shortcut store
 * (context groups, including their swipe and FAB payloads).
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

    fun export(context: Context): String {
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

        return JSONObject()
            .put("version", VERSION)
            .put("connection", connectionJson)
            .put("context_groups", groupsJson)
            .toString(2)
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
        val groupsArr: JSONArray? = root.optJSONArray("context_groups")
        // Decode upfront so a bad payload throws before we touch persisted state.
        val groups = groupsArr?.let { ShortcutStore.decodeContextGroups(it) }

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
        if (groups != null) {
            ShortcutStore(context).saveContextGroups(groups)
        }
    }
}
