package com.fergusonconsultancy.foldtaskbar

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator

/**
 * The Pour app switch, drawn by Fold Taskbar itself rather than requested from Android.
 * One UI is free to ignore or replace the launch animations an app asks for, and on the
 * Fold it appears to. This one cannot be overridden because it is ours.
 *
 * The app's last screen (or, if we have never seen it, a body of the icon's own colour)
 * swells out of the icon you touched like a drop of syrup. The edge facing away from the
 * bar leads on a spring, the sides follow a beat behind and the near edge lets go last,
 * so the window stretches as it travels and settles into the screen. The corners relax
 * from icon-round to the screen's own radius, then the picture melts into the live app.
 *
 * Fanale (23 Oct 2026) is the same pour lit like the Giulietta's rear lamps at night. A ring
 * of LED beads lights round the icon, sweeping away from the screen and back; the window's
 * leading edge carries a beaded bar of light, lit from the icon's height outwards and fading
 * towards its ends; the window casts a shadow ahead of itself and the app behind it dips, so
 * the new app arrives in front of the old one. As it seats, the beads run out from the centre
 * like an indicator switching off, and the picture melts into the live app.
 *
 * Membrana (23 Oct 2026, Bellezza Necessaria) is the same pour as a membrane. Each edge is
 * held at its corners and bows against its own speed, the middle lagging behind like a skin
 * drawn through air: the faster an edge travels, the deeper the curve. As the window lands and
 * the edges slow, the curves relax and the membrane snaps taut, with a fine meniscus of light
 * along it. Nothing on screen is decoration: every curve is a measure of speed.
 */
@SuppressLint("ViewConstructor")
class Pour(context: Context, private val wm: WindowManager) : View(context) {

    private var from = RectF()
    private var to = RectF()
    private var picture: Bitmap? = null
    private var icon: Drawable? = null
    private var body = Color.DKGRAY
    private var t = 0f
    private var running: Animator? = null
    private var lamp = false
    private var membrane = false
    // Membrana: last frame's edges and time, for each edge's speed; and the bow each edge carries
    private val last = RectF()
    private var lastT = -1f
    private val bow = FloatArray(4) // left, top, right, bottom
    private val iconAt = RectF()

    // Fanale: one unit radial glow, placed per bead with a shared matrix (no allocation per frame)
    private val glowShader = android.graphics.RadialGradient(0f, 0f, 1f,
        intArrayOf(Color.argb(255, 232, 24, 34), Color.argb(110, 200, 10, 24), Color.argb(0, 160, 0, 16)),
        floatArrayOf(0f, 0.4f, 1f), android.graphics.Shader.TileMode.CLAMP)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = glowShader }
    private val core = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 134, 116) }
    private val shadowShader = android.graphics.LinearGradient(0f, 0f, 1f, 0f,
        Color.argb(120, 0, 0, 0), Color.TRANSPARENT, android.graphics.Shader.TileMode.CLAMP)
    private val shadow = Paint().apply { shader = shadowShader }
    private val dim = Paint().apply { color = Color.BLACK }
    // The lamp's housing: a narrow band of dark bodywork just inside the leading edge
    private val housingShader = android.graphics.LinearGradient(0f, 0f, 1f, 0f,
        Color.argb(215, 12, 5, 7), Color.TRANSPARENT, android.graphics.Shader.TileMode.CLAMP)
    private val housing = Paint().apply { shader = housingShader }
    private val mx = android.graphics.Matrix()

    private val r = RectF()
    private val path = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val src = Rect()

    private val lead = OvershootInterpolator(1.15f)
    private val follow = PathInterpolator(0.3f, 0f, 0f, 1f)
    private val trail = PathInterpolator(0.55f, 0f, 0.15f, 1f)
    // Fanale: the lit edge crosses the screen on a steadier curve, so the light is seen travelling
    private val lampLead = PathInterpolator(0.45f, 0f, 0.2f, 1f)

    private var attached = false

    /** Keeps the window ready so the first frame of a switch is never lost to window creation. */
    fun ensure() {
        if (attached) return
        visibility = INVISIBLE
        runCatching { wm.addView(this, params()) }.onSuccess { attached = true }
    }

    fun detach() {
        running?.cancel()
        if (attached) runCatching { wm.removeView(this) }
        attached = false
    }

    /** Plays the pour from [source] (an icon on screen) over [screen]. */
    fun play(source: View, screen: Rect, snapshot: Bitmap?, appIcon: Drawable?, lamp: Boolean = false, membrane: Boolean = false) {
        this.lamp = lamp
        this.membrane = membrane
        lastT = -1f
        bow.fill(0f)
        ensure()
        val loc = IntArray(2).also { source.getLocationOnScreen(it) }
        val mine = IntArray(2).also { getLocationOnScreen(it) }
        val ox = (loc[0] - mine[0]).toFloat(); val oy = (loc[1] - mine[1]).toFloat()
        from.set(ox, oy, ox + source.width, oy + source.height)
        iconAt.set(from)
        // Start as a drop a touch smaller than the icon, so it visibly swells out of it
        from.inset(source.width * 0.08f, source.height * 0.08f)
        to.set(0f, 0f, (if (width > 0) width else screen.width()).toFloat(), (if (height > 0) height else screen.height()).toFloat())
        picture = snapshot?.takeIf { !it.isRecycled }
        // A private copy: the cached icon is shared with the bar and must not be resized here
        icon = appIcon?.constantState?.newDrawable(context.resources)?.mutate()
        body = icon?.let { tone(it) } ?: Color.DKGRAY
        running?.cancel()
        alpha = 1f
        t = 0f
        visibility = VISIBLE
        invalidate()
        running = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (lamp) LAMP_TRAVEL_MS else TRAVEL_MS
            interpolator = null
            addUpdateListener { t = it.animatedFraction; invalidate() }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = melt()
            })
            start()
        }
    }

    private fun melt() {
        animate().alpha(0f).setDuration(MELT_MS).setInterpolator(follow)
            .withEndAction { visibility = INVISIBLE; picture = null; icon = null }
            .start()
    }

    fun cancel() {
        running?.cancel()
        animate().cancel()
        visibility = INVISIBLE
    }

    override fun onDraw(canvas: Canvas) {
        val a = if (lamp) lampLead.getInterpolation(t) else lead.getInterpolation(t)
        val b = follow.getInterpolation(((t - 0.06f) / 0.94f).coerceIn(0f, 1f))
        val c = trail.getInterpolation(((t - 0.14f) / 0.86f).coerceIn(0f, 1f))
        // The far edge leads, the sides follow, the near edge lets go last. A bar lying at
        // the top or bottom pours vertically; a bar standing at a side pours across.
        val dx = to.centerX() - from.centerX(); val dy = to.centerY() - from.centerY()
        val across = kotlin.math.abs(dx) / to.width().coerceAtLeast(1f) > kotlin.math.abs(dy) / to.height().coerceAtLeast(1f)
        if (!across) {
            if (dy < 0) { r.top = mix(from.top, to.top, a); r.bottom = mix(from.bottom, to.bottom, c) }
            else { r.bottom = mix(from.bottom, to.bottom, a); r.top = mix(from.top, to.top, c) }
            r.left = mix(from.left, to.left, b); r.right = mix(from.right, to.right, b)
        } else {
            if (dx < 0) { r.left = mix(from.left, to.left, a); r.right = mix(from.right, to.right, c) }
            else { r.right = mix(from.right, to.right, a); r.left = mix(from.left, to.left, c) }
            r.top = mix(from.top, to.top, b); r.bottom = mix(from.bottom, to.bottom, b)
        }
        val radius = mix(from.width() * 0.32f, context.dp(30).toFloat(), b)
        val leadSide = if (!across) (if (dy < 0) 'T' else 'B') else (if (dx < 0) 'L' else 'R')

        // Fanale: the app behind dips and the window casts its shadow ahead of itself
        if (lamp) {
            val d = 0.3f * smooth(t / 0.3f) * (1f - smooth((t - 0.66f) / 0.34f))
            if (d > 0.004f) { dim.alpha = (255 * d).toInt(); canvas.drawRect(to, dim) }
            val sl = context.dp(56).toFloat() * (1f - smooth((t - 0.6f) / 0.4f))
            if (sl > 1f) aheadShadow(canvas, leadSide, sl)
        }

        path.reset()
        if (membrane) membranePath(radius) else path.addRoundRect(r, radius, radius, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(path)
        val pic = picture
        if (pic != null) {
            // Centre-crop the picture into the travelling window
            val scale = maxOf(r.width() / pic.width, r.height() / pic.height)
            val sw = (r.width() / scale).toInt().coerceIn(1, pic.width)
            val sh = (r.height() / scale).toInt().coerceIn(1, pic.height)
            src.set((pic.width - sw) / 2, (pic.height - sh) / 2, (pic.width + sw) / 2, (pic.height + sh) / 2)
            canvas.drawBitmap(pic, src, r, paint)
        } else {
            fill.color = body
            canvas.drawRect(r, fill)
        }
        // The icon rides in the middle and dissolves as the window opens
        icon?.let { d ->
            val k = (1f - b * 1.6f).coerceIn(0f, 1f)
            if (k > 0f) {
                val s = from.width() * (1f + b * 0.6f)
                val cx = r.centerX(); val cy = r.centerY()
                d.setBounds((cx - s / 2).toInt(), (cy - s / 2).toInt(), (cx + s / 2).toInt(), (cy + s / 2).toInt())
                d.alpha = (k * 255).toInt()
                d.draw(canvas)
                d.alpha = 255
            }
        }
        canvas.restore()
        // A thin line of light along the rim, like the meniscus of a liquid, fading as it lands
        sheen.strokeWidth = context.dp(1.5f).toFloat()
        // Membrana's meniscus is champagne, brightest while the membrane is still moving
        sheen.color = if (membrane) Color.argb((150 * (1f - c * 0.8f)).toInt().coerceIn(0, 255), 236, 214, 170)
            else Color.argb(((if (lamp) 40 else 90) * (1f - c)).toInt().coerceIn(0, 255), 255, 255, 255)
        canvas.drawPath(path, sheen)
        if (lamp) { drawEdge(canvas, leadSide); drawRing(canvas) }
    }

    /**
     * The window as a membrane: corners rounded, each edge a curve bowed against its speed.
     * Speeds are measured from the last frame; the bow follows them with a little lag (a
     * membrane's inertia) and is capped so the window never pinches shut.
     */
    private fun membranePath(radius: Float) {
        val dt = if (lastT < 0f) 0f else (t - lastT)
        val sp = floatArrayOf(r.left - last.left, r.top - last.top, r.right - last.right, r.bottom - last.bottom)
        val cap = minOf(r.width(), r.height()) * 0.12f
        for (k in 0..3) {
            // Pixels per unit of the animation's time; the middle of the edge lags the corners,
            // so the bow points against the motion
            val v = if (dt > 0f) sp[k] / dt else 0f
            val want = (-v * 0.035f).coerceIn(-cap, cap)
            bow[k] += (want - bow[k]) * 0.45f
        }
        last.set(r); lastT = t
        val rad = minOf(radius, r.width() / 2f, r.height() / 2f)
        val l = r.left; val tp = r.top; val rt = r.right; val b = r.bottom
        path.moveTo(l + rad, tp)
        path.quadTo((l + rt) / 2f, tp + 2f * bow[1] * 0.5f, rt - rad, tp)
        path.quadTo(rt, tp, rt, tp + rad)
        path.quadTo(rt + 2f * bow[2] * 0.5f, (tp + b) / 2f, rt, b - rad)
        path.quadTo(rt, b, rt - rad, b)
        path.quadTo((l + rt) / 2f, b + 2f * bow[3] * 0.5f, l + rad, b)
        path.quadTo(l, b, l, b - rad)
        path.quadTo(l + 2f * bow[0] * 0.5f, (tp + b) / 2f, l, tp + rad)
        path.quadTo(l, tp, l + rad, tp)
        path.close()
    }

    private fun smooth(x: Float): Float { val c = x.coerceIn(0f, 1f); return c * c * (3f - 2f * c) }

    /** A bead's brightness: dark until [at], then it lights with a brief flare and settles. */
    private fun ignite(at: Float): Float {
        if (t <= at) return 0f
        val on = smooth((t - at) / 0.05f)
        val flare = 0.7f * on * kotlin.math.exp(-((t - at - 0.05f).coerceAtLeast(0f)) / 0.07f)
        return on * 0.72f + flare
    }

    private fun bead(canvas: Canvas, x: Float, y: Float, rCore: Float, level: Float) {
        if (level <= 0.01f) return
        val rg = rCore * 5f
        mx.setScale(rg, rg); mx.postTranslate(x, y)
        glowShader.setLocalMatrix(mx)
        glow.alpha = (255 * (0.5f * level).coerceAtMost(1f)).toInt()
        canvas.drawRect(x - rg, y - rg, x + rg, y + rg, glow)
        core.alpha = (255 * (0.9f * level).coerceAtMost(1f)).toInt()
        canvas.drawCircle(x, y, rCore, core)
    }

    private fun aheadShadow(canvas: Canvas, side: Char, len: Float) {
        when (side) {
            'L' -> { mx.setScale(-len, 1f); mx.postTranslate(r.left, 0f); shadowShader.setLocalMatrix(mx); canvas.drawRect(r.left - len, r.top, r.left, r.bottom, shadow) }
            'R' -> { mx.setScale(len, 1f); mx.postTranslate(r.right, 0f); shadowShader.setLocalMatrix(mx); canvas.drawRect(r.right, r.top, r.right + len, r.bottom, shadow) }
            'T' -> { mx.setScale(-len, 1f); mx.postRotate(90f); mx.postTranslate(0f, r.top); shadowShader.setLocalMatrix(mx); canvas.drawRect(r.left, r.top - len, r.right, r.top, shadow) }
            else -> { mx.setScale(len, 1f); mx.postRotate(90f); mx.postTranslate(0f, r.bottom); shadowShader.setLocalMatrix(mx); canvas.drawRect(r.left, r.bottom, r.right, r.bottom + len, shadow) }
        }
    }

    /**
     * The beaded bar of light on the window's leading edge. Lit from the icon's height outwards,
     * brightest where it started and falling off towards its ends; it runs out from the centre
     * as the window seats.
     */
    private fun drawEdge(canvas: Canvas, side: Char) {
        val vertical = side == 'L' || side == 'R'
        val pos = when (side) { 'L' -> r.left; 'R' -> r.right; 'T' -> r.top; else -> r.bottom }
        val a0 = if (vertical) r.top else r.left
        val a1 = if (vertical) r.bottom else r.right
        val len = a1 - a0
        if (len < context.dp(8)) return
        val pitch = context.dp(12).toFloat()
        val n = (len / pitch).toInt().coerceAtLeast(1)
        val anchor = (if (vertical) iconAt.centerY() else iconAt.centerX()).coerceIn(a0, a1)
        val reach = maxOf(anchor - a0, a1 - anchor).coerceAtLeast(1f)
        val inset = context.dp(3).toFloat() * (if (side == 'L' || side == 'T') 1 else -1)
        val hw = context.dp(22).toFloat()
        val ha = smooth(t / 0.08f) * (1f - smooth((t - 0.8f) / 0.15f))
        if (ha > 0.01f) {
            housing.alpha = (255 * ha).toInt()
            mx.reset()
            when (side) {
                'L' -> { mx.setScale(hw, 1f); mx.postTranslate(r.left, 0f) }
                'R' -> { mx.setScale(-hw, 1f); mx.postTranslate(r.right, 0f) }
                'T' -> { mx.setScale(hw, 1f); mx.postRotate(90f); mx.postTranslate(0f, r.top) }
                else -> { mx.setScale(-hw, 1f); mx.postRotate(90f); mx.postTranslate(0f, r.bottom) }
            }
            housingShader.setLocalMatrix(mx)
            when (side) {
                'L' -> canvas.drawRect(r.left, r.top, r.left + hw, r.bottom, housing)
                'R' -> canvas.drawRect(r.right - hw, r.top, r.right, r.bottom, housing)
                'T' -> canvas.drawRect(r.left, r.top, r.right, r.top + hw, housing)
                else -> canvas.drawRect(r.left, r.bottom - hw, r.right, r.bottom, housing)
            }
        }
        val rc = context.dp(2.4f).toFloat()
        for (k in 0..n) {
            val along = a0 + len * k / n
            val d = kotlin.math.abs(along - anchor) / reach
            val fall = 1f - 0.62f * Math.pow(d.toDouble(), 1.5).toFloat()
            val off = 1f - smooth((t - (0.72f + 0.2f * d)) / 0.07f)
            val level = ignite(0.06f + 0.3f * d) * fall * off
            if (vertical) bead(canvas, pos + inset, along, rc * (0.7f + 0.3f * fall), level)
            else bead(canvas, along, pos + inset, rc * (0.7f + 0.3f * fall), level)
        }
    }

    /** The ring of beads round the icon: it sweeps round from the side facing the screen, then dims as the app leaves it. */
    private fun drawRing(canvas: Canvas) {
        val fade = 1f - smooth((t - 0.28f) / 0.3f)
        if (fade <= 0f) return
        val cx = iconAt.centerX(); val cy = iconAt.centerY()
        val rad = iconAt.width() * 0.6f
        val start = kotlin.math.atan2((to.centerY() - cy).toDouble(), (to.centerX() - cx).toDouble())
        val m = 18
        for (j in 0 until m) {
            val ang = start + 2 * Math.PI * j / m
            val x = cx + (rad * kotlin.math.cos(ang)).toFloat()
            val y = cy + (rad * kotlin.math.sin(ang)).toFloat()
            bead(canvas, x, y, context.dp(2.1f).toFloat(), ignite(0.16f * j / m) * fade)
        }
    }

    private fun mix(x: Float, y: Float, f: Float) = x + (y - x) * f

    /** The icon's most characteristic colour, deepened a little so the body reads as glass, not paint. */
    private fun tone(d: Drawable): Int {
        val b = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
        val cv = Canvas(b)
        d.setBounds(0, 0, 24, 24); d.draw(cv)
        var rs = 0L; var gs = 0L; var bs = 0L; var n = 0L
        val px = IntArray(24 * 24).also { b.getPixels(it, 0, 24, 0, 0, 24, 24) }
        for (p in px) {
            if (Color.alpha(p) < 200) continue
            val hsv = FloatArray(3).also { Color.colorToHSV(p, it) }
            val w = (1 + hsv[1] * 4).toLong()
            rs += Color.red(p) * w; gs += Color.green(p) * w; bs += Color.blue(p) * w; n += w
        }
        b.recycle()
        if (n == 0L) return Color.DKGRAY
        val hsv = FloatArray(3).also { Color.colorToHSV(Color.rgb((rs / n).toInt(), (gs / n).toInt(), (bs / n).toInt()), it) }
        hsv[2] = hsv[2] * 0.78f
        return Color.HSVToColor(hsv)
    }

    private fun params() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        setFitInsetsTypes(0)
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    }

    companion object {
        const val TRAVEL_MS = 460L
        const val LAMP_TRAVEL_MS = 620L
        const val MELT_MS = 240L
    }
}
