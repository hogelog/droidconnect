package org.hogel.pocketssh.ssh

import com.trilead.ssh2.Connection
import com.trilead.ssh2.SCPClient
import com.trilead.ssh2.ServerHostKeyVerifier
import com.trilead.ssh2.Session
import com.trilead.ssh2.auth.SignatureProxy
import java.io.InputStream
import java.io.OutputStream
import org.hogel.pocketssh.tmux.TmuxTitle

/** SSH connection backed by sshlib (Trilead SSH2). */
class SshSession(
    private val host: String,
    private val port: Int,
    private val username: String,
    private val signatureProxy: SignatureProxy,
    private val hostKeyVerifier: ServerHostKeyVerifier,
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
        conn.connect(hostKeyVerifier, 10_000, 10_000)

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
            // Configure tmux to broadcast the active pane's foreground command
            // plus the session's window list as the OSC window title, so the
            // client can render a native tab strip without a second control
            // channel. See [TmuxTitle] for the wire format. Chained into one
            // tmux invocation so the options apply to the same server we then
            // attach to.
            append("tmux set-option -g set-titles on \\; ")
            append("set-option -g set-titles-string \"")
            append(TmuxTitle.TMUX_TITLE_FORMAT)
            append("\" \\; ")
            // Status bar is replaced by the native TabLayout, so hide tmux's
            // own one. Users with a customized status will notice this.
            append("set-option -g status off \\; ")
            // Force re-evaluation of set-titles-string when the window list
            // changes. tmux re-emits the title on redraw, but window add /
            // remove / rename do not by themselves invalidate the cached
            // title of the active pane, so the new window list would not
            // reach the client until the next focus change without these
            // hooks. `-a` appends to any user-defined hook of the same name.
            append("set-hook -ag window-linked 'refresh-client' \\; ")
            append("set-hook -ag window-unlinked 'refresh-client' \\; ")
            append("set-hook -ag window-renamed 'refresh-client' \\; ")
            append("set-hook -ag session-window-changed 'refresh-client' \\; ")
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
        private const val TMUX_SESSION_NAME = "pocketssh"
    }
}

class SshAuthenticationException(message: String) : Exception(message)
