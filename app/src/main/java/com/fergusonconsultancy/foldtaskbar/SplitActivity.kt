package com.fergusonconsultancy.foldtaskbar

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View

/**
 * The stepping stone for split screen and app pairs.
 *
 * Android only honours "open beside me" from an app running full screen, and the taskbar
 * is a background service. So this screen, which is ours and solid, makes the request.
 * The first build made it see-through and One UI ignored it; solid, it counts.
 *
 * It is also the transition: the crease opens as a straight line of light and the two
 * apps slide out to the halves they are about to take. Then it asks for the right-hand
 * app beside itself, hands its own half to the left-hand app, and closes.
 *
 * Every step is written to the event log so a failure can be pinned to one step.
 */
class SplitActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private var right: ComponentName? = null
    private var left: ComponentName? = null
    private var asked = false
    private var method = 0
    private var handedOver = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
        @Suppress("DEPRECATION") right = intent.getParcelableExtra(EXTRA_RIGHT)
        @Suppress("DEPRECATION") left = intent.getParcelableExtra(EXTRA_LEFT)
        method = intent.getIntExtra(EXTRA_METHOD, 0)
        val r = right ?: run { finish(); return }
        EventLog.add(this, "Split step 1: stepping stone open")
        val apps = Apps(this)
        setContentView(CreaseView(this, left?.let { apps.icon(it.packageName) }, apps.icon(r.packageName)))
        // Ask once the crease has opened and this screen is properly in front
        main.postDelayed(::askBeside, ASK_AFTER_MS)
    }

    private fun askBeside() {
        val r = right ?: return
        asked = true
        runCatching {
            startActivity(launcher(r).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT))
        }.onSuccess { EventLog.add(this, "Split step 2: asked for ${r.packageName} beside") }
            .onFailure { EventLog.add(this, "Split step 2 failed: ${it.javaClass.simpleName}"); finishQuietly() }
        // If the screen never splits, say so rather than leave you guessing
        main.postDelayed({
            if (!handedOver) {
                EventLog.add(this, "Split step 3: One UI did not split the screen (in split: $isInMultiWindowMode)")
                finishQuietly()
            }
        }, GIVE_UP_MS)
    }

    @Deprecated("Deprecated in Java")
    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean) {
        @Suppress("DEPRECATION") super.onMultiWindowModeChanged(isInMultiWindowMode)
        handleSplit(isInMultiWindowMode)
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        handleSplit(isInMultiWindowMode)
    }

    private fun handleSplit(inSplit: Boolean) {
        if (!inSplit || !asked || handedOver) return
        handedOver = true
        EventLog.add(this, "Split step 3: screen split")
        val l = left
        if (l == null) { finishQuietly(); return }
        // Let the split finish settling before the left half changes hands. Too soon, and
        // One UI treated the left app as a separate full-screen launch.
        main.postDelayed({
            runCatching { handOver(l) }
                .onSuccess { EventLog.add(this, "Split step 4: handed this half to ${l.packageName} (${HANDOVERS[method]})") }
                .onFailure { EventLog.add(this, "Split step 4 failed (${HANDOVERS[method]}): ${it.javaClass.simpleName}") }
            main.postDelayed(::finishQuietly, 700)
        }, 600)
    }

    /**
     * The left half has to go to the app you were using. One UI does not accept the
     * obvious way (relaunching it), so there are three, tried in turn until one works:
     * relaunch it; relaunch it asking for a split-screen window; or open it inside this
     * screen's own window stack, which keeps it in this half by construction.
     */
    private fun handOver(l: ComponentName) {
        when (method) {
            0 -> startActivity(launcher(l).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            1 -> {
                val o = android.app.ActivityOptions.makeBasic()
                org.lsposed.hiddenapibypass.HiddenApiBypass.invoke(
                    android.app.ActivityOptions::class.java, o, "setLaunchWindowingMode", WINDOWING_MODE_MULTI_WINDOW,
                )
                startActivity(launcher(l).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), o.toBundle())
            }
            else -> startActivity(launcher(l))
        }
    }

    private fun finishQuietly() {
        if (isFinishing) return
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }

    private fun launcher(c: ComponentName) =
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(c)

    /** The crease opens top to bottom; the two apps part from it to their halves. */
    @SuppressLint("ViewConstructor")
    private class CreaseView(context: Context, private val leftIcon: Drawable?, private val rightIcon: Drawable?) : View(context) {
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFD9BB86.toInt() }
        private var t = 0f

        init {
            setBackgroundColor(0xFF0E1311.toInt())
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 520
                addUpdateListener { t = it.animatedFraction; invalidate() }
                start()
            }
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val cx = w / 2f; val cy = h / 2f
            // The crease: a straight hairline of light, drawn outwards from the middle
            val grow = Curves.OPEN.getInterpolation((t / 0.45f).coerceAtMost(1f))
            line.strokeWidth = context.dp(1.5f).toFloat()
            line.alpha = (220 * (1f - 0.5f * ((t - 0.6f) / 0.4f).coerceIn(0f, 1f))).toInt()
            canvas.drawLine(cx, cy - grow * h / 2f, cx, cy + grow * h / 2f, line)

            // The apps part from the crease and settle at the centres of their halves
            val part = Curves.OPEN.getInterpolation(((t - 0.12f) / 0.88f).coerceIn(0f, 1f))
            val size = context.dp(72)
            fun icon(d: Drawable?, toX: Float) {
                d ?: return
                val x = cx + (toX - cx) * part
                d.setBounds((x - size / 2).toInt(), (cy - size / 2).toInt(), (x + size / 2).toInt(), (cy + size / 2).toInt())
                d.alpha = (255 * (part * 1.6f).coerceAtMost(1f)).toInt()
                d.draw(canvas)
            }
            icon(leftIcon, w * 0.25f)
            icon(rightIcon, w * 0.75f)
        }
    }

    companion object {
        const val EXTRA_RIGHT = "right"
        const val EXTRA_LEFT = "left"
        const val EXTRA_METHOD = "method"
        private const val WINDOWING_MODE_MULTI_WINDOW = 6
        val HANDOVERS = listOf("relaunch", "split-window request", "inside the stepping stone", "two pop-ups")
        private const val ASK_AFTER_MS = 260L
        private const val GIVE_UP_MS = 1600L

        fun intent(context: Context, left: ComponentName?, right: ComponentName, method: Int) =
            Intent(context, SplitActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                .putExtra(EXTRA_LEFT, left)
                .putExtra(EXTRA_RIGHT, right)
                .putExtra(EXTRA_METHOD, method)
    }
}
