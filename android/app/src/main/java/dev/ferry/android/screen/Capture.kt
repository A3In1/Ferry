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
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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
 * Encode sizes to try for a [w]x[h] screen, best first: long side ≤ [maxSize] (0 = native), then smaller
 * steps; both sides multiples of 16 (some encoders garble other widths).
 */
fun encodeSizes(w: Int, h: Int, maxSize: Int): List<Pair<Int, Int>> {
    val native = maxOf(w, h)
    val want = if (maxSize <= 0) native else minOf(maxSize, native)
    return (listOf(want) + SIZE_LADDER.filter { it < want }).map { long ->
        val f = long.toDouble() / native
        ((w * f).toInt() and 15.inv()) to ((h * f).toInt() and 15.inv())
    }.distinct()
}

private val SIZE_LADDER = listOf(1920, 1600, 1280, 1024, 720)

/**
 * H.264 from a VirtualDisplay into FRAME messages; INFO + a fresh encoder on every rotation. The encoder
 * runs in async mode on its own thread and never waits for the network: each output buffer is copied once,
 * straight into a whole FSP message, and queued; the sender writes them and [Pacer] drops stale ones.
 */
class Capture(private val ctx: Context, private val projection: MediaProjection, private val start: Msg.Start) {
    @Volatile private var codec: MediaCodec? = null
    private val ceiling = start.bitrate.takeIf { it > 0 }?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: 8_000_000
    // pacer, inFlight and stats are guarded by inFlight's lock (the encoder thread, sender and ACK reader all touch them).
    private val pacer = Pacer(ceiling, SystemClock.elapsedRealtime())
    private val inFlight = InFlight()
    private val stats = SendStats(SystemClock.elapsedRealtime())
    private val waiting = AtomicInteger()
    private val overflowed = AtomicBoolean()

    /** One encoded FRAME, already a whole FSP message. */
    private class Out(val bytes: ByteArray, val ptsUs: Long, val key: Boolean, val config: Boolean)

    fun keyframe() {
        runCatching { codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
    }

    /** The Mac has every FRAME up to [ptsUs]. Any thread. */
    fun acked(ptsUs: Long) = synchronized(inFlight) {
        inFlight.acked(ptsUs, SystemClock.elapsedRealtime())?.let {
            stats.rtt(it)
            pacer.rtt(it)
        }
    }

    /** [send] a message; [sendRaw] an already-encoded one (FRAMEs). */
    suspend fun run(send: suspend (Msg) -> Unit, sendRaw: suspend (ByteArray) -> Unit) {
        val thread = HandlerThread("ferry-encoder", Process.THREAD_PRIORITY_URGENT_DISPLAY).apply { start() }
        val handler = Handler(thread.looper)
        var vd: VirtualDisplay? = null
        try {
            screenSizes(ctx).collectLatest { s ->
                send(Msg.Info(s.w, s.h, caps(true)))
                val frames = Channel<Out>(FRAME_QUEUE)
                waiting.set(0)
                val c = MediaCodec.createEncoderByType(AVC)
                try {
                    val (w, h) = fit(s, c)
                    configure(c, w, h, frames, handler, s)
                    val input = c.createInputSurface()
                    try {
                        c.start()
                        codec = c
                        // One VirtualDisplay per projection (Android 14+): resize it rather than make another.
                        vd = vd?.apply {
                            resize(w, h, s.dpi)
                            surface = input
                        } ?: projection.createVirtualDisplay("Ferry", w, h, s.dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, input, null, null)
                        sendFrames(frames, send, sendRaw)
                    } finally {
                        codec = null
                        vd?.surface = null
                        frames.close()
                        runCatching { c.stop() }
                        input.release()
                    }
                } finally {
                    c.release()
                }
            }
        } finally {
            vd?.release()
            thread.quitSafely()
        }
    }

    private suspend fun sendFrames(frames: ReceiveChannel<Out>, send: suspend (Msg) -> Unit, sendRaw: suspend (ByteArray) -> Unit) {
        for (f in frames) {
            val queued = waiting.decrementAndGet()
            val now = SystemClock.elapsedRealtime()
            val d = synchronized(inFlight) {
                val backlog = if (inFlight.acking) inFlight.oldestAge(now) else null
                // A frame the encoder thread couldn't queue broke the reference chain: same as being far behind.
                val behind = if (overflowed.getAndSet(false)) Int.MAX_VALUE else queued
                pacer.admit(f.key, f.config, backlog, behind, now).also { if (!it.send) stats.dropped() }
            }
            d.bitrate?.let { bitrate(it, send, " (behind: skipping to a keyframe)") }
            if (d.askKeyframe) keyframe()
            if (!d.send) continue
            val t = SystemClock.elapsedRealtime()
            sendRaw(f.bytes)
            val done = SystemClock.elapsedRealtime()
            var change: Int?
            val report = synchronized(inFlight) {
                if (!f.config) inFlight.sent(f.ptsUs, f.bytes.size, done)
                stats.sent(f.bytes.size, done - t)
                change = pacer.sent(done - t, done)
                stats.report(done, pacer.bitrate)?.also { Log.w(SCREEN_TAG, it.line(inFlight.count, stats.lastEncode)) }
            }
            change?.let { bitrate(it, send, "") }
            report?.let { send(it) }
        }
    }

    private suspend fun bitrate(bps: Int, send: suspend (Msg) -> Unit, why: String) {
        Log.w(SCREEN_TAG, "bitrate ${bps / 1000} kbps$why")
        runCatching { codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bps) }) }
        send(Msg.Quality(bps))
    }

    private fun callback(frames: SendChannel<Out>) = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(c: MediaCodec, i: Int) {} // surface input

        override fun onOutputBufferAvailable(c: MediaCodec, i: Int, info: MediaCodec.BufferInfo) {
            try {
                val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                val bytes = if (info.size > 0) {
                    c.getOutputBuffer(i)?.let { b ->
                        b.limit(info.offset + info.size).position(info.offset)
                        Fsp.frame(info.presentationTimeUs, (if (key) 1 else 0) or (if (config) 2 else 0), b)
                    }
                } else {
                    null
                }
                c.releaseOutputBuffer(i, false)
                bytes ?: return
                // Surface input: pts is the buffer timestamp SurfaceFlinger gave the VirtualDisplay frame
                // (CLOCK_MONOTONIC, the same clock as System.nanoTime), so this is composition + encode time.
                if (!config) synchronized(inFlight) { stats.encoded(System.nanoTime() / 1000 - info.presentationTimeUs) }
                waiting.incrementAndGet()
                if (frames.trySend(Out(bytes, info.presentationTimeUs, key, config)).isFailure) {
                    waiting.decrementAndGet()
                    overflowed.set(true)
                    synchronized(inFlight) { stats.dropped() }
                }
            } catch (_: IllegalStateException) {
                // stopped or released under us (rotation, end of session)
            }
        }

        override fun onError(c: MediaCodec, e: MediaCodec.CodecException) {
            Log.w(SCREEN_TAG, "encoder error", e)
            frames.close(e)
        }

        override fun onOutputFormatChanged(c: MediaCodec, format: MediaFormat) {
            Log.w(SCREEN_TAG, "${c.name} output format: $format")
        }
    }

    /** The best encode size the encoder takes at 60 fps (else at all), from [encodeSizes]. */
    private fun fit(s: ScreenSize, c: MediaCodec): Pair<Int, Int> {
        val caps = c.codecInfo.getCapabilitiesForType(AVC).videoCapabilities ?: return encodeSizes(s.w, s.h, start.maxSize).first()
        val sizes = encodeSizes(s.w, s.h, start.maxSize)
        return sizes.firstOrNull { (w, h) -> runCatching { caps.areSizeAndRateSupported(w, h, 60.0) }.getOrDefault(false) }
            ?: sizes.firstOrNull { (w, h) -> caps.isSizeSupported(w, h) }
            ?: encodeSizes(s.w, s.h, 1280).last()
    }

    /**
     * The [Tier.TUNED] format; a tier the encoder refuses (some vendors reject keys they don't know) steps down
     * to the next, down to [Tier.PLAIN]. Logs what was asked and what the encoder says it took.
     */
    private fun configure(c: MediaCodec, w: Int, h: Int, frames: SendChannel<Out>, handler: Handler, s: ScreenSize) {
        val caps = c.codecInfo.getCapabilitiesForType(AVC)
        val video = caps.videoCapabilities
        val fps = if (runCatching { video?.areSizeAndRateSupported(w, h, 60.0) }.getOrNull() != false) 60 else 30
        val maxFps = runCatching { video?.getSupportedFrameRatesFor(w, h)?.upper }.getOrNull()
        val vendor = vendorParams(c)
        val advertised = Build.VERSION.SDK_INT >= 30 && caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
        Log.w(SCREEN_TAG, "${c.name}: FEATURE_LowLatency=$advertised, max ${maxFps?.toInt()} fps at ${w}x$h, latency vendor parameters ${vendor?.let(Tuning::latencyParams) ?: "unknown"}")
        for (tier in Tier.entries) {
            val f = format(w, h, fps, caps, tier, maxFps, vendor)
            try {
                c.setCallback(callback(frames), handler)
                c.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            } catch (e: Exception) {
                if (tier == Tier.PLAIN) throw e
                Log.w(SCREEN_TAG, "${c.name} refused the $tier format, trying the next", e)
                c.reset()
                continue
            }
            if (tier == Tier.TUNED && vendor == null) lateVendorKeys(c)
            val profile = if (f.containsKey(MediaFormat.KEY_PROFILE)) Tuning.profileName(f.getInteger(MediaFormat.KEY_PROFILE)) else "encoder's default profile"
            Log.w(SCREEN_TAG, "encoding ${w}x$h@$fps ($tier, $profile) for screen ${s.w}x${s.h} with ${c.name}: $f")
            Log.w(SCREEN_TAG, "${c.name} took: input ${runCatching { c.inputFormat }.getOrNull()}, output ${runCatching { c.outputFormat }.getOrNull()}")
            return
        }
    }

    /** API 31+; null if unknown (older Android, or the encoder won't say before configure). */
    private fun vendorParams(c: MediaCodec): List<String>? =
        if (Build.VERSION.SDK_INT >= 31) runCatching { c.supportedVendorParameters }.getOrNull() else null

    /** The vendor keys, for an encoder that only lists its vendor parameters once configured. */
    private fun lateVendorKeys(c: MediaCodec) {
        val keys = Tuning.vendorKeys(vendorParams(c)).takeIf { it.isNotEmpty() } ?: return
        runCatching { c.setParameters(Bundle().apply { keys.forEach { (k, v) -> putInt(k, v) } }) }
            .onSuccess { Log.w(SCREEN_TAG, "${c.name}: set $keys after configure") }
            .onFailure { Log.w(SCREEN_TAG, "${c.name}: $keys refused after configure", it) }
    }

    private fun format(w: Int, h: Int, fps: Int, caps: MediaCodecInfo.CodecCapabilities, tier: Tier, maxFps: Double?, vendor: List<String>?) =
        MediaFormat.createVideoFormat(AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, synchronized(inFlight) { pacer.bitrate }) // a rotation keeps what the link settled on
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            // Over QUIC nothing is lost, so periodic keyframes only cost spikes: they come on demand (KEYFRAME, catching up).
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_S)
            // A still screen still sends 10 fps, so nothing looks stuck. Only fires after 100 ms without a new
            // frame, so it never competes with a moving picture.
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000)
            if (tier == Tier.PLAIN) return@apply
            setInteger(MediaFormat.KEY_PRIORITY, 0) // realtime
            setInteger(MediaFormat.KEY_OPERATING_RATE, Tuning.operatingRate(tier, fps, maxFps))
            setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, fps.toFloat()) // 90/120 Hz screens: don't feed it more than it's set for
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0) // B-frames add a frame of delay each
            setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1) // SPS/PPS with every keyframe: any keyframe restarts the Mac
            // VBR: screens are mostly still, then burst on a scroll. CBR would spend the whole budget on still frames
            // (keeping the link loaded) for no gain; the Pacer handles bursts the link can't take.
            if (caps.encoderCapabilities?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR) == true) {
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            }
            Tuning.level(caps.profileLevels.map { it.profile to it.level })?.let {
                setInteger(MediaFormat.KEY_PROFILE, Tuning.PROFILE)
                setInteger(MediaFormat.KEY_LEVEL, it)
            }
            val lowLatency = Build.VERSION.SDK_INT >= 30 &&
                (tier == Tier.TUNED || caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency))
            if (lowLatency) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            if (tier != Tier.TUNED) return@apply
            // FEATURE_LowLatency is documented for decoders and encoders rarely advertise it, but Codec2 passes
            // KEY_LOW_LATENCY to encoders too; KEY_LATENCY 1 asks for an output for every input (no lookahead).
            if (Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LATENCY, 1)
            setLong(MediaFormat.KEY_MAX_PTS_GAP_TO_ENCODER, Tuning.maxPtsGapUs(fps))
            Tuning.vendorKeys(vendor).forEach { (k, v) -> setInteger(k, v) }
        }

    private companion object {
        const val AVC = MediaFormat.MIMETYPE_VIDEO_AVC
        const val I_FRAME_INTERVAL_S = 30
        /** Encoded frames waiting for the writer; past this the link is far behind anyway. */
        const val FRAME_QUEUE = 32
    }
}
