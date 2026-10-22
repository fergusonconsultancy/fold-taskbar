package com.fergusonconsultancy.foldtaskbar

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import android.view.WindowManager

/** Where a dragged app will land if you let go now. */
enum class Drop { LEFT, RIGHT, POPUP, NONE }

/**
 * The guide shown while you drag an app out of the bar. Near the left or right edge the
 * half it will take is drawn as a panel, and the crease lights up between them; in the
 * middle, the pop-up window it will become is drawn under your finger. Over the bar,
 * nothing: letting go there cancels.
 *
 * The guide never takes touches; your finger stays with the bar that started the drag.
 */
class DragGuide(private val context: Context, private val wm: WindowManager) {

    private var view: GuideView? = null

    val zone get() = view?.zone ?: Drop.NONE

    fun start(icon: Drawable?, x: Float, y: Float, barTop: Float) {
        end()
        val v = GuideView(context, icon?.constantState?.newDrawable()?.mutate() ?: icon, barTop)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            setFitInsetsTypes(0)
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        runCatching { wm.addView(v, lp) }.onSuccess { view = v; v.move(x, y) }
    }

    fun move(x: Float, y: Float) = view?.move(x, y)

    /** Where the drop landed, as screen pixels for a pop-up's centre. */
    fun dropPoint() = view?.let { it.fx to it.fy }

    fun end() {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
    }

    @SuppressLint("ViewConstructor")
    private class GuideView(context: Context, private val icon: Drawable?, private val barTop: Float) : View(context) {
        var zone = Drop.NONE
        var fx = 0f
        var fy = 0f

        private val panel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x660E1311 }
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = 0xFFD9BB86.toInt(); strokeWidth = context.dp(2).toFloat()
        }
        private val crease = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFD9BB86.toInt() }
        private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55000000 }
        private val shown = RectF()
        private val target = RectF()
        private var panelAlpha = 0f

        fun move(x: Float, y: Float) {
            fx = x; fy = y
            val w = width.toFloat().takeIf { it > 0 } ?: context.resources.displayMetrics.widthPixels.toFloat()
            val h = height.toFloat().takeIf { it > 0 } ?: context.resources.displayMetrics.heightPixels.toFloat()
            zone = when {
                y > barTop - context.dp(24) -> Drop.NONE
                x < w * 0.28f -> Drop.LEFT
                x > w * 0.72f -> Drop.RIGHT
                else -> Drop.POPUP
            }
            val m = context.dp(12).toFloat()
            when (zone) {
                Drop.LEFT -> target.set(m, m, w / 2f - m, h - m)
                Drop.RIGHT -> target.set(w / 2f + m, m, w - m, h - m)
                Drop.POPUP -> {
                    val pw = w * 0.62f; val ph = h * 0.62f
                    val cx = x.coerceIn(pw / 2, w - pw / 2); val cy = y.coerceIn(ph / 2, h - ph / 2)
                    target.set(cx - pw / 2, cy - ph / 2, cx + pw / 2, cy + ph / 2)
                }
                Drop.NONE -> target.set(x - 1, y - 1, x + 1, y + 1)
            }
            if (shown.isEmpty) shown.set(target)
            postInvalidateOnAnimation()
        }

        override fun onDraw(canvas: Canvas) {
            // The panel glides to its new shape rather than jumping: a quarter of the way per frame
            val k = 0.28f
            shown.left += (target.left - shown.left) * k
            shown.top += (target.top - shown.top) * k
            shown.right += (target.right - shown.right) * k
            shown.bottom += (target.bottom - shown.bottom) * k
            val want = if (zone == Drop.NONE) 0f else 1f
            panelAlpha += (want - panelAlpha) * k

            if (panelAlpha > 0.01f) {
                val r = context.dp(24).toFloat()
                panel.alpha = (0x66 * panelAlpha).toInt()
                edge.alpha = (255 * panelAlpha).toInt()
                canvas.drawRoundRect(shown, r, r, panel)
                canvas.drawRoundRect(shown, r, r, edge)
                if (zone == Drop.LEFT || zone == Drop.RIGHT) {
                    // The crease lights up as the line the two halves will meet on
                    crease.alpha = (230 * panelAlpha).toInt()
                    crease.strokeWidth = context.dp(1.5f).toFloat()
                    canvas.drawLine(width / 2f, 0f, width / 2f, height.toFloat(), crease)
                }
            }

            // The app itself, lifted under your finger
            icon?.let {
                val s = context.dp(60)
                val l = (fx - s / 2).toInt(); val t = (fy - s / 2 - context.dp(28)).toInt()
                canvas.drawCircle(fx, t + s / 2f + context.dp(6), s * 0.46f, shadow)
                it.setBounds(l, t, l + s, t + s)
                it.draw(canvas)
            }

            val settling = abs(shown.left - target.left) + abs(shown.right - target.right) +
                abs(shown.top - target.top) + abs(want - panelAlpha) * 100 > 1f
            if (settling) postInvalidateOnAnimation()
        }

        private fun abs(f: Float) = kotlin.math.abs(f)
    }
}
