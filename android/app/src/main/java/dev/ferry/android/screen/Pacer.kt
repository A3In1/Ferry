package dev.ferry.android.screen

import kotlin.math.roundToInt

/**
 * Keeps the picture live: decides per encoded frame whether to send it, and adapts the encoder's bitrate
 * to the link (never above the Mac's START bitrate). Pure, so it's unit-tested; times in ms.
 *
 * The signal is the Mac's ACKs: write → ACK round trip above the best recent one is queueing on the link,
 * and the age of the oldest unacked FRAME is how stale the picture on the Mac is. QUIC buffers a lot
 * before a write blocks (up to its flow-control window, about a second of video), so ACKs see a backlog
 * long before write timing does. A Mac that never ACKs (older build) falls back to write timing.
 *
 * Behind ([LAG_MS] unacked, or frames piling up locally): drop frames (P-frames reference each other,
 * so all of them) until the link has drained, then ask for one keyframe and resume from it. Hysteresis:
 * down after two hot windows (one if very hot), up only after three calm ones and [HOLD_MS] after a cut.
 */
class Pacer(private val ceiling: Int, now: Long) {
    /** [bitrate]: the encoder's new target, if it changed. */
    data class Decision(val send: Boolean, val askKeyframe: Boolean = false, val bitrate: Int? = null)

    private val floor = maxOf(MIN_BPS, ceiling / 8).coerceAtMost(ceiling)
    var bitrate = ceiling
        private set
    /** Dropping until a keyframe. */
    var skipping = false
        private set
    private var askedAt = NEVER
    private var lastCut = NEVER
    private var acking = false

    private var windowStart = now
    private var blocked = 0L
    private var rttSum = 0L
    private var rttN = 0
    private var rttMin = Long.MAX_VALUE
    private val mins = LongArray(BASE_WINDOWS) { Long.MAX_VALUE }
    private var minsAt = 0
    private var hot = 0
    private var calm = 0

    /** Before writing a FRAME. [backlogMs]: age of the oldest unacked FRAME (null: the Mac doesn't ACK); [queued]: frames waiting behind it. */
    fun admit(key: Boolean, config: Boolean, backlogMs: Long?, queued: Int, now: Long): Decision {
        if (config) return Decision(true) // SPS/PPS: tiny, and the decoder needs them
        val drained = backlogMs == null || backlogMs <= DRAINED_MS
        if (skipping) {
            if (key && drained) {
                skipping = false
                return Decision(true)
            }
            if (drained && now - askedAt >= ASK_EVERY_MS) {
                askedAt = now
                return Decision(false, askKeyframe = true)
            }
            return Decision(false)
        }
        if (backlogMs != null && backlogMs >= LAG_MS || queued >= QUEUE_MAX) return Decision(false, bitrate = behind(now))
        return Decision(true)
    }

    /** A FRAME write → ACK round trip. */
    fun rtt(ms: Long) {
        acking = true
        rttSum += ms
        rttN++
        rttMin = minOf(rttMin, ms)
    }

    /** One FRAME was written in [writeMs]. The encoder's new bitrate, if it changed. */
    fun sent(writeMs: Long, now: Long): Int? {
        blocked += writeMs
        if (!acking && writeMs >= STALL_MS) return behind(now) // no ACKs: a stalled write is the only sign
        val elapsed = now - windowStart
        if (elapsed < WINDOW_MS) return null
        val (isHot, veryHot, clear) = if (acking) {
            mins[minsAt++ % BASE_WINDOWS] = rttMin
            val base = mins.min()
            // No ACK for a whole window while sending: the link is stuck.
            val delay = if (rttN == 0) Long.MAX_VALUE else rttSum / rttN - base
            Triple(delay > HOT_DELAY_MS, delay > 2 * HOT_DELAY_MS, delay < CLEAR_DELAY_MS)
        } else {
            val busy = blocked.toDouble() / elapsed
            Triple(busy > CONGESTED, false, busy < CLEAR)
        }
        windowStart = now
        blocked = 0
        rttSum = 0
        rttN = 0
        rttMin = Long.MAX_VALUE
        if (isHot) {
            calm = 0
            if (++hot >= 2 || veryHot) {
                hot = 0
                lastCut = now
                return set((bitrate * DOWN).roundToInt())
            }
            return null
        }
        hot = 0
        if (!clear) {
            calm = 0
            return null
        }
        if (++calm < CALM_WINDOWS || now - lastCut < HOLD_MS) return null
        calm = 0
        return set((bitrate * UP).roundToInt())
    }

    /** The Mac's picture is stale: skip to a keyframe once the link drains, and cut the bitrate (at most once per window). */
    private fun behind(now: Long): Int? {
        skipping = true
        askedAt = NEVER
        if (now - lastCut < WINDOW_MS) return null
        lastCut = now
        hot = 0
        calm = 0
        return set((bitrate * BEHIND).roundToInt())
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
        /** Write → ACK above the best of the last [BASE_WINDOWS] windows. */
        const val HOT_DELAY_MS = 80L
        const val CLEAR_DELAY_MS = 25L
        const val BASE_WINDOWS = 30 // long enough that a link kept full isn't taken as the new normal; a route change settles within it
        /** Without ACKs: share of the window spent blocked in writes. */
        const val CONGESTED = 0.5
        const val CLEAR = 0.1
        const val CALM_WINDOWS = 3
        const val HOLD_MS = 5_000L
        const val DOWN = 0.75
        const val UP = 1.15
        const val BEHIND = 0.6
        /** The oldest unacked FRAME is this old: what the Mac shows is stale. */
        const val LAG_MS = 300L
        /** Resume (ask for the keyframe) once the oldest unacked FRAME is younger than this. */
        const val DRAINED_MS = 60L
        /** Frames waiting for the writer (it's blocked): the link isn't keeping up. */
        const val QUEUE_MAX = 6
        const val STALL_MS = 400L
        const val ASK_EVERY_MS = 1_000L
        private const val NEVER = Long.MIN_VALUE / 2
    }
}
