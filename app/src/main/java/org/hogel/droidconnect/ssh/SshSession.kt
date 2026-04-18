package org.hogel.droidconnect.ssh

import com.trilead.ssh2.Connection
import com.trilead.ssh2.ServerHostKeyVerifier
import com.trilead.ssh2.Session
import java.io.InputStream
import java.io.OutputStream

/** SSH connection backed by sshlib (Trilead SSH2). */
class SshSession(
    private val host: String,
    private val port: Int,
    private val username: String,
    private val privateKeyPem: CharArray,
) {
    private var connection: Connection? = null
    private var session: Session? = null

    val stdout: InputStream get() = session!!.stdout
    val stdin: OutputStream get() = session!!.stdin

    var isConnected: Boolean = false
        private set

    /** Blocking; call from a background thread. */
    fun connect() {
        val conn = Connection(host, port)
        // Accept all host keys for Phase 1 (personal use only)
        val acceptAllVerifier = ServerHostKeyVerifier { _, _, _, _ -> true }
        conn.connect(acceptAllVerifier, 10_000, 10_000)

        val authenticated = conn.authenticateWithPublicKey(username, privateKeyPem, null)
        if (!authenticated) {
            conn.close()
            throw SshAuthenticationException("Public key authentication failed")
        }

        connection = conn
        isConnected = true
    }

    fun openShell(columns: Int, rows: Int) {
        val conn = connection ?: throw IllegalStateException("Not connected")
        val sess = conn.openSession()
        sess.requestPTY("xterm-256color", columns, rows, 0, 0, null)
        sess.startShell()
        session = sess
    }

    fun resizeWindow(columns: Int, rows: Int) {
        session?.resizePTY(columns, rows, 0, 0)
    }

    /** Send an SSH_MSG_IGNORE packet to keep NAT/firewall mappings warm. */
    fun sendKeepalive() {
        connection?.sendIgnorePacket()
    }

    fun disconnect() {
        isConnected = false
        try { session?.close() } catch (_: Exception) {}
        try { connection?.close() } catch (_: Exception) {}
        session = null
        connection = null
    }

    companion object {
        private const val TAG = "SshSession"
    }
}

class SshAuthenticationException(message: String) : Exception(message)
