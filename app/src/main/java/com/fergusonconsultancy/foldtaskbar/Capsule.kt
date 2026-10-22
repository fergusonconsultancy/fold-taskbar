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
    data class AppPair(val left: String, val right: String) : Slot { val key get() = "pair:$left|$right" }
}

interface CapsuleActions {
    fun onApp(pkg: String, view: View)
    fun onAppHeld(pkg: String, view: View)
    fun onAllApps(view: View)
    fun onAllAppsHeld(view: View)
    fun onPair(left: String, right: String, view: View)
    fun onPairHeld(left: String, right: String, view: View)
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
    private val appViews = HashMap<View, String>()
    private val dividers = mutableListOf<View>()
    private var grid: GlyphView? = null

    // The capsule's current colours. Smoked glass changes them to suit what is behind it.
    private var fillNow = finish.fill
    private var inkNow = finish.ink
    private var edgeNow = finish.edge
    private var lightNow = finish.indicator
    private var lightBar: Boolean? = null
    private var toneAnim: android.animation.ValueAnimator? = null
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

    /** Whether a point on screen is on the grid button, the bar's handle. */
    fun isHandle(x: Float, y: Float): Boolean {
        val g = grid ?: return false
        val loc = IntArray(2).also { g.getLocationOnScreen(it) }
        return x >= loc[0] && x <= loc[0] + g.width && y >= loc[1] && y <= loc[1] + g.height
    }

    /**
     * Smoked glass: given the average colour behind the capsule, turn dark over light
     * content and light over dark content, borrowing a trace of the colour underneath so
     * the glass belongs to what it sits on. The thresholds overlap, so mid-tones never
     * make it flicker between the two.
     */
    fun adaptTo(behind: Int, luminance: Float) {
        val light = when {
            luminance < 0.36f -> true
            luminance > 0.54f -> false
            else -> lightBar ?: false
        }
        lightBar = light
        val base = if (light) 0xF4F0E8 else 0x101412
        val tint = if (light) 0.10f else 0.14f
        fun ch(shift: Int) = ((((base shr shift) and 255) * (1 - tint)) + (((behind shr shift) and 255) * tint)).toInt()
        val fill = ((if (light) 0xE8 else 0xDB) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        val ink = if (light) 0xFF1A201C.toInt() else 0xFFEDEAE2.toInt()
        val edge = if (light) 0x1F000000 else 0x1AFFFFFF
        val glow = if (light) 0xFF8C6A33.toInt() else 0xFFD3B27C.toInt()
        toneTo(fill, ink, edge, glow)
    }

    private fun toneTo(fill: Int, ink: Int, edge: Int, glow: Int) {
        if (fill == fillNow && ink == inkNow && edge == edgeNow && glow == lightNow) return
        val from = intArrayOf(fillNow, inkNow, edgeNow, lightNow)
        val to = intArrayOf(fill, ink, edge, glow)
        val ev = android.animation.ArgbEvaluator()
        toneAnim?.cancel()
        toneAnim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 320
            interpolator = Curves.OPEN
            addUpdateListener { a ->
                val t = a.animatedFraction
                paint(
                    ev.evaluate(t, from[0], to[0]) as Int, ev.evaluate(t, from[1], to[1]) as Int,
                    ev.evaluate(t, from[2], to[2]) as Int, ev.evaluate(t, from[3], to[3]) as Int,
                )
            }
            start()
        }
    }

    private fun paint(fill: Int, ink: Int, edge: Int, glow: Int) {
        fillNow = fill; inkNow = ink; edgeNow = edge; lightNow = glow
        (background as? android.graphics.drawable.GradientDrawable)?.apply {
            setColor(fill)
            setStroke(context.dp(1).coerceAtLeast(1), edge)
        }
        grid?.setInk(ink)
        dividers.forEach { it.setBackgroundColor((ink and 0x00FFFFFF) or 0x30000000) }
        indicators.values.forEach { (it.background as? android.graphics.drawable.GradientDrawable)?.setColor(glow) }
    }

    /** The app icon under a point on screen, for dragging an app out of the bar. */
    fun appAt(x: Float, y: Float): Pair<String, View>? {
        val loc = IntArray(2)
        for ((v, pkg) in appViews) {
            v.getLocationOnScreen(loc)
            if (x >= loc[0] && x <= loc[0] + v.width && y >= loc[1] && y <= loc[1] + v.height) return pkg to v
        }
        return null
    }

    /** Moves the open-app marks without touching anything else, so nothing redraws. */
    fun setActive(active: Set<String>) {
        val lit = indicators.filter { it.value.visibility == VISIBLE && it.value.tag != "off" }.keys
        val on = indicators.keys.filter { it in active && it !in lit }
        val off = lit.filter { it !in active }
        if (!isAttachedToWindow || width == 0) {
            indicators.forEach { (pkg, v) -> v.animate().cancel(); v.scaleX = 1f; v.tag = null; v.visibility = if (pkg in active) VISIBLE else INVISIBLE }
            return
        }
        fun x(v: View) = IntArray(2).also { v.getLocationOnScreen(it) }[if (vertical) 1 else 0]
        val goingTo = on.firstOrNull()?.let { indicators[it] }
        val comingFrom = off.firstOrNull()?.let { indicators[it] }
        off.forEach { pkg ->
            val v = indicators[pkg] ?: return@forEach
            // The light leaves through the side facing the newly opened app...
            v.pivotX = if (goingTo != null && x(goingTo) > x(v)) v.width.toFloat() else 0f
            v.tag = "off"
            v.animate().cancel()
            v.animate().scaleX(0f).setDuration(180).setInterpolator(Curves.CLOSE)
                .withEndAction { if (v.tag == "off") { v.visibility = INVISIBLE; v.scaleX = 1f; v.tag = null } }.start()
        }
        on.forEach { pkg ->
            val v = indicators[pkg] ?: return@forEach
            // ...and arrives through the side facing the app you left
            v.pivotX = if (comingFrom != null && x(comingFrom) > x(v)) v.width.toFloat() else 0f
            v.tag = null
            v.animate().cancel()
            v.scaleX = 0f
            v.visibility = VISIBLE
            v.animate().scaleX(1f).setStartDelay(if (comingFrom != null) 90 else 0).setDuration(280).setInterpolator(Curves.OPEN).start()
        }
    }

    private fun rebuild(slots: List<Slot>) {
        removeAllViews()
        indicators.clear()
        appViews.clear()
        dividers.clear()
        grid = null
        slots.forEachIndexed { i, slot ->
            val v = when (slot) {
                Slot.AllApps -> glyph(Glyph.GRID) { actions.onAllApps(it) }.also { grid = it as GlyphView }.apply {
                    setOnLongClickListener { it.thud(); actions.onAllAppsHeld(it); true }
                }
                Slot.Divider -> View(context).apply {
                    setBackgroundColor(finish.ink and 0x00FFFFFF or 0x30000000)
                    val thin = context.dp(1).coerceAtLeast(1)
                    val long = context.dp(iconDp * 0.6f)
                    layoutParams = if (vertical) LayoutParams(long, thin) else LayoutParams(thin, long)
                }.also { dividers += it }
                is Slot.App -> appIcon(slot)
                is Slot.AppPair -> pairIcon(slot)
            }
            if (i > 0) (v.layoutParams as LayoutParams).apply { if (vertical) topMargin = gap else marginStart = gap }
            addView(v)
        }
        paint(fillNow, inkNow, edgeNow, lightNow)
    }

    /** A pair: the left app up and to the left, the right app down and to the right. */
    private fun pairIcon(slot: Slot.AppPair): View {
        val size = context.dp(iconDp)
        val small = (size * 0.68f).toInt()
        val frame = FrameLayout(context).apply {
            layoutParams = LayoutParams(size, size + context.dp(6))
            contentDescription = "${apps.entry(slot.left)?.label} and ${apps.entry(slot.right)?.label}"
        }
        frame.addView(ImageView(context).apply {
            setImageDrawable(apps.icon(slot.left))
            layoutParams = FrameLayout.LayoutParams(small, small, Gravity.TOP or Gravity.START)
        })
        frame.addView(ImageView(context).apply {
            setImageDrawable(apps.icon(slot.right))
            elevation = context.dp(2).toFloat()
            layoutParams = FrameLayout.LayoutParams(small, small, Gravity.TOP or Gravity.START).apply {
                leftMargin = size - small; topMargin = size - small
            }
        })
        frame.addView(indicator().also { indicators[slot.key] = it })
        pressable(frame)
        frame.setOnClickListener { it.tick(); actions.onPair(slot.left, slot.right, it) }
        frame.setOnLongClickListener { it.thud(); actions.onPairHeld(slot.left, slot.right, it); true }
        return frame
    }

    private fun indicator() = View(context).apply {
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(finish.indicator); cornerRadius = context.dp(2).toFloat()
        }
        visibility = INVISIBLE
        layoutParams = FrameLayout.LayoutParams(context.dp(iconDp * 0.34f), context.dp(3), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
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
        appViews[frame] = slot.pkg
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

    fun setInk(c: Int) {
        if (fill.color == c) return
        fill.color = c; line.color = c
        invalidate()
    }
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
