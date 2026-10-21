package com.fergusonconsultancy.foldtaskbar

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * Runs the taskbar. An accessibility service is the one kind of app Android lets draw
 * over every other app and ask for split screen, so the bar lives here.
 *
 * Steadiness rules, learned on the Fold:
 * - The bar's windows are created once and kept. Changes update the icons in place.
 * - Showing is instant; hiding waits until the reason to hide has lasted a moment, so the
 *   brief gaps during app transitions never blink the bar.
 * - Reading the window list happens off the main thread, so touches never queue behind it.
 */
class TaskbarService : AccessibilityService(), CapsuleActions, DragListener,
    SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var wm: WindowManager
    private lateinit var apps: Apps
    private val main = Handler(Looper.getMainLooper())
    private val worker = HandlerThread("taskbar-scan").apply { start() }
    private val bg = Handler(worker.looper)

    private val capsules = mutableListOf<Capsule>()
    private val hosts = mutableListOf<BarHost>()
    private var layoutKey = ""
    private var barOn = false
    private var menuWindow: View? = null
    private lateinit var drawer: AppDrawer
    private lateinit var aperture: ApertureFx

    /** Auto-hide: whether the bar has been called up, and the strips that call it. */
    private var revealed = false
    private val stripWindows = mutableListOf<View>()

    /** Collapsed to a tab. */
    private var tab: TabView? = null
    /** While collapsing or expanding, the point the capsules fold into or grow out of. */
    private var morphTarget: Pair<Float, Float>? = null
    private val settling = mutableSetOf<Int>()

    /** Apps with a window on screen right now, the focused one first. */
    @Volatile private var onScreen: List<String> = emptyList()
    @Volatile private var keyboardTop: Int? = null
    @Volatile private var systemInFront = false
    private var hidePackages: Set<String> = emptySet()
    private var noRecents: Set<String> = emptySet()

    private var pendingOpen: String? = null
    private var pendingOpenAt = 0L
    private var dragFrom = IntArray(2)

    // ---- Lifecycle ----------------------------------------------------------------

    override fun onServiceConnected() {
        wm = getSystemService(WindowManager::class.java)
        apps = Apps(this)
        drawer = AppDrawer(this, wm, apps, onOpenApp = { pkg, v -> launch(pkg, v) }, onHoldApp = { pkg, v -> openMenu(pkg, v) })
        aperture = ApertureFx(this, wm).also { it.attach() }
        // The bar now stays on the home screen too; only system surfaces such as the
        // notification shade push it aside.
        hidePackages = setOf("com.android.systemui")
        noRecents = homePackages() + hidePackages
        Prefs.of(this).registerOnSharedPreferenceChangeListener(this)
        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, main)
        running = true
        apps.onPackagesChanged { warmApps(rebuildList = true) }
        warmApps(rebuildList = false)
        EventLog.add(this, "Taskbar started. Home app: ${apps.homePackage() ?: "unknown"}")
        scheduleScan()
    }

    override fun onDestroy() {
        running = false
        runCatching { Prefs.of(this).unregisterOnSharedPreferenceChangeListener(this) }
        runCatching { getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener) }
        removeBar()
        removeTab()
        closePanels()
        aperture.detach()
        worker.quitSafely()
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        apps.forget()
        layoutKey = ""
        removeTab()
        warmApps(rebuildList = false)
        scheduleScan()
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) = scheduleScan()
    }

    override fun onSharedPreferenceChanged(p: SharedPreferences?, key: String?) {
        main.removeCallbacks(applyNow)
        main.post(applyNow)
    }

    // ---- Watching the screen ------------------------------------------------------

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = scheduleScan()

    private val scan = Runnable {
        scanWindows()
        main.post(applyNow)
    }
    private val applyNow = Runnable { apply() }

    private fun scheduleScan() {
        bg.removeCallbacks(scan)
        bg.postDelayed(scan, 40)
    }

    /** Runs on the worker thread. */
    private fun scanWindows() {
        val list = runCatching { windows }.getOrDefault(emptyList())
        val found = mutableListOf<String>()
        var imeTop: Int? = null
        var system = false
        val r = Rect()
        for (w in list) {
            when (w.type) {
                AccessibilityWindowInfo.TYPE_INPUT_METHOD -> { w.getBoundsInScreen(r); imeTop = r.top }
                AccessibilityWindowInfo.TYPE_SYSTEM -> if (w.isFocused) system = true
                AccessibilityWindowInfo.TYPE_APPLICATION -> {
                    val pkg = runCatching { w.root?.packageName?.toString() }.getOrNull() ?: continue
                    if (w.isFocused) found.add(0, pkg) else found.add(pkg)
                }
            }
        }
        keyboardTop = imeTop
        systemInFront = system
        // An empty list is a transition in progress, not a real state. Keep the last one.
        if (found.isNotEmpty()) onScreen = found.distinct()
    }

    private fun homePackages(): Set<String> =
        packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_ALL
        ).map { it.activityInfo.packageName }.toSet()

    private fun isMainScreen(): Boolean {
        val b = wm.currentWindowMetrics.bounds
        return minOf(b.width(), b.height()) / resources.displayMetrics.density >= 600
    }

    private fun shouldShow(): Boolean {
        val locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
        val front = onScreen.firstOrNull()
        return isMainScreen() && !locked && !systemInFront && front != null && front !in hidePackages
    }

    // ---- Drawing the bar ----------------------------------------------------------

    private val hideNow = Runnable { setBarOn(false) }

    /** Main thread. Cheap when nothing has changed. */
    private fun apply() {
        if (!::wm.isInitialized) return
        recordRecents()
        reportOpenTime()

        if (!shouldShow()) {
            // The cover screen hides at once; anything else waits to be sure.
            val delay = if (!isMainScreen()) 0L else 220L
            main.removeCallbacks(hideNow); main.postDelayed(hideNow, delay)
            if (!isMainScreen()) closePanels()
            removeStrips()
            removeTab()
            revealed = false
            return
        }
        main.removeCallbacks(hideNow)

        val shape = Prefs.shape(this)
        val finish = Prefs.finish(this)
        val iconDp = Prefs.iconDp(this)
        val bounds = wm.currentWindowMetrics.bounds
        val key = "$shape|$finish|$iconDp|${bounds.width()}x${bounds.height()}|${navInset()}"
        if (key != layoutKey || hosts.isEmpty()) {
            removeBar()
            buildBar(shape, finish, iconDp)
            layoutKey = key
        }

        val groups = slotGroups(shape, iconDp, bounds.width())
        val active = onScreen.toSet()
        capsules.forEachIndexed { i, c -> c.show(groups.getOrElse(i) { emptyList() }, active) }

        val collapsed = Prefs.collapsed(this)
        val panelsOpen = drawer.isOpen || menuWindow != null
        val autoHidden = collapsed == null && Prefs.autoHide(this) && !revealed && !panelsOpen
        if (collapsed != null) showTab(collapsed) else removeTab()
        if (autoHidden) showStrips() else removeStrips()
        setBarOn(true)
    }

    /** Whether one capsule should be on screen right now. */
    private fun hostWanted(i: Int): Boolean {
        if (!barOn || Prefs.collapsed(this) != null) return false
        if (Prefs.autoHide(this) && !revealed && !drawer.isOpen && menuWindow == null) return false
        // The keyboard only pushes aside a capsule it would actually cover
        val top = keyboardTop ?: return true
        val lp = hosts[i].layoutParams as? WindowManager.LayoutParams ?: return true
        return lp.y + hosts[i].height - dp(10) <= top
    }

    private fun setBarOn(on: Boolean) {
        barOn = on
        hosts.forEachIndexed { i, host ->
            val want = hostWanted(i)
            if (want && host.visibility != View.VISIBLE) place(i)
            showHost(host, want)
        }
        if (!on) closePanels()
    }

    private fun showHost(host: BarHost, show: Boolean) {
        val shown = host.visibility == View.VISIBLE && host.tag != "leaving"
        if (show == shown) return
        host.animate().cancel()
        val toward = morphTarget
        if (show) {
            host.tag = null
            host.visibility = View.VISIBLE
            if (toward != null) {
                // Expanding: the capsule grows back out of the tab
                val (tx, ty) = offsetTo(host, toward)
                host.translationX = tx * 0.7f; host.translationY = ty * 0.7f
                host.scaleX = 0.3f; host.scaleY = 0.3f; host.alpha = 0f
                host.animate().translationX(0f).translationY(0f).scaleX(1f).scaleY(1f).alpha(1f)
                    .setDuration(Curves.OPEN_MS).setInterpolator(Curves.OPEN).start()
            } else {
                host.animate().alpha(1f).setDuration(90).start()
            }
        } else {
            host.tag = "leaving"
            val end = Runnable {
                if (host.tag == "leaving") {
                    host.visibility = View.GONE
                    host.translationX = 0f; host.translationY = 0f; host.scaleX = 1f; host.scaleY = 1f
                }
            }
            if (toward != null) {
                // Collapsing: the capsule folds away into the tab
                val (tx, ty) = offsetTo(host, toward)
                host.animate().translationX(tx * 0.7f).translationY(ty * 0.7f).scaleX(0.3f).scaleY(0.3f).alpha(0f)
                    .setDuration(220).setInterpolator(Curves.CLOSE).withEndAction(end).start()
            } else {
                host.animate().alpha(0f).setDuration(90).withEndAction(end).start()
            }
        }
    }

    private fun offsetTo(host: View, target: Pair<Float, Float>): Pair<Float, Float> {
        val lp = host.layoutParams as WindowManager.LayoutParams
        return (target.first - (lp.x + host.width / 2f)) to (target.second - (lp.y + host.height / 2f))
    }

    private fun buildBar(shape: Shape, finish: Finish, iconDp: Int) {
        val count = if (shape == Shape.PAIR) 2 else 1
        repeat(count) { i ->
            val capsule = Capsule(this, apps, finish, iconDp, this)
            capsule.vertical = spotFor(shape, i).vertical
            val host = BarHost(this, this).apply { visibility = View.GONE; addView(capsule) }
            // Keep each capsule centred on its spot as icons come and go
            host.addOnLayoutChangeListener { v, l, t, r, b, ol, ot, or_, ob ->
                val i = hosts.indexOf(v)
                if ((r - l != or_ - ol || b - t != ob - ot) && !(v as BarHost).dragging && i !in settling) place(i)
            }
            add(host, params(Gravity.TOP or Gravity.START, 0, 0))
            capsules += capsule
            hosts += host
        }
        attachDrawer()
    }

    // ---- Where the bar sits -------------------------------------------------------

    private fun screen() = wm.currentWindowMetrics.bounds
    private fun statusInset() =
        wm.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top
    private fun navInset(): Int =
        wm.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars()).bottom

    /** Size of a capsule's window, measured if it has not been laid out yet. */
    private fun hostSize(host: View): Pair<Int, Int> {
        if (host.width > 0 && host.height > 0) return host.width to host.height
        host.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        return host.measuredWidth to host.measuredHeight
    }

    /** The resting place along the bottom, just above the gesture area: the default. */
    private fun defaultSpot(shape: Shape, i: Int): Prefs.Spot {
        val s = screen()
        val hostH = dp(Prefs.iconDp(this) + 6 + 16 + 20)
        val cy = s.height() - navInset() - dp(6) + dp(10) - hostH / 2f
        val fx = if (shape == Shape.PAIR) (if (i == 0) 0.25f else 0.75f) else 0.5f
        return Prefs.Spot(fx, cy / s.height(), false)
    }

    private fun spotFor(shape: Shape, i: Int) = Prefs.spot(this, shape, i) ?: defaultSpot(shape, i)

    /** Puts capsule [i] on its saved spot, kept fully on screen. */
    private fun place(i: Int) {
        val host = hosts.getOrNull(i) ?: return
        val lp = host.layoutParams as? WindowManager.LayoutParams ?: return
        val s = screen()
        val spot = spotFor(Prefs.shape(this), i)
        val (w, h) = hostSize(host)
        val pad = dp(10)
        val x = (spot.fx * s.width() - w / 2f).toInt().coerceIn(-pad, s.width() - w + pad)
        val y = (spot.fy * s.height() - h / 2f).toInt().coerceIn(statusInset() - pad, s.height() - h + pad)
        if (lp.x != x || lp.y != y) {
            lp.x = x; lp.y = y
            runCatching { wm.updateViewLayout(host, lp) }
        }
    }

    override fun onTouched() = onBarTouched()

    override fun onDragStart(host: BarHost) {
        closePanels()
        main.removeCallbacks(autoHideNow)
        val lp = host.layoutParams as WindowManager.LayoutParams
        dragFrom = intArrayOf(lp.x, lp.y)
    }

    override fun onDrag(host: BarHost, dx: Float, dy: Float) {
        val lp = host.layoutParams as WindowManager.LayoutParams
        lp.x = dragFrom[0] + dx.toInt()
        lp.y = dragFrom[1] + dy.toInt()
        runCatching { wm.updateViewLayout(host, lp) }
    }

    /**
     * Where the bar settles when you let go. Near an edge it docks with a magnetic pull,
     * standing upright against the sides. Near the crease it centres on it. Thrown mostly
     * off the screen, it collapses into a tab on that edge.
     */
    override fun onDragEnd(host: BarHost) {
        val i = hosts.indexOf(host)
        if (i < 0) return
        val shape = Prefs.shape(this)
        val s = screen()
        val lp = host.layoutParams as WindowManager.LayoutParams
        val pad = dp(10)
        val left = lp.x + pad; val top = lp.y + pad
        val right = lp.x + host.width - pad; val bottom = lp.y + host.height - pad
        val cw = right - left; val ch = bottom - top

        // Mostly off screen: collapse to a tab on that edge
        val visW = (minOf(right, s.width()) - maxOf(left, 0)).coerceAtLeast(0)
        val visH = (minOf(bottom, s.height()) - maxOf(top, 0)).coerceAtLeast(0)
        if (visW.toFloat() * visH < 0.55f * cw * ch) {
            val over = mapOf('L' to -left, 'R' to right - s.width(), 'T' to -top, 'B' to bottom - s.height())
            val edge = over.maxBy { it.value }.key
            val along = if (edge == 'L' || edge == 'R') (top + ch / 2f) / s.height() else (left + cw / 2f) / s.width()
            dock(i, host, edge, along)
            collapseTo(edge, along.coerceIn(0.08f, 0.92f))
            return
        }

        val m = dp(56)
        val dist = mapOf('L' to left, 'R' to s.width() - right, 'T' to top - statusInset(), 'B' to s.height() - navInset() - bottom)
        val near = dist.minBy { it.value }
        if (near.value <= m) {
            val along = if (near.key == 'L' || near.key == 'R') (top + ch / 2f) / s.height() else (left + cw / 2f) / s.width()
            dock(i, host, near.key, along)
        } else {
            // Floating freely: lies flat, and centres on the crease when close to it
            val capsule = capsules[i]
            capsule.vertical = false
            var cx = left + cw / 2f
            val cy = top + ch / 2f
            cx = creaseSnap(shape, i, cx)
            settle(i, host, cx / s.width(), cy / s.height(), vertical = false)
        }
    }

    /** Pins capsule [i] against [edge], [along] being its centre as a fraction along that edge. */
    private fun dock(i: Int, host: BarHost, edge: Char, along: Float) {
        val s = screen()
        val upright = edge == 'L' || edge == 'R'
        capsules[i].vertical = upright
        host.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val w = host.measuredWidth; val h = host.measuredHeight
        val pad = dp(10); val gap = dp(6)
        val cx = when (edge) {
            'L' -> gap - pad + w / 2f
            'R' -> s.width() - gap + pad - w / 2f
            else -> creaseSnap(Prefs.shape(this), i, along * s.width())
        }
        val cy = when (edge) {
            'T' -> statusInset() + gap - pad + h / 2f
            'B' -> s.height() - navInset() - gap + pad - h / 2f
            else -> (along * s.height()).coerceIn(statusInset() + h / 2f, s.height() - navInset() - h / 2f)
        }
        settle(i, host, cx / s.width(), cy / s.height(), upright)
    }

    /** On a flat bar, the crease is a magnet: centre on it, or on either half for the pair. */
    private fun creaseSnap(shape: Shape, i: Int, cx: Float): Float {
        val w = screen().width()
        val targets = if (shape == Shape.PAIR) listOf(w * 0.25f, w * 0.75f) else listOf(w * 0.5f)
        val pull = dp(28)
        return targets.firstOrNull { abs(it - cx) < pull } ?: cx
    }

    /** Saves the spot and glides the capsule there on the landing curve. */
    private fun settle(i: Int, host: BarHost, fx: Float, fy: Float, vertical: Boolean) {
        Prefs.setSpot(this, Prefs.shape(this), i, Prefs.Spot(fx, fy, vertical))
        val lp = host.layoutParams as WindowManager.LayoutParams
        val fromX = lp.x; val fromY = lp.y
        host.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val s = screen()
        val toX = (fx * s.width() - host.measuredWidth / 2f).toInt()
        val toY = (fy * s.height() - host.measuredHeight / 2f).toInt()
        settling += i
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 240
            interpolator = Curves.OPEN
            addUpdateListener {
                val t = it.animatedFraction
                lp.x = (fromX + (toX - fromX) * t).toInt()
                lp.y = (fromY + (toY - fromY) * t).toInt()
                if (host.isAttachedToWindow) runCatching { wm.updateViewLayout(host, lp) }
            }
            doOnEnd { settling -= i; place(i); attachDrawer() }
            start()
        }
        if (Prefs.autoHide(this) && revealed) scheduleAutoHide()
    }

    private fun ValueAnimator.doOnEnd(block: () -> Unit) = addListener(object : android.animation.AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: android.animation.Animator) = block()
    })

    // ---- Collapsed to a tab -------------------------------------------------------

    private fun collapseTo(edge: Char, along: Float) {
        closePanels()
        val r = tabRect(edge, along)
        morphTarget = r.exactCenterX() to r.exactCenterY()
        Prefs.setCollapsed(this, edge to along)
        main.postDelayed({ morphTarget = null }, 400)
    }

    private fun expand() {
        val c = Prefs.collapsed(this) ?: return
        // Bring the bar back where the tab now is, if the tab was moved along its edge
        val shape = Prefs.shape(this)
        val count = if (shape == Shape.PAIR) 2 else 1
        val spots = (0 until count).map { spotFor(shape, it) }
        val upright = c.first == 'L' || c.first == 'R'
        val mean = spots.map { if (upright) it.fy else it.fx }.average().toFloat()
        val shift = c.second - mean
        spots.forEachIndexed { i, sp ->
            val moved = if (upright) sp.copy(fy = (sp.fy + shift).coerceIn(0.1f, 0.9f)) else sp.copy(fx = (sp.fx + shift).coerceIn(0.1f, 0.9f))
            Prefs.setSpot(this, shape, i, moved)
        }
        hosts.indices.forEach(::place)
        morphTarget = tabCentre()
        Prefs.setCollapsed(this, null)
        attachDrawer()
        main.postDelayed({ morphTarget = null }, 400)
    }

    private fun tabRect(edge: Char, along: Float): Rect {
        val s = screen()
        val upright = edge == 'L' || edge == 'R'
        val w = if (upright) dp(36) else dp(112)
        val h = if (upright) dp(112) else dp(36)
        val x = when (edge) {
            'L' -> 0
            'R' -> s.width() - w
            else -> (along * s.width() - w / 2f).toInt().coerceIn(0, s.width() - w)
        }
        val y = when (edge) {
            'T' -> statusInset()
            'B' -> s.height() - navInset() - h
            else -> (along * s.height() - h / 2f).toInt().coerceIn(statusInset(), s.height() - navInset() - h)
        }
        return Rect(x, y, x + w, y + h)
    }

    private fun tabCentre(): Pair<Float, Float>? {
        val c = Prefs.collapsed(this) ?: return null
        val r = tabRect(c.first, c.second)
        return r.exactCenterX() to r.exactCenterY()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showTab(c: Pair<Char, Float>) {
        val r = tabRect(c.first, c.second)
        tab?.let { t ->
            val lp = t.layoutParams as WindowManager.LayoutParams
            if (t.edge == c.first && abs(lp.x - r.left) <= 2 && abs(lp.y - r.top) <= 2) return
            removeTab()
        }
        val t = TabView(this).apply {
            edge = c.first
            contentDescription = "Show taskbar"
            alpha = 0f
            scaleX = 0.6f; scaleY = 0.6f
        }
        var downX = 0f; var downY = 0f; var moved = false
        var startX = 0; var startY = 0
        t.setOnTouchListener { v, e ->
            val lp = v.layoutParams as WindowManager.LayoutParams
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; moved = false; startX = lp.x; startY = lp.y
                    v.animate().scaleX(1.12f).scaleY(1.12f).setDuration(80).start()
                }
                MotionEvent.ACTION_MOVE -> {
                    val upright = c.first == 'L' || c.first == 'R'
                    val d = if (upright) e.rawY - downY else e.rawX - downX
                    if (abs(d) > dp(8)) moved = true
                    if (moved) {
                        // The tab slides along its edge
                        val s = screen()
                        if (upright) lp.y = (startY + d).toInt().coerceIn(statusInset(), s.height() - navInset() - v.height)
                        else lp.x = (startX + d).toInt().coerceIn(0, s.width() - v.width)
                        runCatching { wm.updateViewLayout(v, lp) }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                    val s = screen()
                    val upright = c.first == 'L' || c.first == 'R'
                    if (moved) {
                        val along = if (upright) (lp.y + v.height / 2f) / s.height() else (lp.x + v.width / 2f) / s.width()
                        Prefs.setCollapsed(this, c.first to along)
                        sampleBackdrop()
                    } else if (e.actionMasked == MotionEvent.ACTION_UP) {
                        v.tick()
                        expand()
                    }
                }
            }
            true
        }
        add(t, params(Gravity.TOP or Gravity.START, r.left, r.top, w = r.width(), h = r.height()))
        tab = t
        t.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(if (morphTarget != null) 140 else 0)
            .setDuration(Curves.OPEN_MS).setInterpolator(Curves.OPEN).start()
        sampleBackdrop()
        main.removeCallbacks(resample)
        main.postDelayed(resample, 2500)
    }

    private fun removeTab() {
        main.removeCallbacks(resample)
        remove(tab); tab = null
    }

    private val resample = object : Runnable {
        override fun run() {
            if (tab == null) return
            sampleBackdrop()
            main.postDelayed(this, 3000)
        }
    }

    /**
     * Reads the brightness of the screen just around the tab and flips the tab to oppose
     * it. Only a handful of pixels are read; the screenshot is discarded straight away.
     */
    private fun sampleBackdrop() {
        val t = tab ?: return
        val c = Prefs.collapsed(this) ?: return
        val r = tabRect(c.first, c.second)
        val s = screen()
        val m = dp(28)
        val area = Rect(r.left - m, r.top - m, r.right + m, r.bottom + m).apply { intersect(0, 0, s.width(), s.height()) }
        if (area.isEmpty) return
        runCatching {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val hb = result.hardwareBuffer
                    runCatching {
                        val full = Bitmap.wrapHardwareBuffer(hb, result.colorSpace) ?: return@runCatching
                        val soft = full.copy(Bitmap.Config.ARGB_8888, false)
                        var sum = 0.0; var n = 0
                        val step = dp(6).coerceAtLeast(1)
                        var y = 0
                        while (y < area.height()) {
                                var x = 0
                            while (x < area.width()) {
                                // Skip the tab itself; only its surroundings matter
                                val sx = area.left + x; val sy = area.top + y
                                if (!r.contains(sx, sy) && sx < soft.width && sy < soft.height) {
                                    val p = soft.getPixel(sx, sy)
                                    sum += (0.2126 * ((p shr 16) and 255) + 0.7152 * ((p shr 8) and 255) + 0.0722 * (p and 255)) / 255.0
                                    n++
                                }
                                x += step
                            }
                            y += step
                        }
                        soft.recycle(); full.recycle()
                        if (n > 0) t.setBackdropLight(sum / n > 0.5)
                    }
                    hb.close()
                }

                override fun onFailure(errorCode: Int) {
                    if (!loggedShotFailure) EventLog.add(this@TaskbarService, "Tab contrast: screen sample refused (code $errorCode); using the two-tone tab")
                    loggedShotFailure = true
                }
            })
        }.onFailure {
            if (!loggedShotFailure) EventLog.add(this, "Tab contrast unavailable: ${it.javaClass.simpleName}; using the two-tone tab")
            loggedShotFailure = true
        }
    }

    private var loggedShotFailure = false

    // ---- Auto-hide ----------------------------------------------------------------

    private val autoHideNow = Runnable {
        if (drawer.isOpen || menuWindow != null || hosts.any { it.dragging }) { scheduleAutoHide(); return@Runnable }
        revealed = false
        apply()
    }

    private fun scheduleAutoHide() {
        main.removeCallbacks(autoHideNow)
        if (Prefs.autoHide(this)) main.postDelayed(autoHideNow, 3500)
    }

    private fun reveal() {
        revealed = true
        apply()
        scheduleAutoHide()
    }

    /** Any touch on the bar keeps it up for a few more seconds. */
    private fun onBarTouched() { if (Prefs.autoHide(this) && revealed) scheduleAutoHide() }

    /** Invisible strips over each hidden capsule's footprint. Swipe or tap one to bring the bar back. */
    @SuppressLint("ClickableViewAccessibility")
    private fun showStrips() {
        if (stripWindows.isNotEmpty()) return
        hosts.forEach { host ->
            val lp = host.layoutParams as? WindowManager.LayoutParams ?: return@forEach
            val (w, h) = hostSize(host)
            val pad = dp(10)
            val strip = View(this).apply {
                contentDescription = "Show taskbar"
                var downX = 0f; var downY = 0f
                setOnTouchListener { _, e ->
                    when (e.actionMasked) {
                        MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY }
                        MotionEvent.ACTION_MOVE -> if (abs(downY - e.rawY) + abs(downX - e.rawX) > dp(6) && !revealed) { tick(); reveal() }
                        MotionEvent.ACTION_UP -> if (!revealed) { tick(); reveal() }
                    }
                    true
                }
            }
            add(strip, params(Gravity.TOP or Gravity.START, lp.x + pad, lp.y + pad, w = w - pad * 2, h = h - pad * 2))
            stripWindows += strip
        }
    }

    private fun removeStrips() { stripWindows.forEach(::remove); stripWindows.clear() }

    // ---- Contents -----------------------------------------------------------------

    private fun recordRecents() {
        val eligible = { p: String -> p !in noRecents && p != packageName && p !in Prefs.pinned(this) && apps.isLaunchable(p) }
        val front = onScreen.firstOrNull() ?: return
        if (front in noRecents || front == packageName || !apps.isLaunchable(front)) return
        Prefs.pushRecent(this, front)
        if (front !in Prefs.pinned(this)) Prefs.trayAdd(this, front, Prefs.recentCount(this), eligible)
    }

    private fun reportOpenTime() {
        val p = pendingOpen ?: return
        if (onScreen.firstOrNull() == p) {
            val ms = SystemClock.uptimeMillis() - pendingOpenAt
            EventLog.add(this, "Opened ${apps.entry(p)?.label ?: p} in ${ms}ms")
            pendingOpen = null
        } else if (SystemClock.uptimeMillis() - pendingOpenAt > 4000) {
            EventLog.add(this, "Tap on ${apps.entry(p)?.label ?: p}: app did not come forward within 4s")
            pendingOpen = null
        }
    }

    private fun slotGroups(shape: Shape, iconDp: Int, screenW: Int): List<List<Slot>> {
        val maxSlots = maxSlots(screenW, iconDp, shape)
        val pinned = Prefs.pinned(this).filter { apps.isLaunchable(it) }
        val tray = Prefs.tray(this)
            .filter { it !in pinned && it !in noRecents && apps.isLaunchable(it) }
            .take(Prefs.recentCount(this))
        return when (shape) {
            Shape.PAIR -> listOf(
                listOf(Slot.AllApps) + pinned.take(maxSlots - 1).map { Slot.App(it) },
                tray.take(maxSlots).map { Slot.App(it) },
            )
            Shape.CAPSULE -> {
                val p = pinned.take((maxSlots - 1) / 2 + 1)
                val r = tray.take(maxSlots - 1 - p.size)
                listOf(
                    listOf(Slot.AllApps) + p.map { Slot.App(it) } +
                        (if (p.isNotEmpty() && r.isNotEmpty()) listOf(Slot.Divider) else emptyList()) +
                        r.map { Slot.App(it) }
                )
            }
        }
    }

    /** How many icons fit in one capsule without crossing the crease or the screen edge. */
    private fun maxSlots(screenW: Int, iconDp: Int, shape: Shape): Int {
        val room = if (shape == Shape.PAIR) screenW / 2 - dp(40) else (screenW * 0.9f).toInt()
        val per = dp(iconDp) + dp(iconDp * 0.28f)
        return ((room - dp(iconDp * 0.64f)) / per).coerceAtLeast(3)
    }

    private fun shadowHost(child: View): FrameLayout = FrameLayout(this).apply {
        clipChildren = false
        clipToPadding = false
        val p = dp(10)
        setPadding(p, p, p, p)
        addView(child)
    }

    private fun params(gravity: Int, x: Int, y: Int, w: Int = WindowManager.LayoutParams.WRAP_CONTENT,
                       h: Int = WindowManager.LayoutParams.WRAP_CONTENT, watchOutside: Boolean = false) =
        WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                (if (watchOutside) WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH else 0),
            PixelFormat.TRANSLUCENT,
        ).apply {
            this.gravity = gravity
            this.x = x
            this.y = y
            // Measure from the physical screen edge; we add the system bars ourselves
            setFitInsetsTypes(0)
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }

    private fun add(v: View, p: WindowManager.LayoutParams) {
        runCatching { wm.addView(v, p) }.onFailure { EventLog.add(this, "Could not draw: ${it.javaClass.simpleName}") }
    }

    private fun remove(v: View?) { if (v != null) runCatching { wm.removeView(v) } }

    private fun removeBar() {
        removeStrips()
        if (::drawer.isInitialized) drawer.detach()
        hosts.forEach(::remove); hosts.clear(); capsules.clear()
    }

    private fun closePanels() { closeMenu(); if (::drawer.isInitialized) drawer.close() }

    // ---- Actions ------------------------------------------------------------------

    override fun onApp(pkg: String, view: View) = launch(pkg, view)

    private fun launch(pkg: String, source: View? = null) {
        if (onScreen.size == 1 && onScreen.first() == pkg) { closePanels(); return }
        pendingOpen = pkg
        pendingOpenAt = SystemClock.uptimeMillis()
        // Launch first, on this frame; the motion runs alongside and never delays the app
        apps.open(pkg, source)
        if (source != null && source.isAttachedToWindow && Prefs.aperture(this)) {
            val loc = IntArray(2).also { source.getLocationOnScreen(it) }
            aperture.play(loc[0] + source.width / 2f, loc[1] + source.height / 2f)
        }
        closePanels()
        if (Prefs.autoHide(this) && revealed) {
            main.removeCallbacks(autoHideNow)
            main.postDelayed(autoHideNow, 600)
        }
    }

    override fun onAppHeld(pkg: String, view: View) = openMenu(pkg, view)

    override fun onAllAppsHeld(view: View) {
        closePanels()
        (view as? GlyphView)?.animateOpen(0f, Curves.CLOSE_MS, Curves.CLOSE)
        val auto = Prefs.autoHide(this)
        showMenu("Taskbar", listOf(
            "Collapse to a tab" to {
                closeMenu()
                hosts.firstOrNull()?.let { host ->
                    val lp = host.layoutParams as WindowManager.LayoutParams
                    val s = screen()
                    val cx = lp.x + host.width / 2f; val cy = lp.y + host.height / 2f
                    val d = mapOf('L' to cx, 'R' to s.width() - cx, 'T' to cy, 'B' to s.height() - cy)
                    val edge = d.minBy { it.value }.key
                    val along = if (edge == 'L' || edge == 'R') cy / s.height() else cx / s.width()
                    collapseTo(edge, along.coerceIn(0.08f, 0.92f))
                }
            },
            (if (auto) "Keep the taskbar showing" else "Auto-hide the taskbar") to {
                closeMenu(); Prefs.setAutoHide(this, !auto); revealed = false
            },
            "Taskbar settings" to {
                closeMenu()
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            },
        ), view)
    }

    override fun onAllApps(view: View) {
        closeMenu()
        if (drawer.isOpen) drawer.close() else drawer.open(view as? GlyphView)
        onBarTouched()
    }

    /**
     * Opens [pkg] beside the app on screen. The request goes through [SplitActivity],
     * because Android only splits the screen for a request from a full-screen app. If
     * One UI still refuses, Android's split-screen shortcut is tried once.
     */
    private fun splitWith(pkg: String) {
        closePanels()
        val label = apps.entry(pkg)?.label ?: pkg
        val target = apps.entry(pkg)?.component ?: return
        val apps2 = onScreen.filter { it !in hidePackages }
        val current = onScreen.firstOrNull()
        if (current == null || current in noRecents) {
            EventLog.add(this, "Split: needs an app on screen to sit beside; opened $label instead")
            apps.open(pkg)
            return
        }
        val back = if (apps2.size >= 2) null else apps.entry(current)?.component
        val intent = Intent(this, SplitActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            .putExtra(SplitActivity.EXTRA_TARGET, target)
            .putExtra(SplitActivity.EXTRA_BACK, back)
        runCatching { startActivity(intent) }
            .onFailure { EventLog.add(this, "Split: could not start (${it.javaClass.simpleName})") }

        main.postDelayed({
            bg.post {
                scanWindows()
                val seen = onScreen
                main.post {
                    val names = seen.joinToString(" + ") { apps.entry(it)?.label ?: it }
                    if (seen.size >= 2 && pkg in seen) {
                        EventLog.add(this, "Split worked: $names")
                    } else {
                        EventLog.add(this, "Split refused by One UI (on screen: $names). Trying the split-screen shortcut")
                        val ok = performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
                        EventLog.add(this, "Split-screen shortcut ${if (ok) "accepted" else "refused"}")
                    }
                }
            }
        }, 1400)
    }

    private fun openMenu(pkg: String, anchor: View) {
        val label = apps.entry(pkg)?.label ?: pkg
        val pinned = Prefs.pinned(this)
        val isPinned = pkg in pinned
        val rows = buildList<Pair<String, () -> Unit>> {
            add("Open in split screen" to { splitWith(pkg) })
            add("Open as pop-up (experimental)" to { closePanels(); apps.openPopup(pkg, wm.currentWindowMetrics.bounds) })
            add((if (isPinned) "Unpin from taskbar" else "Pin to taskbar") to {
                closeMenu()
                Prefs.togglePin(this@TaskbarService, pkg)
                if (!isPinned) Prefs.trayRemove(this@TaskbarService, pkg)
            })
            if (isPinned && pinned.indexOf(pkg) > 0) add("Move left" to { closeMenu(); Prefs.movePin(this@TaskbarService, pkg, -1) })
            if (isPinned && pinned.indexOf(pkg) < pinned.lastIndex) add("Move right" to { closeMenu(); Prefs.movePin(this@TaskbarService, pkg, 1) })
            if (!isPinned && pkg in Prefs.tray(this@TaskbarService)) add("Remove from recents" to { closeMenu(); Prefs.forgetRecent(this@TaskbarService, pkg) })
        }
        showMenu(label, rows, anchor)
    }

    private fun showMenu(title: String, rows: List<Pair<String, () -> Unit>>, anchor: View) {
        closeMenu()
        val menu = ActionMenu(this, Prefs.finish(this), title, rows)
        val host = outsideCloses(shadowHost(menu)) { closeMenu() }
        host.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val loc = IntArray(2).also { anchor.getLocationOnScreen(it) }
        val screen = screen()
        val x = (loc[0] + anchor.width / 2 - host.measuredWidth / 2).coerceIn(0, maxOf(0, screen.width() - host.measuredWidth))
        // Above the icon when there is room, otherwise below it (a bar docked at the top)
        val above = loc[1] - host.measuredHeight - dp(4)
        val y = if (above >= statusInset()) above else loc[1] + anchor.height + dp(4)
        add(host, params(Gravity.TOP or Gravity.START, x, y, watchOutside = true))
        menuWindow = host
    }

    private fun closeMenu() { remove(menuWindow); menuWindow = null }

    /** Loads apps and icons in the background so the grid is complete before the first tap. */
    private fun warmApps(rebuildList: Boolean) {
        bg.post {
            if (rebuildList) apps.forget()
            apps.preload()
            main.post { drawer.refreshApps() }
        }
    }

    /** Attaches the grid's windows next to wherever the grid button's capsule sits. */
    private fun attachDrawer() {
        if (!::drawer.isInitialized) return
        val s = screen()
        val w = minOf((s.width() * 0.8f).toInt(), dp(760))
        val h = (s.height() * 0.6f).toInt()
        val spot = spotFor(Prefs.shape(this), 0)
        val host = hosts.firstOrNull()
        val hostH = host?.let { hostSize(it).second } ?: dp(Prefs.iconDp(this) + 42)
        val cy = spot.fy * s.height()
        val fromTop = cy < s.height() / 2f
        val offset = if (fromTop) (cy + hostH / 2f - dp(10) + dp(12)).toInt()
        else (s.height() - (cy - hostH / 2f + dp(10)) + dp(12)).toInt()
        val room = if (fromTop) s.height() - offset - navInset() - dp(12) else s.height() - offset - statusInset() - dp(12)
        drawer.attach(Prefs.finish(this), w, minOf(h, room).coerceAtLeast(dp(240)), offset, fromTop)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun outsideCloses(v: View, close: () -> Unit): View = v.apply {
        setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_OUTSIDE) close()
            false
        }
    }

    companion object {
        @Volatile var running = false
        private const val SPLIT_METHOD = "split_method"
    }
}
