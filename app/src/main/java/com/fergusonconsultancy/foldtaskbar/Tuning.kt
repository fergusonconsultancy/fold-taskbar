package com.fergusonconsultancy.foldtaskbar

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.content.Context
import android.view.View
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt

/**
 * Advanced tuning (30 Oct 2026), after One UI's floating taskbar. How the bar shows and hides
 * (its starting length and height, its duration, and the spring it settles on), how readily it
 * answers a swipe, and how long it stays before auto-hide takes it away.
 *
 * The mandate sets the defaults: the spring is critically damped, so out of the box the bar
 * arrives on one deceleration and never bounces. Damping below the designed value lets it
 * overshoot; that is the owner's choice, and the screen says so.
 */
object Tuning {

    // Stored as the whole numbers the sliders show
    private const val H_SCALE = "tune_h_scale"
    private const val V_SCALE = "tune_v_scale"
    private const val DURATION = "tune_duration"
    private const val STIFFNESS = "tune_stiffness"
    private const val DAMPING = "tune_damping"
    private const val DELAY = "tune_delay_ms"
    private const val SENSITIVITY = "tune_sensitivity"
    private const val HOLD = "tune_hold"

    const val H_SCALE_DEFAULT = 5
    const val V_SCALE_DEFAULT = 5
    const val DURATION_DEFAULT = 10
    const val STIFFNESS_DEFAULT = 3
    /** Critical damping: the designed value, and the last one with no bounce at all is anything from here up. */
    const val DAMPING_DEFAULT = 6
    const val DELAY_DEFAULT = 0
    const val SENSITIVITY_DEFAULT = 5
    /** Index into [HOLDS]: 3.5 seconds, as before. */
    const val HOLD_DEFAULT = 2

    /** Holding times offered, in seconds; the last is infinite (touch outside the bar to hide it). */
    val HOLDS = floatArrayOf(2f, 3f, 3.5f, 4f, 5f, 6f, 8f, 10f, Float.POSITIVE_INFINITY)
    /** Distance a swipe must travel on the hidden bar's strip before it calls the bar, by sensitivity 1 to 6. */
    private val THRESHOLD_DP = intArrayOf(30, 22, 15, 10, 6, 3)

    /** Presets, like One UI's Simple and Pixel: (h, v, duration, stiffness, damping). */
    enum class Preset(val label: String, val values: IntArray?) {
        CALMA("Calma", intArrayOf(5, 5, 10, 3, 6)),
        PRONTA("Pronta", intArrayOf(7, 7, 6, 6, 6)),
        CUSTOM("Advanced", null),
    }

    private fun p(c: Context) = Prefs.of(c)

    fun hScale(c: Context) = p(c).getInt(H_SCALE, H_SCALE_DEFAULT)
    fun vScale(c: Context) = p(c).getInt(V_SCALE, V_SCALE_DEFAULT)
    fun duration(c: Context) = p(c).getInt(DURATION, DURATION_DEFAULT)
    fun stiffness(c: Context) = p(c).getInt(STIFFNESS, STIFFNESS_DEFAULT)
    fun damping(c: Context) = p(c).getInt(DAMPING, DAMPING_DEFAULT)
    fun delay(c: Context) = p(c).getInt(DELAY, DELAY_DEFAULT)
    fun sensitivity(c: Context) = p(c).getInt(SENSITIVITY, SENSITIVITY_DEFAULT)
    fun hold(c: Context) = p(c).getInt(HOLD, HOLD_DEFAULT)

    fun set(c: Context, key: String, v: Int) = p(c).edit().putInt(key, v).apply()
    fun setHScale(c: Context, v: Int) = set(c, H_SCALE, v.coerceIn(1, 10))
    fun setVScale(c: Context, v: Int) = set(c, V_SCALE, v.coerceIn(1, 10))
    fun setDuration(c: Context, v: Int) = set(c, DURATION, v.coerceIn(1, 20))
    fun setStiffness(c: Context, v: Int) = set(c, STIFFNESS, v.coerceIn(1, 10))
    fun setDamping(c: Context, v: Int) = set(c, DAMPING, v.coerceIn(1, 10))
    fun setDelay(c: Context, v: Int) = set(c, DELAY, v.coerceIn(0, 300))
    fun setSensitivity(c: Context, v: Int) = set(c, SENSITIVITY, v.coerceIn(1, 6))
    fun setHold(c: Context, v: Int) = set(c, HOLD, v.coerceIn(0, HOLDS.size - 1))

    fun preset(c: Context): Preset {
        val now = intArrayOf(hScale(c), vScale(c), duration(c), stiffness(c), damping(c))
        return Preset.entries.firstOrNull { it.values?.contentEquals(now) == true } ?: Preset.CUSTOM
    }

    fun applyPreset(c: Context, preset: Preset) {
        val v = preset.values ?: return
        p(c).edit().putInt(H_SCALE, v[0]).putInt(V_SCALE, v[1]).putInt(DURATION, v[2])
            .putInt(STIFFNESS, v[3]).putInt(DAMPING, v[4]).apply()
    }

    /** Everything back as designed. */
    fun reset(c: Context) {
        p(c).edit().remove(H_SCALE).remove(V_SCALE).remove(DURATION).remove(STIFFNESS).remove(DAMPING)
            .remove(DELAY).remove(SENSITIVITY).remove(HOLD).apply()
    }

    // ---- What the numbers mean -----------------------------------------------------------

    /** The bar's starting length or height as a fraction of its own: 1 starts short, 10 starts full. */
    fun startScale(v: Int) = 0.5f + 0.05f * v
    fun durationMs(v: Int) = 140L + 32L * v
    fun dampingRatio(v: Int) = 0.4f + 0.1f * v
    /** The spring's natural frequency across the whole duration: stiffer settles sooner. */
    fun omega(v: Int) = 5f + 1.5f * v

    fun callThresholdDp(c: Context) = THRESHOLD_DP[sensitivity(c) - 1]
    fun delayMs(c: Context) = delay(c).toLong()
    /** How long the revealed bar stays, or null for as long as you like. */
    fun holdMs(c: Context): Long? = HOLDS[hold(c)].let { if (it.isInfinite()) null else (it * 1000).toLong() }

    fun holdLabel(i: Int): String = HOLDS[i].let {
        when {
            it.isInfinite() -> "Infinite"
            it % 1f == 0f -> "${it.toInt()} s"
            else -> "$it s"
        }
    }

    /**
     * A damped spring released from rest, normalised to land exactly on 1 at the end of its
     * duration. Critically damped or more, it never passes 1; below, it overshoots and rings.
     */
    class Spring(private val zeta: Float, private val w: Float) : TimeInterpolator {
        private val end = raw(1f)
        private fun raw(t: Float): Float {
            val z = zeta.toDouble(); val wt = (w * t).toDouble()
            val v = when {
                z < 0.999 -> {
                    val wd = sqrt(1 - z * z)
                    1 - exp(-z * wt) * (cos(wd * wt) + z / wd * sin(wd * wt))
                }
                z <= 1.001 -> 1 - exp(-wt) * (1 + wt)
                else -> {
                    val wd = sqrt(z * z - 1)
                    1 - exp(-z * wt) * (cosh(wd * wt) + z / wd * sinh(wd * wt))
                }
            }
            return v.toFloat()
        }
        override fun getInterpolation(t: Float): Float = raw(t) + t * (1f - end)
    }

    fun spring(c: Context) = Spring(dampingRatio(damping(c)), omega(stiffness(c)))

    // ---- Showing and hiding the bar --------------------------------------------------------

    private val running = HashMap<View, Animator>()

    fun cancel(v: View) { running.remove(v)?.cancel() }

    /**
     * Sets the point the bar grows from: the middle of the edge it rests against, so it rises
     * out of that edge. "Horizontal" means along the bar's length, also for a bar standing at
     * a side.
     */
    private fun pivot(v: View, vertical: Boolean, nearStart: Boolean) {
        val w = v.width.takeIf { it > 0 } ?: v.measuredWidth
        val h = v.height.takeIf { it > 0 } ?: v.measuredHeight
        if (vertical) { v.pivotX = if (nearStart) 0f else w.toFloat(); v.pivotY = h / 2f }
        else { v.pivotX = w / 2f; v.pivotY = if (nearStart) 0f else h.toFloat() }
    }

    private fun lengthAndHeight(c: Context, vertical: Boolean): Pair<Float, Float> {
        val len = startScale(hScale(c)); val tall = startScale(vScale(c))
        // scaleX, scaleY: for a bar standing at a side its length runs down the screen
        return if (vertical) tall to len else len to tall
    }

    /** The bar appears: from its starting length and height to full, on the tuned spring. */
    fun show(v: View, vertical: Boolean, nearStart: Boolean) {
        cancel(v); v.animate().cancel()
        val c = v.context
        pivot(v, vertical, nearStart)
        val (sx0, sy0) = lengthAndHeight(c, vertical)
        val spring = spring(c)
        v.scaleX = sx0; v.scaleY = sy0; v.alpha = 0f
        running[v] = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs(duration(c))
            interpolator = null
            addUpdateListener {
                val f = it.animatedFraction
                val s = spring.getInterpolation(f)
                v.scaleX = sx0 + (1f - sx0) * s
                v.scaleY = sy0 + (1f - sy0) * s
                // Fully there within the first third, so the shape is read, not a fade
                val a = (f / 0.34f).coerceIn(0f, 1f)
                v.alpha = a * a * (3f - 2f * a)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (running[v] === animation) running.remove(v)
                    v.scaleX = 1f; v.scaleY = 1f; v.alpha = 1f
                    v.resetPivot()
                }
            })
            start()
        }
    }

    /** The bar leaves: back to its starting length and height as it fades, faster than it came. */
    fun hide(v: View, vertical: Boolean, nearStart: Boolean, end: Runnable) {
        cancel(v); v.animate().cancel()
        val c = v.context
        pivot(v, vertical, nearStart)
        val (sx1, sy1) = lengthAndHeight(c, vertical)
        val sx0 = v.scaleX; val sy0 = v.scaleY; val a0 = v.alpha
        val ease = Curves.CLOSE
        running[v] = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = (durationMs(duration(c)) * 0.55f).toLong()
            interpolator = null
            addUpdateListener {
                val f = ease.getInterpolation(it.animatedFraction)
                v.scaleX = sx0 + (sx1 - sx0) * f
                v.scaleY = sy0 + (sy1 - sy0) * f
                v.alpha = a0 * (1f - f)
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) {
                    if (running[v] === animation) running.remove(v)
                    if (!cancelled) { end.run(); v.resetPivot() }
                }
            })
            start()
        }
    }
}
