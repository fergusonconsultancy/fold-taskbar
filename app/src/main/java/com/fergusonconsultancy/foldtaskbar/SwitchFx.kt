package com.fergusonconsultancy.foldtaskbar

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.view.View
import android.view.WindowManager
import java.util.Calendar
import kotlin.math.hypot

/**
 * Aperture: the app-switch transition.
 *
 * The app you tap grows out of its icon (the system's scale-up, given the icon's bounds),
 * and over it a machined double ring opens from the same point and runs out past the
 * screen edges, like an iris opening onto the next app. The circle is Audi's "perfect
 * graphic shape"; the second, finer ring trails the first so the opening has depth.
 *
 * Charter: it starts where you touched (rule 1), the ring only outlines and never fills
 * (rule 2), one ring reaches the edges once (rule 4), it never blocks touch and the app
 * launches on the same frame as your tap (rule 5), and the light warms and dims at night
 * (rule 6).
 */
class ApertureFx(private val context: Context, private val wm: WindowManager) {

    private var view: RingView? = null

    fun attach() {
        if (view != null) return
        val v = RingView(context).apply { visibility = View.INVISIBLE }
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
        runCatching { wm.addView(v, lp) }.onSuccess { view = v }
    }

    fun detach() {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
    }

    /** [cx], [cy]: centre of the tapped icon, in screen pixels. */
    fun play(cx: Float, cy: Float) = view?.play(cx, cy)

    @SuppressLint("ViewConstructor")
    private class RingView(context: Context) : View(context) {
        private val outer = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private var cx = 0f
        private var cy = 0f
        private var t = 0f
        private var anim: ValueAnimator? = null

        fun play(x: Float, y: Float) {
            cx = x; cy = y
            val night = Calendar.getInstance().get(Calendar.HOUR_OF_DAY).let { it >= 20 || it < 7 }
            // Champagne by day; warmer and dimmer at night, kinder to the eyes
            val tone = if (night) 0xFFC8915A.toInt() else 0xFFD9BB86.toInt()
            outer.color = tone; inner.color = tone
            outer.alpha = 0; inner.alpha = 0
            peak = if (night) 150 else 205
            anim?.cancel()
            visibility = VISIBLE
            anim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = DURATION
                interpolator = null // eased per ring below, so the two rings can travel differently
                addUpdateListener { t = it.animatedFraction; invalidate() }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) { visibility = INVISIBLE }
                })
                start()
            }
        }

        private var peak = 205

        override fun onDraw(canvas: Canvas) {
            val far = maxOf(
                hypot(cx.toDouble(), cy.toDouble()), hypot((width - cx).toDouble(), cy.toDouble()),
                hypot(cx.toDouble(), (height - cy).toDouble()), hypot((width - cx).toDouble(), (height - cy).toDouble()),
            ).toFloat() * 1.04f
            val start = context.dp(22).toFloat()
            ring(canvas, outer, ease(t), far, start, context.dp(3.5f).toFloat(), 1f)
            // The finer ring trails by a beat, like the inner bezel of a machined cap
            val lag = ((t * DURATION - 70f) / (DURATION - 70f)).coerceIn(0f, 1f)
            ring(canvas, inner, ease(lag), far * 0.97f, start * 0.7f, context.dp(1.2f).toFloat(), 0.55f)
        }

        private fun ring(canvas: Canvas, p: Paint, e: Float, far: Float, start: Float, width0: Float, strength: Float) {
            if (e <= 0f) return
            val r = start + (far - start) * e
            p.strokeWidth = width0 * (1f - 0.65f * e)
            // Quick to appear, long to fade: one ring of light running out to the edges
            val inA = (e / 0.12f).coerceAtMost(1f)
            val outA = 1f - ((e - 0.55f) / 0.45f).coerceIn(0f, 1f)
            p.alpha = (peak * strength * inA * outA).toInt()
            canvas.drawCircle(cx, cy, r, p)
        }

        private fun ease(x: Float) = Curves.OPEN.getInterpolation(x.coerceIn(0f, 1f))

        companion object { const val DURATION = 460L }
    }
}
