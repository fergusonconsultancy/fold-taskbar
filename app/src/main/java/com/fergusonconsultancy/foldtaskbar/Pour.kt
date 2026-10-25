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
/**
 * 24 Oct 2026, second pass: Pour and Cascata are gone (Pour was Membrana without its reason;
 * Cascata never stopped looking busy) and Lama replaces them. Every look now lands on the app's
 * real window, pixel for pixel, so nothing jumps when the picture hands over to the live app.
 *
 * Lama: a single blade of champagne light is drawn out of the icon along the bar, and the app is
 * uncovered from it, the slit widening into the whole screen. Nothing flies or scales: the app is
 * already in place and the light simply opens it.
 */
enum class Look { MEMBRANA, FUOCO, ONDA, LAMA, ZOOM, REVEAL, GROW }

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
    private var look = Look.MEMBRANA
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
    fun play(source: View, screen: Rect, snapshot: Bitmap?, appIcon: Drawable?, lamp: Boolean = false, membrane: Boolean = false, look: Look? = null, concentrato: Boolean = true, land: Rect? = null) {
        // Fanale was retired from the bar on 25 Oct 2026; its lamp drawing stays dormant
        this.look = look ?: Look.MEMBRANA
        this.lamp = false
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
        picture = snapshot?.takeIf { !it.isRecycled }
        // Land exactly where the app's window is, so the hand-over to the live app is invisible.
        // A picture taken from the app's own window is that window's size; drawn over the whole
        // screen it came out a few percent too large and jumped back as the app appeared.
        if (land != null && picture != null && !land.isEmpty) to.set((land.left - mine[0]).toFloat(), (land.top - mine[1]).toFloat(), (land.right - mine[0]).toFloat(), (land.bottom - mine[1]).toFloat())
        else to.set(0f, 0f, (if (width > 0) width else screen.width()).toFloat(), (if (height > 0) height else screen.height()).toFloat())
        // A private copy: the cached icon is shared with the bar and must not be resized here
        icon = appIcon?.constantState?.newDrawable(context.resources)?.mutate()
        body = icon?.let { tone(it) } ?: Color.DKGRAY
        // The splash: mostly the deep ground of the bar's family, with a quarter of the app's colour
        splash = blendColour(0xFF0E1311.toInt(), body, 0.28f)
        running?.cancel()
        animate().cancel()
        alpha = 1f
        t = 0f
        soft?.recycle(); soft = if (this.look == Look.FUOCO && !quick) makeSoft(picture) else null
        // Depth for Fuoco and Onda: the screen behind defocuses, set once (see depthFrame)
        setBehindBlur(if (this.look == Look.FUOCO || this.look == Look.ONDA) context.dp(if (quick) 12 else 24).toFloat() else 0f)
        travelled = false
        appHere = false
        removeCallbacks(meltAnyway)
        visibility = VISIBLE
        invalidate()
        running = ValueAnimator.ofFloat(0f, 1f).apply {
            val base = when (this@Pour.look) {
                Look.FUOCO -> FUOCO_MS
                Look.ONDA -> ONDA_MS
                Look.LAMA -> LAMA_MS
                Look.ZOOM -> ZOOM_MS
                Look.REVEAL -> REVEAL_MS
                Look.GROW -> GROW_MS
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
        animate().alpha(0f).setDuration(MELT_MS).setInterpolator(null)
            .setUpdateListener(null)
            .withEndAction { setBehindBlur(0f); setRenderEffect(null); visibility = INVISIBLE; picture = null; icon = null; soft?.recycle(); soft = null }
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
    /**
     * 25 Oct 2026, Fuoco was choppy: every frame it re-laid out the window to change the blur
     * behind it and rebuilt a full-screen blur effect on itself, both expensive. Now the blur
     * behind is set once when the switch starts (the veil eases it in), and the focus pull is a
     * cross-fade from a soft copy of the picture made once, so each frame is only two bitmaps.
     */
    private fun depthFrame() = Unit

    private var soft: Bitmap? = null

    /** A soft copy of the picture: shrunk hard and drawn back up with filtering. Made once per switch. */
    private fun makeSoft(src: Bitmap?): Bitmap? = src?.let {
        runCatching {
            val small = Bitmap.createScaledBitmap(it, (it.width / 12).coerceAtLeast(2), (it.height / 12).coerceAtLeast(2), true)
            val back = Bitmap.createScaledBitmap(small, it.width / 3, it.height / 3, true)
            small.recycle(); back
        }.getOrNull()
    }

    override fun onDraw(canvas: Canvas) {
        // The veil: what you are leaving recedes into the bar's own dark ground. It also hides
        // One UI shrinking the old app away behind the switch, which showed as grey margins
        veil(canvas)
        when (look) {
            Look.FUOCO -> { drawFuoco(canvas); return }
            Look.ONDA -> { drawOnda(canvas); return }
            Look.LAMA -> { drawLama(canvas); return }
            Look.ZOOM -> { drawZoom(canvas); return }
            Look.REVEAL -> { drawReveal(canvas); return }
            Look.GROW -> { drawGrow(canvas); return }
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
        // Corners open out to the screen's own: square on landing, so nothing changes shape at the hand-over
        val radius = mix(from.width() * 0.32f, context.dp(26).toFloat(), b) * (1f - smooth((t - 0.6f) / 0.4f))
        val leadSide = if (!across) (if (dy < 0) 'T' else 'B') else (if (dx < 0) 'L' else 'R')

        // Depth for every look: the app behind dips a little and the window casts a soft shadow
        // ahead of itself, both gone by the time it lands
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
        // Taut by the time it lands: the bow is let go over the last stretch, never left mid-curve
        val taut = 1f - smooth((t - 0.6f) / 0.35f)
        for (k in 0..3) bow[k] *= taut
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
            // 25 Oct 2026: no more blocks of the app's colour with its icon in the middle. With no
            // picture of the app yet, the shape is the bar's own dark glass, neutral and quiet,
            // and the motion is carried by its light alone until the live app takes over.
            fill.color = 0xFF0E1311.toInt()
            fill.alpha = (paint.alpha * 0.9f).toInt()
            canvas.drawRect(dst, fill)
            fill.alpha = 255
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
    private fun depthDim(canvas: Canvas) = Unit

    private val ground = Paint().apply { color = 0xFF0E1311.toInt() }

    /** Rises within the first eighth of the switch and stays until the hand-over. */
    private fun veil(canvas: Canvas) {
        val v = (if (quick) 0.32f else 0.52f) * smooth(t / 0.12f)
        if (v <= 0.004f) return
        ground.alpha = (255 * v).toInt()
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), ground)
    }

    /**
     * Grow: the whole app at its true proportions scales up out of the icon. The content scales
     * with it (you see the app itself enlarge), on one deceleration, centred on its path from the
     * icon to the screen. Never wider or taller than the screen at any moment.
     */
    private fun drawGrow(canvas: Canvas) {
        val g = emph.getInterpolation(t)
        val aspect = to.height() / to.width().coerceAtLeast(1f)
        val w0 = iconAt.width(); val w = mix(w0, to.width(), g); val h = w * aspect
        val cx = mix(iconAt.centerX(), to.centerX(), g); val cy = mix(iconAt.centerY(), to.centerY(), g)
        r.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
        val radius = mix(w0 * 0.28f, 0f, g)
        path.reset(); path.addRoundRect(r, radius, radius, Path.Direction.CW)
        canvas.save(); canvas.clipPath(path)
        paint.alpha = (255 * smooth(t / 0.12f)).toInt()
        picture(canvas, r)
        paint.alpha = 255
        canvas.restore()
    }

    /**
     * Reveal: nothing scales. The app is already in place at full size and a window with the
     * screen's proportions widens from the icon's centre to uncover it, its corners squaring
     * as it reaches the edges. What moves is only the opening.
     */
    private fun drawReveal(canvas: Canvas) {
        val g = emph.getInterpolation(t)
        val ox = iconAt.centerX(); val oy = iconAt.centerY()
        r.set(mix(ox, to.left, g), mix(oy, to.top, g), mix(ox, to.right, g), mix(oy, to.bottom, g))
        val radius = context.dp(28).toFloat() * (1f - smooth((g - 0.5f) / 0.5f))
        path.reset(); path.addRoundRect(r, radius, radius, Path.Direction.CW)
        canvas.save(); canvas.clipPath(path)
        picture(canvas, to)
        canvas.restore()
        // One hairline on the opening's edge while it travels
        val rim = 1f - smooth((g - 0.3f) / 0.4f)
        if (rim > 0.02f && !quick) {
            sheen.strokeWidth = context.dp(1f).toFloat()
            sheen.color = Color.argb((120 * rim).toInt(), 236, 214, 170)
            canvas.drawPath(path, sheen)
        }
    }

    /**
     * Zoom: the icon itself becomes the app. Its artwork swells out of the bar at full
     * strength, then gives way to the screen you left while still travelling, so the last
     * thing you see of the icon is the app it opened.
     */
    private fun drawZoom(canvas: Canvas) {
        val g = emph.getInterpolation(t)
        r.set(mix(iconAt.left, to.left, g), mix(iconAt.top, to.top, g), mix(iconAt.right, to.right, g), mix(iconAt.bottom, to.bottom, g))
        val radius = mix(iconAt.width() * 0.24f, 0f, g)
        path.reset(); path.addRoundRect(r, radius, radius, Path.Direction.CW)
        canvas.save(); canvas.clipPath(path)
        // The icon gives way to the app; no ground of the icon's colour behind it
        val appIn = smooth((t - 0.12f) / 0.38f)
        paint.alpha = (255 * appIn).toInt()
        picture(canvas, r)
        paint.alpha = 255
        icon?.let { d ->
            val a = 1f - smooth((t - 0.08f) / 0.32f)
            if (a > 0.01f) {
                val s = minOf(r.width(), r.height()) * mix(1f, 0.5f, g)
                d.setBounds((r.centerX() - s / 2).toInt(), (r.centerY() - s / 2).toInt(), (r.centerX() + s / 2).toInt(), (r.centerY() + s / 2).toInt())
                d.alpha = (255 * a).toInt(); d.draw(canvas); d.alpha = 255
            }
        }
        canvas.restore()
    }

    private val blade = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bladeGlow = Paint(Paint.ANTI_ALIAS_FLAG)

    // Lama's one-sided light: falls from a lip onto the app it uncovers, never onto the veil
    private val lipLight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = android.graphics.LinearGradient(0f, 0f, 0f, 1f,
            intArrayOf(Color.argb(150, 236, 206, 150), Color.argb(40, 217, 187, 134), Color.TRANSPARENT),
            floatArrayOf(0f, 0.35f, 1f), android.graphics.Shader.TileMode.CLAMP)
    }
    // And the cover's thickness: a soft shadow on the veil side of each lip
    private val lipShade = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = android.graphics.LinearGradient(0f, 0f, 0f, 1f, Color.argb(110, 0, 0, 0), Color.TRANSPARENT, android.graphics.Shader.TileMode.CLAMP)
    }
    private val head = Paint(Paint.ANTI_ALIAS_FLAG)
    // The same two, running across x, for a bar standing at a side
    private val lipLightH = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = android.graphics.LinearGradient(0f, 0f, 1f, 0f,
            intArrayOf(Color.argb(150, 236, 206, 150), Color.argb(40, 217, 187, 134), Color.TRANSPARENT),
            floatArrayOf(0f, 0.35f, 1f), android.graphics.Shader.TileMode.CLAMP)
    }
    private val lipShadeH = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = android.graphics.LinearGradient(0f, 0f, 1f, 0f, Color.argb(110, 0, 0, 0), Color.TRANSPARENT, android.graphics.Shader.TileMode.CLAMP)
    }

    /**
     * Lama, refined (25 Oct 2026). One blade of champagne light leaves the icon: two bright
     * heads race from it in opposite directions along the bar's axis, drawing the line behind
     * them to the screen's edges. The slit opens from that line on one crisp deceleration. Each
     * lip is a fine line of light that falls onto the app it uncovers, with a soft shadow on its
     * other side, so the cover reads as a thin sheet with an edge. The lips lose their light as
     * they reach the edges and the app is simply there. Concentrato keeps only the lines.
     */
    private fun drawLama(canvas: Canvas) {
        val dx = to.centerX() - iconAt.centerX(); val dy = to.centerY() - iconAt.centerY()
        val across = kotlin.math.abs(dx) / to.width().coerceAtLeast(1f) > kotlin.math.abs(dy) / to.height().coerceAtLeast(1f)
        val draw = emph.getInterpolation((t / 0.3f).coerceAtMost(1f))
        val open = emph.getInterpolation(((t - 0.14f) / 0.86f).coerceIn(0f, 1f))
        val pos = if (!across) iconAt.centerY().coerceIn(to.top, to.bottom) else iconAt.centerX().coerceIn(to.left, to.right)
        if (!across) r.set(to.left, mix(pos, to.top, open), to.right, mix(pos, to.bottom, open))
        else r.set(mix(pos, to.left, open), to.top, mix(pos, to.right, open), to.bottom)
        if (open > 0f) {
            canvas.save(); canvas.clipRect(r)
            picture(canvas, to)
            canvas.restore()
        }
        val light = 1f - smooth((open - 0.5f) / 0.45f)
        if (light <= 0.01f) return
        val thick = context.dp(1.5f).toFloat()
        val spill = context.dp(if (quick) 0 else 22).toFloat() * (0.4f + 0.6f * light)
        blade.color = Color.argb((240 * light).toInt(), 248, 232, 196)
        // The cut's axis: c along the blade, the two lips at lo and hi across it
        val c = if (!across) iconAt.centerX() else iconAt.centerY()
        val start = if (!across) to.left else to.top
        val end = if (!across) to.right else to.bottom
        val a0 = mix(c, start, draw); val a1 = mix(c, end, draw)
        val lo = if (!across) r.top else r.left
        val hi = if (!across) r.bottom else r.right
        // One lip: the line, light falling into the opening (dir points into it), shadow onto the cover
        fun lip(at: Float, dir: Float) {
            if (spill > 0f && open > 0.02f) {
                val inA = at; val inB = at + spill * dir
                val shA = at; val shB = at - spill * 0.5f * dir
                if (!across) {
                    mx.reset(); mx.setScale(1f, inB - inA); mx.postTranslate(0f, inA)
                    lipLight.shader.setLocalMatrix(mx); lipLight.alpha = (255 * light).toInt()
                    canvas.drawRect(a0, minOf(inA, inB), a1, maxOf(inA, inB), lipLight)
                    mx.reset(); mx.setScale(1f, shB - shA); mx.postTranslate(0f, shA)
                    lipShade.shader.setLocalMatrix(mx); lipShade.alpha = (255 * light).toInt()
                    canvas.drawRect(a0, minOf(shA, shB), a1, maxOf(shA, shB), lipShade)
                } else {
                    mx.reset(); mx.setScale(inB - inA, 1f); mx.postTranslate(inA, 0f)
                    lipLightH.shader.setLocalMatrix(mx); lipLightH.alpha = (255 * light).toInt()
                    canvas.drawRect(minOf(inA, inB), a0, maxOf(inA, inB), a1, lipLightH)
                    mx.reset(); mx.setScale(shB - shA, 1f); mx.postTranslate(shA, 0f)
                    lipShadeH.shader.setLocalMatrix(mx); lipShadeH.alpha = (255 * light).toInt()
                    canvas.drawRect(minOf(shA, shB), a0, maxOf(shA, shB), a1, lipShadeH)
                }
            }
            if (!across) canvas.drawRect(a0, at - thick / 2, a1, at + thick / 2, blade)
            else canvas.drawRect(at - thick / 2, a0, at + thick / 2, a1, blade)
        }
        lip(lo, 1f)
        lip(hi, -1f)
        // The two heads drawing the blade: brightest while they run, gone once it is drawn
        val run = 1f - smooth((draw - 0.7f) / 0.3f)
        if (!quick && run > 0.02f) {
            val mid = (lo + hi) / 2f
            for (p in floatArrayOf(a0, a1)) {
                val x = if (!across) p else mid; val y = if (!across) mid else p
                val rad = context.dp(10).toFloat()
                mx.reset(); mx.setScale(rad, rad); mx.postTranslate(x, y)
                headShader.setLocalMatrix(mx)
                head.alpha = (230 * run).toInt()
                canvas.drawCircle(x, y, rad, head)
            }
        }
    }

    private val headShader = android.graphics.RadialGradient(0f, 0f, 1f,
        intArrayOf(Color.argb(255, 255, 246, 226), Color.argb(120, 236, 206, 150), Color.TRANSPARENT),
        floatArrayOf(0f, 0.3f, 1f), android.graphics.Shader.TileMode.CLAMP)
    init { head.shader = headShader }

    /**
     * Fuoco: a focus pull. The app rises from the icon soft and sharpens as it fills the screen.
     * 24 Oct 2026: it no longer rises oversized and contracts back; that return read as a bounce.
     * It grows on one deceleration and stops exactly at the window's edges.
     */
    private fun drawFuoco(canvas: Canvas) {
        val g = emph.getInterpolation((t / 0.8f).coerceAtMost(1f))
        r.set(mix(from.left, to.left, g), mix(from.top, to.top, g), mix(from.right, to.right, g), mix(from.bottom, to.bottom, g))
        val radius = mix(from.width() * 0.32f, 0f, g)
        path.reset(); path.addRoundRect(r, radius, radius, Path.Direction.CW)
        val a = smooth(t / 0.2f)
        canvas.save(); canvas.clipPath(path)
        val sf = soft
        if (sf != null && picture != null) {
            // The focus pull: soft first, the sharp picture coming up through it
            val sharp = smooth((t - 0.12f) / 0.5f)
            paint.alpha = (255 * a).toInt()
            canvas.drawBitmap(sf, null, r, paint)
            paint.alpha = (255 * a * sharp).toInt()
            picture(canvas, r)
        } else {
            paint.alpha = (255 * a).toInt()
            picture(canvas, r)
        }
        paint.alpha = 255
        canvas.restore()
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
        // The content stays in place inside the ripple (no swell to settle back: that was a bounce)
        r.set(to)
        picture(canvas, r)
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
        /** The hand-over to the live app: with the picture landing on the app exactly, a near cut. */
        const val MELT_MS = 90L
        const val FUOCO_MS = 440L
        const val LAMA_MS = 420L
        const val ZOOM_MS = 400L
        const val REVEAL_MS = 360L
        const val GROW_MS = 380L
        const val ONDA_MS = 440L
        /** Longest the picture waits for the real app before dissolving anyway. */
        const val ARRIVAL_WAIT_MS = 900L
    }
}
