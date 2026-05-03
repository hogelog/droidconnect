package org.hogel.droidconnect.ssh

import com.trilead.ssh2.Connection
import com.trilead.ssh2.SCPClient
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

    /**
     * Open an interactive session.
     *
     * When [useTmux] is false, starts a normal login shell.
     *
     * When [useTmux] is true, runs `tmux new-session -A -s <name>` via
     * `bash -lc` so ~/.profile / ~/.bashrc are sourced (PATH, aliases, nvm,
     * etc. are available) and the session survives disconnects: reconnecting
     * attaches to the existing session, or creates a new one if none exists.
     * When the session command exits, the SSH channel closes.
     */
    fun openShell(
        columns: Int,
        rows: Int,
        useTmux: Boolean = false,
    ) {
        val conn = connection ?: throw IllegalStateException("Not connected")
        val sess = conn.openSession()
        sess.requestPTY("xterm-256color", columns, rows, 0, 0, null)
        val remoteCommand = buildRemoteCommand(useTmux)
        if (remoteCommand == null) {
            sess.startShell()
        } else {
            sess.execCommand(remoteCommand)
        }
        session = sess
    }

    private fun buildRemoteCommand(useTmux: Boolean): String? {
        if (!useTmux) return null

        val inner = buildString {
            // Configure tmux to broadcast the active pane's foreground
            // command as the OSC window title so the client can detect the
            // running app and surface app-specific shortcut keys. Chained
            // into one tmux invocation so the options apply to the same
            // server we then attach to.
            append("tmux set-option -g set-titles on \\; ")
            append("set-option -g set-titles-string \"#{pane_current_command}\" \\; ")
            append("new-session -A -s ")
            append(TMUX_SESSION_NAME)
        }
        return "bash -lc ${shellQuote(inner)}"
    }

    /** Wrap [value] in single quotes for safe use inside a POSIX shell command. */
    private fun shellQuote(value: String): String {
        val escaped = value.replace("'", "'\\''")
        return "'$escaped'"
    }

    fun resizeWindow(columns: Int, rows: Int) {
        session?.resizePTY(columns, rows, 0, 0)
    }

    /**
     * Upload [bytes] to [remoteDir] on the remote host as [filename] via SCP.
     * Opens its own SSH session over the existing [Connection], so it does
     * not interfere with the interactive shell session. Blocking; call from
     * a background thread.
     */
    fun uploadBytes(bytes: ByteArray, filename: String, remoteDir: String) {
        val conn = connection ?: throw IllegalStateException("Not connected")
        SCPClient(conn).put(bytes, filename, remoteDir)
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
        private const val TMUX_SESSION_NAME = "droidconnect"
    }
}

class SshAuthenticationException(message: String) : Exception(message)
