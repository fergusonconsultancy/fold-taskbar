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
/**
 * The three depth switches (24 Oct 2026), after the notification-shade studies Mechack shared:
 * the screen behind is not covered but falls out of focus (the system blurs the live app under
 * this window), everything arrives from the icon you touched, nearest first, and settles into
 * focus. Concentrato: switching again within 700ms is cycling, so the next switch is quicker and
 * shallower; the faster you cycle, the emptier it gets.
 *
 * Fuoco: the app's picture rises out of the icon soft and oversized, the app behind falls out of
 * focus, and the picture sharpens as it contracts into the screen; a focus pull, then the live
 * app takes over and the background returns to focus.
 *
 * Cascata: the picture arrives as a trencadís, Gaudí's broken-tile mosaic. Its tiles leave the
 * icon on arcs, nearest first, each turning and swelling into its place, and close their seams
 * as they land.
 *
 * Onda: the app wells up from the icon like water from a spring, a ripple whose liquid edge
 * relaxes into a perfect circle as it spreads, a fine meniscus on its rim, the content inside
 * settling as the water does.
 */
enum class Look { POUR, FANALE, MEMBRANA, FUOCO, CASCATA, ONDA }

@SuppressLint("ViewConstructor")
class Pour(context: Context, private val wm: WindowManager) : View(context) {

    private var from = RectF()
    private var to = RectF()
    private var picture: Bitmap? = null
    private var icon: Drawable? = null
    private var body = Color.DKGRAY
    private var splash = Color.DKGRAY
    private var t = 0f
    private var running: Animator? = null
    private var lamp = false
    private var membrane = false
    private var look = Look.POUR
    private var quick = false
    private var lastPlayAt = 0L
    private var concentrato = true
    private val tile = RectF()
    private val tileSrc = Rect()
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

    // 24 Oct 2026, tightened: every look lands on one crisp deceleration, no overshoot past the screen
    private val emph = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
    private val lead = emph
    private val lift = PathInterpolator(0.2f, 0f, 0f, 1f)
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
    fun play(source: View, screen: Rect, snapshot: Bitmap?, appIcon: Drawable?, lamp: Boolean = false, membrane: Boolean = false, look: Look? = null, concentrato: Boolean = true) {
        this.look = look ?: when { lamp -> Look.FANALE; membrane -> Look.MEMBRANA; else -> Look.POUR }
        this.lamp = this.look == Look.FANALE
        this.membrane = this.look == Look.MEMBRANA
        this.concentrato = concentrato
        val now = android.os.SystemClock.uptimeMillis()
        quick = concentrato && now - lastPlayAt < 700
        lastPlayAt = now
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
        // The splash: mostly the deep ground of the bar's family, with a quarter of the app's colour
        splash = blendColour(0xFF0E1311.toInt(), body, 0.28f)
        // Cascata is a mosaic of the app's picture; without one, it rises as Fuoco instead
        if (this.look == Look.CASCATA && picture == null) this.look = Look.FUOCO
        running?.cancel()
        animate().cancel()
        alpha = 1f
        t = 0f
        travelled = false
        appHere = false
        removeCallbacks(meltAnyway)
        visibility = VISIBLE
        invalidate()
        running = ValueAnimator.ofFloat(0f, 1f).apply {
            val base = when (this@Pour.look) {
                Look.FANALE -> LAMP_TRAVEL_MS
                Look.FUOCO -> FUOCO_MS
                Look.CASCATA -> CASCATA_MS
                Look.ONDA -> ONDA_MS
                else -> TRAVEL_MS
            }
            duration = if (quick) (base * 0.68f).toLong() else base
            interpolator = null
            addUpdateListener { t = it.animatedFraction; depthFrame(); invalidate() }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { travelled = true; meltWhenReady() }
            })
            start()
        }
    }

    // The picture dissolves only once the real app is on screen, so the two are never seen at once
    private var travelled = false
    private var appHere = false
    private val meltAnyway = Runnable { melt() }

    /** The app has come forward (the service saw it): the picture may dissolve into it. */
    fun arrived() { appHere = true; if (travelled) meltWhenReady() }

    private fun meltWhenReady() {
        if (visibility != VISIBLE || !travelled) return
        removeCallbacks(meltAnyway)
        if (appHere) melt() else postDelayed(meltAnyway, ARRIVAL_WAIT_MS)
    }

    private fun melt() {
        removeCallbacks(meltAnyway)
        if (visibility != VISIBLE) return
        val from = behind
        animate().alpha(0f).setDuration(MELT_MS).setInterpolator(follow)
            // The background returns to focus as the picture melts into the live app
            .setUpdateListener { setBehindBlur(from * (1f - it.animatedFraction)) }
            .withEndAction { setBehindBlur(0f); setRenderEffect(null); visibility = INVISIBLE; picture = null; icon = null }
            .start()
    }

    fun cancel() {
        removeCallbacks(meltAnyway)
        running?.cancel()
        animate().cancel()
        setBehindBlur(0f)
        setRenderEffect(null)
        visibility = INVISIBLE
    }

    // ---- Depth: the live app behind falls out of focus ------------------------------------

    private var behind = 0f

    private val blurSupported get() = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S &&
        runCatching { wm.isCrossWindowBlurEnabled }.getOrDefault(false)

    /** Blurs whatever is behind this window, the live app and the bar, by [px]. */
    private fun setBehindBlur(px: Float) {
        behind = px
        if (!attached || android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) return
        val lp = layoutParams as? WindowManager.LayoutParams ?: return
        val want = if (blurSupported) px.toInt().coerceAtLeast(0) else 0
        if (kotlin.math.abs(lp.blurBehindRadius - want) < 1 && !(want == 0 && lp.blurBehindRadius != 0)) return
        lp.blurBehindRadius = want
        runCatching { wm.updateViewLayout(this, lp) }
    }

    /** Per frame of the depth looks: how far out of focus the background is, and the picture's own focus pull. */
    private fun depthFrame() {
        if (look != Look.FUOCO && look != Look.CASCATA && look != Look.ONDA) return
        val deep = context.dp(if (quick) 14 else 30).toFloat()
        setBehindBlur(deep * smooth(t / 0.3f))
        if (look == Look.FUOCO && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val r = context.dp(if (quick) 8 else 16).toFloat() * (1f - smooth((t - 0.05f) / 0.5f))
            setRenderEffect(if (r < 0.5f) null else android.graphics.RenderEffect.createBlurEffect(r, r, android.graphics.Shader.TileMode.CLAMP))
        }
    }

    override fun onDraw(canvas: Canvas) {
        when (look) {
            Look.FUOCO -> { drawFuoco(canvas); return }
            Look.CASCATA -> { drawCascata(canvas); return }
            Look.ONDA -> { drawOnda(canvas); return }
            else -> Unit
        }
        val a = if (lamp) lampLead.getInterpolation(t) else lead.getInterpolation(t)
        // The far edge leads by a breath, the sides follow, the near edge lets go last: just
        // enough stagger to read direction, never enough to look like a stretched rectangle
        val b = emph.getInterpolation(((t - 0.03f) / 0.97f).coerceIn(0f, 1f))
        val c = emph.getInterpolation(((t - 0.08f) / 0.92f).coerceIn(0f, 1f))
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
        val radius = mix(from.width() * 0.32f, context.dp(26).toFloat(), b)
        val leadSide = if (!across) (if (dy < 0) 'T' else 'B') else (if (dx < 0) 'L' else 'R')

        // Depth for every look: the app behind dips a little and the window casts a soft shadow
        // ahead of itself, both gone by the time it lands
        val d = (if (quick) 0.08f else 0.16f) * smooth(t / 0.25f) * (1f - smooth((t - 0.55f) / 0.4f))
        if (d > 0.004f) { dim.alpha = (255 * d).toInt(); canvas.drawRect(to, dim) }
        val sl = context.dp(36).toFloat() * (1f - smooth((t - 0.45f) / 0.4f))
        if (sl > 1f && !quick) aheadShadow(canvas, leadSide, sl)

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
            picture(canvas, r)
        }
        canvas.restore()
        // A thin line of light along the rim, like the meniscus of a liquid, fading as it lands
        // One hairline of light on the rim while it travels, gone before it lands. Membrana's is
        // champagne, the meniscus of the membrane
        sheen.strokeWidth = context.dp(1f).toFloat()
        val rimA = 1f - smooth((t - 0.2f) / 0.45f)
        sheen.color = if (membrane) Color.argb((110 * rimA).toInt().coerceIn(0, 255), 236, 214, 170)
            else Color.argb((if (lamp) 0 else (48 * rimA).toInt()).coerceIn(0, 255), 255, 255, 255)
        if (Color.alpha(sheen.color) > 2) canvas.drawPath(path, sheen)
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
        val cap = minOf(r.width(), r.height()) * 0.06f
        for (k in 0..3) {
            // Pixels per unit of the animation's time; the middle of the edge lags the corners,
            // so the bow points against the motion
            val v = if (dt > 0f) sp[k] / dt else 0f
            val want = (-v * 0.022f).coerceIn(-cap, cap)
            bow[k] += (want - bow[k]) * 0.3f
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

    /**
     * Draws the picture into [dst], centre-cropped from the region [region] of the screen. With
     * no picture yet (an app the bar has not seen), a calm splash instead: a deep surface with a
     * trace of the app's colour and its icon at the centre, never a flat blob of colour.
     */
    private fun picture(canvas: Canvas, dst: RectF, region: RectF? = null) {
        val pic = picture
        if (pic == null) {
            fill.color = splash
            fill.alpha = paint.alpha
            canvas.drawRect(dst, fill)
            fill.alpha = 255
            if (region == null) icon?.let { d ->
                val s = (minOf(dst.width(), dst.height()) * 0.16f).coerceIn(context.dp(40).toFloat(), context.dp(88).toFloat())
                d.setBounds((dst.centerX() - s / 2).toInt(), (dst.centerY() - s / 2).toInt(), (dst.centerX() + s / 2).toInt(), (dst.centerY() + s / 2).toInt())
                d.alpha = paint.alpha
                d.draw(canvas)
                d.alpha = 255
            }
            return
        }
        if (region == null) {
            val scale = maxOf(dst.width() / pic.width, dst.height() / pic.height)
            val sw = (dst.width() / scale).toInt().coerceIn(1, pic.width)
            val sh = (dst.height() / scale).toInt().coerceIn(1, pic.height)
            src.set((pic.width - sw) / 2, (pic.height - sh) / 2, (pic.width + sw) / 2, (pic.height + sh) / 2)
        } else {
            val kx = pic.width / to.width().coerceAtLeast(1f); val ky = pic.height / to.height().coerceAtLeast(1f)
            src.set((region.left * kx).toInt(), (region.top * ky).toInt(), (region.right * kx).toInt().coerceAtMost(pic.width), (region.bottom * ky).toInt().coerceAtMost(pic.height))
        }
        canvas.drawBitmap(pic, src, dst, paint)
    }

    /** The background's dimming, deepest mid-switch: depth, not cover. */
    private fun depthDim(canvas: Canvas) {
        val d = (if (quick) 0.08f else 0.14f) * smooth(t / 0.3f)
        if (d > 0.004f) { dim.alpha = (255 * d).toInt(); canvas.drawRect(to, dim) }
    }

    /** Fuoco: rises soft and oversized from the icon, then contracts into the screen as it sharpens. */
    private fun drawFuoco(canvas: Canvas) {
        depthDim(canvas)
        val g = follow.getInterpolation((t / 0.62f).coerceAtMost(1f))
        val over = to.width() * 0.025f
        val settle = smooth((t - 0.5f) / 0.5f)
        val tl = mix(-over, 0f, settle); val tt = mix(-over * 0.86f, 0f, settle)
        r.set(mix(from.left, to.left + tl, g), mix(from.top, to.top + tt, g), mix(from.right, to.right - tl, g), mix(from.bottom, to.bottom - tt, g))
        val radius = mix(from.width() * 0.32f, context.dp(30).toFloat(), g)
        path.reset(); path.addRoundRect(r, radius, radius, Path.Direction.CW)
        val a = smooth(t / 0.2f)
        canvas.save(); canvas.clipPath(path)
        paint.alpha = (255 * a).toInt()
        picture(canvas, r)
        paint.alpha = 255
        canvas.restore()
    }

    /**
     * Cascata, tightened (24 Oct 2026): the first version flew every tile out of the icon, which
     * piled them up against the bar. Now each tile of the trencadís starts in its own place,
     * drawn a short way back towards the icon, small and clear, and rises into position in a
     * wave that spreads from the icon, nearest first. The mosaic is only ever seen in motion:
     * landed tiles meet edge to edge, and once all are down it is one picture.
     */
    private fun drawCascata(canvas: Canvas) {
        depthDim(canvas)
        val cols = if (to.width() >= to.height()) 6 else 4
        val rows = (cols * to.height() / to.width()).toInt().coerceAtLeast(3)
        val tw = to.width() / cols; val th = to.height() / rows
        val ox = iconAt.centerX(); val oy = iconAt.centerY()
        val far = kotlin.math.hypot(to.width(), to.height())
        val stagger = if (quick) 0.16f else 0.3f
        val span = 1f - stagger
        if (t >= 1f) { r.set(to); picture(canvas, r); return }
        val rad = context.dp(8).toFloat()
        for (row in 0 until rows) for (col in 0 until cols) {
            val tx = to.left + col * tw; val ty = to.top + row * th
            val cx = tx + tw / 2f; val cy = ty + th / 2f
            val u = ((t - stagger * kotlin.math.hypot(cx - ox, cy - oy) / far) / span).coerceIn(0f, 1f)
            if (u <= 0f) continue
            r.set(tx, ty, tx + tw, ty + th)
            if (u >= 1f) {
                tile.set(tx - 0.75f, ty - 0.75f, tx + tw + 0.75f, ty + th + 0.75f)
                picture(canvas, tile, r)
                continue
            }
            val e = lift.getInterpolation(u)
            // A short rise from towards the icon, growing from 86% as it comes
            val back = (1f - e) * 0.16f
            val px = cx + (ox - cx) * back; val py = cy + (oy - cy) * back
            val sc = 0.86f + 0.14f * e
            val corner = rad * (1f - e)
            canvas.save()
            canvas.translate(px, py); canvas.scale(sc, sc)
            tile.set(-tw / 2f, -th / 2f, tw / 2f, th / 2f)
            path.reset(); path.addRoundRect(tile, corner, corner, Path.Direction.CW)
            canvas.clipPath(path)
            paint.alpha = (255 * smooth(u / 0.35f)).toInt()
            picture(canvas, tile, r)
            paint.alpha = 255
            canvas.restore()
        }
    }

    /**
     * Onda, tightened (24 Oct 2026): a clean circle rising from the icon, its edge only faintly
     * liquid at the very start and true almost at once, the content inside settling from a
     * slight swell. A hairline meniscus fades as it spreads.
     */
    private fun drawOnda(canvas: Canvas) {
        depthDim(canvas)
        val ox = iconAt.centerX(); val oy = iconAt.centerY()
        val reach = maxOf(kotlin.math.hypot(ox - to.left, oy - to.top), kotlin.math.hypot(ox - to.right, oy - to.top),
            kotlin.math.hypot(ox - to.left, oy - to.bottom), kotlin.math.hypot(ox - to.right, oy - to.bottom))
        val g = emph.getInterpolation((t / 0.9f).coerceAtMost(1f))
        val rad = mix(iconAt.width() * 0.5f, reach * 1.01f, g)
        val amp = 0.022f * (1f - smooth(g / 0.35f))
        path.reset()
        if (amp <= 0.0005f) path.addCircle(ox, oy, rad, Path.Direction.CW)
        else {
            val n = 64
            for (i in 0..n) {
                val th = (2 * Math.PI * i / n).toFloat()
                val rr = rad * (1f + amp * kotlin.math.sin(4f * th + t * 6f))
                val x = ox + rr * kotlin.math.cos(th); val y = oy + rr * kotlin.math.sin(th)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
        }
        canvas.save(); canvas.clipPath(path)
        val swell = 1f + 0.03f * (1f - g)
        canvas.save(); canvas.scale(swell, swell, ox, oy)
        r.set(to)
        picture(canvas, r)
        canvas.restore()
        canvas.restore()
        if (!quick) {
            sheen.strokeWidth = context.dp(1f).toFloat()
            sheen.color = Color.argb((110 * (1f - smooth(g / 0.7f))).toInt().coerceIn(0, 255), 236, 214, 170)
            if (Color.alpha(sheen.color) > 2) canvas.drawPath(path, sheen)
        }
    }

    private fun blendColour(a: Int, b: Int, t: Float): Int {
        fun ch(sh: Int) = (((a shr sh) and 255) * (1 - t) + ((b shr sh) and 255) * t).toInt()
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
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
        val rg = rCore * 4.2f
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
        val pitch = context.dp(15).toFloat()
        val n = (len / pitch).toInt().coerceAtLeast(1)
        val anchor = (if (vertical) iconAt.centerY() else iconAt.centerX()).coerceIn(a0, a1)
        val reach = maxOf(anchor - a0, a1 - anchor).coerceAtLeast(1f)
        val inset = context.dp(3).toFloat() * (if (side == 'L' || side == 'T') 1 else -1)
        // The housing is gone (24 Oct 2026): it read as a smear. The beads alone carry the lamp
        val hw = context.dp(22).toFloat()
        val ha = 0f
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
        val rc = context.dp(1.9f).toFloat()
        for (k in 0..n) {
            val along = a0 + len * k / n
            val d = kotlin.math.abs(along - anchor) / reach
            // A short bar of light, not the whole edge: it falls away well before the corners
            val fall = (1f - d * 1.35f).coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
            val off = 1f - smooth((t - (0.58f + 0.18f * d)) / 0.08f)
            val level = ignite(0.04f + 0.24f * d) * fall * off
            if (vertical) bead(canvas, pos + inset, along, rc * (0.7f + 0.3f * fall), level)
            else bead(canvas, along, pos + inset, rc * (0.7f + 0.3f * fall), level)
        }
    }

    /** The ring of beads round the icon: it sweeps round from the side facing the screen, then dims as the app leaves it. */
    private fun drawRing(canvas: Canvas) {
        val fade = 1f - smooth((t - 0.22f) / 0.22f)
        if (fade <= 0f || quick) return
        val cx = iconAt.centerX(); val cy = iconAt.centerY()
        val rad = iconAt.width() * 0.62f
        val start = kotlin.math.atan2((to.centerY() - cy).toDouble(), (to.centerX() - cx).toDouble())
        val m = 14
        for (j in 0 until m) {
            val ang = start + 2 * Math.PI * j / m
            val x = cx + (rad * kotlin.math.cos(ang)).toFloat()
            val y = cy + (rad * kotlin.math.sin(ang)).toFloat()
            bead(canvas, x, y, context.dp(1.6f).toFloat(), ignite(0.12f * j / m) * fade)
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
        // Depth: lets the switch blur the live app behind it (Android 12 and later, where One UI allows)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            flags = flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
            blurBehindRadius = 0
        }
        setFitInsetsTypes(0)
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    }

    companion object {
        // 24 Oct 2026: tighter throughout. A switch should feel immediate and then settle
        const val TRAVEL_MS = 400L
        const val LAMP_TRAVEL_MS = 480L
        const val MELT_MS = 150L
        const val FUOCO_MS = 440L
        const val CASCATA_MS = 520L
        const val ONDA_MS = 440L
        /** Longest the picture waits for the real app before dissolving anyway. */
        const val ARRIVAL_WAIT_MS = 420L
    }
}
