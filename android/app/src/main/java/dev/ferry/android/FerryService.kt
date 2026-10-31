package dev.ferry.android

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.ferry.core.FerryNode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.launch
import java.io.File

/**
 * Owns the one [FerryNode] while it runs: start → USB bridge + NSD presence → ongoing
 * notification. Started only from a visible activity (Android 12+ forbids background FGS
 * starts; the Stop action reaches the already-running service).
 */
class FerryService : Service() {
    private var job: Job? = null
    private var presence: NsdPresence? = null
    private var notification: Notification? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        goForeground(ongoing("Ferry is starting", ""))
        // Main thread: startup bookkeeping can't interleave with onDestroy. Nothing here blocks.
        job = Ferry.scope.launch(Dispatchers.Main) { run() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
        } else {
            // Every startForegroundService() call must be answered with startForeground().
            notification?.let(::goForeground)
        }
        // shortcut: not sticky, a killed process stays stopped until the app is opened again;
        // a CDM association (later phase) allows restarting from the background.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        job?.cancel()
        presence?.stop()
        presence = null
        Ferry.usbListening.value = false
        Ferry.nearby.value = emptySet()
        Ferry.status.value = "Stopped"
        Ferry.node.getAndUpdate { null }?.let { node ->
            Ferry.shutdownJob = Ferry.scope.launch {
                node.shutdown()
                node.close()
            }
        }
        super.onDestroy()
    }

    private suspend fun run() {
        Ferry.shutdownJob?.join()
        Ferry.status.value = "Starting…"
        val data = File(filesDir, "ferry").apply { mkdirs() }
        val incoming = Ferry.incomingDir(this).apply { mkdirs() }
        val node = try {
            FerryNode.start(data.path, deviceName(), incoming.path, Bridge(applicationContext))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "node start failed", e)
            Ferry.status.value = "Failed to start: ${e.text()}"
            return
        }
        Ferry.node.value = node
        Ferry.status.value = "Running"
        Ferry.refreshPeers()
        presence = NsdPresence(this, node).also { it.start(Ferry.scope) }
        try {
            node.usbServe()
            Ferry.usbListening.value = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Wi-Fi still works without it.
            Log.w(TAG, "USB bridge unavailable", e)
        }
        combine(Ferry.peers, Ferry.nearby) { peers, near -> peers.filter { it.id in near }.map { it.name } }
            .collect { near ->
                val n = if (near.isEmpty()) {
                    ongoing("Ready to receive", if (Ferry.peers.value.isEmpty()) "Open Ferry to pair a Mac" else "Waiting for a paired Mac on this network")
                } else {
                    ongoing("Ferry is connected", near.joinToString())
                }
                Notifs.post(this, Notifs.ID_ONGOING, n)
            }
    }

    private fun goForeground(n: Notification) {
        notification = n
        ServiceCompat.startForeground(this, Notifs.ID_ONGOING, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
    }

    private fun ongoing(title: String, text: String): Notification {
        val stop = PendingIntent.getService(
            this,
            0,
            Intent(this, FerryService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, Notifs.CH_SERVICE)
            .setSmallIcon(R.drawable.ic_ferry)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(Notifs.openApp(this))
            .addAction(0, "Stop", stop)
            .build()
            .also { notification = it }
    }

    private fun deviceName(): String =
        Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() } ?: Build.MODEL

    companion object {
        private const val ACTION_STOP = "dev.ferry.android.STOP"

        fun start(ctx: Context) = ContextCompat.startForegroundService(ctx, Intent(ctx, FerryService::class.java))
    }
}
