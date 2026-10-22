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

    private val r = RectF()
    private val path = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val src = Rect()

    private val lead = OvershootInterpolator(1.15f)
    private val follow = PathInterpolator(0.3f, 0f, 0f, 1f)
    private val trail = PathInterpolator(0.55f, 0f, 0.15f, 1f)

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
    fun play(source: View, screen: Rect, snapshot: Bitmap?, appIcon: Drawable?) {
        ensure()
        val loc = IntArray(2).also { source.getLocationOnScreen(it) }
        val mine = IntArray(2).also { getLocationOnScreen(it) }
        val ox = (loc[0] - mine[0]).toFloat(); val oy = (loc[1] - mine[1]).toFloat()
        from.set(ox, oy, ox + source.width, oy + source.height)
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
            duration = TRAVEL_MS
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
        val a = lead.getInterpolation(t)
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

        path.reset()
        path.addRoundRect(r, radius, radius, Path.Direction.CW)
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
        sheen.color = Color.argb((90 * (1f - c)).toInt().coerceIn(0, 255), 255, 255, 255)
        canvas.drawPath(path, sheen)
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
        const val MELT_MS = 240L
    }
}
