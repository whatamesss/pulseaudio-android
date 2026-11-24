package us.foxtek.pulseserver.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import us.foxtek.pulseserver.R
import us.foxtek.pulseserver.mpd.MpdOutputController
import us.foxtek.pulseserver.proto.PAConst
import us.foxtek.pulseserver.proto.PulseClientHandler
import us.foxtek.pulseserver.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections

private const val TAG             = "PulseAudioService"
private const val NOTIF_CHANNEL   = "pulse_server"
private const val NOTIF_ID        = 1
private const val DEFAULT_PORT    = PAConst.DEFAULT_PORT

sealed class ServerState {
    object Idle      : ServerState()
    object Starting  : ServerState()
    data class Running(val port: Int, val ip: String) : ServerState()
    data class ClientConnected(val ip: String)        : ServerState()
    data class Error(val message: String)             : ServerState()
}

class PulseAudioService : LifecycleService() {

    inner class LocalBinder : Binder() {
        val service get() = this@PulseAudioService
    }

    private val binder = LocalBinder()

    // Observable state for the UI
    var state: ServerState = ServerState.Idle
        private set(v) { field = v; stateListener?.invoke(v) }

    var stateListener: ((ServerState) -> Unit)? = null

    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private val micLock = Mutex()
    private var activePort: Int = DEFAULT_PORT
    private val clientSockets = Collections.synchronizedSet(mutableSetOf<Socket>())
    private val mpdOutput by lazy { MpdOutputController(this, lifecycleScope) }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> {
                val port = intent.getIntExtra(EXTRA_PORT, DEFAULT_PORT)
                startServer(port)
            }
            ACTION_STOP  -> stopServer()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }

    // ─── Server control ───────────────────────────────────────────────────────

    fun startServer(port: Int = DEFAULT_PORT) {
        if (serverJob?.isActive == true) return
        activePort = port
        state = ServerState.Starting
        mpdOutput.enable()

        startForeground(NOTIF_ID, buildNotification("Starting…"))

        serverJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                val ss = ServerSocket(activePort)
                serverSocket = ss
                val ip = getLocalIpAddress()
                state = ServerState.Running(activePort, ip)
                updateNotification("Listening on $ip:$activePort")
                Log.i(TAG, "Server listening on $ip:$activePort")

                while (isActive) {
                    val client: Socket = try {
                        ss.accept()
                    } catch (e: Exception) {
                        if (isActive) Log.w(TAG, "Accept error", e)
                        break
                    }
                    client.tcpNoDelay = true
                    clientSockets.add(client)
                    Log.i(TAG, "Client connected: ${client.inetAddress.hostAddress}")

                    // Each connection gets its own independent child scope so
                    // a new connection never cancels an existing record/playback stream.
                    launch {
                        try {
                            val handler = PulseClientHandler(client, ::onStatus, this, micLock)
                            withContext(Dispatchers.IO) { handler.run() }
                        } finally {
                            clientSockets.remove(client)
                            runCatching { client.close() }
                        }
                    }
                }
            } catch (e: Exception) {
                if (isActive) {
                    Log.e(TAG, "Server error", e)
                    state = ServerState.Error(e.message ?: "Unknown error")
                    updateNotification("Error: ${e.message}")
                }
            }
        }
    }

    fun stopServer() {
        mpdOutput.disable()
        serverJob?.cancel()
        serverJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        synchronized(clientSockets) {
            clientSockets.forEach { runCatching { it.close() } }
            clientSockets.clear()
        }
        state = ServerState.Idle
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun onStatus(msg: String) {
        Log.i(TAG, msg)
        when {
            msg.startsWith("Client connected") -> {
                val ip = msg.substringAfter(": ")
                state = ServerState.ClientConnected(ip)
                updateNotification("Connected: $ip")
            }
            msg.startsWith("Client disconnected") -> {
                val runningState = serverSocket?.let {
                    ServerState.Running(activePort, getLocalIpAddress())
                } ?: ServerState.Idle
                state = runningState
                updateNotification(
                    if (runningState is ServerState.Running)
                        "Listening on ${runningState.ip}:${runningState.port}"
                    else "Idle"
                )
            }
            else -> updateNotification(msg)
        }
    }

    // ─── Notification ─────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val mgr = getSystemService(NotificationManager::class.java)
        val ch  = NotificationChannel(
            NOTIF_CHANNEL,
            "PulseAudio Server",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "PulseAudio server foreground service" }
        mgr.createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, NOTIF_CHANNEL)
            .setContentTitle("PulseAudio Server")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.notify(NOTIF_ID, buildNotification(text))
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun getLocalIpAddress(): String {
        return try {
            java.net.NetworkInterface.getNetworkInterfaces()
                .asSequence()
                .flatMap { it.inetAddresses.asSequence() }
                .firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }
                ?.hostAddress ?: "0.0.0.0"
        } catch (_: Exception) { "0.0.0.0" }
    }

    companion object {
        const val ACTION_START = "us.foxtek.pulseserver.START"
        const val ACTION_STOP  = "us.foxtek.pulseserver.STOP"
        const val EXTRA_PORT   = "us.foxtek.pulseserver.EXTRA_PORT"
    }
}
