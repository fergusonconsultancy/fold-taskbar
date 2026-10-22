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
object Curves {
    val OPEN = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
    val CLOSE = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)
    const val OPEN_MS = 320L
    const val CLOSE_MS = 180L
}

private const val POUR_MS = 560L
private const val DRAIN_MS = 240L

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
    fun attach(finish: Finish, rect: android.graphics.Rect, side: Side): Boolean {
        val widthPx = rect.width(); val heightPx = rect.height()
        val k = "$finish|$rect|$side"
        if (k == key && panelHost != null) return false
        detach()
        key = k
        this.side = side

        scrim = View(context).apply {
            setBackgroundColor(0x73000000)
            alpha = 0f
            visibility = View.INVISIBLE
            setOnClickListener { close() }
        }
        wm.addView(scrim, params(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, Gravity.TOP, 0))

        val pad = context.dp(18)
        val cell = context.dp(84)
        val columns = ((widthPx - pad * 2) / cell).coerceIn(4, 8)
        adapter = Adapter(finish)
        grid = RecyclerView(context).apply {
            layoutManager = GridLayoutManager(context, columns)
            adapter = this@AppDrawer.adapter
            itemAnimator = null
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            setHasFixedSize(true)
            setItemViewCacheSize(columns * 2)
            addOnItemTouchListener(dragOut)
        }
        panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // The glass is drawn by the liquid around it
            setPadding(pad, pad, pad, pad)
            addView(TextView(context).apply {
                text = "All apps"
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(finish.ink)
                setPadding(context.dp(6), 0, 0, context.dp(12))
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
        wm.addView(panelHost, params(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, Gravity.TOP, 0))
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
        title?.text = heading
        val host = panelHost ?: return
        val l = liquid ?: return
        val s = scrim ?: return
        if (isOpen) return
        isOpen = true
        button = from
        drainTo = null
        running?.cancel()
        setTouchable(true)

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
            addUpdateListener { l.progress = it.animatedFraction; l.invalidate() }
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
            addUpdateListener { l.progress = it.animatedValue as Float; l.invalidate() }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { if (!isOpen) hideNow(host) }
            })
            start()
        }
    }

    private fun hideNow(host: View) {
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
            c.scaleX = 0.45f; c.scaleY = 0.45f
            // Each icon starts a little closer to where the liquid came from
            c.translationX = -dx * 0.12f; c.translationY = -dy * 0.12f
            c.animate()
                .alpha(1f).scaleX(1f).scaleY(1f).translationX(0f).translationY(0f)
                .setStartDelay((70 + d / far * 260).toLong())
                .setDuration(420)
                .setInterpolator(android.view.animation.OvershootInterpolator(1.6f))
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
     * The panel's glass, drawn as a liquid. At progress 0 it is a drop at [origin]; as it
     * rises, the edge facing away from the bar surges ahead on a soft spring while the
     * sides follow, so a column climbs out of the button and then spreads into the panel,
     * its corners relaxing from round to the panel's radius. A neck joins it to the drop
     * until the panel has filled out. Content is clipped to the same shape.
     */
    private inner class LiquidPanel(private val finish: Finish) : FrameLayout(context) {
        var origin = android.graphics.RectF()
        var progress = 1f
        val inset = context.dp(10).toFloat()
        private val path = android.graphics.Path()
        private val extra = android.graphics.Path()
        private val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = finish.fill }
        private val edge = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE; color = finish.edge; strokeWidth = context.dp(1).toFloat()
        }
        private val shade = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0x33000000 }
        private val r = android.graphics.RectF()
        private val surge = android.view.animation.OvershootInterpolator(0.9f)

        init { setWillNotDraw(false); clipChildren = true }

        override fun dispatchDraw(canvas: Canvas) {
            val p = getChildAt(0) ?: return
            val full = android.graphics.RectF(p.left.toFloat(), p.top.toFloat(), p.right.toFloat(), p.bottom.toFloat())
            val t = progress.coerceIn(0f, 1f)
            // Far edge leads on a spring; the sides follow a beat behind
            val lead = surge.getInterpolation((t / 0.8f).coerceAtMost(1f))
            val spread = Curves.OPEN.getInterpolation(((t - 0.14f) / 0.86f).coerceIn(0f, 1f))
            val near = minOf(spread * 1.2f, 1f)
            fun mix(a: Float, b: Float, f: Float) = a + (b - a) * f
            // The far edge leads, the near edge (at the bar) follows, the cross edges spread
            r.left = mix(origin.left, full.left, when (side) { Side.RIGHT -> lead; Side.LEFT -> near; else -> spread })
            r.right = mix(origin.right, full.right, when (side) { Side.LEFT -> lead; Side.RIGHT -> near; else -> spread })
            r.top = mix(origin.top, full.top, when (side) { Side.BOTTOM -> lead; Side.TOP -> near; else -> spread })
            r.bottom = mix(origin.bottom, full.bottom, when (side) { Side.TOP -> lead; Side.BOTTOM -> near; else -> spread })
            val radius = (origin.width() / 2f) + (context.dp(26) - origin.width() / 2f) * spread

            path.reset()
            path.addRoundRect(r, radius, radius, android.graphics.Path.Direction.CW)
            // The drop on the grid button empties into the panel through a pinched neck,
            // and fills again when the panel drains back into it
            val left = 1f - spread
            if (left > 0.02f && t < 1f) {
                val cx = origin.centerX(); val cy = origin.centerY()
                val drop = origin.width() / 2f * (0.35f + 0.65f * left)
                extra.reset()
                extra.addCircle(cx, cy, drop, android.graphics.Path.Direction.CW)
                path.op(extra, android.graphics.Path.Op.UNION)
                extra.reset()
                val top = origin.width() * 0.5f * left
                val foot = drop * 0.8f
                val waist = minOf(top, foot) * 0.45f
                if (!sideways) {
                    val nearY = if (fromTop) r.top else r.bottom
                    val gapping = if (fromTop) nearY < cy else nearY > cy
                    if (!gapping) {
                        val midY = (nearY + cy) / 2f
                        extra.moveTo(cx - top, nearY)
                        extra.quadTo(cx - waist, midY, cx - foot, cy)
                        extra.lineTo(cx + foot, cy)
                        extra.quadTo(cx + waist, midY, cx + top, nearY)
                        extra.close()
                    }
                } else {
                    val nearX = if (side == Side.LEFT) r.left else r.right
                    val gapping = if (side == Side.LEFT) nearX < cx else nearX > cx
                    if (!gapping) {
                        val midX = (nearX + cx) / 2f
                        extra.moveTo(nearX, cy - top)
                        extra.quadTo(midX, cy - waist, cx, cy - foot)
                        extra.lineTo(cx, cy + foot)
                        extra.quadTo(midX, cy + waist, nearX, cy + top)
                        extra.close()
                    }
                }
                // Joined as one body, so the overlaps never cancel out
                path.op(extra, android.graphics.Path.Op.UNION)
            }
            canvas.save(); canvas.translate(0f, context.dp(3).toFloat()); canvas.drawPath(path, shade); canvas.restore()
            canvas.drawPath(path, fill)
            canvas.drawPath(path, edge)
            canvas.save()
            canvas.clipPath(path)
            super.dispatchDraw(canvas)
            canvas.restore()
        }
    }

    private fun setTouchable(on: Boolean) {
        listOf(scrim, panelHost).forEach { v ->
            if (v == null || !v.isAttachedToWindow) return@forEach
            val lp = v.layoutParams as WindowManager.LayoutParams
            lp.flags = if (on) lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            else lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            runCatching { wm.updateViewLayout(v, lp) }
        }
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

    private inner class Adapter(private val finish: Finish) : RecyclerView.Adapter<Holder>() {
        private var items: List<AppEntry> = emptyList()

        @SuppressLint("NotifyDataSetChanged")
        fun submit(list: List<AppEntry>) { items = list; notifyDataSetChanged() }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val icon = ImageView(context).apply { layoutParams = LinearLayout.LayoutParams(context.dp(48), context.dp(48)) }
            val label = TextView(context).apply {
                textSize = 12f
                setTextColor(finish.ink)
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(context.dp(2), context.dp(6), context.dp(2), 0)
            }
            val cell = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, context.dp(6), 0, context.dp(10))
                layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT)
                addView(icon); addView(label)
            }
            return Holder(cell, icon, label)
        }

        override fun onBindViewHolder(h: Holder, position: Int) {
            val app = items[position]
            h.icon.setImageDrawable(apps.icon(app.pkg))
            h.label.text = app.label
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
