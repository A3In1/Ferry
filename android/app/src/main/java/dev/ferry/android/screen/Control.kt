package dev.ferry.android.screen

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.GestureDescription.StrokeDescription
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PointF
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.hypot

/**
 * "Ferry control": off until the user turns it on in Accessibility settings. Acts only on input
 * from an open Share Cursor session ([Session] checks [instance]); turning it off drops caps bit1.
 */
class ControlService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private val moves = Moves()
    private var cursor: View? = null // the full-screen overlay window
    private var pointer: View? = null
    private var awake: View? = null
    private var cursorMoves = 0
    private var cursorSince = 0L
    private var size: ScreenSize? = null
    private var downAt = 0L
    private var down: PointF? = null
    private var last = PointF()
    private var lastAt = 0L
    private var stroke: StrokeDescription? = null
    private var pendingMove: PointF? = null
    private var moveQueued = false
    private var scrollDrag: ScrollDrag? = null
    private var scrollStroke: StrokeDescription? = null
    private var scrolling = false
    private val edits = mutableListOf<Msg>()

    override fun onServiceConnected() {
        instance.value = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance.value === this) instance.value = null
        reset()
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    /**
     * From the session's reader thread; applied in order on the main thread. Cursor moves are merged: only
     * the newest waits for the main thread, so a busy moment never leaves a queue of old moves to replay.
     * A later non-move message brings the merged move along first, so order holds.
     */
    fun input(m: Msg) {
        if (m is Msg.Cursor && m.action == 2) {
            synchronized(moves) {
                val first = moves.latest == null
                moves.latest = m
                if (first) {
                    val gen = moves.gen
                    main.post { takeMove(gen)?.let { cursor(it.action, it.x, it.y) } }
                }
            }
            return
        }
        val before = synchronized(moves) {
            moves.gen++
            moves.latest.also { moves.latest = null }
        }
        before?.let { b -> main.post { cursor(b.action, b.x, b.y) } }
        main.post {
            if (m is Msg.Text || m is Msg.Key) {
                edits += m
                if (edits.size == 1) main.postDelayed(applyEdits, EDIT_BATCH_MS)
                return@post
            }
            applyEdits.run() // typing lands before the tap or nav that follows it
            when (m) {
                is Msg.Cursor -> cursor(m.action, m.x, m.y)
                is Msg.Scroll -> scroll(m)
                else -> {}
            }
        }
    }

    /** The merged cursor move posted in generation [gen], unless a non-move message took it along since. */
    private fun takeMove(gen: Int): Msg.Cursor? = synchronized(moves) {
        if (moves.gen != gen) return null
        moves.latest.also { moves.latest = null }
    }

    private class Moves {
        var latest: Msg.Cursor? = null
        var gen = 0
    }

    /** Session over: hide the cursor and lift a finger that's still down. */
    fun reset() = main.post {
        edits.clear()
        main.removeCallbacks(applyEdits)
        stopScroll(lift = true)
        dropMove()
        if (stroke != null) segment(last, SystemClock.uptimeMillis(), false)
        down = null
        stroke = null
        hideCursor()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        size = null // rotated, folded, …
    }

    /** The screen size, cached: input arrives up to every frame and the lookup isn't free. */
    private fun size() = size ?: screenSize(this).also { size = it }

    private fun px(x: Float, y: Float): PointF {
        val s = size()
        return PointF(x.coerceIn(0f, 1f) * (s.w - 1), y.coerceIn(0f, 1f) * (s.h - 1))
    }

    /** A drag is played back as continued strokes as it arrives; a press that never moves becomes a tap on up. */
    private fun touch(action: Int, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        val p = px(x, y)
        when (action) {
            0 -> {
                dropMove()
                stopScroll(lift = false) // the touch's own gesture replaces the scroll's
                if (stroke != null) segment(last, now, false)
                stroke = null
                down = p
                downAt = now
                last = p
                lastAt = now
            }
            2 -> {
                val d = down ?: return
                if (stroke == null && hypot(p.x - d.x, p.y - d.y) < ViewConfiguration.get(this).scaledTouchSlop) return
                // Moves can arrive in bursts (Wi-Fi batches packets): play the latest at most every MIN_SEGMENT_MS,
                // so the finger moves in even steps instead of 1 ms jumps that jitter and fling oddly.
                pendingMove = p
                if (!moveQueued) {
                    moveQueued = true
                    main.postDelayed(flushMove, (lastAt + ScrollDrag.MIN_SEGMENT_MS - now).coerceAtLeast(0))
                }
            }
            1 -> {
                pendingMove?.let { if (down != null && stroke == null) segment(it, now, true) } // a drag still queued isn't a tap
                dropMove()
                val d = down ?: return
                if (stroke == null) {
                    // Held time kept, so a long press stays a long press.
                    gesture(StrokeDescription(Path().apply { moveTo(d.x, d.y) }, 0, (now - downAt).coerceIn(50, 10_000)))
                } else {
                    segment(p, now, false)
                }
                down = null
                stroke = null
            }
        }
    }

    private val flushMove = Runnable {
        moveQueued = false
        val p = pendingMove ?: return@Runnable
        pendingMove = null
        if (down != null) segment(p, SystemClock.uptimeMillis(), true)
    }

    private fun dropMove() {
        if (moveQueued) main.removeCallbacks(flushMove)
        moveQueued = false
        pendingMove = null
    }

    private fun segment(p: PointF, now: Long, more: Boolean) {
        val path = Path().apply {
            moveTo(last.x, last.y)
            lineTo(p.x, p.y)
        }
        val dur = (now - lastAt).coerceIn(1, 10_000)
        val s = stroke?.continueStroke(path, 0, dur, more) ?: StrokeDescription(path, 0, dur, more)
        gesture(s)
        stroke = s
        last = p
        lastAt = now
    }

    private fun gesture(s: StrokeDescription) {
        dispatchGesture(GestureDescription.Builder().addStroke(s).build(), null, null)
    }

    /** SCROLL → [ScrollDrag], played one segment per frame by [scrollTick]. */
    private fun scroll(m: Msg.Scroll) {
        if (down != null) return // don't break a drag in progress
        val p = px(m.x, m.y)
        val d = scrollDrag?.takeIf { it.busy } ?: size().let { s ->
            ScrollDrag(s.w - 1f, s.h - 1f, 8 * resources.displayMetrics.density).also { scrollDrag = it }
        }
        // Trackpad points arrive as tenths of a line: 2 dp of finger per point; a wheel step is a list row's worth.
        val unit = (if (m.continuous) 20 else 40) * resources.displayMetrics.density
        d.add(p.x, p.y, m.dx * unit, m.dy * unit, m.continuous, m.end, SystemClock.uptimeMillis())
        if (!scrolling) {
            scrolling = true
            main.post(scrollTick)
        }
    }

    private val scrollTick: Runnable = object : Runnable {
        override fun run() {
            val d = scrollDrag
            if (d == null || !d.busy) {
                scrolling = false
                return
            }
            d.step(SystemClock.uptimeMillis())?.let { s ->
                val path = Path().apply {
                    moveTo(s.fromX, s.fromY)
                    lineTo(s.toX, s.toY)
                }
                val prev = scrollStroke
                val stroke = if (s.first || prev == null) StrokeDescription(path, 0, s.ms, s.more) else prev.continueStroke(path, 0, s.ms, s.more)
                scrollStroke = stroke.takeIf { s.more }
                gesture(stroke)
            }
            main.postDelayed(this, ScrollDrag.FRAME_MS)
        }
    }

    /** Ends a scroll; [lift] its finger, unless a touch is taking over (its new gesture replaces the stroke). */
    private fun stopScroll(lift: Boolean) {
        val at = scrollDrag?.cancel()
        val s = scrollStroke
        if (lift && s != null && at != null) gesture(s.continueStroke(Path().apply { moveTo(at.first, at.second) }, 0, 1, false))
        scrollStroke = null
    }

    /**
     * The overlay cursor: a full-screen, untouchable accessibility overlay with the arrow as a child. A move
     * only sets the arrow's translation (drawn on the next frame by the render thread): no window relayout.
     */
    private fun cursor(action: Int, x: Float, y: Float) {
        if (action == 3) return hideCursor()
        val p = px(x, y)
        if (cursor == null) {
            val arrow = Pointer(this)
            val size = (24 * resources.displayMetrics.density).toInt()
            val root = android.widget.FrameLayout(this).apply { addView(arrow, android.widget.FrameLayout.LayoutParams(size, size)) }
            val lp = overlayParams(WindowManager.LayoutParams.MATCH_PARENT, 0)
            if (runCatching { getSystemService(WindowManager::class.java).addView(root, lp) }.isSuccess) {
                cursor = root
                pointer = arrow
                cursorSince = SystemClock.uptimeMillis()
                cursorMoves = 0
                Log.w(SCREEN_TAG, "cursor: shown")
            }
        }
        pointer?.apply {
            translationX = p.x
            translationY = p.y
        }
        cursorMoves++
        val now = SystemClock.uptimeMillis()
        if (now - cursorSince >= 2_000) {
            Log.w(SCREEN_TAG, "cursor: ${cursorMoves * 1000 / (now - cursorSince)} updates/s")
            cursorSince = now
            cursorMoves = 0
        }
        touch(action, x, y)
    }

    private fun overlayParams(size: Int, extraFlags: Int) = WindowManager.LayoutParams(
        size,
        size,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or extraFlags,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        if (Build.VERSION.SDK_INT >= 30) fitInsetsTypes = 0 // the whole screen, so the arrow lands at screen px
    }

    private fun hideCursor() {
        cursor?.let {
            runCatching { getSystemService(WindowManager::class.java).removeView(it) }
            Log.w(SCREEN_TAG, "cursor: hidden")
        }
        cursor = null
        pointer = null
    }

    /**
     * Keeps the screen on while a session is open: a 1 px invisible overlay window with FLAG_KEEP_SCREEN_ON
     * (the supported way; wake locks that turn the screen on are deprecated). It never touches the keyguard:
     * the phone still locks if the user presses power, and that ends the session. Any thread.
     */
    fun keepAwake(on: Boolean) = main.post {
        val wm = getSystemService(WindowManager::class.java)
        if (on && awake == null) {
            val v = View(this)
            if (runCatching { wm.addView(v, overlayParams(1, WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)) }.isSuccess) {
                awake = v
                Log.w(SCREEN_TAG, "keeping the screen on")
            }
        } else if (!on) {
            awake?.let {
                runCatching { wm.removeView(it) }
                Log.w(SCREEN_TAG, "screen may sleep again")
            }
            awake = null
        }
    }

    /**
     * TEXT/KEY typed within [EDIT_BATCH_MS] of each other: applied to one snapshot of the focused field at
     * its caret/selection, then written back with one SET_TEXT (only if the text changed) and SET_SELECTION.
     */
    private val applyEdits: Runnable = object : Runnable {
        override fun run() {
            main.removeCallbacks(this) // run early (a tap followed the typing): drop the timer
            if (edits.isEmpty()) return
            // shortcut: password fields hide their text from us, so typing there replaces it; there's no safe way to read it.
            val n = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable } ?: return edits.clear()
            val hint = n.isShowingHintText
            val was = if (hint) Field("", 0, 0) else Field.of(n.text?.toString().orEmpty(), n.textSelectionStart, n.textSelectionEnd)
            var f = was
            while (edits.isNotEmpty()) {
                val m = edits.removeAt(0)
                val next = when (m) {
                    is Msg.Text -> f.type(m.text)
                    is Msg.Key -> f.key(m.code, m.meta) ?: if (m.code == Keys.ENTER && n.isMultiLine) f.type("\n") else null
                    else -> f
                }
                if (next != null) {
                    f = next
                    continue
                }
                // Not a text edit (up/down, Enter in a one-line field): write what we have, then let the field act;
                // the rest goes on a fresh snapshot.
                write(n, was, f)
                if (m is Msg.Key) fieldKey(n, m)
                if (edits.isNotEmpty()) main.post(this)
                return
            }
            write(n, was, f)
        }
    }

    private fun write(n: AccessibilityNodeInfo, was: Field, f: Field) {
        if (f == was) return
        val old = was.text
        if (f.text != old) {
            val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, f.text) }
            if (!n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return
        }
        // SET_TEXT leaves the caret at the end; skip the second call when that's where it belongs.
        if (f.text != old && f.anchor == f.text.length && f.caret == f.text.length) return
        val sel = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, f.anchor)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, f.caret)
        }
        n.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
    }

    /** Keys only the field can do: Enter (its IME action), up/down a line. */
    private fun fieldKey(n: AccessibilityNodeInfo, m: Msg.Key) {
        when (m.code) {
            Keys.ENTER -> if (Build.VERSION.SDK_INT >= 30) n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            Keys.UP, Keys.DOWN -> n.performAction(
                if (m.code == Keys.UP) AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY else AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY,
                Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT, AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE)
                    putBoolean(AccessibilityNodeInfo.ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN, m.meta and Keys.SHIFT != 0)
                },
            )
        }
    }

    /** Arrow with its tip at the view's top-left corner. */
    private class Pointer(ctx: Context) : View(ctx) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val arrow = Path()

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            val s = w / 24f
            arrow.reset()
            arrow.moveTo(1 * s, 1 * s)
            listOf(1 to 19, 6 to 14, 10 to 22, 13 to 21, 9 to 13, 16 to 13).forEach { (x, y) -> arrow.lineTo(x * s, y * s) }
            arrow.close()
        }

        override fun onDraw(canvas: Canvas) {
            val s = width / 24f
            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            canvas.drawPath(arrow, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.5f * s
            paint.color = Color.BLACK
            canvas.drawPath(arrow, paint)
        }
    }

    companion object {
        /** Bound by the system = the user turned "Ferry control" on. */
        val instance = MutableStateFlow<ControlService?>(null)

        /** Keystrokes closer together than this are applied as one edit. */
        const val EDIT_BATCH_MS = 8L

        fun openSettings(ctx: Context) = ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
