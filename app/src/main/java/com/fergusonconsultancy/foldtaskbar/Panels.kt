package com.fergusonconsultancy.foldtaskbar

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.content.Context
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

/** Motion curves from the charter: open lands gently, close leaves quickly. */
object Curves {
    val OPEN = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
    val CLOSE = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)
    const val OPEN_MS = 320L
    const val CLOSE_MS = 180L
}

/**
 * The all-apps grid. Built and attached once, invisible until called, so opening it is
 * only an animation. The grid draws only the icons on screen.
 */
class AppDrawer(
    private val context: Context,
    private val wm: WindowManager,
    private val apps: Apps,
    private val onOpenApp: (String) -> Unit,
    private val onHoldApp: (String, View) -> Unit,
) {
    private var scrim: View? = null
    private var panelHost: FrameLayout? = null
    private var panel: LinearLayout? = null
    private var grid: RecyclerView? = null
    private var adapter: Adapter? = null
    private var button: GlyphView? = null
    private var key = ""
    private var running: Animator? = null

    var isOpen = false
        private set

    /** Builds the windows for this size and finish. Cheap to call: does nothing if unchanged. */
    fun attach(finish: Finish, widthPx: Int, heightPx: Int, bottomPx: Int) {
        val k = "$finish|$widthPx|$heightPx|$bottomPx"
        if (k == key && panelHost != null) return
        detach()
        key = k

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
        }
        panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = surface(context, finish, 26f)
            elevation = context.dp(10).toFloat()
            setPadding(pad, pad, pad, pad)
            addView(TextView(context).apply {
                text = "All apps"
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(finish.ink)
                setPadding(context.dp(6), 0, 0, context.dp(12))
            })
            addView(grid, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        panelHost = FrameLayout(context).apply {
            clipChildren = false
            clipToPadding = false
            val p = context.dp(10)
            setPadding(p, p, p, p)
            addView(panel, FrameLayout.LayoutParams(widthPx, heightPx))
            visibility = View.INVISIBLE
        }
        wm.addView(panelHost, params(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, bottomPx - context.dp(10)))
        adapter?.submit(apps.all())
    }

    fun detach() {
        running?.cancel()
        listOf(scrim, panelHost).forEach { v -> if (v != null) runCatching { wm.removeView(v) } }
        scrim = null; panelHost = null; panel = null; grid = null; adapter = null
        isOpen = false
        key = ""
    }

    fun refreshApps() = adapter?.submit(apps.all())

    /** [from] is the grid button: the drawer grows out of it and folds back into it. */
    fun open(from: GlyphView?) {
        val host = panelHost ?: return
        val p = panel ?: return
        val s = scrim ?: return
        if (isOpen) return
        isOpen = true
        button = from
        running?.cancel()
        setTouchable(true)

        // Rule 5: the button answers first, in the same frame.
        from?.animateOpen(1f, Curves.OPEN_MS, Curves.OPEN)

        s.visibility = View.VISIBLE
        s.animate().alpha(1f).setDuration(240).setInterpolator(Curves.OPEN).start()

        host.visibility = View.VISIBLE
        p.alpha = 1f
        val (cx, cy) = originIn(p, from)
        val radius = hypot(maxOf(cx, p.width - cx).toDouble(), maxOf(cy, p.height - cy).toDouble()).toFloat()
        running = runCatching {
            ViewAnimationUtils.createCircularReveal(p, cx.toInt(), cy.toInt(), context.dp(20).toFloat(), radius).apply {
                duration = Curves.OPEN_MS
                interpolator = Curves.OPEN
                start()
            }
        }.getOrNull()
        cascade()
    }

    fun close(animated: Boolean = true) {
        if (!isOpen) return
        isOpen = false
        setTouchable(false)
        running?.cancel()
        button?.animateOpen(0f, Curves.CLOSE_MS, Curves.CLOSE)
        val host = panelHost ?: return
        val p = panel ?: return
        val s = scrim
        s?.animate()?.alpha(0f)?.setDuration(Curves.CLOSE_MS)?.setInterpolator(Curves.CLOSE)
            ?.withEndAction { if (!isOpen) s.visibility = View.INVISIBLE }?.start()

        if (!animated || !p.isAttachedToWindow) { hideNow(host); return }
        val (cx, cy) = originIn(p, button)
        val radius = hypot(maxOf(cx, p.width - cx).toDouble(), maxOf(cy, p.height - cy).toDouble()).toFloat()
        running = runCatching {
            ViewAnimationUtils.createCircularReveal(p, cx.toInt(), cy.toInt(), radius, context.dp(20).toFloat()).apply {
                duration = Curves.CLOSE_MS
                interpolator = Curves.CLOSE
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) { if (!isOpen) hideNow(host) }
                })
                start()
            }
        }.getOrElse { hideNow(host); null }
    }

    private fun hideNow(host: View) {
        host.visibility = View.INVISIBLE
        grid?.scrollToPosition(0)
    }

    /** Icons rise from the bar upwards, nearest row first, so the grid assembles towards you. */
    private fun cascade() {
        val g = grid ?: return
        val lm = g.layoutManager as? GridLayoutManager ?: return
        val span = lm.spanCount
        val rise = context.dp(18).toFloat()
        val children = (0 until g.childCount).map { g.getChildAt(it) }
        if (children.isEmpty()) return
        val rows = children.map { g.getChildAdapterPosition(it) / span }
        val bottomRow = rows.maxOrNull() ?: 0
        children.forEachIndexed { i, child ->
            val fromBottom = bottomRow - rows[i]
            child.animate().cancel()
            child.alpha = 0f
            child.translationY = rise
            child.scaleX = 0.92f; child.scaleY = 0.92f
            child.animate()
                .alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                .setStartDelay(30L + fromBottom * 26L)
                .setDuration(260)
                .setInterpolator(Curves.OPEN)
                .start()
        }
    }

    private fun originIn(panel: View, from: View?): Pair<Float, Float> {
        if (from == null || !from.isAttachedToWindow || !panel.isAttachedToWindow) return panel.width / 2f to panel.height.toFloat()
        val a = IntArray(2).also { from.getLocationOnScreen(it) }
        val b = IntArray(2).also { panel.getLocationOnScreen(it) }
        return (a[0] + from.width / 2f - b[0]) to (a[1] + from.height / 2f - b[1])
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
            h.itemView.setOnClickListener { it.tick(); onOpenApp(app.pkg) }
            h.itemView.setOnLongClickListener { it.thud(); onHoldApp(app.pkg, it); true }
        }
    }

    private class Holder(v: View, val icon: ImageView, val label: TextView) : RecyclerView.ViewHolder(v)
}
