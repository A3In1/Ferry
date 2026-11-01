package dev.ferry.android.screen

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.GestureDescription.StrokeDescription
import android.content.Context
import android.content.Intent
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
 * from an open screen session ([Session] checks [instance]); turning it off drops caps bit1.
 */
class ControlService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private var cursor: View? = null
    private var downAt = 0L
    private var down: PointF? = null
    private var last = PointF()
    private var lastAt = 0L
    private var stroke: StrokeDescription? = null
    private var scrollDrag: ScrollDrag? = null
    private var scrollStroke: StrokeDescription? = null
    private var scrolling = false

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

    /** From the session's reader thread; applied in order on the main thread. */
    fun input(m: Msg) {
        main.post {
            when (m) {
                is Msg.Touch -> touch(m.action, m.x, m.y)
                is Msg.Cursor -> cursor(m.action, m.x, m.y)
                is Msg.Scroll -> scroll(m)
                is Msg.Nav -> NAV[m.action]?.let(::performGlobalAction)
                is Msg.Text -> edit { it + m.text }
                is Msg.Key -> key(m.code)
                else -> {}
            }
        }
    }

    /** Session over: hide the cursor and lift a finger that's still down. */
    fun reset() = main.post {
        stopScroll(lift = true)
        if (stroke != null) segment(last, SystemClock.uptimeMillis(), false)
        down = null
        stroke = null
        cursor?.let { runCatching { getSystemService(WindowManager::class.java).removeView(it) } }
        cursor = null
    }

    private fun px(x: Float, y: Float): PointF {
        val s = screenSize(this)
        return PointF(x.coerceIn(0f, 1f) * (s.w - 1), y.coerceIn(0f, 1f) * (s.h - 1))
    }

    /** A drag is played back as continued strokes as it arrives; a press that never moves becomes a tap on up. */
    private fun touch(action: Int, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        val p = px(x, y)
        when (action) {
            0 -> {
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
                segment(p, now, true)
            }
            1 -> {
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
        val d = scrollDrag?.takeIf { it.busy } ?: screenSize(this).let { s ->
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

    private fun cursor(action: Int, x: Float, y: Float) {
        val wm = getSystemService(WindowManager::class.java)
        if (action == 3) {
            cursor?.let { runCatching { wm.removeView(it) } }
            cursor = null
            return
        }
        val p = px(x, y)
        val v = cursor ?: Pointer(this).also {
            val size = (24 * resources.displayMetrics.density).toInt()
            val lp = WindowManager.LayoutParams(
                size,
                size,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            )
            lp.gravity = Gravity.TOP or Gravity.START
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            wm.addView(it, lp)
            cursor = it
        }
        val lp = v.layoutParams as WindowManager.LayoutParams
        lp.x = p.x.toInt()
        lp.y = p.y.toInt()
        wm.updateViewLayout(v, lp)
        touch(action, x, y)
    }

    // shortcut: TEXT appends at the end (not at the caret) and can't edit password fields, whose text is hidden from us;
    // use ACTION_SET_SELECTION + caret-aware edits if users type mid-text.
    private fun edit(change: (String) -> String?) {
        val n = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable } ?: return
        val now = if (n.isShowingHintText) "" else n.text?.toString().orEmpty()
        val next = change(now) ?: return
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, next) }
        n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun key(code: Long) {
        when (code) {
            67L -> edit { if (it.isEmpty()) null else it.substring(0, it.offsetByCodePoints(it.length, -1)) }
            66L -> {
                val n = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable } ?: return
                if (Build.VERSION.SDK_INT >= 30) {
                    n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                } else if (n.isMultiLine) {
                    edit { it + "\n" }
                }
            }
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

        private val NAV = mapOf(
            1 to GLOBAL_ACTION_BACK,
            2 to GLOBAL_ACTION_HOME,
            3 to GLOBAL_ACTION_RECENTS,
            4 to GLOBAL_ACTION_NOTIFICATIONS,
        )

        fun openSettings(ctx: Context) = ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
