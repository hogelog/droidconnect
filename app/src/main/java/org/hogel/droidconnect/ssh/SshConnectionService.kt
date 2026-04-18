package org.hogel.droidconnect.ssh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import org.hogel.droidconnect.R
import org.hogel.droidconnect.ui.TerminalActivity
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Foreground service that owns the SSH session so the connection survives
 * the UI being backgrounded or destroyed. The bound activity attaches an
 * output listener; while no listener is attached, output bytes accumulate
 * in a bounded buffer and are replayed on the next attach.
 */
class SshConnectionService : Service() {

    enum class State { IDLE, CONNECTING, CONNECTED, FAILED, DISCONNECTED }

    interface StatusListener {
        fun onSshConnected() {}
        fun onSshDisconnected(error: Throwable?) {}
    }

    inner class LocalBinder : Binder() {
        fun getService(): SshConnectionService = this@SshConnectionService
    }

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    var state: State = State.IDLE
        private set

    @Volatile
    var lastError: Throwable? = null
        private set

    private var session: SshSession? = null
    private var readThread: Thread? = null
    private var connectionLabel: String = ""

    private val outputLock = Any()
    private val pendingOutput = ByteArrayOutputStream()
    private var outputListener: ((ByteArray) -> Unit)? = null

    private val statusListeners = CopyOnWriteArrayList<StatusListener>()

    private val sshWriteExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ssh-write").apply { isDaemon = true }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(getString(R.string.notification_text_connecting)),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        return START_NOT_STICKY
    }

    /**
     * Start a new SSH connection. No-op if a connection is already running.
     */
    fun connect(params: ConnectionParams, columns: Int, rows: Int) {
        synchronized(this) {
            if (state == State.CONNECTING || state == State.CONNECTED) return
            state = State.CONNECTING
            lastError = null
            connectionLabel = "${params.username}@${params.host}:${params.port}"
            updateNotification(getString(R.string.notification_text_connecting))
            readThread = thread(name = "ssh-read") {
                runReadLoop(params, columns, rows)
            }
        }
    }

    private fun runReadLoop(params: ConnectionParams, columns: Int, rows: Int) {
        var caught: Throwable? = null
        try {
            val ssh = SshSession(params.host, params.port, params.username, params.privateKey)
            ssh.connect()
            ssh.openShell(columns.coerceAtLeast(1), rows.coerceAtLeast(1))
            session = ssh
            state = State.CONNECTED
            updateNotification(getString(R.string.notification_text_connected, connectionLabel))
            notifyStatus { it.onSshConnected() }

            val buffer = ByteArray(8192)
            val input = ssh.stdout
            while (true) {
                val n = input.read(buffer)
                if (n == -1) break
                deliverOutput(buffer.copyOf(n))
            }
        } catch (e: Throwable) {
            caught = e
            Log.e(TAG, "SSH session error", e)
        } finally {
            runCatching { session?.disconnect() }
            session = null
            lastError = caught
            state = if (caught != null) State.FAILED else State.DISCONNECTED
            notifyStatus { it.onSshDisconnected(caught) }
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun deliverOutput(data: ByteArray) {
        val current: ((ByteArray) -> Unit)?
        synchronized(outputLock) {
            current = outputListener
            if (current == null) {
                pendingOutput.write(data)
                if (pendingOutput.size() > MAX_BUFFER_BYTES) {
                    val all = pendingOutput.toByteArray()
                    val keep = all.copyOfRange(all.size - MAX_BUFFER_BYTES, all.size)
                    pendingOutput.reset()
                    pendingOutput.write(keep)
                }
            }
        }
        current?.let { listener ->
            mainHandler.post { listener(data) }
        }
    }

    /**
     * Register an output listener. Any output buffered while no listener was
     * attached is delivered on the main thread before this returns.
     */
    fun attachOutputListener(listener: (ByteArray) -> Unit) {
        val backlog: ByteArray?
        synchronized(outputLock) {
            backlog = if (pendingOutput.size() > 0) pendingOutput.toByteArray() else null
            pendingOutput.reset()
            outputListener = listener
        }
        backlog?.let { mainHandler.post { listener(it) } }
    }

    fun detachOutputListener() {
        synchronized(outputLock) { outputListener = null }
    }

    fun addStatusListener(listener: StatusListener) {
        statusListeners += listener
        when (state) {
            State.CONNECTED -> mainHandler.post { listener.onSshConnected() }
            State.FAILED, State.DISCONNECTED ->
                mainHandler.post { listener.onSshDisconnected(lastError) }
            else -> Unit
        }
    }

    fun removeStatusListener(listener: StatusListener) {
        statusListeners.remove(listener)
    }

    private fun notifyStatus(action: (StatusListener) -> Unit) {
        mainHandler.post { statusListeners.forEach(action) }
    }

    fun writeToSsh(data: ByteArray) {
        val out = session?.stdin ?: return
        sshWriteExecutor.execute {
            try {
                out.write(data)
                out.flush()
            } catch (e: Exception) {
                Log.e(TAG, "SSH write error", e)
            }
        }
    }

    fun resizeWindow(columns: Int, rows: Int) {
        session?.resizeWindow(columns, rows)
    }

    /** Tear down the SSH connection; the service will stop itself. */
    fun shutdown() {
        runCatching { session?.disconnect() }
        // The read loop will break out of read() and run its finally block.
    }

    override fun onDestroy() {
        sshWriteExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, TerminalActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP,
            ),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, SshConnectionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_terminal)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.notification_action_disconnect), stopIntent)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    data class ConnectionParams(
        val host: String,
        val port: Int,
        val username: String,
        val privateKey: CharArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ConnectionParams) return false
            return host == other.host &&
                port == other.port &&
                username == other.username &&
                privateKey.contentEquals(other.privateKey)
        }

        override fun hashCode(): Int {
            var result = host.hashCode()
            result = 31 * result + port
            result = 31 * result + username.hashCode()
            result = 31 * result + privateKey.contentHashCode()
            return result
        }
    }

    companion object {
        const val ACTION_STOP = "org.hogel.droidconnect.action.STOP_CONNECTION"
        private const val CHANNEL_ID = "ssh_connection"
        private const val NOTIFICATION_ID = 1001
        private const val MAX_BUFFER_BYTES = 256 * 1024
        private const val TAG = "SshConnectionService"
    }
}
