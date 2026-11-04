package dev.ferry.android.screen

import android.annotation.SuppressLint
import android.app.Activity
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import dev.ferry.android.Ferry
import dev.ferry.android.FerryService
import dev.ferry.android.Notifs
import dev.ferry.android.text
import dev.ferry.core.ScreenStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

// vivo hides Info logs: everything here logs at warn.
const val SCREEN_TAG = "FerryScreen"

fun caps(video: Boolean) = (if (video) Fsp.CAP_VIDEO else 0) or (if (ControlService.instance.value != null) Fsp.CAP_CONTROL else 0)

/** The one screen session (docs/screen-protocol.md). A new stream replaces the old one. */
object Screen {
    /** Sessions run one after another: the next starts once the old one has cleaned up. */
    val turn = Mutex()
    @SuppressLint("StaticFieldLeak") // holds the application context only
    @Volatile private var current: Session? = null
    @Volatile var ask: Ask? = null

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

    /** "Keep phone awake while mirroring" (default on); loaded by [load]. */
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

/** MediaProjection consent, answered by [ProjectionActivity]. */
class Ask {
    val shown = CompletableDeferred<Unit>()
    val result = CompletableDeferred<Pair<Int, Intent?>>()
}

class Session(private val ctx: Context, private val peerId: String, private val stream: ScreenStream) {
    private val mac = Ferry.peerName(peerId)
    private val lock = Mutex()
    private var opened = false
    @Volatile private var ended = false
    private var started = false
    @Volatile private var capture: Capture? = null
    @Volatile private var projection: MediaProjection? = null
    @Volatile private var stopped: MediaProjection.Callback? = null

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
            runCatching { send(Msg.End(reason, detail), last = true) }
            job.cancel()
        }
    }

    private suspend fun send(m: Msg, last: Boolean = false) = sendRaw(Fsp.encode(m), last)

    /** An encoded message (FRAMEs are encoded straight from the encoder's buffer). */
    private suspend fun sendRaw(bytes: ByteArray, last: Boolean = false) = lock.withLock {
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
            runCatching { send(Msg.End(Fsp.END_ERROR, e.text()), last = true) }
        } finally {
            ended = true // nothing more on the wire, incl. END from our own projection.stop() below
            ctx.unregisterReceiver(screenOff)
            wifi.release()
            projection?.let {
                stopped?.let(it::unregisterCallback) // our own stop isn't "stopped on the phone"
                it.stop()
                FerryService.instance?.projecting(false)
            }
            ControlService.instance.value?.apply {
                reset()
                keepAwake(false)
            }
            Notifs.cancel(ctx, Notifs.ID_SCREEN)
        }
    }

    private fun CoroutineScope.handle(m: Msg) {
        when (m) {
            is Msg.Start -> if (!started) {
                started = true
                launch { start(m) }
            }
            Msg.Keyframe -> capture?.keyframe()
            is Msg.Ack -> capture?.acked(m.ptsUs)
            is Msg.Touch, is Msg.Scroll, is Msg.Text, is Msg.Key, is Msg.Nav, is Msg.Cursor ->
                if (started) ControlService.instance.value?.input(m)
            else -> {} // phone → Mac kinds
        }
    }

    private fun locked() = ctx.getSystemService(KeyguardManager::class.java).isKeyguardLocked ||
        !ctx.getSystemService(PowerManager::class.java).isInteractive

    private suspend fun start(m: Msg.Start) {
        val control = ControlService.instance.value != null
        Log.w(SCREEN_TAG, "START video=${m.video} control=$control max=${m.maxSize} bitrate=${m.bitrate}")
        when {
            locked() -> return end(Fsp.END_LOCKED, "phone locked")
            m.video && Ferry.bluetoothOnly(peerId) -> return end(Fsp.END_ERROR, "Bluetooth is too slow for video")
            !m.video && !control -> return end(Fsp.END_NOT_ALLOWED, "turn on Ferry control")
        }
        if (!m.video) {
            Notifs.screen(ctx, mac, "Ferry control is on: $mac can tap, scroll and type here")
            return screenSizes(ctx).collect { send(Msg.Info(it.w, it.h, caps(false))) }
        }
        val p = project() ?: return end(Fsp.END_NOT_ALLOWED, "screen sharing was not allowed on the phone")
        projection = p
        val cb = object : MediaProjection.Callback() {
            override fun onStop() = end(if (locked()) Fsp.END_LOCKED else Fsp.END_STOPPED, "screen sharing stopped on the phone")
        }
        p.registerCallback(cb, Handler(Looper.getMainLooper()))
        stopped = cb
        Notifs.screen(ctx, mac, if (control) "Screen sharing is on, and $mac can tap and type here" else "Screen sharing is on")
        Capture(ctx, p, m).also { capture = it }.run(::send) { sendRaw(it) }
    }

    /** Consent: the activity if Android lets us start it, otherwise a notification the user taps. */
    private suspend fun project(): MediaProjection? {
        val a = Ask()
        Screen.ask = a
        try {
            withContext(Dispatchers.Main) {
                runCatching { ctx.startActivity(Intent(ctx, ProjectionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            if (withTimeoutOrNull(2_000) { a.shown.await() } == null) {
                Log.w(SCREEN_TAG, "consent activity blocked in the background; posting a notification")
                Notifs.screenAsk(ctx, mac)
            }
            val (code, data) = withTimeoutOrNull(120_000) { a.result.await() } ?: return null
            if (code != Activity.RESULT_OK || data == null) return null
            // Android 14+: the service must hold the mediaProjection type before getMediaProjection.
            val service = FerryService.instance ?: error("Ferry isn't running")
            service.projecting(true)
            // Don't leave the service holding the mediaProjection type if Android refuses the token.
            return runCatching { ctx.getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data) }
                .onFailure { Log.w(SCREEN_TAG, "getMediaProjection failed", it) }
                .getOrNull() ?: run {
                service.projecting(false)
                null
            }
        } finally {
            Screen.ask = null
            Notifs.cancel(ctx, Notifs.ID_SCREEN_ASK)
        }
    }
}

/**
 * Wi-Fi power save off for a session: phone Wi-Fi dozes between beacons (+30–100 ms spikes on a stream that
 * is idle for a moment). LOW_LATENCY is honored only while Ferry is in the foreground with the screen on,
 * which during mirroring it usually isn't (the user is in other apps), so HIGH_PERF is held too as the
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

/** Transparent: shows only Android's "Start now" dialog, then closes. */
class ProjectionActivity : ComponentActivity() {
    private val consent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        Screen.ask?.result?.complete(r.resultCode to r.data)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return // recreated: the result still arrives
        // A second copy (late activity start + the notification tap) just closes.
        if (Screen.ask?.shown?.complete(Unit) != true) return finish()
        val mpm = getSystemService(MediaProjectionManager::class.java)
        // Whole screen only: a single-app share would break the Mac's coordinates.
        consent.launch(
            if (Build.VERSION.SDK_INT >= 34) mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
            else mpm.createScreenCaptureIntent(),
        )
    }

    override fun onDestroy() {
        if (isFinishing) Screen.ask?.result?.complete(RESULT_CANCELED to null)
        super.onDestroy()
    }
}

/** Stop on the "connected to your screen" notification. */
class ScreenStop : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Screen.stop(Fsp.END_STOPPED, "stopped on the phone")
    }
}
