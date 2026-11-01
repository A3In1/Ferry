package dev.ferry.android

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import dev.ferry.core.FerryNode
import dev.ferry.core.FfiTxt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.random.Random

/**
 * LAN presence through NsdManager (the Android build of the core has no mdns-sd):
 * advertise `_ferry._udp` with the core's rotating TXT, discover others, and feed the
 * addresses of paired peers (recognised by [FerryNode.matchPresence]) into the core.
 */
class NsdPresence(private val ctx: Context, private val node: FerryNode) {
    private val nsd = ctx.getSystemService(NsdManager::class.java)
    private val multicast = ctx.applicationContext.getSystemService(WifiManager::class.java)
        .createMulticastLock("ferry-nsd").apply { setReferenceCounted(false) }

    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private val infoCallbacks = ConcurrentHashMap<String, NsdManager.ServiceInfoCallback>() // API 34+
    private val seen = ConcurrentHashMap<String, String>() // service name -> peer id
    private val toResolve = Channel<NsdServiceInfo>(Channel.UNLIMITED) // pre-34: one resolve at a time
    private var job: Job? = null
    /** Set by [stop]: NSD callbacks still in flight must not touch the stopped node or re-add nearby peers. */
    @Volatile private var stopped = false

    /** Call [start] and [stop] on the main thread. */
    fun start(scope: CoroutineScope) {
        // Main, like stop(): a round can't be half-registered (listener or multicast lock not yet
        // recorded) when stop() runs, which would leak it, keeping discovery or the Wi-Fi lock on for good.
        job = scope.launch(Dispatchers.Main) {
            if (Build.VERSION.SDK_INT < 34) launch(Dispatchers.IO) { for (info in toResolve) resolveLegacy(info)?.let(::onResolved) }
            // The presence tag rotates every 15 min: re-advertise, and restart discovery so a
            // network change can't leave it dead for long.
            // Battery: discovery and the multicast lock (which keeps Wi-Fi awake for every
            // multicast packet) run only for the first minute of each round. The advert stays up;
            // on Android 14+ the system's mDNS answers queries for it. Once found, the core
            // remembers the Mac's address, so later rounds rarely matter.
            while (isActive) {
                advertise()
                multicast.acquire()
                discover()
                delay(DISCOVER_MS)
                discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }
                discovery = null
                if (multicast.isHeld) multicast.release()
                delay(ROUND_MS - DISCOVER_MS)
                stopRound()
            }
        }
    }

    fun stop() {
        stopped = true
        job?.cancel()
        stopRound()
        if (multicast.isHeld) multicast.release()
    }

    private fun advertise() {
        val info = NsdServiceInfo().apply {
            // Random, like the core's own mDNS: nothing identifying in the instance name.
            serviceName = "ferry-" + Random.nextBytes(6).joinToString("") { "%02x".format(it) }
            serviceType = SERVICE_TYPE
            port = node.port().toInt()
            node.presenceTxt().forEach { setAttribute(it.key, it.value) }
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) { Log.w(TAG, "advertising ${info.serviceName}") }
            override fun onRegistrationFailed(info: NsdServiceInfo, err: Int) { Log.w(TAG, "NSD register failed: $err") }
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, err: Int) {}
        }
        registration = l
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
    }

    private fun discover() {
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, err: Int) { Log.w(TAG, "NSD discovery failed: $err") }
            override fun onStopDiscoveryFailed(type: String, err: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                if (stopped) return
                if (Build.VERSION.SDK_INT >= 34) watch(info) else toResolve.trySend(info)
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                if (Build.VERSION.SDK_INT >= 34) infoCallbacks.remove(info.serviceName)?.let(::unwatch)
                lost(info.serviceName)
            }
        }
        discovery = l
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
    }

    private fun stopRound() {
        registration?.let { runCatching { nsd.unregisterService(it) } }
        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        registration = null
        discovery = null
        if (Build.VERSION.SDK_INT >= 34) infoCallbacks.values.forEach(::unwatch)
        infoCallbacks.clear()
        seen.clear()
        Ferry.nearby.value = emptySet()
    }

    @RequiresApi(34)
    private fun watch(info: NsdServiceInfo) {
        val cb = object : NsdManager.ServiceInfoCallback {
            override fun onServiceInfoCallbackRegistrationFailed(err: Int) { Log.w(TAG, "NSD resolve failed: $err") }
            override fun onServiceUpdated(info: NsdServiceInfo) = onResolved(info)
            override fun onServiceLost() {}
            override fun onServiceInfoCallbackUnregistered() {}
        }
        if (infoCallbacks.putIfAbsent(info.serviceName, cb) != null) return
        // Not the main thread: addPeerAddrs can write the trust store to disk.
        nsd.registerServiceInfoCallback(info, Dispatchers.IO.asExecutor(), cb)
    }

    @RequiresApi(34)
    private fun unwatch(cb: NsdManager.ServiceInfoCallback) {
        runCatching { nsd.unregisterServiceInfoCallback(cb) }
    }

    @Suppress("DEPRECATION")
    private suspend fun resolveLegacy(info: NsdServiceInfo): NsdServiceInfo? = suspendCancellableCoroutine { c ->
        nsd.resolveService(
            info,
            object : NsdManager.ResolveListener {
                override fun onServiceResolved(info: NsdServiceInfo) = c.resume(info)
                override fun onResolveFailed(info: NsdServiceInfo, err: Int) = c.resume(null)
            },
        )
    }

    private fun onResolved(info: NsdServiceInfo) {
        if (stopped) return
        val txt = info.attributes.map { (k, v) -> FfiTxt(k, v?.decodeToString() ?: "") }
        val peerId = node.matchPresence(txt) ?: return // not one of our paired Macs (or ourselves)
        val addrs = hosts(info).mapNotNull { a ->
            a.hostAddress?.let { Tickets.hostPort(it, (a as? Inet6Address)?.scopeId ?: 0, info.port) }
        }
        if (addrs.isEmpty()) return
        try {
            node.addPeerAddrs(peerId, addrs)
        } catch (e: Exception) {
            Log.w(TAG, "addPeerAddrs $addrs", e)
            return
        }
        seen[info.serviceName] = peerId
        if (!stopped) Ferry.nearby.update { it + peerId }
    }

    private fun lost(serviceName: String) {
        val id = seen.remove(serviceName) ?: return
        if (id !in seen.values) Ferry.nearby.update { it - id }
    }

    @Suppress("DEPRECATION")
    private fun hosts(info: NsdServiceInfo): List<InetAddress> =
        if (Build.VERSION.SDK_INT >= 34) info.hostAddresses else listOfNotNull(info.host)

    private companion object {
        const val SERVICE_TYPE = "_ferry._udp"
        private const val DISCOVER_MS = 60_000L
        private const val ROUND_MS = 15 * 60_000L
    }
}
