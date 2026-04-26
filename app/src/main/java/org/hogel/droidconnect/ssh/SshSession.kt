package org.hogel.droidconnect.ssh

import com.trilead.ssh2.Connection
import com.trilead.ssh2.ServerHostKeyVerifier
import com.trilead.ssh2.Session
import com.trilead.ssh2.auth.SignatureProxy
import java.io.InputStream
import java.io.OutputStream

/** SSH connection backed by sshlib (Trilead SSH2). */
class SshSession(
    private val host: String,
    private val port: Int,
    private val username: String,
    private val signatureProxy: SignatureProxy,
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

        val authenticated = conn.authenticateWithPublicKey(username, signatureProxy)
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
     * With no [initialCommand] and no [useTmux], starts a normal login shell.
     *
     * Otherwise runs a command via `bash -lc` on the remote so ~/.profile /
     * ~/.bashrc are sourced (PATH, aliases, nvm, etc. are available). When
     * [useTmux] is true, the command is wrapped in
     * `tmux new-session -A -s <name>` so the session survives disconnects:
     * reconnecting attaches to the existing session, or creates a new one if
     * none exists. When the session command exits, the SSH channel closes.
     */
    fun openShell(
        columns: Int,
        rows: Int,
        initialCommand: String? = null,
        useTmux: Boolean = false,
    ) {
        val conn = connection ?: throw IllegalStateException("Not connected")
        val sess = conn.openSession()
        sess.requestPTY("xterm-256color", columns, rows, 0, 0, null)
        val remoteCommand = buildRemoteCommand(initialCommand, useTmux)
        if (remoteCommand == null) {
            sess.startShell()
        } else {
            sess.execCommand(remoteCommand)
        }
        session = sess
    }

    private fun buildRemoteCommand(initialCommand: String?, useTmux: Boolean): String? {
        val trimmed = initialCommand?.takeIf { it.isNotBlank() }
        if (!useTmux && trimmed == null) return null

        val inner = if (useTmux) {
            buildString {
                // Configure tmux to broadcast the active pane's foreground
                // command as the OSC window title so the client can detect the
                // running app and surface app-specific shortcut keys. Chained
                // into one tmux invocation so the options apply to the same
                // server we then attach to.
                append("tmux set-option -g set-titles on \\; ")
                append("set-option -g set-titles-string \"#{pane_current_command}\" \\; ")
                append("new-session -A -s ")
                append(TMUX_SESSION_NAME)
                if (trimmed != null) {
                    append(' ')
                    append(shellQuote(trimmed))
                }
            }
        } else {
            trimmed!!
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
