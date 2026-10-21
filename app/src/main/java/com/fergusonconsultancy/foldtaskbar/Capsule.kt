package com.fergusonconsultancy.foldtaskbar

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout

sealed interface Slot {
    object AllApps : Slot
    object Divider : Slot
    data class App(val pkg: String) : Slot
}

interface CapsuleActions {
    fun onApp(pkg: String, view: View)
    fun onAppHeld(pkg: String, view: View)
    fun onAllApps(view: View)
    fun onAllAppsHeld(view: View)
}

/** One capsule of the bar: a row of icons, sized only to what it holds. */
@SuppressLint("ViewConstructor")
class Capsule(
    context: Context,
    private val apps: Apps,
    private val finish: Finish,
    private val iconDp: Int,
    private val actions: CapsuleActions,
) : LinearLayout(context) {

    private val gap = context.dp(iconDp * 0.28f)
    private val padX = context.dp(iconDp * 0.32f)
    private val padY = context.dp(8)
    private val indicators = HashMap<String, View>()
    private var current: List<Slot> = emptyList()

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = surface(context, finish, 999f)
        setPadding(padX, padY, padX, padY)
        // Room for the shadow that lifts the capsule off the app beneath
        elevation = context.dp(6).toFloat()
        clipToPadding = false
    }

    /** Docked against a side edge, the capsule stands upright. */
    var vertical = false
        set(v) {
            if (field == v) return
            field = v
            orientation = if (v) VERTICAL else HORIZONTAL
            gravity = if (v) Gravity.CENTER_HORIZONTAL else Gravity.CENTER_VERTICAL
            if (v) setPadding(padY, padX, padY, padX) else setPadding(padX, padY, padX, padY)
            rebuild(current)
        }

    /** Rebuilds only when the set of icons changes; otherwise leaves the views alone. */
    fun show(slots: List<Slot>, active: Set<String>) {
        if (slots != current) {
            current = slots
            rebuild(slots)
        }
        setActive(active)
    }

    /** Moves the open-app marks without touching anything else, so nothing redraws. */
    fun setActive(active: Set<String>) {
        indicators.forEach { (pkg, v) -> v.visibility = if (pkg in active) VISIBLE else INVISIBLE }
    }

    private fun rebuild(slots: List<Slot>) {
        removeAllViews()
        indicators.clear()
        slots.forEachIndexed { i, slot ->
            val v = when (slot) {
                Slot.AllApps -> glyph(Glyph.GRID) { actions.onAllApps(it) }.apply {
                    setOnLongClickListener { it.thud(); actions.onAllAppsHeld(it); true }
                }
                Slot.Divider -> View(context).apply {
                    setBackgroundColor(finish.ink and 0x00FFFFFF or 0x30000000)
                    val thin = context.dp(1).coerceAtLeast(1)
                    val long = context.dp(iconDp * 0.6f)
                    layoutParams = if (vertical) LayoutParams(long, thin) else LayoutParams(thin, long)
                }
                is Slot.App -> appIcon(slot)
            }
            if (i > 0) (v.layoutParams as LayoutParams).apply { if (vertical) topMargin = gap else marginStart = gap }
            addView(v)
        }
    }

    private fun appIcon(slot: Slot.App): View {
        val size = context.dp(iconDp)
        val frame = FrameLayout(context).apply {
            layoutParams = LayoutParams(size, size + context.dp(6))
            contentDescription = apps.entry(slot.pkg)?.label
        }
        frame.addView(ImageView(context).apply {
            setImageDrawable(apps.icon(slot.pkg))
            layoutParams = FrameLayout.LayoutParams(size, size, Gravity.TOP)
        })
        frame.addView(View(context).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(finish.indicator); cornerRadius = context.dp(2).toFloat()
            }
            visibility = INVISIBLE
            layoutParams = FrameLayout.LayoutParams(context.dp(iconDp * 0.34f), context.dp(3), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        }.also { indicators[slot.pkg] = it })
        pressable(frame)
        frame.setOnClickListener { it.tick(); actions.onApp(slot.pkg, it) }
        frame.setOnLongClickListener { it.thud(); actions.onAppHeld(slot.pkg, it); true }
        return frame
    }

    private fun glyph(kind: Glyph, narrow: Boolean = false, onTap: (View) -> Unit): View =
        GlyphView(context, kind, finish.ink).apply {
            val size = context.dp(iconDp)
            layoutParams = LayoutParams(if (narrow) (size * 0.55f).toInt() else size, size)
            contentDescription = "All apps"
            setOnClickListener { it.tick(); onTap(it) }
            pressable(this)
        }

    /** Icons sink slightly the instant a finger lands, so every touch is visibly received. */
    @SuppressLint("ClickableViewAccessibility")
    private fun pressable(v: View) {
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    view.animate().scaleX(0.86f).scaleY(0.86f).alpha(0.8f).setDuration(70).start()
                    // The grid button starts to part under the finger, before the tap even completes
                    if (view is GlyphView && !view.isParted) view.animateOpen(0.3f, 90, Curves.OPEN)
                }
                android.view.MotionEvent.ACTION_UP ->
                    view.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(140).start()
                android.view.MotionEvent.ACTION_CANCEL -> {
                    view.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(140).start()
                    if (view is GlyphView && !view.isParted) view.animateOpen(0f, 140, Curves.CLOSE)
                }
            }
            false
        }
    }
}

enum class Glyph { GRID, CHEVRON }

/** Draws the two non-app controls, so they scale with the icon size and take the finish's ink. */
@SuppressLint("ViewConstructor")
class GlyphView(context: Context, private val kind: Glyph, ink: Int) : View(context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ink; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val r = RectF()
    private val path = Path()
    private var openness = 0f
    private var anim: android.animation.ValueAnimator? = null

    /** 0 is closed, 1 is fully parted. The squares part as the grid they stand for opens. */
    fun animateOpen(target: Float, ms: Long, curve: android.animation.TimeInterpolator) {
        anim?.cancel()
        anim = android.animation.ValueAnimator.ofFloat(openness, target).apply {
            duration = ms
            interpolator = curve
            addUpdateListener { openness = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    val isParted get() = openness > 0.5f

    override fun onDraw(canvas: Canvas) {
        val s = minOf(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        when (kind) {
            Glyph.GRID -> {
                val cell = s * (0.2f - 0.03f * openness)
                val g = s * (0.08f + 0.1f * openness)
                val start = cx - cell - g / 2
                val top = cy - cell - g / 2
                for (row in 0..1) for (col in 0..1) {
                    r.set(start + col * (cell + g), top + row * (cell + g), start + col * (cell + g) + cell, top + row * (cell + g) + cell)
                    canvas.drawRoundRect(r, cell * 0.3f, cell * 0.3f, fill)
                }
            }
            Glyph.CHEVRON -> {
                line.strokeWidth = s * 0.07f
                val w = s * 0.2f
                path.reset()
                path.moveTo(cx - w, cy - w / 2); path.lineTo(cx, cy + w / 2); path.lineTo(cx + w, cy - w / 2)
                line.alpha = 180
                canvas.drawPath(path, line)
            }
        }
    }
}
