package dev.ferry.android.screen

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import androidx.core.os.bundleOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

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

/** H.264 from a VirtualDisplay into FRAME messages; INFO + a fresh encoder on every rotation. */
class Capture(private val ctx: Context, private val projection: MediaProjection, private val start: Msg.Start) {
    @Volatile private var codec: MediaCodec? = null

    fun keyframe() {
        runCatching { codec?.setParameters(bundleOf(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME to 0)) }
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
        while (isActive) {
            val i = c.dequeueOutputBuffer(info, 100_000)
            if (i < 0) continue
            val data = ByteArray(info.size)
            c.getOutputBuffer(i)?.apply { position(info.offset) }?.get(data)
            c.releaseOutputBuffer(i, false)
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            val key = if (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) 1 else 0
            val config = if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) 2 else 0
            if (data.isNotEmpty()) send(Msg.Frame(info.presentationTimeUs, key or config, data))
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
        setInteger(MediaFormat.KEY_BIT_RATE, start.bitrate.takeIf { it > 0 }?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: 8_000_000)
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
