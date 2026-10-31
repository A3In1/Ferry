package dev.ferry.android

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.ferry.core.FerryNode
import dev.ferry.core.FfiEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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
    private var battery: FfiEvent.Battery? = null

    private val batteryChanged = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, i: Intent) {
            val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level < 0 || scale <= 0) return
            val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            val now = FfiEvent.Battery((level * 100 / scale).toUInt(), charging)
            if (now != battery) {
                battery = now
                sendBattery()
            }
        }
    }

    private fun sendBattery() {
        val b = battery
        if (b != null && Grant.BATTERY in Grants) Outbox.broadcast(b)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        goForeground(ongoing("Ferry is starting", ""))
        // System broadcast: NOT_EXPORTED is fine (and required for runtime receivers on 34+).
        ContextCompat.registerReceiver(this, batteryChanged, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
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
        unregisterReceiver(batteryChanged)
        Ringer.stop(this)
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
        // Re-send now and then so a Mac that just connected learns the level.
        coroutineScope {
            launch {
                while (true) {
                    delay(5 * 60_000)
                    sendBattery()
                }
            }
            showStatus()
        }
    }

    private suspend fun showStatus() {
        combine(Ferry.peers, Ferry.nearby, Grants.on) { peers, near, grants -> Triple(peers, peers.filter { it.id in near }.map { it.name }, grants) }
            .collect { (peers, near, grants) ->
                val n = if (near.isEmpty()) {
                    ongoing("Ready to receive", if (peers.isEmpty()) "Open Ferry to pair a Mac" else "Waiting for a paired Mac on this network", grants)
                } else {
                    ongoing("Ferry is connected", near.joinToString(), grants)
                }
                Notifs.post(this, Notifs.ID_ONGOING, n)
                sendBattery() // a Mac appeared or the grant changed: tell it now, not in 5 min
            }
    }

    private fun goForeground(n: Notification) {
        notification = n
        ServiceCompat.startForeground(this, Notifs.ID_ONGOING, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
    }

    /** The visible indicator of what's shared: the active grants are always listed here. */
    private fun ongoing(title: String, text: String, grants: Set<Grant> = Grants.on.value): Notification {
        val stop = PendingIntent.getService(
            this,
            0,
            Intent(this, FerryService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val sharing = if (grants.isEmpty()) "Sharing nothing" else "Sharing: " + grants.sorted().joinToString { it.short }
        return NotificationCompat.Builder(this, Notifs.CH_SERVICE)
            .setSmallIcon(R.drawable.ic_ferry)
            .setContentTitle(title)
            .setContentText(if (text.isEmpty()) sharing else "$text · $sharing")
            .setStyle(NotificationCompat.BigTextStyle().bigText(listOf(text, sharing).filter { it.isNotEmpty() }.joinToString("\n")))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(Notifs.openApp(this))
            .addAction(0, "Stop", stop)
            .apply { if (Grant.CLIPBOARD in grants) addAction(0, "Send clipboard", Clip.pendingIntent(this@FerryService)) }
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
