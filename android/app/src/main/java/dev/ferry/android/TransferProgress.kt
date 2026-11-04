package dev.ferry.android

import java.util.Locale
import java.util.UUID

/**
 * One transfer's progress from the core's per-file events. Up to 8 files are in flight at once,
 * so bytes are summed per file; speed is the slope over the last few seconds, so a resumed
 * file's head start never counts as speed. Not thread-safe (Transfers handles events on one coroutine).
 */
class TransferProgress(var names: List<String>, var totalBytes: Long = 0) {
    private val done = HashMap<UInt, Long>()
    private val finished = HashSet<UInt>()
    private var current: UInt? = null
    private val samples = ArrayDeque<Pair<Long, Long>>() // (elapsed ms, bytes done)

    val bytesDone: Long get() = done.values.sum().let { if (totalBytes > 0) it.coerceAtMost(totalBytes) else it }

    /** 0..1, or null while the size isn't known (indeterminate bar). */
    val fraction: Float? get() = if (totalBytes > 0) (bytesDone.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f) else null

    val fileCount: Int get() = names.size

    /** 1-based number of the file being worked on, for "File 2 of 3"; never past the last. */
    val fileNumber: Int get() = (finished.size + 1).coerceAtMost(names.size.coerceAtLeast(1))

    val currentName: String? get() = current?.toInt()?.let { names.getOrNull(it) }

    fun progress(index: UInt, bytes: Long, total: Long, nowMs: Long) {
        done[index] = bytes.coerceAtMost(total)
        if (bytes >= total) finished += index
        current = index
        samples.addLast(nowMs to bytesDone)
        while (samples.size > 2 && nowMs - samples.first().first > WINDOW_MS) samples.removeFirst()
        while (samples.size > 64) samples.removeFirst()
    }

    /** Saved or failed: no longer in progress. */
    fun finish(index: UInt) {
        finished += index
    }

    /** Bytes per second, or null until there's enough to say (or nothing moved lately). */
    fun speed(nowMs: Long): Double? {
        val (t0, b0) = samples.firstOrNull() ?: return null
        val (t1, b1) = samples.last()
        if (t1 - t0 < 500 || nowMs - t1 >= WINDOW_MS) return null
        return (b1 - b0).coerceAtLeast(0) * 1000.0 / (t1 - t0)
    }

    fun secondsLeft(nowMs: Long): Double? {
        val s = speed(nowMs)?.takeIf { it > 0 && totalBytes > 0 } ?: return null
        return (totalBytes - bytesDone).coerceAtLeast(0) / s
    }

    /** "12 MB of 40 MB · 25.3 MB/s · 2 s left"; [size] formats bytes (Formatter on the device). */
    fun detail(nowMs: Long, size: (Long) -> String): String {
        if (totalBytes <= 0) return ""
        val parts = mutableListOf("${size(bytesDone)} of ${size(totalBytes)}")
        speed(nowMs)?.let { parts += String.format(Locale.US, "%.1f MB/s", it / 1e6) }
        if (bytesDone < totalBytes) secondsLeft(nowMs)?.let { parts += timeLeft(it) }
        return parts.joinToString(" · ")
    }

    companion object {
        const val WINDOW_MS = 3_000L

        /** "5 s left", "3 min left", "about 2 h left". */
        fun timeLeft(seconds: Double): String {
            val s = kotlin.math.ceil(seconds).toInt()
            return when {
                s < 60 -> "${s.coerceAtLeast(1)} s left"
                s < 3600 -> "${(s + 59) / 60} min left"
                else -> "about ${(s + 1800) / 3600} h left"
            }
        }

        /** A transfer id the core accepts and reports back: 32 lowercase hex chars. */
        fun newId(): String = UUID.randomUUID().toString().replace("-", "")
    }
}
