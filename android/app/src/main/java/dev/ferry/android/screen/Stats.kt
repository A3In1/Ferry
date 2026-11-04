package dev.ferry.android.screen

import java.util.Locale

// Pure mirroring measurements (no Android types, so they're unit-tested on the JVM). Times in ms unless named.

/**
 * FRAMEs written but not yet ACKed by the Mac, oldest first. ACK names the newest frame the Mac has, so
 * everything up to and including it has arrived. A Mac that never ACKs (older build) just fills the ring.
 */
class InFlight(private val cap: Int = 512) {
    private val pts = LongArray(cap)
    private val at = LongArray(cap)
    private val size = IntArray(cap)
    private var head = 0
    private var n = 0

    /** Bytes written and not yet ACKed. */
    var bytes = 0L
        private set

    /** Any ACK seen: the Mac speaks ACK, so [oldestAge] and [bytes] mean something. */
    var acking = false
        private set

    val count get() = n

    fun sent(ptsUs: Long, bytes: Int, now: Long) {
        if (n == cap) pop()
        val i = (head + n) % cap
        pts[i] = ptsUs
        at[i] = now
        size[i] = bytes
        this.bytes += bytes
        n++
    }

    /** The round trip (write → ACK) of the acked frame, or null if it isn't in flight (late or duplicate ACK). */
    fun acked(ptsUs: Long, now: Long): Long? {
        acking = true
        var k = 0
        while (k < n && pts[(head + k) % cap] != ptsUs) k++
        if (k == n) return null
        val sentAt = at[(head + k) % cap]
        repeat(k + 1) { pop() }
        return now - sentAt
    }

    /** How long the oldest unacked frame has been on the way; 0 if none. */
    fun oldestAge(now: Long) = if (n == 0) 0L else now - at[head]

    fun clear() {
        while (n > 0) pop()
    }

    private fun pop() {
        bytes -= size[head]
        head = (head + 1) % cap
        n--
    }
}

/** Per-window counters for the phone's log line and the STATS message. */
class SendStats(private var windowStart: Long) {
    private var encoded = 0
    private var sent = 0
    private var dropped = 0
    private var bytes = 0L
    private var encodeUs = 0L
    private var encodeN = 0
    private var writeMs = 0L
    private var rttMs = 0L
    private var rttN = 0
    private var rttMin = Long.MAX_VALUE

    /** One frame out of the encoder, [captureToOutputUs] after capture (null if the clock doesn't match). */
    fun encoded(captureToOutputUs: Long?) {
        encoded++
        if (captureToOutputUs != null && captureToOutputUs in 0..1_000_000) {
            encodeUs += captureToOutputUs
            encodeN++
        }
    }

    fun sent(bytes: Int, writeMs: Long) {
        sent++
        this.bytes += bytes
        this.writeMs += writeMs
    }

    fun dropped() {
        dropped++
    }

    fun rtt(ms: Long) {
        rttMs += ms
        rttN++
        rttMin = minOf(rttMin, ms)
    }

    /** The window's numbers once [WINDOW_MS] have passed (and a new window starts), else null. */
    fun report(now: Long, targetBps: Int): Msg.Stats? {
        val ms = now - windowStart
        if (ms < WINDOW_MS) return null
        fun tenth(totalTenths: Long, count: Int) = if (count == 0) Msg.Stats.UNKNOWN else (totalTenths / count).toInt()
        val s = Msg.Stats(
            windowMs = ms.toInt().coerceAtMost(0xffff),
            encoded = encoded,
            sent = sent,
            dropped = dropped,
            targetBps = targetBps,
            outBps = (bytes * 8 * 1000 / ms).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            encodeTenthMs = tenth(encodeUs / 100, encodeN),
            writeTenthMs = tenth(writeMs * 10, sent),
            rttTenthMs = tenth(rttMs * 10, rttN),
            rttMinTenthMs = if (rttN == 0) Msg.Stats.UNKNOWN else (rttMin * 10).toInt(),
        )
        windowStart = now
        encoded = 0
        sent = 0
        dropped = 0
        bytes = 0
        encodeUs = 0
        encodeN = 0
        writeMs = 0
        rttMs = 0
        rttN = 0
        rttMin = Long.MAX_VALUE
        return s
    }

    companion object {
        const val WINDOW_MS = 2_000L
    }
}

/** The phone's `adb logcat -s FerryScreen` line for one STATS window. */
fun Msg.Stats.line(inFlight: Int): String {
    val s = windowMs / 1000.0
    fun t(v: Int) = if (v == Msg.Stats.UNKNOWN) "-" else "%.1f".format(Locale.ROOT, v / 10.0)
    return "stats: encoded %.0f fps, sent %.0f fps, dropped %d | %.2f of %.2f Mbps | capture→encoded %s ms, write %s ms, write→ack %s ms (min %s), in flight %d"
        .format(Locale.ROOT, encoded / s, sent / s, dropped, outBps / 1e6, targetBps / 1e6, t(encodeTenthMs), t(writeTenthMs), t(rttTenthMs), t(rttMinTenthMs), inFlight)
}
