package dev.ferry.android

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi
import androidx.core.content.IntentCompat
import dev.ferry.core.ByteSink
import dev.ferry.core.FerryNode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

const val BLE_TAG = "FerryBle"

/** Fixed GATT service on the Mac that holds the L2CAP PSM characteristic. */
val BLE_SERVICE: UUID = UUID.fromString("f377e501-0000-1000-8000-00805f9b34fb")
/** Apple's standard L2CAP PSM characteristic (CBUUIDL2CAPPSMCharacteristicString). */
val BLE_PSM_CHAR: UUID = UUID.fromString("ABDD3056-28FA-441D-A470-55A75A52553A")
/** Scan-filter mask: only the fixed first 4 bytes of the Mac's rotating presence UUID must match. */
val BLE_MASK: UUID = UUID(0xffffffffL shl 32, 0)

/** `f377e501` (the core's blePrefix()) → f377e501-0000-0000-0000-000000000000. */
fun blePrefixUuid(hex: String): UUID {
    require(hex.length == 8) { "BLE prefix must be 4 bytes of hex: $hex" }
    return UUID(hex.toLong(16) shl 32, 0)
}

/** The PSM characteristic value: UInt16 little-endian; null if malformed or 0. */
fun parsePsm(v: ByteArray?): Int? {
    if (v == null || v.size != 2) return null
    return ((v[0].toInt() and 0xff) or ((v[1].toInt() and 0xff) shl 8)).takeIf { it != 0 }
}

/** Per-key "not before" times: [take] succeeds at most once per window. */
class Cooldown {
    private val until = HashMap<String, Long>()

    @Synchronized
    fun take(key: String, now: Long, ms: Long): Boolean {
        if (now < (until[key] ?: Long.MIN_VALUE)) return false
        until[key] = now + ms
        return true
    }

    @Synchronized
    fun hold(key: String, now: Long, ms: Long) {
        until[key] = now + ms
    }
}

/**
 * Bluetooth (API 31+ only): a battery-friendly PendingIntent scan for paired Macs' presence
 * UUIDs, and an L2CAP byte-stream bridge carrying the normal Ferry session when Wi-Fi/USB fail.
 * The Mac is the peripheral; the phone is the central.
 */
@SuppressLint("MissingPermission") // every entry point checks allowed() first; SecurityException is caught
object Ble {
    private const val RETRY_MS = 60_000L // after a bridge attempt
    private const val CHECKED_IN_MS = 5 * 60_000L // Wi-Fi/USB worked: don't re-check every scan hit
    @SuppressLint("InlinedApi") // plain strings, only requested on 31+
    val PERMISSIONS = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)

    @get:ChecksSdkIntAtLeast(api = 31)
    val supported get() = Build.VERSION.SDK_INT >= 31

    @ChecksSdkIntAtLeast(api = 31)
    fun allowed(ctx: Context) = supported && PERMISSIONS.all(ctx::granted)

    private val cooldown = Cooldown()
    @Volatile private var scanning = false
    @Volatile private var noStartUntil = 0L

    /** Scan while allowed and a Mac is paired. Idempotent; call on any change. */
    @Synchronized
    fun sync(ctx: Context) {
        val node = Ferry.node.value ?: return
        val want = allowed(ctx) && Ferry.peers.value.isNotEmpty()
        if (want == scanning) return
        if (want) startScan(ctx, node) else stopScan(ctx)
    }

    /** Bluetooth turned off drops all scans; the next [sync] re-registers. */
    fun adapterReset() {
        scanning = false
    }

    @RequiresApi(31)
    private fun startScan(ctx: Context, node: FerryNode) {
        val scanner = scanner(ctx)
        if (scanner == null) {
            Log.w(BLE_TAG, "no scanner (Bluetooth off?)")
            return
        }
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(blePrefixUuid(node.blePrefix())), ParcelUuid(BLE_MASK))
            .build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build()
        try {
            scanner.stopScan(scanIntent(ctx)) // re-registering the same PendingIntent: start clean
            val err = scanner.startScan(listOf(filter), settings, scanIntent(ctx))
            scanning = err == 0
            Log.w(BLE_TAG, if (scanning) "presence scan on" else "presence scan failed: $err")
        } catch (e: Exception) { // IllegalStateException when the adapter is off, SecurityException
            Log.w(BLE_TAG, "presence scan: ${e.text()}")
        }
    }

    fun stopScan(ctx: Context) {
        scanning = false
        if (!allowed(ctx)) return
        runCatching { scanner(ctx)?.stopScan(scanIntent(ctx)) }
    }

    @RequiresApi(31)
    private fun scanIntent(ctx: Context) = PendingIntent.getBroadcast(
        ctx,
        0,
        Intent(ctx, BleScanReceiver::class.java),
        // Mutable: the system adds the results as extras. The intent is explicit.
        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun scanner(ctx: Context): BluetoothLeScanner? =
        ctx.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner

    /** Scan hits (manifest receiver; the process may have been dead). */
    fun onResults(ctx: Context, results: List<ScanResult>) {
        if (!allowed(ctx) || results.isEmpty()) return
        val app = ctx.applicationContext
        if (Ferry.node.value == null) {
            if (SystemClock.elapsedRealtime() < noStartUntil) return
            try {
                FerryService.start(app) // from the background: allowed with a CDM association
            } catch (e: Exception) {
                noStartUntil = SystemClock.elapsedRealtime() + RETRY_MS
                Log.w(BLE_TAG, "can't start Ferry from the background (no companion association?): ${e.text()}")
                return
            }
        }
        Ferry.scope.launch {
            val node = Ferry.awaitNode() ?: return@launch
            for (r in results) {
                val uuids = r.scanRecord?.serviceUuids ?: continue
                val peer = uuids.firstNotNullOfOrNull { node.matchPresenceBle(it.uuid.toString()) } ?: continue
                launch { handle(app, node, r.device, peer) }
            }
        }
    }

    /** A paired Mac is in Bluetooth range: prefer Wi-Fi/USB (check-in), else bridge over BLE. */
    private suspend fun handle(ctx: Context, node: FerryNode, device: BluetoothDevice, peer: String) {
        if (Ferry.ble.value == peer || link?.isActive == true) return
        if (!cooldown.take(peer, SystemClock.elapsedRealtime(), RETRY_MS)) return
        catching { node.checkIn(peer) }
            .onSuccess { return cooldown.hold(peer, SystemClock.elapsedRealtime(), CHECKED_IN_MS) }
            .onFailure { Log.w(BLE_TAG, "check-in with ${peer.take(8)} failed (${it.text()}); trying Bluetooth") }
        startLink(ctx, node, device, peer)
    }

    // The one live (or connecting) bridge. Its socket/gatt are closed directly on stop, because
    // blocking connect()/read() don't react to coroutine cancellation.
    @Volatile private var link: Job? = null
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var gatt: BluetoothGatt? = null

    @Synchronized
    private fun startLink(ctx: Context, node: FerryNode, device: BluetoothDevice, peer: String) {
        if (link?.isActive == true) return
        link = Ferry.scope.launch {
            try {
                bridge(ctx, node, device, peer)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(BLE_TAG, "bridge to ${peer.take(8)}: ${e.text()}")
            } finally {
                closeLink()
                Ferry.ble.compareAndSet(peer, null)
            }
        }
    }

    /** Service stopping: tear the bridge down. */
    fun stopBridge() {
        link?.cancel()
        closeLink()
        Ferry.ble.value = null
    }

    private fun closeLink() {
        socket?.let { runCatching { it.close() } }
        gatt?.let { runCatching { it.close() } }
        socket = null
        gatt = null
    }

    private suspend fun bridge(ctx: Context, node: FerryNode, device: BluetoothDevice, peer: String) {
        val cb = GattSteps()
        // GATT stays connected for the bridge's lifetime: it's idle (no cost), holds the LE link
        // and connection priority on stacks that tie them to a GATT client, and its disconnect
        // callback is a second "link lost" signal.
        // The replacement (BluetoothGattConnectionSettings) is API 37+ only.
        @Suppress("DEPRECATION")
        val g = device.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE) ?: error("connectGatt returned null")
        gatt = g
        cb.onDisconnect = { socket?.let { runCatching { it.close() } } }
        val psm = withTimeoutOrNull(30_000) {
            if (!cb.connected.await()) error("GATT connect failed")
            if (g.requestMtu(517)) withTimeoutOrNull(5_000) { cb.mtu.await() }
            if (!g.discoverServices() || !cb.services.await()) error("service discovery failed")
            val ch = g.getService(BLE_SERVICE)?.getCharacteristic(BLE_PSM_CHAR) ?: error("no Ferry service on this device")
            if (!g.readCharacteristic(ch)) error("PSM read failed")
            parsePsm(cb.psm.await()) ?: error("bad PSM")
        } ?: error("GATT timed out")
        g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
        // Insecure: no bonding needed (Apple↔Android secure CoC fails unbonded); QUIC/TLS secures it.
        val s = device.createInsecureL2capChannel(psm)
        socket = s
        coroutineScope {
            val watchdog = launch {
                delay(20_000)
                runCatching { s.close() }
            }
            withContext(Dispatchers.IO) { s.connect() }
            watchdog.cancel()
        }
        Log.w(BLE_TAG, "L2CAP open (psm $psm), starting bridge")
        val out = s.outputStream
        val bridge = node.openStreamBridge(
            "ble:" + device.address,
            object : ByteSink {
                // Called from a core blocking thread, one chunk at a time.
                override fun write(data: ByteArray) = try {
                    out.write(data)
                    out.flush()
                    true
                } catch (e: Exception) {
                    false
                }

                override fun finish() { // bridge ended
                    runCatching { s.close() }
                }
            },
        )
        try {
            coroutineScope {
                launch(Dispatchers.IO) {
                    val input = s.inputStream
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        // -1 (EOF; also a disconnect when targeting Android 17) or IOException: done.
                        val n = try { input.read(buf) } catch (e: Exception) { -1 }
                        if (n < 0) break
                        if (n > 0 && runCatching { bridge.push(buf.copyOf(n)) }.isFailure) break // bridge ended
                    }
                    bridge.finish()
                }
                val who = withTimeoutOrNull(20_000) { runCatching { bridge.waitPeer() }.getOrNull() }
                if (who != peer) {
                    Log.w(BLE_TAG, "bridge peer mismatch or no hello (${who?.take(8)}), closing")
                    runCatching { s.close() } // ends the reader
                } else {
                    Log.w(BLE_TAG, "connected over Bluetooth to ${peer.take(8)}")
                    Ferry.ble.value = peer
                }
            }
        } finally {
            runCatching { bridge.finish() }
            bridge.destroy()
            Log.w(BLE_TAG, "Bluetooth bridge closed")
        }
    }

    /** GATT callbacks (binder thread) → one-shot results for the steps above. */
    private class GattSteps : BluetoothGattCallback() {
        val connected = CompletableDeferred<Boolean>()
        val mtu = CompletableDeferred<Unit>()
        val services = CompletableDeferred<Boolean>()
        val psm = CompletableDeferred<ByteArray?>()
        @Volatile var onDisconnect: (() -> Unit)? = null

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connected.complete(true)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                connected.complete(false)
                services.complete(false)
                psm.complete(null)
                mtu.complete(Unit)
                onDisconnect?.invoke()
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            this.mtu.complete(Unit)
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            services.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        // API 31–32 only; on 33+ the overload below is called instead.
        @Deprecated("API 33 passes the value directly")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            psm.complete(if (status == BluetoothGatt.GATT_SUCCESS) c.value else null)
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            psm.complete(if (status == BluetoothGatt.GATT_SUCCESS) value else null)
        }
    }
}

/** Presence scan results (PendingIntent from [Ble.startScan]); works with the process dead. */
class BleScanReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val err = intent.getIntExtra(BluetoothLeScanner.EXTRA_ERROR_CODE, 0)
        if (err != 0) {
            Log.w(BLE_TAG, "scan error $err")
            return
        }
        val results = IntentCompat.getParcelableArrayListExtra(intent, BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT, ScanResult::class.java)
        Ble.onResults(ctx, results ?: return)
    }
}

/**
 * Companion Device Manager association with the Mac. It only lets Ferry start its foreground
 * service from the background (a scan hit); presence observing breaks on the Mac's address
 * rotation, so it isn't used.
 */
object Cdm {
    fun available(ctx: Context) =
        Ble.supported && ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)

    @Suppress("DEPRECATION")
    fun associated(ctx: Context): Boolean {
        if (!available(ctx)) return false
        val cdm = ctx.getSystemService(CompanionDeviceManager::class.java) ?: return false
        return if (Build.VERSION.SDK_INT >= 33) cdm.myAssociations.isNotEmpty() else cdm.associations.isNotEmpty()
    }

    /** Shows the system's device chooser through [launch]; failures are toasted. */
    @Suppress("DEPRECATION")
    fun associate(ctx: Context, blePrefix: String, launch: (IntentSender) -> Unit) {
        if (!available(ctx)) return
        val cdm = ctx.getSystemService(CompanionDeviceManager::class.java) ?: return
        val scan = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(blePrefixUuid(blePrefix)), ParcelUuid(BLE_MASK))
            .build()
        val request = AssociationRequest.Builder()
            .addDeviceFilter(BluetoothLeDeviceFilter.Builder().setScanFilter(scan).build())
            .setSingleDevice(false)
            .build()
        val cb = object : CompanionDeviceManager.Callback() {
            override fun onAssociationPending(sender: IntentSender) = launch(sender) // 33+

            @Deprecated("API 33: onAssociationPending")
            override fun onDeviceFound(sender: IntentSender) = launch(sender) // 31–32

            override fun onFailure(error: CharSequence?) {
                Log.w(BLE_TAG, "CDM associate failed: $error")
                Ferry.toast(ctx, "Couldn't set up background access (${error ?: "no Mac found"}). Turn on Bluetooth and Location, keep the Mac nearby, and try again.")
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) cdm.associate(request, ctx.mainExecutor, cb) else cdm.associate(request, cb, null)
        } catch (e: Exception) {
            Ferry.toast(ctx, "Couldn't set up background access: ${e.text()}")
        }
    }
}
