package org.hogel.pocketssh.ssh

import android.content.Context
import androidx.core.content.edit
import java.util.Base64

/**
 * Persists accepted server host keys keyed by `host:port`. Backs the TOFU
 * (Trust On First Use) check in [TofuHostKeyVerifier]: the first connection
 * to a host is gated by a user prompt, every subsequent connection must
 * present the same key bytes.
 *
 * The on-disk format (`"<algorithm> <base64-key>"` in SharedPreferences) is
 * deliberately app-private and not OpenSSH-compatible — it only needs to
 * survive across launches, not interoperate with anything else.
 */
class HostKeyStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun get(host: String, port: Int): StoredHostKey? {
        val raw = prefs.getString(prefKey(host, port), null) ?: return null
        val space = raw.indexOf(' ')
        if (space < 0) return null
        val algorithm = raw.substring(0, space)
        val keyBytes = Base64.getDecoder().decode(raw.substring(space + 1))
        return StoredHostKey(algorithm, keyBytes)
    }

    fun put(host: String, port: Int, algorithm: String, key: ByteArray) {
        val encoded = Base64.getEncoder().encodeToString(key)
        prefs.edit { putString(prefKey(host, port), "$algorithm $encoded") }
    }

    private fun prefKey(host: String, port: Int): String = "$host:$port"

    companion object {
        private const val PREFS_NAME = "host_keys"
    }
}

class StoredHostKey(val algorithm: String, val key: ByteArray)
