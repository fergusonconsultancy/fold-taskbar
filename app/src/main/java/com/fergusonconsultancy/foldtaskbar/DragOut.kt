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
enum class Drop { LEFT, RIGHT, TOP, BOTTOM, POPUP, NONE }

/**
 * The guide shown while you drag an app out of the bar or the grid. Towards any edge,
 * the half it will take is drawn as a panel and the line the two halves will meet on
 * lights up; in the centre, the pop-up window it will become is drawn under your finger.
 * Over the bar, nothing: letting go there cancels.
 *
 * Android, not the app, decides whether a split is side by side or top and bottom. The
 * half you choose decides which of the two apps comes first.
 *
 * The guide never takes touches; your finger stays with the bar that started the drag.
 */
class DragGuide(private val context: Context, private val wm: WindowManager) {

    private var view: GuideView? = null

    val zone get() = view?.zone ?: Drop.NONE

    fun start(icon: Drawable?, x: Float, y: Float, cancelArea: RectF) {
        end()
        val v = GuideView(context, icon?.constantState?.newDrawable()?.mutate() ?: icon, cancelArea, Prefs.concentrato(context))
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
    private class GuideView(context: Context, private val icon: Drawable?, private val cancelArea: RectF, private val concentrato: Boolean) : View(context) {
        // Concentrato: how fast the finger is moving, smoothed; fast movement empties the guide
        // down to the app and the place it will land
        private var lastAt = 0L
        private var speed = 0f
        private var detail = 1f
        var zone = Drop.NONE
        var fx = 0f
        var fy = 0f

        private val panel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0E1311.toInt() }
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = 0xFFD9BB86.toInt(); strokeWidth = context.dp(2).toFloat()
        }
        private val crease = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFD9BB86.toInt() }
        private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55000000 }
        private val dim = Paint().apply { color = 0xFF000000.toInt() }
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFEDEAE2.toInt(); textAlign = Paint.Align.CENTER
        }
        private var ix = Float.NaN
        private var iy = Float.NaN
        private val shown = RectF()
        private val target = RectF()
        private var panelAlpha = 0f

        fun move(x: Float, y: Float) {
            val now = android.os.SystemClock.uptimeMillis()
            if (lastAt > 0L && now > lastAt) {
                val dpPerMs = kotlin.math.hypot(x - fx, y - fy) / (now - lastAt) / context.resources.displayMetrics.density
                speed += (dpPerMs - speed) * 0.35f
            }
            lastAt = now
            fx = x; fy = y
            val w = width.toFloat().takeIf { it > 0 } ?: context.resources.displayMetrics.widthPixels.toFloat()
            val h = height.toFloat().takeIf { it > 0 } ?: context.resources.displayMetrics.heightPixels.toFloat()
            val pad = context.dp(24).toFloat()
            val edgeX = minOf(x, w - x) / w
            val edgeY = minOf(y, h - y) / h
            zone = when {
                x > cancelArea.left - pad && x < cancelArea.right + pad &&
                    y > cancelArea.top - pad && y < cancelArea.bottom + pad -> Drop.NONE
                minOf(edgeX, edgeY) > 0.24f -> Drop.POPUP
                edgeX <= edgeY -> if (x < w / 2) Drop.LEFT else Drop.RIGHT
                else -> if (y < h / 2) Drop.TOP else Drop.BOTTOM
            }
            val m = context.dp(12).toFloat()
            when (zone) {
                Drop.LEFT -> target.set(m, m, w / 2f - m, h - m)
                Drop.RIGHT -> target.set(w / 2f + m, m, w - m, h - m)
                Drop.TOP -> target.set(m, m, w - m, h / 2f - m)
                Drop.BOTTOM -> target.set(m, h / 2f + m, w - m, h - m)
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
            // Detail: full when the finger is slow or still, falling away above about 0.6 dp per ms
            val wantDetail = if (!concentrato) 1f else 1f - (((speed - 0.6f) / 1.6f).coerceIn(0f, 1f)).let { it * it * (3 - 2 * it) }
            detail += (wantDetail - detail) * 0.2f
            speed *= 0.9f

            if (panelAlpha > 0.01f) {
                // The rest of the screen steps back...
                dim.alpha = (0x70 * panelAlpha).toInt()
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
                // ...and the place the app will take comes forward, solid, with a champagne edge
                val r = context.dp(24).toFloat()
                panel.alpha = (0xD8 * panelAlpha).toInt()
                edge.alpha = (255 * panelAlpha * (0.25f + 0.75f * detail)).toInt()
                canvas.drawRoundRect(shown, r, r, panel)
                canvas.drawRoundRect(shown, r, r, edge)
                crease.alpha = (230 * panelAlpha * detail).toInt()
                crease.strokeWidth = context.dp(1.5f).toFloat()
                // The line the two halves will meet on lights up
                if (zone == Drop.LEFT || zone == Drop.RIGHT) canvas.drawLine(width / 2f, 0f, width / 2f, height.toFloat(), crease)
                if (zone == Drop.TOP || zone == Drop.BOTTOM) canvas.drawLine(0f, height / 2f, width.toFloat(), height / 2f, crease)
            }

            // The app: under your finger while it travels, then it settles into the middle of
            // the place it will open, as One UI does, with the instruction beneath it
            val settled = zone != Drop.NONE
            val wantX = if (settled) shown.centerX() else fx
            val wantY = if (settled) shown.centerY() - context.dp(16) else fy - context.dp(28)
            if (ix.isNaN()) { ix = wantX; iy = wantY }
            ix += (wantX - ix) * k; iy += (wantY - iy) * k
            icon?.let {
                val s = context.dp(if (settled) 72 else 60)
                val l = (ix - s / 2).toInt(); val t = (iy - s / 2).toInt()
                if (!settled) canvas.drawCircle(ix, iy + context.dp(6), s * 0.46f, shadow)
                it.setBounds(l, t, l + s, t + s)
                it.draw(canvas)
            }
            if (panelAlpha > 0.5f) {
                label.alpha = (255 * (panelAlpha - 0.5f) * 2 * detail).toInt().coerceIn(0, 255)
                label.textSize = context.dp(14).toFloat()
                canvas.drawText(if (zone == Drop.POPUP) "Drop here for a pop-up" else "Drop here to open", ix, iy + context.dp(60), label)
            }

            val settling = abs(shown.left - target.left) + abs(shown.right - target.right) +
                abs(shown.top - target.top) + abs(want - panelAlpha) * 100 + abs(ix - wantX) + abs(iy - wantY) + abs(wantDetail - detail) * 100 > 1f
            if (settling) postInvalidateOnAnimation()
        }

        private fun abs(f: Float) = kotlin.math.abs(f)
    }
}
