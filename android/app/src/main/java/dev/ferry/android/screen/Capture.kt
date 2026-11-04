package dev.ferry.android.screen

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

data class ScreenSize(val w: Int, val h: Int, val dpi: Int)

fun screenSize(ctx: Context): ScreenSize {
    val m = DisplayMetrics()
    @Suppress("DEPRECATION") // still the real size in the current rotation, and works from a non-visual context
    ctx.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(m)
    return ScreenSize(m.widthPixels, m.heightPixels, m.densityDpi)
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

/**
 * Adapts the encoder's bitrate to the link from how long FRAME writes block (QUIC flow control pushes
 * back when the network can't keep up): down fast when writes block much of the time, back up slowly
 * when they don't, never above the Mac's START bitrate. A write that stalls means the frames queued
 * behind it are stale: [Change.catchUp] says to drop frames until a fresh keyframe.
 */
class BitrateControl(private val ceiling: Int, now: Long) {
    data class Change(val bitrate: Int?, val catchUp: Boolean)

    private val floor = maxOf(MIN_BPS, ceiling / 8).coerceAtMost(ceiling)
    var bitrate = ceiling
        private set
    private var windowStart = now
    private var blocked = 0L
    private var calm = 0
    private var lastCatchUp = Long.MIN_VALUE / 2

    /** One FRAME was written in [writeMs]; [now] in ms. */
    fun sent(writeMs: Long, now: Long): Change {
        blocked += writeMs
        if (writeMs >= STALL_MS && now - lastCatchUp >= CATCH_UP_EVERY_MS) {
            lastCatchUp = now
            restart(now)
            return Change(set(bitrate / 2), catchUp = true)
        }
        val elapsed = now - windowStart
        if (elapsed < WINDOW_MS) return Change(null, false)
        val busy = blocked.toDouble() / elapsed
        restart(now)
        return when {
            busy > CONGESTED -> {
                calm = 0
                Change(set((bitrate * 0.7).roundToInt()), false)
            }
            busy < CLEAR && ++calm >= CALM_WINDOWS -> {
                calm = 0
                Change(set((bitrate * 1.2).roundToInt()), false)
            }
            else -> {
                if (busy >= CLEAR) calm = 0
                Change(null, false)
            }
        }
    }

    private fun restart(now: Long) {
        windowStart = now
        blocked = 0
    }

    /** The new bitrate, or null if it didn't change. */
    private fun set(bps: Int): Int? {
        val b = bps.coerceIn(floor, ceiling)
        if (b == bitrate) return null
        bitrate = b
        return b
    }

    companion object {
        const val MIN_BPS = 500_000
        const val WINDOW_MS = 1_000L
        const val CONGESTED = 0.5
        const val CLEAR = 0.1
        const val CALM_WINDOWS = 3
        const val STALL_MS = 400L
        const val CATCH_UP_EVERY_MS = 2_000L
    }
}

/** H.264 from a VirtualDisplay into FRAME messages; INFO + a fresh encoder on every rotation. */
class Capture(private val ctx: Context, private val projection: MediaProjection, private val start: Msg.Start) {
    @Volatile private var codec: MediaCodec? = null
    private val ceiling = start.bitrate.takeIf { it > 0 }?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: 8_000_000
    private val quality = BitrateControl(ceiling, SystemClock.elapsedRealtime())
    private val inFlight = InFlight() // guarded by itself, with stats
    private val stats = SendStats(SystemClock.elapsedRealtime())

    /** The Mac has every FRAME up to [ptsUs]. Any thread. */
    fun acked(ptsUs: Long) = synchronized(inFlight) {
        inFlight.acked(ptsUs, SystemClock.elapsedRealtime())?.let(stats::rtt)
    }

    fun keyframe() {
        runCatching { codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
    }

    suspend fun run(send: suspend (Msg) -> Unit) {
        var vd: VirtualDisplay? = null
        try {
            screenSizes(ctx).collectLatest { s ->
                send(Msg.Info(s.w, s.h, caps(true)))
                val c = MediaCodec.createEncoderByType(AVC)
                try {
                    val (w, h) = fit(s, c)
                    c.configure(format(w, h, c), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    val input = c.createInputSurface()
                    try {
                        c.start()
                        codec = c
                        Log.w(SCREEN_TAG, "encoding ${w}x$h for screen ${s.w}x${s.h}")
                        // One VirtualDisplay per projection (Android 14+): resize it rather than make another.
                        vd = vd?.apply {
                            resize(w, h, s.dpi)
                            surface = input
                        } ?: projection.createVirtualDisplay("Ferry", w, h, s.dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, input, null, null)
                        drain(c, send)
                    } finally {
                        codec = null
                        vd?.surface = null
                        input.release()
                    }
                } finally {
                    c.release()
                }
            }
        } finally {
            vd?.release()
        }
    }

    private suspend fun drain(c: MediaCodec, send: suspend (Msg) -> Unit) = withContext(Dispatchers.IO) {
        val info = MediaCodec.BufferInfo()
        var skipping = false // behind after a stalled write: drop frames until a keyframe
        while (isActive) {
            val i = c.dequeueOutputBuffer(info, 100_000)
            if (i < 0) continue
            val key = if (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) 1 else 0
            val config = if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) 2 else 0
            if (key != 0) skipping = false
            if (config == 0 && info.size > 0) {
                // Surface input: pts is the capture time on System.nanoTime's clock.
                synchronized(inFlight) { stats.encoded(System.nanoTime() / 1000 - info.presentationTimeUs) }
            }
            if (skipping && config == 0 || info.size == 0) {
                if (info.size > 0) synchronized(inFlight) { stats.dropped() }
                c.releaseOutputBuffer(i, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                continue
            }
            val data = ByteArray(info.size)
            c.getOutputBuffer(i)?.apply { position(info.offset) }?.get(data)
            c.releaseOutputBuffer(i, false)
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            val t = SystemClock.elapsedRealtime()
            send(Msg.Frame(info.presentationTimeUs, key or config, data))
            val now = SystemClock.elapsedRealtime()
            val report = synchronized(inFlight) {
                inFlight.sent(info.presentationTimeUs, data.size, now)
                stats.sent(data.size, now - t)
                stats.report(now, quality.bitrate)?.also { Log.w(SCREEN_TAG, it.line(inFlight.count)) }
            }
            report?.let { send(it) }
            val change = quality.sent(now - t, now)
            change.bitrate?.let {
                Log.w(SCREEN_TAG, "bitrate ${it / 1000} kbps${if (change.catchUp) " (catching up)" else ""}")
                runCatching { c.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, it) }) }
                send(Msg.Quality(it))
            }
            if (change.catchUp) {
                skipping = true
                keyframe()
            }
        }
    }

    /** Long side ≤ max_size (0 = native), multiples of 8; falls back to 1280 if the encoder can't do it. */
    private fun fit(s: ScreenSize, c: MediaCodec): Pair<Int, Int> {
        fun scaled(max: Int): Pair<Int, Int> {
            val f = if (max <= 0) 1.0 else minOf(1.0, max.toDouble() / maxOf(s.w, s.h))
            return ((s.w * f).toInt() and 7.inv()) to ((s.h * f).toInt() and 7.inv())
        }
        val caps = c.codecInfo.getCapabilitiesForType(AVC).videoCapabilities
        // shortcut: one fallback size, not a search of the encoder's ranges; upgrade if an encoder refuses 1280 too.
        return scaled(start.maxSize).takeIf { (w, h) -> caps?.isSizeSupported(w, h) != false } ?: scaled(1280)
    }

    private fun format(w: Int, h: Int, c: MediaCodec) = MediaFormat.createVideoFormat(AVC, w, h).apply {
        setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        setInteger(MediaFormat.KEY_BIT_RATE, quality.bitrate) // a rotation keeps what the link settled on
        setInteger(MediaFormat.KEY_FRAME_RATE, 60)
        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 10)
        setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000)
        setInteger(MediaFormat.KEY_PRIORITY, 0) // realtime
        setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
        if (Build.VERSION.SDK_INT >= 30 &&
            c.codecInfo.getCapabilitiesForType(AVC).isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
        ) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
    }

    private companion object {
        const val AVC = MediaFormat.MIMETYPE_VIDEO_AVC
    }
}
