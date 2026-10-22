package com.fergusonconsultancy.foldtaskbar

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.hypot

interface DragListener {
    fun onTouched()
    /** The app under this screen point in [host], if any. */
    fun appAt(host: BarHost, x: Float, y: Float): String?
    fun onAppDragStart(host: BarHost, pkg: String, x: Float, y: Float)
    fun onAppDrag(x: Float, y: Float)
    fun onAppDragEnd(x: Float, y: Float, cancelled: Boolean)
    fun onDragStart(host: BarHost)
    fun onDrag(host: BarHost, dx: Float, dy: Float)
    fun onDragEnd(host: BarHost)
}

/**
 * Holds one capsule in its window. Two gestures start here:
 * - Press an app and slide it straight out: the app comes with your finger, to drop as a
 *   split half or a pop-up.
 * - Hold anywhere until the bar lifts, then slide: the whole bar moves.
 * A quick tap or a hold without moving still reaches the icons as normal.
 */
@SuppressLint("ViewConstructor")
class BarHost(context: Context, private val listener: DragListener) : FrameLayout(context) {

    private val slop = ViewConfiguration.get(context).scaledTouchSlop * 1.5f
    private val holdMs = ViewConfiguration.getLongPressTimeout().toLong()
    private var downX = 0f
    private var downY = 0f
    private var armed = false
    var dragging = false
        private set
    private var appDrag: String? = null

    private val arm = Runnable {
        armed = true
        // Picked up: the capsule rises a little towards you
        animate().scaleX(1.05f).scaleY(1.05f).setDuration(120).setInterpolator(Curves.OPEN).start()
    }

    init {
        clipChildren = false
        clipToPadding = false
        val p = context.dp(10)
        setPadding(p, p, p, p)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                listener.onTouched()
                downX = ev.rawX; downY = ev.rawY
                armed = false; dragging = false; appDrag = null
                postDelayed(arm, holdMs)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.rawX - downX
                val dy = ev.rawY - downY
                if (dragging) { listener.onDrag(this, dx, dy); return true }
                if (appDrag != null) { listener.onAppDrag(ev.rawX, ev.rawY); return true }
                if (hypot(dx, dy) > slop) {
                    if (!armed) {
                        val pkg = listener.appAt(this, downX, downY)
                        if (pkg != null) {
                            removeCallbacks(arm)
                            appDrag = pkg
                            performHapticFeedback(HapticFeedbackConstants.GESTURE_START)
                            val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
                            super.dispatchTouchEvent(cancel)
                            cancel.recycle()
                            listener.onAppDragStart(this, pkg, ev.rawX, ev.rawY)
                            return true
                        }
                    }
                    if (armed) {
                        dragging = true
                        performHapticFeedback(HapticFeedbackConstants.GESTURE_START)
                        val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
                        super.dispatchTouchEvent(cancel)
                        cancel.recycle()
                        listener.onDragStart(this)
                        listener.onDrag(this, dx, dy)
                        return true
                    }
                    removeCallbacks(arm)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(arm)
                if (armed) animate().scaleX(1f).scaleY(1f).setDuration(160).setInterpolator(Curves.OPEN).start()
                val wasDragging = dragging
                val wasApp = appDrag
                armed = false; dragging = false; appDrag = null
                if (wasDragging) { listener.onDragEnd(this); return true }
                if (wasApp != null) {
                    listener.onAppDragEnd(ev.rawX, ev.rawY, ev.actionMasked == MotionEvent.ACTION_CANCEL)
                    return true
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }
}

/**
 * The collapsed bar. Built like a mouse pointer: a fill with a ring of the opposite tone,
 * so one of the two always stands out. The fill also flips to oppose whatever is on
 * screen behind it.
 */
class TabView(context: Context) : View(context) {
    var edge: Char = 'R'
        set(v) { field = v; invalidate() }

    private var darkFill = true
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x40000000 }
    private val r = RectF()

    /** Light background behind the tab: dark tab with a light ring. Dark background: the reverse. */
    fun setBackdropLight(light: Boolean) {
        if (darkFill == light) return
        darkFill = light
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val upright = edge == 'L' || edge == 'R'
        val long = (if (upright) height else width) * 0.72f
        val thick = context.dp(9).toFloat()
        val cx = when (edge) { 'L' -> context.dp(4) + thick / 2; 'R' -> width - context.dp(4) - thick / 2; else -> width / 2f }
        val cy = when (edge) { 'T' -> context.dp(4) + thick / 2; 'B' -> height - context.dp(4) - thick / 2; else -> height / 2f }
        if (upright) r.set(cx - thick / 2, cy - long / 2, cx + thick / 2, cy + long / 2)
        else r.set(cx - long / 2, cy - thick / 2, cx + long / 2, cy + thick / 2)

        fill.color = if (darkFill) 0xFF101412.toInt() else 0xFFF6F1E6.toInt()
        ring.color = if (darkFill) 0xFFF6F1E6.toInt() else 0xFF101412.toInt()
        ring.strokeWidth = context.dp(2).toFloat()

        val rad = thick / 2
        canvas.save(); canvas.translate(0f, context.dp(1.5f).toFloat())
        canvas.drawRoundRect(r, rad, rad, shadow)
        canvas.restore()
        canvas.drawRoundRect(r, rad, rad, fill)
        canvas.drawRoundRect(r, rad, rad, ring)
    }
}
