package dev.ferry.android.screen

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import dev.ferry.android.Ferry
import dev.ferry.android.Notifs
import dev.ferry.android.text
import dev.ferry.core.ScreenStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// vivo hides Info logs: everything here logs at warn.
const val SCREEN_TAG = "FerryScreen"

fun caps() = if (ControlService.instance.value != null) Fsp.CAP_CONTROL else 0

data class ScreenSize(val w: Int, val h: Int)

fun screenSize(ctx: Context): ScreenSize {
    val m = DisplayMetrics()
    @Suppress("DEPRECATION") // still the real size in the current rotation, and works from a non-visual context
    ctx.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(m)
    return ScreenSize(m.widthPixels, m.heightPixels)
}

/** The screen size now and after every rotation. */
fun screenSizes(ctx: Context) = callbackFlow {
    val dm = ctx.getSystemService(DisplayManager::class.java)
    val l = object : DisplayManager.DisplayListener {
        override fun onDisplayChanged(id: Int) {
            if (id == Display.DEFAULT_DISPLAY) trySend(screenSize(ctx))
        }
        override fun onDisplayAdded(id: Int) {}
        override fun onDisplayRemoved(id: Int) {}
    }
    trySend(screenSize(ctx))
    dm.registerDisplayListener(l, Handler(Looper.getMainLooper()))
    awaitClose { dm.unregisterDisplayListener(l) }
}.distinctUntilChanged()

/** The one Share Cursor session (docs/screen-protocol.md). A new stream replaces the old one. */
object Screen {
    /** Sessions run one after another: the next starts once the old one has cleaned up. */
    val turn = Mutex()
    @SuppressLint("StaticFieldLeak") // holds the application context only
    @Volatile private var current: Session? = null

    fun open(ctx: Context, peerId: String, stream: ScreenStream) {
        val s = Session(ctx.applicationContext, peerId, stream)
        synchronized(this) { current.also { current = s } }?.end(Fsp.END_NORMAL, "replaced by a new screen session")
    }

    fun stop(reason: Int, detail: String) = current?.end(reason, detail)

    /** The setting changed: apply it to the open session, if any. */
    fun keepAwake(on: Boolean) {
        if (current != null) ControlService.instance.value?.keepAwake(on)
    }

    fun done(s: Session) = synchronized(this) { if (current === s) current = null }
}

/** Phone-side screen settings. */
object ScreenPrefs {
    private const val KEEP_AWAKE = "keepAwake"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("screen", Context.MODE_PRIVATE)

    /** "Keep phone awake while sharing the cursor" (default on); loaded by [load]. */
    val keepAwake = MutableStateFlow(true)

    fun load(ctx: Context) {
        keepAwake.value = prefs(ctx).getBoolean(KEEP_AWAKE, true)
    }

    fun setKeepAwake(ctx: Context, on: Boolean) {
        prefs(ctx).edit { putBoolean(KEEP_AWAKE, on) }
        keepAwake.value = on
        Screen.keepAwake(on)
    }
}

class Session(private val ctx: Context, peerId: String, private val stream: ScreenStream) {
    private val mac = Ferry.peerName(peerId)
    private val lock = Mutex()
    private var opened = false
    @Volatile private var ended = false
    private var started = false

    // ATOMIC: runs (and finishes the stream) even if ended before it was dispatched.
    @OptIn(DelicateCoroutinesApi::class)
    private val job = Ferry.scope.launch(start = CoroutineStart.ATOMIC) {
        try {
            Screen.turn.withLock { serve() }
        } finally {
            withContext(NonCancellable) { stream.finish() }
            Screen.done(this@Session)
        }
    }

    /** Sends END (once, if the stream still works) and tears the session down. Any thread. */
    fun end(reason: Int, detail: String = "") {
        Log.w(SCREEN_TAG, "end $reason $detail")
        Ferry.scope.launch {
            runCatching { send(Fsp.end(reason, detail), last = true) }
            job.cancel()
        }
    }

    private suspend fun send(bytes: ByteArray, last: Boolean = false) = lock.withLock {
        if (ended) return@withLock
        ended = last
        stream.write(if (opened) bytes else Fsp.MAGIC + bytes)
        opened = true
    }

    private suspend fun serve() {
        Log.w(SCREEN_TAG, "screen stream from $mac")
        val screenOff = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = end(Fsp.END_LOCKED, "phone locked")
        }
        ContextCompat.registerReceiver(ctx, screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
        ScreenPrefs.load(ctx)
        // Needs Ferry control (the overlay window); without it the phone sleeps on its usual timeout.
        if (ScreenPrefs.keepAwake.value) ControlService.instance.value?.keepAwake(true)
        val wifi = WifiLocks(ctx)
        try {
            Notifs.screen(ctx, mac, "Getting ready…")
            coroutineScope {
                val parser = FspParser()
                while (true) {
                    val chunk = stream.read()
                    if (chunk.isEmpty()) break
                    parser.feed(chunk).forEach { handle(it) }
                }
                Log.w(SCREEN_TAG, "$mac closed the screen stream")
                coroutineContext.cancelChildren()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(SCREEN_TAG, "screen session failed", e)
            runCatching { send(Fsp.end(Fsp.END_ERROR, e.text()), last = true) }
        } finally {
            ended = true // nothing more on the wire
            ctx.unregisterReceiver(screenOff)
            wifi.release()
            ControlService.instance.value?.apply {
                reset()
                keepAwake(false)
            }
            Notifs.cancel(ctx, Notifs.ID_SCREEN)
        }
    }

    private fun CoroutineScope.handle(m: Msg) {
        when (m) {
            Msg.Start -> if (!started) {
                started = true
                launch { start() }
            }
            else -> if (started) ControlService.instance.value?.input(m)
        }
    }

    private fun locked() = ctx.getSystemService(KeyguardManager::class.java).isKeyguardLocked ||
        !ctx.getSystemService(PowerManager::class.java).isInteractive

    private suspend fun start() {
        val control = ControlService.instance.value != null
        Log.w(SCREEN_TAG, "START control=$control")
        when {
            locked() -> return end(Fsp.END_LOCKED, "phone locked")
            !control -> return end(Fsp.END_NOT_ALLOWED, "turn on Ferry control")
        }
        Notifs.screen(ctx, mac, "$mac can tap, scroll and type here")
        screenSizes(ctx).collect { send(Fsp.info(it.w, it.h, caps())) }
    }
}

/**
 * Wi-Fi power save off for a session: phone Wi-Fi dozes between beacons (+30–100 ms spikes on a stream that
 * is idle for a moment). LOW_LATENCY is honored only while Ferry is in the foreground with the screen on,
 * which during Share Cursor it usually isn't (the user is in other apps), so HIGH_PERF is held too as the
 * fallback; Android applies the stronger one it allows at the moment. Both need WAKE_LOCK.
 */
private class WifiLocks(ctx: Context) {
    private val locks = runCatching {
        val wm = ctx.applicationContext.getSystemService(WifiManager::class.java)
        @Suppress("DEPRECATION") // HIGH_PERF: deprecated for LOW_LATENCY, which doesn't cover background use
        listOf(
            wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "Ferry:screen-low-latency"),
            wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Ferry:screen-high-perf"),
        ).onEach {
            it.setReferenceCounted(false)
            it.acquire()
        }
    }.onSuccess { Log.w(SCREEN_TAG, "wifi locks held (low-latency + high-perf)") }
        .onFailure { Log.w(SCREEN_TAG, "wifi locks unavailable", it) }
        .getOrDefault(emptyList())

    fun release() {
        if (locks.isEmpty()) return
        locks.forEach { runCatching { it.release() } }
        Log.w(SCREEN_TAG, "wifi locks released")
    }
}

/** Stop on the "sharing its cursor" notification. */
class ScreenStop : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Screen.stop(Fsp.END_STOPPED, "stopped on the phone")
    }
}
