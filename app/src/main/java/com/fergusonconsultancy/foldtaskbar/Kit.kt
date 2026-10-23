package com.fergusonconsultancy.foldtaskbar

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The settings screens' kit (23 Oct 2026), shared by Fold Motion and Fold Taskbar so the two
 * read as one family with their icons: smoked green-black, ivory ink, champagne for what is
 * chosen, Alfa red only where a lamp is meant. Italiana names things; Jost (a geometric sans in
 * the Bauhaus line) does everything else. Every control is drawn here, so nothing falls back
 * to a stock grey Android button.
 */
object Kit {
    const val GROUND = 0xFF0E1311.toInt()
    const val SURFACE = 0xFF151C18.toInt()
    const val RAISED = 0xFF1D2621.toInt()
    const val HAIRLINE = 0x1AF6F1E6
    const val INK = 0xFFF6F1E6.toInt()
    const val MUTED = 0xFF96A098.toInt()
    const val CHAMPAGNE = 0xFFD9BB86.toInt()
    const val ROSSO = 0xFFD7262E.toInt()
    const val GOOD = 0xFF7FC79A.toInt()
    const val WARN = 0xFFE0A15A.toInt()

    private var display: Typeface? = null
    private val jost = HashMap<Int, Typeface>()

    fun display(c: Context): Typeface = display ?: runCatching {
        Typeface.createFromAsset(c.assets, "fonts/Italiana-Regular.ttf")
    }.getOrDefault(Typeface.SERIF).also { display = it }

    /** Jost at a given weight (variable font), falling back to the system sans. */
    fun sans(c: Context, weight: Int = 400): Typeface = jost.getOrPut(weight) {
        runCatching {
            Typeface.Builder(c.assets, "fonts/Jost.ttf").setFontVariationSettings("'wght' $weight").build()
        }.getOrNull() ?: Typeface.create(Typeface.SANS_SERIF, weight, false)
    }

    val ease = PathInterpolator(0.2f, 0f, 0f, 1f)
}

fun Context.px(v: Number): Int = (v.toFloat() * resources.displayMetrics.density + 0.5f).toInt()

private fun rounded(fill: Int, radius: Float, stroke: Int = 0, strokeW: Int = 0) = GradientDrawable().apply {
    setColor(fill); cornerRadius = radius
    if (strokeW > 0) setStroke(strokeW, stroke)
}

/** A text view in the kit's type: [size] in sp, [weight] for Jost, or the display face. */
fun Context.text(s: CharSequence, size: Float, color: Int = Kit.INK, weight: Int = 400, displayFace: Boolean = false) =
    TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        typeface = if (displayFace) Kit.display(context) else Kit.sans(context, weight)
        includeFontPadding = false
        setLineSpacing(0f, 1.28f)
    }

/** The small tracked capitals that head a section. */
fun Context.eyebrow(s: String) = text(s.uppercase(), 11.5f, Kit.CHAMPAGNE, 500).apply {
    letterSpacing = 0.2f
    setPadding(px(4), px(30), 0, px(10))
}

/** The screen: a scrolling column on the ground colour, with the app's name and a status pill. */
class Screen(private val ctx: Context, name: String, over: String) {
    val column = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        val side = ctx.px(20)
        setPadding(side, ctx.px(34), side, ctx.px(40))
    }
    val root = android.widget.ScrollView(ctx).apply {
        setBackgroundColor(Kit.GROUND)
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(FrameLayout(ctx).apply {
            // The column never stretches wider than a comfortable reading measure on the main screen
            addView(column, FrameLayout.LayoutParams(minOf(ctx.resources.displayMetrics.widthPixels, ctx.px(620)), -2, Gravity.CENTER_HORIZONTAL))
        })
    }
    val status = Pill(ctx)

    init {
        column.addView(ctx.text(over.uppercase(), 11f, Kit.MUTED, 500).apply { letterSpacing = 0.22f; setPadding(ctx.px(4), 0, 0, ctx.px(6)) })
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(ctx.text(name, 44f, Kit.INK, displayFace = true), LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(status)
        column.addView(head)
    }

    fun add(v: View, top: Int = 0) {
        column.addView(v, LinearLayout.LayoutParams(-1, -2).apply { topMargin = top })
    }

    fun section(title: String) = column.addView(ctx.eyebrow(title))
}

/** A rounded status pill: a coloured dot and a word. */
class Pill(ctx: Context) : LinearLayout(ctx) {
    private val dot = View(ctx)
    private val label = ctx.text("", 13f, Kit.INK, 500)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(ctx.px(12), ctx.px(7), ctx.px(14), ctx.px(7))
        background = rounded(Kit.SURFACE, ctx.px(20).toFloat(), Kit.HAIRLINE, ctx.px(1))
        addView(dot, LayoutParams(ctx.px(7), ctx.px(7)).apply { marginEnd = ctx.px(8) })
        addView(label)
    }

    fun set(text: String, color: Int) {
        label.text = text
        dot.background = rounded(color, 100f)
    }
}

/** A card: one rounded surface holding rows divided by hairlines. */
class Card(ctx: Context) : LinearLayout(ctx) {
    init {
        orientation = VERTICAL
        background = rounded(Kit.SURFACE, ctx.px(22).toFloat(), Kit.HAIRLINE, ctx.px(1))
        clipToOutline = true
    }

    fun row(v: View) {
        if (childCount > 0) addView(View(context).apply { setBackgroundColor(Kit.HAIRLINE) }, LayoutParams(-1, context.px(1)).apply {
            marginStart = context.px(18); marginEnd = context.px(18)
        })
        addView(v, LayoutParams(-1, -2))
    }
}

/**
 * A row: a title, an optional line under it, and something at the end (a switch, a value, an
 * arrow). Tappable rows dim a touch under the finger.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class Row(ctx: Context, title: String, sub: String? = null, end: View? = null, onTap: (() -> Unit)? = null) : LinearLayout(ctx) {
    val titleView = ctx.text(title, 16f, Kit.INK, 450)
    val subView = ctx.text(sub ?: "", 13f, Kit.MUTED, 400)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = ctx.px(60)
        setPadding(ctx.px(18), ctx.px(14), ctx.px(16), ctx.px(14))
        val words = LinearLayout(ctx).apply { orientation = VERTICAL }
        words.addView(titleView)
        if (sub != null) words.addView(subView, LayoutParams(-2, -2).apply { topMargin = ctx.px(3) })
        addView(words, LayoutParams(0, -2, 1f))
        end?.let { addView(it, LayoutParams(-2, -2).apply { marginStart = ctx.px(12) }) }
        if (onTap != null) {
            isClickable = true
            setOnClickListener { performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); onTap() }
            setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> v.animate().alpha(0.6f).setDuration(60).start()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().alpha(1f).setDuration(200).start()
                }
                false
            }
        }
    }

    fun setSub(s: String) { subView.text = s }
}

/** A value at the end of a row, in champagne when it asks for action, muted when all is well. */
fun Context.endValue(s: String, color: Int = Kit.MUTED) = text(s, 14f, color, 500)

/** A pill switch, drawn: champagne track with an ivory knob when on, smoke when off. */
@SuppressLint("ViewConstructor")
class Toggle(ctx: Context, on: Boolean, private val changed: (Boolean) -> Unit) : View(ctx) {
    var on = on
        private set
    private var pos = if (on) 1f else 0f
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    init {
        isClickable = true
        setOnClickListener { set(!this.on, animate = true); performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); changed(this.on) }
    }

    fun set(v: Boolean, animate: Boolean = false) {
        on = v
        if (!animate) { pos = if (v) 1f else 0f; invalidate(); return }
        ValueAnimator.ofFloat(pos, if (v) 1f else 0f).apply {
            duration = 260; interpolator = Kit.ease
            addUpdateListener { pos = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(context.px(50), context.px(30))

    override fun onDraw(c: Canvas) {
        val h = height.toFloat(); val w = width.toFloat()
        r.set(0f, 0f, w, h)
        track.color = blend(Kit.RAISED, Kit.CHAMPAGNE, pos)
        c.drawRoundRect(r, h / 2, h / 2, track)
        val d = h - context.px(6)
        val x = context.px(3) + (w - d - context.px(6)) * pos
        knob.color = blend(Kit.MUTED, Kit.GROUND, pos)
        c.drawCircle(x + d / 2 + context.px(0), h / 2, d / 2, knob)
    }
}

fun blend(a: Int, b: Int, t: Float): Int {
    val u = t.coerceIn(0f, 1f)
    fun ch(s: Int) = (((a shr s) and 255) * (1 - u) + ((b shr s) and 255) * u).toInt()
    return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

/**
 * A segmented control: options side by side in a smoked track, the chosen one carried by a
 * champagne thumb that glides between them.
 */
@SuppressLint("ViewConstructor")
class Segmented(ctx: Context, private val options: List<String>, selected: Int, private val picked: (Int) -> Unit) : FrameLayout(ctx) {
    private var index = selected
    private var thumbX = 0f
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Kit.CHAMPAGNE }
    private val labels = mutableListOf<TextView>()
    private val r = RectF()
    private val row = LinearLayout(ctx)

    init {
        setWillNotDraw(false)
        background = rounded(Kit.RAISED, ctx.px(16).toFloat())
        setPadding(ctx.px(4), ctx.px(4), ctx.px(4), ctx.px(4))
        options.forEachIndexed { i, o ->
            val t = ctx.text(o, 14f, if (i == index) Kit.GROUND else Kit.INK, 500).apply {
                gravity = Gravity.CENTER
                setPadding(ctx.px(6), ctx.px(10), ctx.px(6), ctx.px(10))
                setOnClickListener { choose(i); performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
            }
            labels += t
            row.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
        }
        addView(row, LayoutParams(-1, -2))
    }

    private fun segW() = (width - paddingLeft - paddingRight) / options.size.toFloat()

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        thumbX = paddingLeft + segW() * index
    }

    private fun choose(i: Int) {
        if (i == index) return
        val from = thumbX
        val to = paddingLeft + segW() * i
        labels[index].setTextColor(Kit.INK)
        index = i
        labels[i].setTextColor(Kit.GROUND)
        ValueAnimator.ofFloat(from, to).apply {
            duration = 300; interpolator = Kit.ease
            addUpdateListener { thumbX = it.animatedValue as Float; invalidate() }
            start()
        }
        picked(i)
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        r.set(thumbX, paddingTop.toFloat(), thumbX + segW(), (height - paddingBottom).toFloat())
        val rad = context.px(12).toFloat()
        c.drawRoundRect(r, rad, rad, thumb)
    }
}

/** A choice among a few richer options: each a row with a painted swatch, the chosen one outlined in champagne. */
@SuppressLint("ViewConstructor")
class ChoiceList(ctx: Context, private val items: List<Triple<String, String, (Canvas, RectF) -> Unit>>, selected: Int, private val picked: (Int) -> Unit) : LinearLayout(ctx) {
    private var index = selected
    private val rows = mutableListOf<View>()

    init {
        orientation = VERTICAL
        items.forEachIndexed { i, (name, detail, paint) ->
            val v = LinearLayout(ctx).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(ctx.px(12), ctx.px(12), ctx.px(16), ctx.px(12))
                addView(Swatch(ctx, paint), LayoutParams(ctx.px(84), ctx.px(56)))
                val words = LinearLayout(ctx).apply { orientation = VERTICAL; setPadding(ctx.px(16), 0, 0, 0) }
                words.addView(ctx.text(name, 17f, Kit.INK, 500))
                words.addView(ctx.text(detail, 13f, Kit.MUTED), LayoutParams(-2, -2).apply { topMargin = ctx.px(3) })
                addView(words, LayoutParams(0, -2, 1f))
                setOnClickListener { choose(i); performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
            }
            rows += v
            addView(v, LayoutParams(-1, -2).apply { if (i > 0) topMargin = ctx.px(10) })
        }
        paintRows()
    }

    private fun choose(i: Int) { if (i == index) return; index = i; paintRows(); picked(i) }

    private fun paintRows() = rows.forEachIndexed { i, v ->
        v.background = rounded(Kit.SURFACE, context.px(20).toFloat(), if (i == index) Kit.CHAMPAGNE else Kit.HAIRLINE, context.px(if (i == index) 1.5f else 1f))
    }
}

/** A small painted preview tile. */
@SuppressLint("ViewConstructor")
class Swatch(ctx: Context, private val paint: (Canvas, RectF) -> Unit) : View(ctx) {
    private val r = RectF()
    private val clip = android.graphics.Path()
    override fun onDraw(c: Canvas) {
        r.set(0f, 0f, width.toFloat(), height.toFloat())
        clip.reset(); clip.addRoundRect(r, context.px(12).toFloat(), context.px(12).toFloat(), android.graphics.Path.Direction.CW)
        c.save(); c.clipPath(clip); paint(c, r); c.restore()
    }
}

/** The main action: a full-width champagne pill, or an outlined one for the quieter choice. */
fun Context.primary(label: String, filled: Boolean = true, onTap: () -> Unit) = text(label, 16f, if (filled) Kit.GROUND else Kit.INK, 500).apply {
    gravity = Gravity.CENTER
    setPadding(px(20), px(17), px(20), px(17))
    background = if (filled) rounded(Kit.CHAMPAGNE, px(28).toFloat()) else rounded(0, px(28).toFloat(), 0x40F6F1E6, px(1))
    isClickable = true
    setOnClickListener { it.performHapticFeedback(HapticFeedbackConstants.CONFIRM); onTap() }
}

/**
 * A card that folds away: its title row shows a chevron, and tapping it opens or closes the
 * body beneath with a soft height change.
 */
class Fold(ctx: Context, title: String, sub: String? = null) : LinearLayout(ctx) {
    val body = LinearLayout(ctx).apply { orientation = VERTICAL; setPadding(ctx.px(18), 0, ctx.px(18), ctx.px(18)); visibility = GONE }
    private val chevron = ctx.text("›", 22f, Kit.MUTED, 300)

    init {
        orientation = VERTICAL
        background = rounded(Kit.SURFACE, ctx.px(22).toFloat(), Kit.HAIRLINE, ctx.px(1))
        addView(Row(ctx, title, sub, chevron) { toggle() })
        addView(body, LayoutParams(-1, -2))
        chevron.rotation = 90f
    }

    private fun toggle() {
        val open = body.visibility != VISIBLE
        (parent as? ViewGroup)?.let { android.transition.TransitionManager.beginDelayedTransition(it.rootView as ViewGroup) }
        body.visibility = if (open) VISIBLE else GONE
        chevron.animate().rotation(if (open) 270f else 90f).setDuration(240).setInterpolator(Kit.ease).start()
    }
}
