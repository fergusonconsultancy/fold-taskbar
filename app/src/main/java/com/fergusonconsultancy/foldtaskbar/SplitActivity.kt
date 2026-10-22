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
        if (l == null) {
            if (intent.getBooleanExtra(EXTRA_PICKER, false)) showPicker() else finishQuietly()
            return
        }
        // Let the split finish settling before the left half changes hands. Too soon, and
        // One UI treated the left app as a separate full-screen launch.
        main.postDelayed({
            runCatching { handOver(l) }
                .onSuccess { EventLog.add(this, "Split step 4: handed this half to ${l.packageName}") }
                .onFailure { EventLog.add(this, "Split step 4 failed: ${it.javaClass.simpleName}") }
            main.postDelayed(::finishQuietly, 700)
        }, 600)
    }

    /**
     * The other half has to go to the second app. One UI will not move an app that is
     * already open into this half, so there are two ways: relaunch it (works for an app
     * that is not already running), or open a fresh window of it (method 1; works for
     * apps that allow more than one window, which most Samsung and Google apps do).
     */
    private fun handOver(l: ComponentName) {
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or
            (if (method == 1) Intent.FLAG_ACTIVITY_MULTIPLE_TASK else 0)
        startActivity(launcher(l).addFlags(flags))
    }

    /**
     * Opened from the home screen there is no second app yet, so, as One UI does, this
     * half offers the apps to choose from. The choice opens in a fresh window so it stays
     * in this half.
     */
    private fun showPicker() {
        EventLog.add(this, "Split step 4: choosing the app for the other half")
        val apps = Apps(this)
        val pad = dp(20)
        val grid = android.widget.GridLayout(this).apply { columnCount = 4; setPadding(pad, pad, pad, pad) }
        apps.all().forEach { app ->
            grid.addView(android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                setPadding(0, dp(8), 0, dp(12))
                layoutParams = android.widget.GridLayout.LayoutParams().apply {
                    width = 0; columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)
                }
                addView(android.widget.ImageView(this@SplitActivity).apply {
                    setImageDrawable(apps.icon(app.pkg))
                    layoutParams = android.widget.LinearLayout.LayoutParams(dp(52), dp(52))
                })
                addView(android.widget.TextView(this@SplitActivity).apply {
                    text = app.label; textSize = 12f; setTextColor(0xFFE6E8E2.toInt())
                    gravity = android.view.Gravity.CENTER; maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(dp(2), dp(6), dp(2), 0)
                })
                setOnClickListener {
                    runCatching {
                        startActivity(launcher(app.component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK))
                    }.onSuccess { EventLog.add(this@SplitActivity, "Split step 5: chose ${app.label}") }
                    main.postDelayed(::finishQuietly, 700)
                }
            })
        }
        setContentView(android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(0xFF0E1311.toInt())
            addView(android.widget.TextView(this@SplitActivity).apply {
                text = "Choose an app for this side"
                textSize = 16f; setTextColor(0xFFD9BB86.toInt())
                setPadding(pad, pad * 2, pad, 0)
            })
            addView(android.widget.ScrollView(this@SplitActivity).apply { addView(grid) })
        })
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

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
        const val EXTRA_PICKER = "picker"
        private const val ASK_AFTER_MS = 260L
        private const val GIVE_UP_MS = 1600L

        fun intent(context: Context, left: ComponentName?, right: ComponentName, method: Int, picker: Boolean = false) =
            Intent(context, SplitActivity::class.java)
                .putExtra(EXTRA_PICKER, picker)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                .putExtra(EXTRA_LEFT, left)
                .putExtra(EXTRA_RIGHT, right)
                .putExtra(EXTRA_METHOD, method)
    }
}
