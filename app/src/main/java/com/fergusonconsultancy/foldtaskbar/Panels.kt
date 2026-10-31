package com.fergusonconsultancy.foldtaskbar

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewAnimationUtils
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.hypot

/** The small menu that opens when you hold an app in the bar or the drawer. */
@SuppressLint("ViewConstructor")
class ActionMenu(
    context: Context,
    finish: Finish,
    title: String,
    rows: List<Pair<String, () -> Unit>>,
) : LinearLayout(context) {
    init {
        orientation = VERTICAL
        background = surface(context, finish, 18f)
        elevation = context.dp(8).toFloat()
        val pad = context.dp(6)
        setPadding(pad, pad, pad, pad)
        addView(TextView(context).apply {
            text = title
            textSize = 13f
            setTextColor(finish.ink)
            alpha = 0.6f
            setPadding(context.dp(14), context.dp(8), context.dp(14), context.dp(4))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        rows.forEach { (label, action) ->
            addView(TextView(context).apply {
                text = label
                textSize = 16f
                setTextColor(finish.ink)
                minHeight = context.dp(44)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(context.dp(14), 0, context.dp(20), 0)
                setOnClickListener { it.tick(); action() }
            })
        }
    }
}

/** The grid's side of dragging an app out; the bar's side is [BarHost]. */
interface GridDrag {
    fun onGridDragStart(pkg: String, x: Float, y: Float)
    fun onGridDrag(x: Float, y: Float)
    fun onGridDragEnd(x: Float, y: Float, cancelled: Boolean)
}

/** Motion curves from the charter: open lands gently, close leaves quickly. */
/**
 * The bar's lattice: its capsule on screen, the centre of its first icon along its length,
 * the distance from one icon to the next, the icon size, and whether it stands upright.
 */
data class Lattice(val bar: android.graphics.Rect, val anchor: Float, val pitch: Float, val iconPx: Int, val vertical: Boolean, val last: Float = Float.NaN) {
    /**
     * The grid's columns under a lying bar (30 Oct 2026): spaced evenly from the bar's first
     * icon (the grid button) to its last, each at least [minCell] wide. The grid's outer columns
     * stand exactly under the bar's ends, and there is no empty margin beside the first column.
     * Returns the count, the first centre and the spacing, or null if the bar is too short.
     */
    fun columns(minCell: Float): Triple<Int, Float, Float>? {
        if (vertical || last.isNaN() || last - anchor < minCell * 2) return null
        val span = last - anchor
        val n = (kotlin.math.floor(span / minCell).toInt() + 1).coerceIn(3, 6)
        return Triple(n, anchor, span / (n - 1))
    }
}

object Curves {
    val OPEN = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
    val CLOSE = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)
    const val OPEN_MS = 320L
    const val CLOSE_MS = 180L
}

private const val POUR_MS = 380L
private const val DRAIN_MS = 220L

/**
 * The all-apps grid. Built and attached once, invisible until called, so opening it is
 * only an animation. The grid draws only the icons on screen.
 */
class AppDrawer(
    private val context: Context,
    private val wm: WindowManager,
    private val apps: Apps,
    private val onOpenApp: (String, View) -> Unit,
    private val onHoldApp: (String, View) -> Unit,
    private val drag: GridDrag,
) {
    // Dragging an app out of the grid: hold it, then slide. A plain slide scrolls the grid.
    private var armedPkg: String? = null
    private var draggingApp = false
    private var downX = 0f
    private var downY = 0f
    private val dragSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    private val dragOut = object : RecyclerView.SimpleOnItemTouchListener() {
        override fun onInterceptTouchEvent(rv: RecyclerView, e: android.view.MotionEvent): Boolean {
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; armedPkg = null; draggingApp = false }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val pkg = armedPkg
                    if (pkg != null && hypot((e.rawX - downX).toDouble(), (e.rawY - downY).toDouble()) > dragSlop) {
                        draggingApp = true
                        // The grid steps out of the way, leaving the app under your finger
                        panelHost?.animate()?.alpha(0f)?.setDuration(140)?.start()
                        scrim?.animate()?.alpha(0f)?.setDuration(140)?.start()
                        drag.onGridDragStart(pkg, e.rawX, e.rawY)
                        return true
                    }
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> armedPkg = null
            }
            return false
        }

        override fun onTouchEvent(rv: RecyclerView, e: android.view.MotionEvent) {
            if (!draggingApp) return
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_MOVE -> drag.onGridDrag(e.rawX, e.rawY)
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    draggingApp = false
                    armedPkg = null
                    drag.onGridDragEnd(e.rawX, e.rawY, e.actionMasked == android.view.MotionEvent.ACTION_CANCEL)
                    close(animated = false)
                }
            }
        }
    }


    private var scrim: View? = null
    private var panelHost: FrameLayout? = null
    private var panel: LinearLayout? = null
    private var grid: RecyclerView? = null
    /** Concentrato: how much of the grid's labels show, falling with fling speed. */
    private var labelDetail = 1f
    private var adapter: Adapter? = null
    private var button: GlyphView? = null
    private var title: TextView? = null
    private var key = ""
    private var running: Animator? = null
    private var liquid: LiquidPanel? = null
    /** The panel's edge that faces the bar: the edge the liquid pours in through. */
    enum class Side { BOTTOM, TOP, LEFT, RIGHT }
    private var side = Side.BOTTOM
    private val fromTop get() = side == Side.TOP
    private val sideways get() = side == Side.LEFT || side == Side.RIGHT
    /** Set when an app is opened from the grid: the panel drains into its icon. */
    private var drainTo: View? = null

    var isOpen = false
        private set

    /** The view a system drag can start from while a grid drag is underway. */
    val dragSource: View? get() = grid

    /** Builds the windows for this size and finish. Cheap to call: does nothing if unchanged. */
    /** [rect] is where the panel sits on screen; [side] is its edge facing the bar. */
    fun attach(finish: Finish, rect: android.graphics.Rect, side: Side, lat: Lattice? = null): Boolean {
        val widthPx = rect.width(); val heightPx = rect.height()
        val k = "$finish|$rect|$side|$lat"
        finishNow = finish
        if (k == key && panelHost != null) return false
        // Never rebuilt under your finger: an open grid keeps its windows until it closes
        if (isOpen && panelHost != null) return false
        detach()
        key = k
        this.side = side

        scrim = View(context).apply {
            // With the screen behind blurred, the shade only needs to settle it, not hide it
            setBackgroundColor(if (blurSupported) 0x38000000 else 0x73000000)
            alpha = 0f
            visibility = View.INVISIBLE
            setOnClickListener { close() }
        }
        wm.addView(scrim, params(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, Gravity.TOP, 0))

        val pad = context.dp(22)
        // Aligned to the bar (25 Oct 2026): the grid uses the bar's own icon size, and its
        // columns (or, beside an upright bar, its rows) sit on the bar's lattice, so every icon
        // in the grid lines up with icons on the bar. A cell spans as many of the bar's steps as
        // its label needs; the eyebrow lines up with the first column's icon.
        val iconPx = lat?.iconPx ?: context.dp(52)
        val titleH = context.dp(40)
        var columns: Int
        var padStart = pad; var padEnd = pad; var padTop = 0
        var rowH = iconPx + context.dp(40)
        var cellW: Float
        val even = lat?.columns(context.dp(84).toFloat())
        if (lat != null && even != null) {
            columns = even.first; cellW = even.third
            padStart = (even.second - cellW / 2f - rect.left).toInt().coerceAtLeast(0)
            padEnd = (widthPx - padStart - columns * cellW).toInt().coerceAtLeast(0)
        } else if (lat != null && !lat.vertical) {
            val stride = kotlin.math.ceil(context.dp(72) / lat.pitch).toInt().coerceAtLeast(1)
            cellW = stride * lat.pitch
            val k0 = kotlin.math.ceil((rect.left + cellW / 2f - lat.anchor) / lat.pitch)
            val c0 = lat.anchor + k0 * lat.pitch
            padStart = (c0 - cellW / 2f - rect.left).toInt().coerceAtLeast(0)
            columns = ((widthPx - padStart) / cellW).toInt().coerceAtLeast(3)
            padEnd = (widthPx - padStart - columns * cellW).toInt().coerceAtLeast(0)
        } else {
            cellW = context.dp(92).toFloat()
            columns = ((widthPx - pad * 2) / cellW).toInt().coerceIn(4, 8)
            if (lat != null) {
                val stride = kotlin.math.ceil((iconPx + context.dp(38)) / lat.pitch).toInt().coerceAtLeast(1)
                rowH = (stride * lat.pitch).toInt()
                // Each row's icon centre sits on one of the bar's icon centres
                val gridTop = rect.top + titleH
                val iconMid = context.dp(8) + iconPx / 2f
                val k0 = kotlin.math.ceil((gridTop + iconMid - lat.anchor) / lat.pitch)
                padTop = (lat.anchor + k0 * lat.pitch - iconMid - gridTop).toInt().coerceAtLeast(0)
            }
        }
        adapter = Adapter(finish, iconPx, rowH)
        grid = RecyclerView(context).apply {
            layoutManager = GridLayoutManager(context, columns)
            // Concentrato: the faster the grid is flung, the more its labels fall away, leaving
            // only the icons streaming past; they return as it slows
            if (Prefs.concentrato(context)) addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                    val v = kotlin.math.abs(dy) / rv.resources.displayMetrics.density
                    val want = 1f - ((v - 6f) / 22f).coerceIn(0f, 1f)
                    labelDetail += (want - labelDetail) * 0.35f
                    fade(rv)
                }
                override fun onScrollStateChanged(rv: RecyclerView, state: Int) {
                    if (state == RecyclerView.SCROLL_STATE_IDLE) {
                        android.animation.ValueAnimator.ofFloat(labelDetail, 1f).apply {
                            duration = 260
                            addUpdateListener { labelDetail = it.animatedValue as Float; fade(rv) }
                            start()
                        }
                    }
                }
                private fun fade(rv: RecyclerView) {
                    for (i in 0 until rv.childCount) (rv.getChildViewHolder(rv.getChildAt(i)) as? Holder)?.label?.alpha = labelDetail
                }
            })
            adapter = this@AppDrawer.adapter
            itemAnimator = null
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            setHasFixedSize(true)
            setItemViewCacheSize(columns * 2)
            addOnItemTouchListener(dragOut)
            setPadding(padStart, padTop, padEnd, pad)
            clipToPadding = false
        }
        panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // The glass is drawn by the liquid around it; the grid carries its own side padding
            setPadding(0, context.dp(18), 0, 0)
            // The doctrine's eyebrow: small champagne capitals, widely spaced
            addView(TextView(context).apply {
                text = "ALL APPS"
                textSize = 11.5f
                typeface = Kit.sans(context, 500)
                letterSpacing = 0.18f
                setTextColor(if (finish.adaptive) faceFor(finish, 0xFF1A1F1C.toInt(), false).glow else finish.indicator)
                // Lined up with the first column's icon
                setPadding(padStart + ((cellW - iconPx) / 2f).toInt(), 0, 0, context.dp(4))
                minHeight = titleH - context.dp(18)
            }.also { title = it })
            addView(grid, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        panel?.isClickable = true
        // The liquid spans the whole screen, so it can rise out of the grid button itself
        // and drain back into it, wherever the bar sits.
        liquid = LiquidPanel(finish).apply {
            addView(panel, FrameLayout.LayoutParams(widthPx, heightPx, Gravity.TOP or Gravity.START).apply {
                leftMargin = rect.left; topMargin = rect.top
            })
            setOnClickListener { close() }
        }
        panelHost = FrameLayout(context).apply {
            addView(liquid, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            visibility = View.INVISIBLE
        }
        wm.addView(panelHost, params(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, Gravity.TOP, 0).apply {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) { flags = flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND; blurBehindRadius = 0 }
        })
        adapter?.submit(apps.all())
        return true
    }

    fun detach() {
        running?.cancel()
        listOf(scrim, panelHost).forEach { v -> if (v != null) runCatching { wm.removeView(v) } }
        scrim = null; panelHost = null; panel = null; grid = null; adapter = null; liquid = null
        isOpen = false
        key = ""
    }

    fun refreshApps() = adapter?.submit(apps.all())

    /** [from] is the grid button: the grid pours up out of it and drains back into it. */
    fun open(from: GlyphView?, heading: String = "All apps") {
        title?.text = heading.uppercase()
        val host = panelHost ?: return
        val l = liquid ?: return
        val s = scrim ?: return
        if (isOpen) return
        isOpen = true
        button = from
        drainTo = null
        running?.cancel()
        setTouchable(true)
        // Once drawn, make sure the change held
        host.post { if (isOpen) ensureTouchable() }

        // Rule 5: the button answers first, in the same frame.
        from?.animateOpen(1f, Curves.OPEN_MS, Curves.OPEN)

        host.alpha = 1f
        s.visibility = View.VISIBLE
        s.animate().alpha(1f).setDuration(300).setInterpolator(Curves.OPEN).start()
        host.visibility = View.VISIBLE

        l.origin = originRect(l, from)
        running = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = POUR_MS
            interpolator = null
            addUpdateListener { l.progress = it.animatedFraction; l.invalidate(); blurTo(l.progress) }
            start()
        }
        bubble(l.origin)
    }

    fun close(animated: Boolean = true) {
        if (!isOpen) return
        isOpen = false
        setTouchable(false)
        running?.cancel()
        button?.animateOpen(0f, Curves.CLOSE_MS, Curves.CLOSE)
        val host = panelHost ?: return
        val l = liquid ?: return
        val s = scrim
        s?.animate()?.alpha(0f)?.setDuration(DRAIN_MS)?.setInterpolator(Curves.CLOSE)
            ?.withEndAction { if (!isOpen) s.visibility = View.INVISIBLE }?.start()

        if (!animated || !l.isAttachedToWindow) { hideNow(host); return }
        // Drain into the app you chose, or back into the grid button
        l.origin = drainTo?.let { rectOf(l, it) } ?: originRect(l, button)
        grid?.let { g -> (0 until g.childCount).map { g.getChildAt(it) }.forEach { c ->
            if (drainTo == null || c !== drainTo?.parent) c.animate().alpha(0f).scaleX(0.9f).scaleY(0.9f).setStartDelay(0).setDuration(110).start()
        } }
        val from = l.progress
        running = ValueAnimator.ofFloat(from, 0f).apply {
            duration = DRAIN_MS
            interpolator = Curves.CLOSE
            addUpdateListener { l.progress = it.animatedValue as Float; l.invalidate(); blurTo(l.progress) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { if (!isOpen) hideNow(host) }
            })
            start()
        }
    }

    private val blurSupported get() = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S &&
        runCatching { wm.isCrossWindowBlurEnabled }.getOrDefault(false)

    /**
     * Depth (Concentrato): the screen behind falls out of focus as the grid opens, so nothing
     * behind the glass is ever legible through it. Before, text showed through the panel in
     * ghosted lines; now the glass is clean and what is behind is only colour and light.
     */
    private fun blurTo(f: Float) {
        val host = panelHost ?: return
        if (!blurSupported) return
        val lp = host.layoutParams as? WindowManager.LayoutParams ?: return
        val want = (context.dp(36) * f.coerceIn(0f, 1f)).toInt()
        if (kotlin.math.abs(lp.blurBehindRadius - want) < 2 && !(want == 0 && lp.blurBehindRadius != 0)) return
        lp.blurBehindRadius = want
        runCatching { wm.updateViewLayout(host, lp) }
    }

    /**
     * Adaptive glass: the panel takes the colour of what it opens over. Over a light screen it
     * is deep smoke with a trace of that colour; over a dark one it lifts a step, so it always
     * stands clear of what is behind it. [colour] and [lum] are read from the screen under it.
     */
    private var finishNow: Finish? = null
    private var lightNow: Boolean? = null

    /**
     * The grid wears what the bar is wearing (25 Oct 2026): the same finish, adapted the same
     * way to the screen it opens over (see faceFor). Over a dark screen smoked glass turns to
     * light glass with dark ink, exactly as the bar does; fixed finishes keep their own face.
     */
    fun setBackdrop(colour: Int, lum: Float) {
        val l = liquid ?: return
        val finish = finishNow ?: return
        if (!finish.adaptive) return
        val light = lightOver(lum, lightNow)
        lightNow = light
        val face = faceFor(finish, colour or 0xFF000000.toInt(), light)
        // Never thinner than the bar's own glass; with the screen behind blurred it can be a touch clearer
        val alpha = maxOf((face.fill ushr 24), if (blurSupported) 0xE0 else 0xF5)
        val from = l.tone; val fromEdge = l.edgeColour; val fromInk = adapter?.ink ?: face.ink
        val fromTitle = title?.currentTextColor ?: face.glow
        android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220
            interpolator = Curves.OPEN
            addUpdateListener {
                val k = it.animatedFraction
                l.tone = (blend(from, face.fill, k) and 0x00FFFFFF) or (alpha shl 24)
                l.edgeColour = blend(fromEdge, face.edge, k)
                l.invalidate()
                adapter?.ink = blend(fromInk, face.ink, k)
                title?.setTextColor(blend(fromTitle, face.glow, k))
            }
            start()
        }
    }

    private fun hideNow(host: View) {
        blurTo(0f)
        host.visibility = View.INVISIBLE
        host.alpha = 1f
        drainTo = null
        grid?.let { g -> (0 until g.childCount).map { g.getChildAt(it) }.forEach { c ->
            c.animate().cancel(); c.alpha = 1f; c.scaleX = 1f; c.scaleY = 1f; c.translationX = 0f; c.translationY = 0f
        } }
        grid?.scrollToPosition(0)
    }

    /**
     * The icons bubble up out of the liquid as it arrives: nearest the grid button first,
     * each on a soft spring that swells a touch past its size and settles, like a drop
     * finding its shape.
     */
    private fun bubble(origin: android.graphics.RectF) {
        val g = grid ?: return
        val l = liquid ?: return
        val children = (0 until g.childCount).map { g.getChildAt(it) }
        if (children.isEmpty()) return
        val gl = IntArray(2).also { g.getLocationOnScreen(it) }
        val ll = IntArray(2).also { l.getLocationOnScreen(it) }
        val ox = origin.centerX() + ll[0] - gl[0]
        val oy = origin.centerY() + ll[1] - gl[1]
        val far = children.maxOf { c -> hypot((c.left + c.width / 2f - ox).toDouble(), (c.top + c.height / 2f - oy).toDouble()) }.toFloat().coerceAtLeast(1f)
        children.forEach { c ->
            val dx = c.left + c.width / 2f - ox
            val dy = c.top + c.height / 2f - oy
            val d = hypot(dx.toDouble(), dy.toDouble()).toFloat()
            c.animate().cancel()
            c.alpha = 0f
            c.scaleX = 0.82f; c.scaleY = 0.82f
            // Each icon starts a little closer to where the liquid came from
            c.translationX = -dx * 0.06f; c.translationY = -dy * 0.06f
            c.animate()
                .alpha(1f).scaleX(1f).scaleY(1f).translationX(0f).translationY(0f)
                .setStartDelay((40 + d / far * 160).toLong())
                .setDuration(300)
                .setInterpolator(Curves.OPEN)
                .start()
        }
    }

    /**
     * Where the pour begins: the grid button itself, as a round drop sitting exactly over it.
     * Without a button on screen, a drop at the middle of the panel's near edge.
     */
    private fun originRect(l: View, from: View?): android.graphics.RectF {
        from?.takeIf { it.isAttachedToWindow && it.width > 0 }?.let { b ->
            val r = rectOf(l, b)
            val d = minOf(r.width(), r.height())
            return android.graphics.RectF(r.centerX() - d / 2, r.centerY() - d / 2, r.centerX() + d / 2, r.centerY() + d / 2)
        }
        val p = panel ?: return android.graphics.RectF()
        val size = context.dp(44).toFloat()
        val cx = (p.left + p.right) / 2f; val cy = (p.top + p.bottom) / 2f
        return when (side) {
            Side.TOP -> android.graphics.RectF(cx - size / 2, p.top.toFloat(), cx + size / 2, p.top + size)
            Side.BOTTOM -> android.graphics.RectF(cx - size / 2, p.bottom - size, cx + size / 2, p.bottom.toFloat())
            Side.LEFT -> android.graphics.RectF(p.left.toFloat(), cy - size / 2, p.left + size, cy + size / 2)
            Side.RIGHT -> android.graphics.RectF(p.right - size, cy - size / 2, p.right.toFloat(), cy + size / 2)
        }
    }

    private fun rectOf(l: View, v: View): android.graphics.RectF {
        val a = IntArray(2).also { v.getLocationOnScreen(it) }
        val b = IntArray(2).also { l.getLocationOnScreen(it) }
        return android.graphics.RectF((a[0] - b[0]).toFloat(), (a[1] - b[1]).toFloat(), (a[0] - b[0] + v.width).toFloat(), (a[1] - b[1] + v.height).toFloat())
    }

    /**
     * The panel's glass. At progress 0 it is the grid button's own outline at [origin]; it
     * fades in as it lifts away and opens into the panel, corners relaxing from round to the
     * panel's radius. Content is clipped to the same shape.
     */
    private inner class LiquidPanel(private val finish: Finish) : FrameLayout(context) {
        var origin = android.graphics.RectF()
        var progress = 1f
        val inset = context.dp(10).toFloat()
        private val path = android.graphics.Path()
        // Adaptive finishes take their tone from the screen beneath (setBackdrop); fixed finishes keep theirs
        var tone = if (finish.adaptive) (faceFor(finish, 0xFF1A1F1C.toInt(), false).fill and 0x00FFFFFF) or ((if (blurSupported) 0xE0 else 0xF5) shl 24) else finish.fill
        var edgeColour = if (finish.adaptive) faceFor(finish, 0xFF1A1F1C.toInt(), false).edge else 0x33D9BB86
        private val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        // One hairline of champagne: the edge of the glass, never a border
        private val edge = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE; strokeWidth = context.dp(1).toFloat()
        }
        private val r = android.graphics.RectF()

        init { setWillNotDraw(false); clipChildren = true }

        override fun dispatchDraw(canvas: Canvas) {
            val p = getChildAt(0) ?: return
            val full = android.graphics.RectF(p.left.toFloat(), p.top.toFloat(), p.right.toFloat(), p.bottom.toFloat())
            val t = progress.coerceIn(0f, 1f)
            // One clean shape lifts off the grid button and opens into the panel. Every edge
            // rides the same curve; the far edge runs a step ahead and the near edge a step
            // behind, which is all the "pour" there is. No drop, no neck, no overshoot.
            val far = Curves.OPEN.getInterpolation((t * 1.12f).coerceAtMost(1f))
            val near = Curves.OPEN.getInterpolation(((t - 0.08f) / 0.92f).coerceIn(0f, 1f))
            val cross = Curves.OPEN.getInterpolation(((t - 0.03f) / 0.97f).coerceIn(0f, 1f))
            fun mix(a: Float, b: Float, f: Float) = a + (b - a) * f
            r.left = mix(origin.left, full.left, when (side) { Side.RIGHT -> far; Side.LEFT -> near; else -> cross })
            r.right = mix(origin.right, full.right, when (side) { Side.LEFT -> far; Side.RIGHT -> near; else -> cross })
            r.top = mix(origin.top, full.top, when (side) { Side.BOTTOM -> far; Side.TOP -> near; else -> cross })
            r.bottom = mix(origin.bottom, full.bottom, when (side) { Side.TOP -> far; Side.BOTTOM -> near; else -> cross })
            val radius = mix(origin.width() / 2f, context.dp(30).toFloat(), cross)

            path.reset()
            path.addRoundRect(r, radius, radius, android.graphics.Path.Direction.CW)
            // The glass fades in as it leaves the button, so nothing dark ever sits on it
            val a = (t / 0.22f).coerceAtMost(1f)
            val layer = if (a < 1f) canvas.saveLayerAlpha(null, (a * 255).toInt()) else -1
            fill.color = tone
            edge.color = edgeColour
            canvas.drawPath(path, fill)
            canvas.drawPath(path, edge)
            canvas.save()
            canvas.clipPath(path)
            super.dispatchDraw(canvas)
            canvas.restore()
            if (layer >= 0) canvas.restoreToCount(layer)
        }
    }

    /**
     * 31 Oct 2026: opening the grid moments after its windows were rebuilt could leave it
     * untouchable, so a scroll went to the app behind. A new window is not yet attached for a
     * frame or two, and this used to skip it then. The window manager takes the change as soon
     * as the window has been added, so it is applied at once, and checked again once drawn.
     */
    private fun setTouchable(on: Boolean) {
        listOf(scrim, panelHost).forEach { v ->
            if (v == null) return@forEach
            val lp = v.layoutParams as? WindowManager.LayoutParams ?: return@forEach
            lp.flags = if (on) lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            else lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            runCatching { wm.updateViewLayout(v, lp) }
        }
    }

    private fun ensureTouchable() {
        val stuck = listOf(scrim, panelHost).any { v ->
            val lp = v?.layoutParams as? WindowManager.LayoutParams
            lp != null && lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0
        }
        if (stuck) setTouchable(true)
    }

    private fun params(w: Int, h: Int, gravity: Int, y: Int) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
        PixelFormat.TRANSLUCENT,
    ).apply {
        this.gravity = gravity
        this.y = y
        setFitInsetsTypes(0)
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    }

    private inner class Adapter(private val finish: Finish, private val iconPx: Int, private val rowH: Int) : RecyclerView.Adapter<Holder>() {
        /** The labels' ink: follows the panel's face as it adapts. */
        var ink: Int = if (finish.adaptive) faceFor(finish, 0xFF1A1F1C.toInt(), false).ink else finish.ink
            set(v) {
                field = v
                grid?.let { g -> for (i in 0 until g.childCount) (g.getChildViewHolder(g.getChildAt(i)) as? Holder)?.label?.setTextColor(v) }
            }
        private var items: List<AppEntry> = emptyList()

        @SuppressLint("NotifyDataSetChanged")
        fun submit(list: List<AppEntry>) { items = list; notifyDataSetChanged() }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val icon = ImageView(context).apply { layoutParams = LinearLayout.LayoutParams(iconPx, iconPx) }
            val label = TextView(context).apply {
                textSize = 11.5f
                typeface = Kit.sans(context, 400)
                letterSpacing = 0.01f
                setTextColor(ink)
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(context.dp(2), context.dp(8), context.dp(2), 0)
            }
            val cell = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, context.dp(8), 0, 0)
                // Every row the same height, so rows keep the bar's rhythm
                layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, rowH)
                addView(icon); addView(label)
            }
            return Holder(cell, icon, label)
        }

        override fun onBindViewHolder(h: Holder, position: Int) {
            val app = items[position]
            h.icon.setImageDrawable(apps.icon(app.pkg))
            h.label.text = app.label
            h.label.setTextColor(ink)
            h.label.alpha = labelDetail
            h.itemView.contentDescription = app.label
            h.itemView.setOnClickListener {
                it.tick()
                drainTo = h.icon
                h.icon.animate().scaleX(1.14f).scaleY(1.14f).setDuration(140).setInterpolator(Curves.OPEN).start()
                onOpenApp(app.pkg, h.icon)
            }
            h.itemView.setOnLongClickListener { it.thud(); armedPkg = app.pkg; onHoldApp(app.pkg, it); true }
        }
    }

    private class Holder(v: View, val icon: ImageView, val label: TextView) : RecyclerView.ViewHolder(v)
}
