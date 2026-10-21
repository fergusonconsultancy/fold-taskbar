package com.fergusonconsultancy.foldtaskbar

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.FrameLayout

/**
 * Runs the taskbar. An accessibility service is the one kind of app Android lets draw
 * over every other app and ask for split screen, so the bar lives here.
 *
 * Steadiness rules, learned from the first build on the Fold:
 * - The bar's windows are created once and kept. Changes update the icons in place.
 * - Showing is instant; hiding waits until the reason to hide has lasted a moment, so the
 *   brief gaps during app transitions never blink the bar.
 * - Reading the window list happens off the main thread, so touches are never queued
 *   behind it.
 */
class TaskbarService : AccessibilityService(), CapsuleActions, SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var wm: WindowManager
    private lateinit var apps: Apps
    private val main = Handler(Looper.getMainLooper())
    private val worker = HandlerThread("taskbar-scan").apply { start() }
    private val bg = Handler(worker.looper)

    private val capsules = mutableListOf<Capsule>()
    private val barWindows = mutableListOf<View>()
    private var layoutKey = ""
    private var barShown = false
    private var menuWindow: View? = null
    private lateinit var drawer: AppDrawer

    /** Auto-hide: whether the bar has been called up, and the swipe strips that call it. */
    private var revealed = false
    private val stripWindows = mutableListOf<View>()

    /** Apps with a window on screen right now, the focused one first. */
    @Volatile private var onScreen: List<String> = emptyList()
    @Volatile private var keyboardUp = false
    @Volatile private var systemInFront = false
    private var hidePackages: Set<String> = emptySet()

    private var pendingOpen: String? = null
    private var pendingOpenAt = 0L

    // ---- Lifecycle ----------------------------------------------------------------

    override fun onServiceConnected() {
        wm = getSystemService(WindowManager::class.java)
        apps = Apps(this)
        drawer = AppDrawer(this, wm, apps, onOpenApp = { launch(it) }, onHoldApp = { pkg, v -> openMenu(pkg, v) })
        hidePackages = homePackages() + "com.android.systemui"
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
        closePanels()
        worker.quitSafely()
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        apps.forget()
        layoutKey = ""
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
        var ime = false
        var system = false
        for (w in list) {
            when (w.type) {
                AccessibilityWindowInfo.TYPE_INPUT_METHOD -> ime = true
                AccessibilityWindowInfo.TYPE_SYSTEM -> if (w.isFocused) system = true
                AccessibilityWindowInfo.TYPE_APPLICATION -> {
                    val pkg = runCatching { w.root?.packageName?.toString() }.getOrNull() ?: continue
                    if (w.isFocused) found.add(0, pkg) else found.add(pkg)
                }
            }
        }
        keyboardUp = ime
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
        return isMainScreen() && !locked && !keyboardUp && !systemInFront &&
            front != null && front !in hidePackages
    }

    // ---- Drawing the bar ----------------------------------------------------------

    private val hideNow = Runnable { setBarVisible(false) }

    /** Main thread. Cheap when nothing has changed. */
    private fun apply() {
        if (!::wm.isInitialized) return
        recordRecents()
        reportOpenTime()

        if (!shouldShow()) {
            // Keyboard and cover screen hide at once; anything else waits to be sure.
            val delay = if (keyboardUp || !isMainScreen()) 0L else 220L
            if (barShown) { main.removeCallbacks(hideNow); main.postDelayed(hideNow, delay) }
            if (!isMainScreen()) closePanels()
            removeStrips()
            revealed = false
            return
        }
        main.removeCallbacks(hideNow)

        val shape = Prefs.shape(this)
        val finish = Prefs.finish(this)
        val iconDp = Prefs.iconDp(this)
        val bounds = wm.currentWindowMetrics.bounds
        val key = "$shape|$finish|$iconDp|${bounds.width()}x${bounds.height()}|${navInset()}"
        if (key != layoutKey || barWindows.isEmpty()) {
            removeBar()
            buildBar(shape, finish, iconDp, bounds)
            layoutKey = key
        }

        val groups = slotGroups(shape, iconDp, bounds.width())
        val active = onScreen.toSet()
        capsules.forEachIndexed { i, c -> c.show(groups.getOrElse(i) { emptyList() }, active) }

        val panelsOpen = drawer.isOpen || menuWindow != null
        if (Prefs.autoHide(this) && !revealed && !panelsOpen) {
            setBarVisible(false)
            showStrips(shape, bounds)
        } else {
            removeStrips()
            setBarVisible(true)
        }
    }

    // ---- Auto-hide ----------------------------------------------------------------

    private val autoHideNow = Runnable {
        if (drawer.isOpen || menuWindow != null) { scheduleAutoHide(); return@Runnable }
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

    /**
     * Invisible strips where the bar sits. Swipe up (or tap) on one to bring the bar
     * back. They cover only the bar's own footprint, so the rest of the app stays touchable.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun showStrips(shape: Shape, bounds: Rect) {
        if (stripWindows.isNotEmpty()) return
        val h = dp(22)
        val y = navInset()
        val specs = if (shape == Shape.PAIR)
            listOf(-bounds.width() / 4 to bounds.width() / 2 - dp(80), bounds.width() / 4 to bounds.width() / 2 - dp(80))
        else listOf(0 to (bounds.width() * 0.6f).toInt())
        specs.forEach { (x, w) ->
            val strip = View(this).apply {
                contentDescription = "Show taskbar"
                var downY = 0f
                setOnTouchListener { _, e ->
                    when (e.actionMasked) {
                        MotionEvent.ACTION_DOWN -> downY = e.rawY
                        MotionEvent.ACTION_MOVE -> if (downY - e.rawY > dp(6)) { tick(); reveal() }
                        MotionEvent.ACTION_UP -> if (!revealed) { tick(); reveal() }
                    }
                    true
                }
            }
            add(strip, params(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, x, y, w = w, h = h))
            stripWindows += strip
        }
    }

    private fun removeStrips() { stripWindows.forEach(::remove); stripWindows.clear() }

    private fun recordRecents() {
        val eligible = { p: String -> p !in hidePackages && p != packageName && p !in Prefs.pinned(this) && apps.isLaunchable(p) }
        val front = onScreen.firstOrNull() ?: return
        if (front in hidePackages || front == packageName || !apps.isLaunchable(front)) return
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
            .filter { it !in pinned && it !in hidePackages && apps.isLaunchable(it) }
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

    private fun buildBar(shape: Shape, finish: Finish, iconDp: Int, bounds: Rect) {
        val count = if (shape == Shape.PAIR) 2 else 1
        val bottom = navInset() + dp(6) - dp(10)
        repeat(count) { i ->
            val capsule = Capsule(this, apps, finish, iconDp, this)
            val host = shadowHost(capsule, onTouch = ::onBarTouched).apply { visibility = View.GONE }
            val x = when {
                shape == Shape.PAIR && i == 0 -> -bounds.width() / 4
                shape == Shape.PAIR -> bounds.width() / 4
                else -> 0
            }
            add(host, params(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, x, bottom))
            capsules += capsule
            barWindows += host
        }
        barShown = false
        attachDrawer(bounds)
    }

    private fun setBarVisible(show: Boolean) {
        if (show == barShown) return
        barShown = show
        barWindows.forEach { host ->
            host.animate().cancel()
            if (show) {
                host.visibility = View.VISIBLE
                host.animate().alpha(1f).setDuration(90).start()
            } else {
                host.animate().alpha(0f).setDuration(90).withEndAction { if (!barShown) host.visibility = View.GONE }.start()
            }
        }
        if (!show) closePanels()
    }

    /** How many icons fit in one capsule without crossing the crease or the screen edge. */
    private fun maxSlots(screenW: Int, iconDp: Int, shape: Shape): Int {
        val room = if (shape == Shape.PAIR) screenW / 2 - dp(40) else (screenW * 0.9f).toInt()
        val per = dp(iconDp) + dp(iconDp * 0.28f)
        return ((room - dp(iconDp * 0.64f)) / per).coerceAtLeast(3)
    }

    private fun shadowHost(child: View, onTouch: (() -> Unit)? = null): FrameLayout = object : FrameLayout(this) {
        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) onTouch?.invoke()
            return super.dispatchTouchEvent(ev)
        }
    }.apply {
        clipChildren = false
        clipToPadding = false
        val p = dp(10)
        setPadding(p, p, p, p)
        addView(child)
    }

    private fun navInset(): Int =
        wm.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars()).bottom

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
            // Measure from the physical screen edge; we add the gesture area ourselves
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
        barWindows.forEach(::remove); barWindows.clear(); capsules.clear(); barShown = false
    }

    private fun closePanels() { closeMenu(); if (::drawer.isInitialized) drawer.close() }

    // ---- Actions ------------------------------------------------------------------

    override fun onApp(pkg: String, view: View) = launch(pkg)

    private fun launch(pkg: String) {
        closePanels()
        if (onScreen.size == 1 && onScreen.first() == pkg) return
        pendingOpen = pkg
        pendingOpenAt = SystemClock.uptimeMillis()
        apps.open(pkg)
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

    private fun splitWith(pkg: String) {
        closePanels()
        val before = onScreen.filter { it !in hidePackages }
        val label = apps.entry(pkg)?.label ?: pkg
        if (before.size >= 2) {
            apps.openAdjacent(pkg)
            EventLog.add(this, "Split: replaced the other pane with $label")
            return
        }
        val current = before.firstOrNull() ?: run { apps.open(pkg); return }
        val currentLabel = apps.entry(current)?.label ?: current
        val p = Prefs.of(this)
        if (p.getString(SPLIT_METHOD, "adjacent") == "adjacent") {
            apps.openAdjacent(pkg)
            main.postDelayed({
                bg.post {
                    scanWindows()
                    main.post {
                        if (onScreen.size >= 2) {
                            EventLog.add(this, "Split: $label beside $currentLabel (direct launch worked)")
                        } else {
                            EventLog.add(this, "Split: direct launch ignored, switching to the split-screen shortcut")
                            p.edit().putString(SPLIT_METHOD, "toggle").apply()
                            toggleThenLaunch(current)
                        }
                    }
                }
            }, 900)
        } else {
            toggleThenLaunch(pkg)
        }
    }

    /** Docks the app on screen into split view, then fills the other side with [pkg]. */
    private fun toggleThenLaunch(pkg: String) {
        val ok = performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
        main.postDelayed({
            apps.openAdjacent(pkg)
            main.postDelayed({
                bg.post {
                    scanWindows()
                    EventLog.add(this, "Split shortcut ${if (ok) "accepted" else "refused"}; apps on screen: ${onScreen.size}")
                }
            }, 900)
        }, 650)
    }

    private fun openMenu(pkg: String, anchor: View) {
        closeMenu()
        val finish = Prefs.finish(this)
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
        val screen = wm.currentWindowMetrics.bounds
        val x = (loc[0] + anchor.width / 2 - host.measuredWidth / 2).coerceIn(0, maxOf(0, screen.width() - host.measuredWidth))
        val y = (loc[1] - host.measuredHeight - dp(4)).coerceAtLeast(0)
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

    /** Attaches the grid's windows alongside the bar, so opening it is only an animation. */
    private fun attachDrawer(bounds: Rect) {
        val w = minOf((bounds.width() * 0.8f).toInt(), dp(760))
        val h = (bounds.height() * 0.6f).toInt()
        val bottom = navInset() + dp(Prefs.iconDp(this) + 16 + 6 + 12)
        drawer.attach(Prefs.finish(this), w, h, bottom)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun outsideCloses(v: View, close: () -> Unit): View = v.apply {
        setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_OUTSIDE) {
                close()
            }
            false
        }
    }

    companion object {
        @Volatile var running = false
        private const val SPLIT_METHOD = "split_method"
    }
}
