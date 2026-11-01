package dev.ferry.android

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.LinkProperties
import android.net.Network
import android.net.ConnectivityManager
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
import dev.ferry.android.screen.Fsp
import dev.ferry.android.screen.Screen
import dev.ferry.core.FerryNode
import dev.ferry.core.FfiEvent
import dev.ferry.core.FfiPeer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.launch
import java.io.File

/**
 * Owns the one [FerryNode] while it runs: start → USB bridge + NSD presence + Bluetooth scan →
 * ongoing notification. Started from a visible activity, or from a Bluetooth scan hit in the
 * background (Android 12+ allows that only with a CDM association, see [Cdm]).
 */
class FerryService : Service() {
    private var job: Job? = null
    private var presence: NsdPresence? = null
    private var notification: Notification? = null
    private var battery: FfiEvent.Battery? = null
    private var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE

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

    private var checkIn: Job? = null

    /** Turning Bluetooth off drops our PendingIntent scan: re-register when it's back on. */
    private val bluetoothChanged = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, i: Intent) {
            if (i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) != BluetoothAdapter.STATE_ON) return
            Ble.adapterReset()
            Ble.sync(ctx)
        }
    }

    /** Joined Wi-Fi or got a new address: tell each Mac where we are now, so it can reach us
     * without the cable (networks that block mDNS give it no other way to find out). */
    private val networkChanged = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = scheduleCheckIn()
        override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) = scheduleCheckIn()
    }

    private fun scheduleCheckIn() {
        checkIn?.cancel()
        checkIn = Ferry.scope.launch {
            delay(3_000) // let addresses settle; collapses bursts of callbacks
            val node = Ferry.node.value ?: return@launch
            Ferry.peers.value.forEach { p ->
                catching { node.checkIn(p.id) }.onFailure { Log.w(TAG, "check-in with ${p.name}: ${it.message}") }
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
        instance = this
        goForeground(ongoing("Ferry is starting", ""))
        // System broadcast: NOT_EXPORTED is fine (and required for runtime receivers on 34+).
        ContextCompat.registerReceiver(this, batteryChanged, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(this, bluetoothChanged, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(this, Dnd.changed, IntentFilter(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkChanged)
        Calls.sync(this)
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
        // Not sticky: a killed process stays stopped until the app is opened again or (with a
        // CDM association) a Bluetooth scan hit restarts it.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        stopNode("Ferry stopped on the phone")
        unregisterReceiver(batteryChanged)
        unregisterReceiver(bluetoothChanged)
        unregisterReceiver(Dnd.changed)
        Calls.stop(this)
        // Only Stop ends the service, and Stop means stop: no Bluetooth wake-ups until the app is opened again.
        Ble.stopScan(this)
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkChanged) }
        checkIn?.cancel()
        Ringer.stop(this)
        super.onDestroy()
    }

    /** Applies a saved node setting (the internet relay): same node, shut down and started again. Main thread. */
    fun restart() {
        stopNode("Ferry restarted on the phone")
        job = Ferry.scope.launch(Dispatchers.Main) { run() } // run() waits for the old node's shutdown
    }

    /** Everything tied to the running node; the service's receivers and Bluetooth scan stay. */
    private fun stopNode(why: String) {
        Screen.stop(Fsp.END_STOPPED, why)
        job?.cancel()
        Ble.stopBridge()
        presence?.stop()
        presence = null
        Ferry.usbListening.value = false
        Ferry.nearby.value = emptySet()
        Ferry.status.value = "Stopped"
        Ferry.node.getAndUpdate { null }?.let { node ->
            Ferry.shutdownJob = Ferry.scope.launch { node.use { it.shutdown() } }
        }
    }

    private suspend fun run() {
        Ferry.shutdownJob?.join()
        Ferry.status.value = "Starting…"
        val data = File(filesDir, "ferry").apply { mkdirs() }
        val incoming = Ferry.incomingDir(this).apply { mkdirs() }
        val node = catching { FerryNode.start(data.path, deviceName(), incoming.path, Bridge(applicationContext)) }.getOrElse { e ->
            Log.e(TAG, "node start failed", e)
            Ferry.status.value = "Failed to start: ${e.text()}"
            return
        }
        Ferry.node.value = node
        Ferry.status.value = "Running"
        Ferry.refreshPeers()
        presence = NsdPresence(this, node).also { it.start(Ferry.scope) }
        catching { node.usbServe() }
            .onSuccess { Ferry.usbListening.value = true }
            .onFailure { Log.w(TAG, "USB bridge unavailable", it) } // Wi-Fi still works without it.
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
        combine(Ferry.peers, Ferry.nearby, Grants.on, Ferry.ble) { peers, near, grants, ble ->
            Shown(peers, peers.filter { it.id in near }.map { it.name }, grants, peers.firstOrNull { it.id == ble }?.name)
        }
            .collect { (peers, near, grants, ble) ->
                Ble.sync(this@FerryService) // pairing or forgetting the last Mac starts/stops the presence scan
                val n = when {
                    near.isNotEmpty() -> ongoing("Ferry is connected", near.joinToString(), grants)
                    ble != null -> ongoing("Connected over Bluetooth", ble, grants)
                    else -> ongoing("Ready to receive", if (peers.isEmpty()) "Open Ferry to pair a Mac" else "Waiting for a paired Mac on this network", grants)
                }
                Notifs.post(this, Notifs.ID_ONGOING, n)
                sendBattery() // a Mac appeared or the grant changed: tell it now, not in 5 min
            }
    }

    private data class Shown(val peers: List<FfiPeer>, val near: List<String>, val grants: Set<Grant>, val ble: String?)

    private fun goForeground(n: Notification) {
        notification = n
        ServiceCompat.startForeground(this, Notifs.ID_ONGOING, n, types)
    }

    /** Adds/drops the mediaProjection type; add it after consent and before getMediaProjection (Android 14+). */
    fun projecting(on: Boolean) {
        types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or (if (on) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0)
        notification?.let(::goForeground)
    }

    /** The visible indicator of what's shared: the active grants are always listed here. */
    private fun ongoing(title: String, text: String, grants: Set<Grant> = Grants.on.value): Notification {
        val stop = PendingIntent.getService(this, 0, Intent(this, FerryService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val sharing = if (grants.isEmpty()) "Sharing nothing" else "Sharing: " + grants.sorted().joinToString { it.short }
        return Notifs.builder(this, Notifs.CH_SERVICE, title)
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

        @Volatile var instance: FerryService? = null

        fun start(ctx: Context) = ContextCompat.startForegroundService(ctx, Intent(ctx, FerryService::class.java))
    }
}
