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
    /** The hinge pair's second handle, so each capsule can be lifted and moved on its own. */
    object Grip : Slot
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
    private var grip: GlyphView? = null

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

    /**
     * Where this capsule sits on screen and the rhythm of its icons, so the app grid can be
     * laid on exactly the same lattice (25 Oct 2026). Null until it has been laid out.
     */
    fun lattice(): Lattice? {
        if (!isAttachedToWindow || width == 0 || height == 0) return null
        val loc = IntArray(2).also { getLocationOnScreen(it) }
        val first = (0 until childCount).map { getChildAt(it) }.firstOrNull { it.visibility == VISIBLE && it.width > 0 && it !in dividers } ?: return null
        val anchor = if (vertical) loc[1] + first.top + first.height / 2f else loc[0] + first.left + first.width / 2f
        return Lattice(android.graphics.Rect(loc[0], loc[1], loc[0] + width, loc[1] + height), anchor,
            (context.dp(iconDp) + gap).toFloat(), context.dp(iconDp), vertical)
    }

    /**
     * The entrance (25 Oct 2026): the icons settle into the glass from the middle outwards, each
     * rising a few points from the direction the bar arrives from and coming into full view on a
     * long, gentle ease, so the bar seems to wake and turn towards you. [dx], [dy] point the way
     * the bar itself travels in.
     */
    fun enter(delay: Long, dx: Float, dy: Float) {
        val kids = (0 until childCount).map { getChildAt(it) }.filter { it.visibility == VISIBLE }
        if (kids.isEmpty()) return
        val mid = (kids.size - 1) / 2f
        val ease = android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)
        kids.forEachIndexed { i, v ->
            v.animate().cancel()
            v.alpha = 0f
            v.translationX = dx * 0.45f; v.translationY = dy * 0.45f
            v.animate().alpha(1f).translationX(0f).translationY(0f)
                .setStartDelay(delay + (kotlin.math.abs(i - mid) * 34f).toLong())
                .setDuration(460).setInterpolator(ease).start()
        }
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

    // ---- Bellezza Necessaria (23 Oct 2026) ------------------------------------------------

    /** 0 awake, 1 resting as trim (Presence: Trim). */
    private var rest = 0f
    private var restAnim: android.animation.ValueAnimator? = null
    val resting get() = rest > 0.5f

    /** Every icon, handle and pair in the capsule, in order. */
    private fun items(): List<View> = (0 until childCount).map { getChildAt(it) }.filter { it !in dividers }

    /**
     * Principle 3, the Giulietta principle: the bar rests as a fine line of trim, its icons
     * dissolved into it. Touched at (x, y) on screen, it wakes: the glass widens and the icons
     * materialise outwards from the finger, nearest first, the way the Giulietta's hidden rear
     * handle only shows itself to the hand that reaches for it.
     */
    fun setResting(on: Boolean, x: Float = Float.NaN, y: Float = Float.NaN) {
        val target = if (on) 1f else 0f
        if (rest == target && restAnim?.isRunning != true) return
        restAnim?.cancel()
        val loc = IntArray(2).also { getLocationOnScreen(it) }
        val along = if (vertical) (if (y.isNaN()) height / 2f else y - loc[1]) else (if (x.isNaN()) width / 2f else x - loc[0])
        items().forEach { v ->
            val c = if (vertical) v.top + v.height / 2f else v.left + v.width / 2f
            val d = kotlin.math.abs(c - along) / context.dp(iconDp)
            v.animate().cancel()
            if (on) v.animate().alpha(0f).scaleX(0.55f).scaleY(0.55f).setStartDelay((d * 12).toLong()).setDuration(220).setInterpolator(Curves.CLOSE).start()
            else v.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay((d * 26).toLong()).setDuration(320)
                .setInterpolator(android.view.animation.OvershootInterpolator(1.6f)).start()
        }
        restAnim = android.animation.ValueAnimator.ofFloat(rest, target).apply {
            duration = if (on) 360 else 280
            interpolator = if (on) Curves.CLOSE else Curves.OPEN
            addUpdateListener { rest = it.animatedValue as Float; glass.invalidateSelf(); invalidateOutline(); invalidate() }
            start()
        }
    }

    /**
     * Proximity magnetism while the finger slides along the trim: icons swell towards the
     * finger, the nearest most, and settle back as it moves on.
     */
    fun magnify(x: Float, y: Float) {
        val loc = IntArray(2).also { getLocationOnScreen(it) }
        val along = if (vertical) y - loc[1] else x - loc[0]
        val reach = context.dp(iconDp) * 1.8f
        items().forEach { v ->
            val c = if (vertical) v.top + v.height / 2f else v.left + v.width / 2f
            val k = (1f - kotlin.math.abs(c - along) / reach).coerceIn(0f, 1f)
            val s = 1f + 0.32f * k * k * (3f - 2f * k)
            v.scaleX = s; v.scaleY = s
            val lift = -context.dp(8).toFloat() * k
            if (vertical) v.translationX = lift else v.translationY = lift
        }
    }

    fun unmagnify() = items().forEach { v ->
        v.animate().scaleX(1f).scaleY(1f).translationX(0f).translationY(0f).setDuration(260)
            .setInterpolator(android.view.animation.OvershootInterpolator(1.4f)).start()
    }

    /** The app under a point on screen, by whichever icon sits there (for lifting off the trim). */
    fun keyAt(x: Float, y: Float): String? = appAt(x, y)?.first

    /**
     * Principle 1, the membrane: an app lifted out of the bar draws its neighbours towards the
     * gap it leaves, nearest most, and the glass swells slightly where it left, before the
     * surface springs back. One object's movement changes the stance of its peers.
     */
    fun yieldTo(key: String) {
        val src = viewFor(key) ?: return
        val i = items().indexOf(src)
        if (i < 0) return
        items().forEachIndexed { j, v ->
            if (j == i) return@forEachIndexed
            val d = kotlin.math.abs(j - i)
            if (d > 4) return@forEachIndexed
            val pull = context.dp(7f / d).toFloat() * (if (j > i) -1 else 1)
            val prop = if (vertical) View.TRANSLATION_Y else View.TRANSLATION_X
            android.animation.ObjectAnimator.ofFloat(v, prop, 0f, pull, 0f).apply {
                startDelay = 24L * d; duration = 620
                interpolator = android.view.animation.PathInterpolator(0.25f, 0f, 0.15f, 1f)
                start()
            }
        }
        val swell = if (vertical) View.SCALE_X else View.SCALE_Y
        android.animation.ObjectAnimator.ofFloat(this, swell, 1f, 1.07f, 1f).apply {
            duration = 520; interpolator = android.view.animation.PathInterpolator(0.3f, 0f, 0.1f, 1f); start()
        }
    }

    /**
     * Principle 2, Concentrato: while an app is carried out of the bar, everything else on it
     * steps back, leaving only what is moving. [focus] 1 is fully purged, 0 restores.
     */
    fun purge(except: String?, focus: Float) {
        val keep = except?.let { viewFor(it) }
        val a = 1f - 0.82f * focus.coerceIn(0f, 1f)
        items().forEach { v -> if (v !== keep) v.alpha = a }
        dividers.forEach { it.alpha = a }
        lightAlpha = a
        invalidate()
    }

    private var lightAlpha = 1f

    /** Puts every icon back exactly at rest: after a drag, nothing is left leaning, faded or shrunk. */
    fun settleAll() {
        items().forEach { v ->
            if (v.scaleX == 1f && v.alpha == 1f && v.translationX == 0f && v.translationY == 0f) return@forEach
            v.animate().scaleX(1f).scaleY(1f).alpha(1f).translationX(0f).translationY(0f).setDuration(200).setInterpolator(Curves.OPEN).start()
        }
        dividers.forEach { it.alpha = 1f }
        lightAlpha = 1f
        scaleX = 1f; scaleY = 1f
        invalidate()
    }

    /** Whether a point on screen is on one of the capsule's handles: the grid button or the grip. */
    fun isHandle(x: Float, y: Float): Boolean = listOfNotNull(grid, grip).any { g ->
        val loc = IntArray(2).also { g.getLocationOnScreen(it) }
        x >= loc[0] && x <= loc[0] + g.width && y >= loc[1] && y <= loc[1] + g.height
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
            fun mixWith(base: Int, tint: Float, shift: Int) = ((((base shr shift) and 255) * (1 - tint)) + (((colour shr shift) and 255) * tint)).toInt()
            fun tinted(base: Int, tint: Float, alpha: Int) = (alpha shl 24) or (mixWith(base, tint, 16) shl 16) or (mixWith(base, tint, 8) shl 8) or mixWith(base, tint, 0)
            when (finish) {
                Finish.MEMBRANE -> {
                    // A thin skin: more translucent, and far more of the colour beneath it
                    f[z] = if (light) tinted(0xF2EEE6, 0.42f, 0xC4) else tinted(0x0E1210, 0.45f, 0xB8)
                    i[z] = if (light) 0xFF141A16.toInt() else 0xFFF4F1EA.toInt()
                    e[z] = if (light) 0x26000000 else 0x24FFFFFF
                    g[z] = if (light) 0xFF7A5A28.toInt() else 0xFFE2C48E.toInt()
                }
                Finish.ROSSO -> {
                    // Alfa red that keeps its contrast: oxblood over bright screens, bright rosso over dark
                    f[z] = if (light) tinted(0xB8141E, 0.06f, 0xF0) else tinted(0x6E0A12, 0.05f, 0xF2)
                    i[z] = 0xFFF6F1E6.toInt()
                    e[z] = if (light) 0x55F6D9A8 else 0x40D9BB86
                    g[z] = 0xFFE9CF9E.toInt()
                }
                else -> {
                    val base = if (light) 0xF4F0E8 else 0x101412
                    val tint = if (light) 0.10f else 0.14f
                    f[z] = tinted(base, tint, if (light) 0xE8 else 0xDB)
                    i[z] = if (light) 0xFF1A201C.toInt() else 0xFFEDEAE2.toInt()
                    e[z] = if (light) 0x1F000000 else 0x1AFFFFFF
                    g[z] = if (light) 0xFF8C6A33.toInt() else 0xFFD3B27C.toInt()
                }
            }
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
        grip?.let { it.setInk(inks[zoneOf(it)]) }
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
            // Trim: at rest the capsule narrows to a fine line of trim along its length
            if (rest > 0f) {
                val thin = context.dp(5).toFloat()
                if (vertical) { val cx = r.centerX(); val half = (r.width() / 2f) + (thin / 2f - r.width() / 2f) * rest; r.left = cx - half; r.right = cx + half; val inset = r.height() * 0.18f * rest; r.top += inset; r.bottom -= inset }
                else { val cy = r.centerY(); val half = (r.height() / 2f) + (thin / 2f - r.height() / 2f) * rest; r.top = cy - half; r.bottom = cy + half; val inset = r.width() * 0.18f * rest; r.left += inset; r.right -= inset }
            }
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
            if (rest > 0.02f) { outline.setEmpty(); return }
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
    /**
     * The active-app light, drawn by the capsule itself so it can behave like a liquid.
     * When you switch apps, the light's leading edge races ahead to the new app on a soft
     * spring while its trailing edge follows a beat behind, so it stretches out along the
     * bar; and because a liquid keeps its volume, it thins as it stretches and swells back
     * as it gathers itself in at the new app. Lights that appear grow from their centre;
     * lights that leave drain away into themselves.
     */
    private class Light(val key: String, val now: RectF, var from: RectF, var to: RectF?, var travel: Boolean)

    private val lights = mutableListOf<Light>()
    private var lightAnim: android.animation.ValueAnimator? = null
    private val lightCore = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val lightHalo = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val lightGlow = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

    /** Perceived brightness of a colour, 0 to 1 (alpha ignored: the glass is mostly opaque). */
    private fun luminance(c: Int): Float =
        (0.2126f * ((c shr 16) and 255) + 0.7152f * ((c shr 8) and 255) + 0.0722f * (c and 255)) / 255f
    private val lead = android.view.animation.OvershootInterpolator(1.1f)
    private val trail = android.view.animation.PathInterpolator(0.55f, 0f, 0.15f, 1f)

    private fun markRect(key: String): RectF? {
        val v = indicators[key] ?: return null
        val frame = v.parent as? View ?: return null
        if (frame.width == 0) return null
        return RectF((frame.left + v.left).toFloat(), (frame.top + v.top).toFloat(),
            (frame.left + v.right).toFloat(), (frame.top + v.bottom).toFloat())
    }

    fun setActive(active: Set<String>) {
        val want = indicators.keys.filter { it in active }
        val have = lights.filter { it.to != null }.map { it.key }
        if (want.toSet() == have.toSet() && lightAnim?.isRunning != true) return
        if (!isAttachedToWindow || width == 0) {
            lights.clear()
            want.forEach { k -> markRect(k)?.let { lights += Light(k, RectF(it), RectF(it), RectF(it), false) } }
            post { if (width > 0) setActiveNow(active) }
            invalidate(); return
        }
        setActiveNow(active)
    }

    private fun setActiveNow(active: Set<String>) {
        val want = indicators.keys.filter { it in active }.mapNotNull { k -> markRect(k)?.let { k to it } }
        val leaving = lights.filter { l -> l.to != null && want.none { it.first == l.key } }.toMutableList()
        val next = mutableListOf<Light>()
        want.forEach { (k, rect) ->
            val existing = lights.firstOrNull { it.key == k && it.to != null }
            when {
                existing != null -> next += Light(k, RectF(existing.now), RectF(existing.now), rect, false)
                leaving.isNotEmpty() -> {
                    // One app replaced another: the old light travels to the new one
                    val src = leaving.removeAt(0)
                    next += Light(k, RectF(src.now), RectF(src.now), rect, true)
                }
                else -> {
                    val seed = RectF(rect.centerX(), rect.top, rect.centerX(), rect.bottom)
                    next += Light(k, RectF(seed), seed, rect, false)
                }
            }
        }
        leaving.forEach { l ->
            val sink = RectF(l.now.centerX(), l.now.top, l.now.centerX(), l.now.bottom)
            next += Light(l.key, RectF(l.now), RectF(l.now), null, false).also { it.from = RectF(l.now); it.now.set(l.now); it.to = null; drains[it] = sink }
        }
        lights.clear(); lights += next
        lightAnim?.cancel()
        lightAnim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 560
            addUpdateListener { a -> stepLights(a.animatedFraction); invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    lights.removeAll { it.to == null }; drains.clear(); invalidate()
                }
            })
            start()
        }
    }

    private val drains = HashMap<Light, RectF>()

    private fun stepLights(t: Float) {
        for (l in lights) {
            val to = l.to
            if (to == null) {
                val sink = drains[l] ?: continue
                lerpRect(l.now, l.from, sink, Curves.CLOSE.getInterpolation((t / 0.45f).coerceAtMost(1f)))
                continue
            }
            if (!l.travel) {
                lerpRect(l.now, l.from, to, lead.getInterpolation((t / 0.7f).coerceAtMost(1f)))
                continue
            }
            // Travelling: leading edge first, trailing edge a beat behind
            val forward = if (vertical) to.centerY() >= l.from.centerY() else to.centerX() >= l.from.centerX()
            val fast = lead.getInterpolation((t / 0.62f).coerceAtMost(1f))
            val slow = trail.getInterpolation(((t - 0.12f) / 0.88f).coerceIn(0f, 1f))
            if (!vertical) {
                val a = if (forward) slow else fast
                val b = if (forward) fast else slow
                l.now.left = l.from.left + (to.left - l.from.left) * a
                l.now.right = l.from.right + (to.right - l.from.right) * b
            } else {
                val a = if (forward) slow else fast
                val b = if (forward) fast else slow
                l.now.top = l.from.top + (to.top - l.from.top) * a
                l.now.bottom = l.from.bottom + (to.bottom - l.from.bottom) * b
            }
            // The other two edges follow evenly
            val even = trail.getInterpolation(t)
            if (!vertical) { l.now.top = l.from.top + (to.top - l.from.top) * even; l.now.bottom = l.from.bottom + (to.bottom - l.from.bottom) * even }
            else { l.now.left = l.from.left + (to.left - l.from.left) * even; l.now.right = l.from.right + (to.right - l.from.right) * even }
        }
    }

    private fun lerpRect(out: RectF, a: RectF, b: RectF, t: Float) {
        out.set(a.left + (b.left - a.left) * t, a.top + (b.top - a.top) * t, a.right + (b.right - a.right) * t, a.bottom + (b.bottom - a.bottom) * t)
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (lights.isEmpty()) return
        val restLen = context.dp(iconDp * 0.42f).toFloat()
        val restThick = context.dp(4).toFloat()
        for (l in lights) {
            val r = l.now
            val len = if (vertical && l.travel) r.height() else r.width()
            if (len <= 0.5f) continue
            // Volume is kept: a longer light is a thinner one
            val thick = (restThick * (restLen / len).coerceIn(0.35f, 1.4f)).coerceAtLeast(context.dp(1).toFloat())
            val cx = r.centerX(); val cy = r.centerY()
            val draw = if (vertical && l.travel) RectF(cx - thick / 2, r.top, cx + thick / 2, r.bottom)
            else RectF(r.left, cy - thick / 2, r.right, cy + thick / 2)
            // Adaptive pill (24 Oct 2026): it reads the glass under it, zone by zone, and takes
            // whichever tone stands out most, ringed in the opposite tone like a pointer, so it
            // stays clear over light glass, dark glass, lacquer or a membrane tinted by any app
            val z = zoneAt(if (vertical) cy else cx)
            val darkGlass = luminance(fills[z]) < 0.5f
            val colour = if (darkGlass) PILL_LIGHT else PILL_DARK
            val ring = if (darkGlass) 0x8C000000.toInt() else 0x99FFFFFF.toInt()
            val halo = RectF(draw).apply { inset(-context.dp(1.5f).toFloat(), -context.dp(1.5f).toFloat()) }
            // A soft glow of its own tone, only over dark glass, where light can glow
            if (darkGlass) {
                val glowR = RectF(draw).apply { inset(-thick * 1.1f, -thick * 1.1f) }
                lightGlow.color = (colour and 0x00FFFFFF) or ((0x38 * lightAlpha * (1f - rest)).toInt() shl 24)
                canvas.drawRoundRect(glowR, glowR.height().coerceAtMost(glowR.width()) / 2, glowR.height().coerceAtMost(glowR.width()) / 2, lightGlow)
            }
            lightHalo.color = (ring and 0x00FFFFFF) or (((ring ushr 24) * lightAlpha * (1f - rest)).toInt() shl 24)
            canvas.drawRoundRect(halo, halo.height().coerceAtMost(halo.width()) / 2, halo.height().coerceAtMost(halo.width()) / 2, lightHalo)
            lightCore.color = colour
            lightCore.alpha = (255 * lightAlpha * (1f - rest)).toInt()
            val rad = minOf(draw.width(), draw.height()) / 2
            canvas.drawRoundRect(draw, rad, rad, lightCore)
        }
    }

    private fun zoneAt(pos: Float): Int {
        val len = if (vertical) height else width
        if (len <= 0) return 0
        return (pos / len * ZONES).toInt().coerceIn(0, ZONES - 1)
    }

    /**
     * A tap sends a small ripple along the bar: the neighbours lean away from the icon you
     * touched and spring back, nearest first, as if the bar were a surface you pressed.
     */
    private fun ripple(from: View) {
        val i = indexOfChild(from)
        if (i < 0) return
        for (j in 0 until childCount) {
            if (j == i) continue
            val v = getChildAt(j)
            val d = kotlin.math.abs(j - i)
            if (d > 3) continue
            val push = context.dp(4f / d).toFloat() * (if (j > i) 1 else -1)
            v.animate().cancel()
            val prop = if (vertical) View.TRANSLATION_Y else View.TRANSLATION_X
            android.animation.ObjectAnimator.ofFloat(v, prop, 0f, push, 0f).apply {
                startDelay = 30L * d
                duration = 380
                interpolator = android.view.animation.PathInterpolator(0.3f, 0f, 0.2f, 1f)
                start()
            }
        }
    }

    private fun rebuild(slots: List<Slot>) {
        removeAllViews()
        lights.clear()
        indicators.clear()
        appViews.clear()
        dividers.clear()
        grid = null
        grip = null
        slots.forEachIndexed { i, slot ->
            val v = when (slot) {
                Slot.AllApps -> glyph(Glyph.GRID) { actions.onAllApps(it) }.also { grid = it as GlyphView }.apply {
                    setOnLongClickListener { it.thud(); actions.onAllAppsHeld(it); true }
                }
                // Holding the grip lifts this capsule alone (BarHost arms on any handle)
                Slot.Grip -> glyph(Glyph.GRIP, narrow = true) { }.also { grip = it as GlyphView }.apply {
                    contentDescription = "Move this half of the bar"
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
        frame.setOnClickListener { it.tick(); ripple(it); actions.onPair(slot.left, slot.right, it) }
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
        frame.setOnClickListener { it.tick(); ripple(it); actions.onApp(slot.pkg, it) }
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
                    // Released, the icon springs back with a little give, like something soft
                    view.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(340)
                        .setInterpolator(android.view.animation.OvershootInterpolator(3f)).start()
                android.view.MotionEvent.ACTION_CANCEL -> {
                    view.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(140).start()
                    if (view is GlyphView && !view.isParted) view.animateOpen(0f, 140, Curves.CLOSE)
                }
            }
            false
        }
    }
}

enum class Glyph { GRID, CHEVRON, GRIP }

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
            Glyph.GRIP -> {
                // Two columns of three dots, the long way along the bar. They spread a little
                // under the finger, as the grid's squares part: the handle answers the hold.
                val upright = height >= width
                val dot = s * 0.075f
                val step = s * (0.17f + 0.05f * openness)
                val across = s * (0.11f + 0.04f * openness)
                for (a in -1..1) for (b in 0..1) {
                    val along = a * step
                    val side = if (b == 0) -across / 2 else across / 2
                    val x = if (upright) cx + side else cx + along
                    val y = if (upright) cy + along else cy + side
                    canvas.drawCircle(x, y, dot, fill)
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

/** The active-app pill's two faces: light champagne over dark glass, deep bronze over light. */
private const val PILL_LIGHT = 0xFFF6E4BC.toInt()
private const val PILL_DARK = 0xFF3A2A0E.toInt()
