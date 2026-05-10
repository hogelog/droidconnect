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

    /** Every stored entry, in `host:port` insertion-defined order. */
    fun list(): List<HostKeyEntry> = buildList {
        for ((rawKey, value) in prefs.all) {
            if (value !is String) continue
            val (host, port) = parsePrefKey(rawKey) ?: continue
            val space = value.indexOf(' ')
            if (space < 0) continue
            val algorithm = value.substring(0, space)
            val keyBytes = runCatching { Base64.getDecoder().decode(value.substring(space + 1)) }
                .getOrNull() ?: continue
            add(HostKeyEntry(host, port, algorithm, keyBytes))
        }
    }

    fun delete(host: String, port: Int) {
        prefs.edit { remove(prefKey(host, port)) }
    }

    private fun prefKey(host: String, port: Int): String = "$host:$port"

    private fun parsePrefKey(raw: String): Pair<String, Int>? {
        // Hosts can be IPv6 literals containing colons; the port is always the
        // suffix after the last colon, so split there rather than the first.
        val sep = raw.lastIndexOf(':')
        if (sep <= 0 || sep == raw.length - 1) return null
        val port = raw.substring(sep + 1).toIntOrNull() ?: return null
        return raw.substring(0, sep) to port
    }

    companion object {
        private const val PREFS_NAME = "host_keys"
    }
}

class StoredHostKey(val algorithm: String, val key: ByteArray)

data class HostKeyEntry(
    val host: String,
    val port: Int,
    val algorithm: String,
    val key: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is HostKeyEntry &&
            host == other.host &&
            port == other.port &&
            algorithm == other.algorithm &&
            key.contentEquals(other.key)

    override fun hashCode(): Int {
        var result = host.hashCode()
        result = 31 * result + port
        result = 31 * result + algorithm.hashCode()
        result = 31 * result + key.contentHashCode()
        return result
    }
}
