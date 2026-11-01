package dev.ferry.android.screen

// Pure input helpers for ControlService (no Android types, so they're unit-tested on the JVM).

/**
 * SCROLL as one continued finger drag (Accessibility can only replay strokes, not wheel events).
 * Deltas are summed and played as one short segment per frame, so a trackpad scroll and its momentum
 * are a single drag that follows the Mac instead of a row of flicks. The finger holds still before it
 * lifts, so Android doesn't fling on top of the Mac's own momentum. It lifts when the Mac says the
 * scroll ended (after [GRACE_MS], in case momentum follows the finger lift), after a pause, or to
 * re-grip at the start point when it would run off the screen. Coordinates are screen px.
 */
class ScrollDrag(private val w: Float, private val h: Float, private val margin: Float) {
    /** One stroke to dispatch, from → to over [ms]. [first] = a new finger down, else it continues the last; [more] = willContinue. */
    data class Step(val fromX: Float, val fromY: Float, val toX: Float, val toY: Float, val ms: Long, val first: Boolean, val more: Boolean)

    private var down = false
    private var startX = 0f
    private var startY = 0f
    private var x = 0f
    private var y = 0f
    private var dx = 0f
    private var dy = 0f
    private var lastInput = 0L
    private var lastStep = 0L
    private var idleMs = 0L
    private var endAt = Long.MAX_VALUE
    private var holdUntil = 0L
    private var regrip = false

    /** Something left to play or a finger still down: keep calling [step] every frame. */
    val busy get() = down || dx != 0f || dy != 0f

    /** [px], [py]: the pointer (where a new stroke starts). [ddx], [ddy]: finger travel in px (+y = down = scroll up). */
    fun add(px: Float, py: Float, ddx: Float, ddy: Float, continuous: Boolean, end: Boolean, now: Long) {
        if (!down) {
            startX = px.coerceIn(margin, w - margin)
            startY = py.coerceIn(margin, h - margin)
        }
        dx += ddx
        dy += ddy
        lastInput = now
        // Trackpads say when they end, so a pause there is a finger resting; wheels never do.
        idleMs = if (continuous) TRACKPAD_IDLE_MS else WHEEL_IDLE_MS
        endAt = if (end) now + GRACE_MS else Long.MAX_VALUE
    }

    /** What to dispatch this frame, or null. */
    fun step(now: Long): Step? {
        if (now < holdUntil) return null // the last lift is still playing
        if (regrip) return lift(now)
        if (dx != 0f || dy != 0f) {
            val fx = if (down) x else startX
            val fy = if (down) y else startY
            val tx = (fx + dx).coerceIn(margin, w - margin)
            val ty = (fy + dy).coerceIn(margin, h - margin)
            if (!down) { // a fresh stroke can't go further along an axis it's already at the end of
                if (tx == fx) dx = 0f
                if (ty == fy) dy = 0f
            }
            if (tx == fx && ty == fy) return if (down) lift(now) else null
            dx -= tx - fx
            dy -= ty - fy
            regrip = dx != 0f || dy != 0f // ran into the edge: lift and carry the rest into a new stroke
            val first = !down
            val ms = if (first) FRAME_MS else (now - lastStep).coerceIn(MIN_SEGMENT_MS, MAX_SEGMENT_MS)
            down = true
            x = tx
            y = ty
            lastStep = now
            return Step(fx, fy, tx, ty, ms, first, more = true)
        }
        if (down && (now >= endAt || now - lastInput >= idleMs)) return lift(now)
        return null
    }

    /** Stops the scroll; returns where the finger is if it's still down, for the caller to lift it there. */
    fun cancel(): Pair<Float, Float>? {
        val at = if (down) x to y else null
        down = false
        dx = 0f
        dy = 0f
        regrip = false
        endAt = Long.MAX_VALUE
        return at
    }

    private fun lift(now: Long): Step? {
        regrip = false
        endAt = Long.MAX_VALUE
        if (!down) return null
        down = false
        holdUntil = now + HOLD_MS + FRAME_MS
        return Step(x, y, x, y, HOLD_MS, first = false, more = false)
    }

    companion object {
        const val FRAME_MS = 16L
        const val MIN_SEGMENT_MS = 8L
        const val MAX_SEGMENT_MS = 50L
        /** Still for longer than VelocityTracker's 40 ms "pointer stopped" window, so the lift doesn't fling. */
        const val HOLD_MS = 50L
        const val GRACE_MS = 40L
        const val WHEEL_IDLE_MS = 120L
        const val TRACKPAD_IDLE_MS = 500L
    }
}
