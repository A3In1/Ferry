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
import android.net.NetworkCapabilities
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
import kotlinx.coroutines.flow.distinctUntilChanged
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
            val cm = getSystemService(ConnectivityManager::class.java)
            val onWifi = cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            Ferry.peers.value.forEach { p ->
                if (onWifi && Ferry.ble.value == p.id) return@forEach Ble.tryWifi(this@FerryService, node, p.id)
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
        goForeground(ongoing("Starting Ferry…", ""))
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
        Screen.stop(Fsp.END_STOPPED, "Ferry stopped on the phone")
        job?.cancel()
        unregisterReceiver(batteryChanged)
        unregisterReceiver(bluetoothChanged)
        unregisterReceiver(Dnd.changed)
        Calls.stop(this)
        // Only Stop ends the service, and Stop means stop: no Bluetooth wake-ups until the app is opened again.
        Ble.stopScan(this)
        Ble.stopBridge()
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkChanged) }
        checkIn?.cancel()
        Ringer.stop(this)
        presence?.stop()
        presence = null
        Ferry.usbListening.value = false
        Ferry.nearby.value = emptySet()
        Ferry.usb.value = emptySet()
        Transfers.clear(this)
        Ferry.status.value = STOPPED
        Ferry.node.getAndUpdate { null }?.let { node ->
            Ferry.shutdownJob = Ferry.scope.launch { node.use { it.shutdown() } }
        }
        super.onDestroy()
    }

    private suspend fun run() {
        Ferry.shutdownJob?.join()
        Ferry.status.value = STARTING
        val data = File(filesDir, "ferry").apply { mkdirs() }
        val incoming = Ferry.incomingDir(this).apply { mkdirs() }
        val node = catching { FerryNode.start(data.path, deviceName(), incoming.path, Bridge(applicationContext)) }.getOrElse { e ->
            Log.e(TAG, "node start failed", e)
            Ferry.status.value = FAILED + "Close Ferry and open it again. If it keeps happening, restart the phone. (${e.text().removePrefix("reason=")})"
            return
        }
        Ferry.node.value = node
        Ferry.status.value = RUNNING
        Ferry.refreshPeers()
        presence = NsdPresence(this, node).also { it.start(Ferry.scope) }
        val usbUp = catching { node.usbServe() }
            .onSuccess { Ferry.usbListening.value = true }
            .onFailure { Log.w(TAG, "USB bridge unavailable", it) } // Wi-Fi still works without it.
            .isSuccess
        // Re-send now and then so a Mac that just connected learns the level.
        coroutineScope {
            launch {
                while (true) {
                    delay(5 * 60_000)
                    sendBattery()
                }
            }
            // The core has no USB link events; a cheap poll keeps "Connected over USB" honest. Off the
            // main thread (it's a blocking core call), and only when the USB bridge is listening at all.
            if (usbUp) launch(Dispatchers.Default) {
                while (true) {
                    // Bluetooth bridges are listed too ("ble:…"); they're Ferry.ble, not USB.
                    Ferry.usb.value = catching { node.usbLinks().filterNot { it.serial.startsWith("ble:") }.map { it.peerId }.toSet() }.getOrDefault(emptySet())
                    delay(3_000)
                }
            }
            showStatus()
        }
    }

    private suspend fun showStatus() {
        combine(Ferry.peers, Ferry.nearby, Grants.on, Ferry.ble, Ferry.usb) { peers, near, grants, ble, usb ->
            Shown(peers, peers.associate { it.name to Ferry.links(it.id, near, usb, ble) }.filterValues { it.isNotEmpty() }, grants)
        }
            .distinctUntilChanged()
            .collect { (peers, linked, grants) ->
                Ble.sync(this@FerryService) // pairing or forgetting the last Mac starts/stops the presence scan
                val via = joinAnd(linked.values.flatten().distinct())
                val n = when {
                    linked.isNotEmpty() -> ongoing("Connected to ${joinAnd(linked.keys.toList())}", "Over $via", grants)
                    peers.isEmpty() -> ongoing("No Mac paired yet", "Open Ferry to pair with your Mac", grants)
                    else -> ongoing("Waiting for your Mac", "Connects over the same Wi-Fi, a USB cable or Bluetooth", grants)
                }
                Notifs.post(this, Notifs.ID_ONGOING, n)
                sendBattery() // a Mac appeared or the grant changed: tell it now, not in 5 min
            }
    }

    private data class Shown(val peers: List<FfiPeer>, val linked: Map<String, List<String>>, val grants: Set<Grant>)

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
        val sharing = if (grants.isEmpty()) "Nothing shared" else "Sharing " + grants.sorted().joinToString { it.short }
        return Notifs.builder(this, Notifs.CH_SERVICE, title)
            .setContentText(if (text.isEmpty()) sharing else "$text · $sharing")
            .setStyle(NotificationCompat.BigTextStyle().bigText(listOf(text, sharing).filter { it.isNotEmpty() }.joinToString("\n")))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(Notifs.openApp(this))
            .addAction(0, "Stop Ferry", stop)
            .apply { if (Grant.CLIPBOARD in grants) addAction(0, "Send clipboard", Clip.pendingIntent(this@FerryService)) }
            .build()
            .also { notification = it }
    }

    private fun deviceName(): String =
        Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() } ?: Build.MODEL

    companion object {
        private const val ACTION_STOP = "dev.ferry.android.STOP"

        @Volatile var instance: FerryService? = null

        // Ferry.status values the UI tells apart.
        const val STARTING = "Starting…"
        const val RUNNING = "Running"
        const val STOPPED = "Stopped"
        const val FAILED = "Couldn't start: "

        fun start(ctx: Context) = ContextCompat.startForegroundService(ctx, Intent(ctx, FerryService::class.java))
    }
}
