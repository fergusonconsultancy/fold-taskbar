package com.fergusonconsultancy.foldtaskbar

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import kotlin.math.roundToInt

/**
 * Advanced tuning (30 Oct 2026), after One UI's floating taskbar: the bar's show and hide
 * animation, its spring, and how it is called and held. A preview bar at the top replays the
 * motion after every change, and on a tap, so nothing is tuned blind.
 */
class TuningActivity : Activity() {

    private lateinit var preview: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Kit.GROUND
        window.navigationBarColor = Kit.GROUND
        val screen = Screen(this, "Advanced tuning", "Fold Taskbar")

        // ---- The preview
        val stage = FrameLayout(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Kit.SURFACE); cornerRadius = px(22).toFloat(); setStroke(px(1), Kit.HAIRLINE)
            }
            setOnClickListener { replay(hideFirst = true) }
        }
        preview = PreviewBar(this)
        stage.addView(preview, FrameLayout.LayoutParams(px(232), px(54), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = px(22) })
        stage.addView(text("Tap to play it again", 12f, Kit.MUTED).apply { setPadding(px(16), px(14), 0, 0) },
            FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))
        stage.minimumHeight = px(150)
        screen.add(stage, px(6))

        // ---- Presets, like One UI's Simple and Pixel
        screen.section("Animation")
        val presets = Tuning.Preset.entries
        screen.add(Segmented(this, presets.map { it.label }, presets.indexOf(Tuning.preset(this))) { i ->
            val chosen = presets[i]
            if (chosen.values != null) { Tuning.applyPreset(this, chosen); recreate() }
        })
        screen.add(note("Calma rises softly. Pronta answers at once. Move any slider and you are tuning your own."), 0)

        // ---- Show and hide
        screen.section("Show and hide animation")
        screen.add(Card(this).apply {
            row(withNote(whole("Horizontal scale", 1, 10, Tuning.hScale(this@TuningActivity), Tuning.H_SCALE_DEFAULT, Tuning::setHScale),
                "How long the bar is as it starts to appear. Increase to keep it long, decrease to start short. Measured along the bar, also when it stands at a side."))
            row(withNote(whole("Vertical scale", 1, 10, Tuning.vScale(this@TuningActivity), Tuning.V_SCALE_DEFAULT, Tuning::setVScale),
                "How tall it is as it starts. Low values rise up out of the edge; high values only fade in."))
            row(withNote(whole("Duration", 1, 20, Tuning.duration(this@TuningActivity), Tuning.DURATION_DEFAULT, Tuning::setDuration) { "$it  ·  ${Tuning.durationMs(it)} ms" },
                "Increase for a softer feel, decrease for a faster response. Hiding always takes about half as long."))
        })

        // ---- The spring
        screen.section("Spring animation")
        screen.add(Card(this).apply {
            row(withNote(whole("Stiffness", 1, 10, Tuning.stiffness(this@TuningActivity), Tuning.STIFFNESS_DEFAULT, Tuning::setStiffness),
                "How quickly the spring pulls the bar to its full size within the duration. Stiffer arrives sooner and spends longer settling."))
            row(withNote(whole("Damping", 1, 10, Tuning.damping(this@TuningActivity), Tuning.DAMPING_DEFAULT, Tuning::setDamping) {
                when {
                    it < Tuning.DAMPING_DEFAULT -> "$it  ·  bounces"
                    it == Tuning.DAMPING_DEFAULT -> "$it  ·  critical"
                    else -> "$it  ·  no bounce"
                }
            }, "How much the spring bounces. 6 is critical damping, the designed value: the bar arrives on one deceleration and never overshoots. Below 6 it bounces, which the doctrine argues against; above, it eases in more slowly still."))
        })

        // ---- Other options
        screen.section("Other options")
        screen.add(Card(this).apply {
            row(withNote(whole("Reaction delay", 0, 300, Tuning.delay(this@TuningActivity), Tuning.DELAY_DEFAULT, Tuning::setDelay, replays = false) { "$it ms" },
                "With auto-hide on, how long the bar waits before appearing once you swipe where it sits. A little delay forgives a swipe meant for the app."))
            row(withNote(whole("Call sensitivity", 1, 6, Tuning.sensitivity(this@TuningActivity), Tuning.SENSITIVITY_DEFAULT, Tuning::setSensitivity, replays = false) {
                when (it) { 1 -> "1  ·  favours the app"; 6 -> "6  ·  favours the bar"; else -> "$it" }
            }, "How far a swipe must travel before it calls the bar. Low gives the gesture to the app beneath; high calls the bar at the lightest touch."))
            row(withNote(whole("Holding time", 0, Tuning.HOLDS.size - 1, Tuning.hold(this@TuningActivity), Tuning.HOLD_DEFAULT, Tuning::setHold, replays = false) { Tuning.holdLabel(it) },
                "How long the bar stays once called before auto-hide takes it away. Infinite keeps it until you touch anywhere outside it."))
        })
        screen.add(primary("Reset to the designed values", filled = false) { Tuning.reset(this); recreate() }, px(18))

        setContentView(screen.root)
        preview.post { replay(hideFirst = false) }
    }

    /** A slider and the line explaining it, as one row of a card. */
    private fun withNote(slider: View, s: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(slider); addView(note(s))
    }

    private fun note(s: String) = text(s, 13f, Kit.MUTED).apply { setPadding(px(18), 0, px(18), px(14)) }

    /** A slider of whole numbers. */
    private fun whole(
        label: String, min: Int, max: Int, value: Int, designed: Int,
        save: (Context, Int) -> Unit, replays: Boolean = true, format: (Int) -> String = { "$it" },
    ) = Slider(this, label, min.toFloat(), max.toFloat(), value.toFloat(), designed.toFloat(), { format(it.roundToInt()) }) {
        save(this, it.roundToInt())
        if (replays) replay(hideFirst = false)
    }

    private fun replay(hideFirst: Boolean) {
        if (hideFirst && preview.alpha > 0.5f) Tuning.hide(preview, false, false) { Tuning.show(preview, false, false) }
        else Tuning.show(preview, false, false)
    }

    /** A small capsule with a row of icons: the bar, in miniature. */
    private class PreviewBar(ctx: Context) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val r = RectF()
        override fun onDraw(c: Canvas) {
            val h = height.toFloat(); val w = width.toFloat()
            p.color = 0xDB121614.toInt()
            r.set(0f, 0f, w, h); c.drawRoundRect(r, h / 2f, h / 2f, p)
            p.color = 0x14FFFFFF; p.style = Paint.Style.STROKE; p.strokeWidth = context.px(1).toFloat()
            c.drawRoundRect(r, h / 2f, h / 2f, p); p.style = Paint.Style.FILL
            val n = 5; val s = h * 0.52f; val gap = (w - n * s) / (n + 1)
            for (i in 0 until n) {
                p.color = if (i == 0) Kit.CHAMPAGNE else 0xFF46564E.toInt()
                val x = gap + i * (s + gap)
                r.set(x, (h - s) / 2f, x + s, (h + s) / 2f)
                c.drawRoundRect(r, s * 0.3f, s * 0.3f, p)
            }
        }
    }
}
