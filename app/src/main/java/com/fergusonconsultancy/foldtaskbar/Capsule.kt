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

/** How many stretches of the bar adapt their tone separately. */
const val ZONES = 8

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

    // The capsule's colours, zone by zone along its length. Smoked glass sets each zone to
    // suit what is behind that part of the bar; fixed finishes keep every zone the same.
    private val zones = ZONES
    private var fills = IntArray(zones) { finish.fill }
    private var inks = IntArray(zones) { finish.ink }
    private var edges = IntArray(zones) { finish.edge }
    private var glows = IntArray(zones) { finish.indicator }
    private val lightZone = arrayOfNulls<Boolean>(zones)
    private var toneAnim: android.animation.ValueAnimator? = null
    private val glass = Glass()
    private var current: List<Slot> = emptyList()

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = glass
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
     * Smoked glass, zone by zone. [behind] holds the colour and brightness behind each
     * stretch of the capsule, start to end. Each zone turns dark over light content and
     * light over dark, borrowing a trace of the colour underneath; the zones blend into one
     * continuous piece of glass, so a bar half over a white page and half over a dark photo
     * is smoke on one side and frost on the other. Each zone's thresholds overlap, so
     * mid-tones never make it flicker.
     */
    fun adaptTo(behind: List<Pair<Int, Float>?>) {
        if (behind.size != zones) return
        val f = fills.copyOf(); val i = inks.copyOf(); val e = edges.copyOf(); val g = glows.copyOf()
        for (z in 0 until zones) {
            val (colour, lum) = behind[z] ?: continue
            val light = when {
                lum < 0.36f -> true
                lum > 0.54f -> false
                else -> lightZone[z] ?: false
            }
            lightZone[z] = light
            val base = if (light) 0xF4F0E8 else 0x101412
            val tint = if (light) 0.10f else 0.14f
            fun ch(shift: Int) = ((((base shr shift) and 255) * (1 - tint)) + (((colour shr shift) and 255) * tint)).toInt()
            f[z] = ((if (light) 0xE8 else 0xDB) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
            i[z] = if (light) 0xFF1A201C.toInt() else 0xFFEDEAE2.toInt()
            e[z] = if (light) 0x1F000000 else 0x1AFFFFFF
            g[z] = if (light) 0xFF8C6A33.toInt() else 0xFFD3B27C.toInt()
        }
        toneTo(f, i, e, g)
    }

    private fun toneTo(f: IntArray, i: IntArray, e: IntArray, g: IntArray) {
        if (f.contentEquals(fills) && i.contentEquals(inks) && e.contentEquals(edges) && g.contentEquals(glows)) return
        val from = listOf(fills.copyOf(), inks.copyOf(), edges.copyOf(), glows.copyOf())
        val to = listOf(f, i, e, g)
        val ev = android.animation.ArgbEvaluator()
        toneAnim?.cancel()
        toneAnim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 320
            interpolator = Curves.OPEN
            addUpdateListener { a ->
                val t = a.animatedFraction
                val mix = (0..3).map { k -> IntArray(zones) { z -> ev.evaluate(t, from[k][z], to[k][z]) as Int } }
                paint(mix[0], mix[1], mix[2], mix[3])
            }
            start()
        }
    }

    private fun paint(f: IntArray, i: IntArray, e: IntArray, g: IntArray) {
        fills = f; inks = i; edges = e; glows = g
        glass.invalidateSelf()
        // Every mark on the bar takes the tone of the zone it sits in
        grid?.let { it.setInk(inks[zoneOf(it)]) }
        dividers.forEach { it.setBackgroundColor((inks[zoneOf(it)] and 0x00FFFFFF) or 0x30000000) }
        indicators.values.forEach { v ->
            val frame = v.parent as? View ?: v
            (v.background as? android.graphics.drawable.GradientDrawable)?.setColor(glows[zoneOf(frame)])
        }
    }

    /** Which zone a child sits in, by its centre along the capsule. */
    private fun zoneOf(v: View): Int {
        val len = if (vertical) height else width
        if (len <= 0) return 0
        val c = if (vertical) v.top + v.height / 2f else v.left + v.width / 2f
        return (c / len * zones).toInt().coerceIn(0, zones - 1)
    }

    /** The glass itself: one rounded capsule whose fill and edge run through the zones' colours. */
    private inner class Glass : android.graphics.drawable.Drawable() {
        private val fillPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val edgePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = context.dp(1).coerceAtLeast(1).toFloat()
        }
        private val r = RectF()
        private val stops = FloatArray(ZONES) { (it + 0.5f) / ZONES }

        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.isEmpty) return
            r.set(b)
            val radius = minOf(r.width(), r.height()) / 2f
            val x1 = if (vertical) r.left else r.right
            val y1 = if (vertical) r.bottom else r.top
            fillPaint.shader = android.graphics.LinearGradient(r.left, r.top, x1, y1, fills, stops, android.graphics.Shader.TileMode.CLAMP)
            canvas.drawRoundRect(r, radius, radius, fillPaint)
            val h = edgePaint.strokeWidth / 2
            r.inset(h, h)
            edgePaint.shader = android.graphics.LinearGradient(r.left, r.top, x1, y1, edges, stops, android.graphics.Shader.TileMode.CLAMP)
            canvas.drawRoundRect(r, radius - h, radius - h, edgePaint)
        }

        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
        override fun getOutline(outline: android.graphics.Outline) {
            val b = bounds
            outline.setRoundRect(b, minOf(b.width(), b.height()) / 2f)
        }
    }

    /** The view showing [key] (an app's package, or a pair's key), if it is in this capsule. */
    fun viewFor(key: String): View? = appViews.entries.firstOrNull { it.value == key }?.key

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
        post { paint(fills, inks, edges, glows) }
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
        appViews[frame] = slot.key
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
