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
 */
class TaskbarService : AccessibilityService(), CapsuleActions, SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var wm: WindowManager
    private lateinit var apps: Apps
    private val main = Handler(Looper.getMainLooper())

    private val barWindows = mutableListOf<View>()
    private var handleWindow: View? = null
    private var drawerWindow: View? = null
    private var menuWindow: View? = null
    private var drawerClosedAt = 0L

    /** Apps with a window on screen right now, the focused one first. */
    private var onScreen: List<String> = emptyList()
    private var keyboardUp = false
    private var systemInFront = false
    private var hidePackages: Set<String> = emptySet()
    private var lastSignature = ""

    // ---- Lifecycle ----------------------------------------------------------------

    override fun onServiceConnected() {
        wm = getSystemService(WindowManager::class.java)
        apps = Apps(this)
        hidePackages = homePackages() + "com.android.systemui"
        Prefs.of(this).registerOnSharedPreferenceChangeListener(this)
        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, main)
        running = true
        EventLog.add(this, "Taskbar started. Home app: ${apps.homePackage() ?: "unknown"}")
        scheduleScan()
    }

    override fun onDestroy() {
        running = false
        runCatching { Prefs.of(this).unregisterOnSharedPreferenceChangeListener(this) }
        runCatching { getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener) }
        clearAll()
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        apps.forget()
        lastSignature = ""
        scheduleScan()
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) { lastSignature = ""; scheduleScan() }
    }

    override fun onSharedPreferenceChanged(p: SharedPreferences?, key: String?) = scheduleRender()

    // ---- Watching the screen ------------------------------------------------------

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = scheduleScan()

    private val scan = Runnable { scanWindows(); render() }
    private val renderNow = Runnable { render() }

    private fun scheduleScan() { main.removeCallbacks(scan); main.postDelayed(scan, 60) }
    private fun scheduleRender() { main.removeCallbacks(renderNow); main.post(renderNow) }

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
        onScreen = found.distinct()
        // Oldest first, so the focused app ends up at the front of the recents
        onScreen.asReversed()
            .filter { it !in hidePackages && it != packageName && apps.isLaunchable(it) }
            .forEach { Prefs.pushRecent(this, it) }
    }

    private fun homePackages(): Set<String> =
        packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_ALL
        ).map { it.activityInfo.packageName }.toSet()

    private fun isMainScreen(): Boolean {
        val b = wm.currentWindowMetrics.bounds
        val d = resources.displayMetrics.density
        return minOf(b.width(), b.height()) / d >= 600
    }

    private fun shouldShow(): Boolean {
        val locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
        val front = onScreen.firstOrNull()
        return isMainScreen() && !locked && !keyboardUp && !systemInFront &&
            front != null && front !in hidePackages
    }

    // ---- Drawing the bar ----------------------------------------------------------

    private fun render() {
        if (!::wm.isInitialized) return
        val show = shouldShow()
        val tucked = Prefs.tucked(this)
        if (!show) {
            clearAll(); lastSignature = ""; return
        }
        if (tucked) {
            clearBar(); closePanels(); showHandle(); lastSignature = "tucked"; return
        }
        removeHandle()

        val shape = Prefs.shape(this)
        val finish = Prefs.finish(this)
        val iconDp = Prefs.iconDp(this)
        val bounds = wm.currentWindowMetrics.bounds
        val maxSlots = maxSlots(bounds.width(), iconDp, shape)

        val pinned = Prefs.pinned(this).filter { apps.isLaunchable(it) }
        val recents = Prefs.recents(this)
            .filter { it !in pinned && it !in hidePackages && it != packageName && apps.isLaunchable(it) }
            .take(Prefs.recentCount(this))
        val active = onScreen.toSet()
        fun app(p: String) = Slot.App(p, p in active)

        val groups: List<List<Slot>> = when (shape) {
            Shape.PAIR -> listOf(
                listOf(Slot.AllApps) + pinned.take(maxSlots - 1).map(::app),
                recents.take(maxSlots - 1).map(::app) + Slot.Tuck,
            )
            Shape.CAPSULE -> {
                val p = pinned.take((maxSlots - 2) / 2 + 1)
                val r = recents.take(maxSlots - 2 - p.size)
                listOf(
                    listOf(Slot.AllApps) + p.map(::app) +
                        (if (p.isNotEmpty() && r.isNotEmpty()) listOf(Slot.Divider) else emptyList()) +
                        r.map(::app) + Slot.Tuck
                )
            }
        }

        val signature = "$shape|$finish|$iconDp|${bounds.width()}x${bounds.height()}|$groups"
        if (signature == lastSignature && barWindows.isNotEmpty()) return
        lastSignature = signature

        clearBar()
        val bottom = navInset() + dp(6)
        groups.forEachIndexed { i, slots ->
            val capsule = Capsule(this, apps, finish, iconDp, this).apply { show(slots) }
            val host = shadowHost(capsule)
            val x = when {
                shape == Shape.PAIR && i == 0 -> -bounds.width() / 4
                shape == Shape.PAIR -> bounds.width() / 4
                else -> 0
            }
            add(host, params(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, x, bottom - dp(10)))
            barWindows += host
        }
    }

    /** How many icons fit in one capsule without crossing the crease or the screen edge. */
    private fun maxSlots(screenW: Int, iconDp: Int, shape: Shape): Int {
        val room = if (shape == Shape.PAIR) screenW / 2 - dp(40) else (screenW * 0.9f).toInt()
        val per = dp(iconDp) + dp(iconDp * 0.28f)
        return ((room - dp(iconDp * 0.64f)) / per).coerceAtLeast(3)
    }

    private fun shadowHost(child: View) = FrameLayout(this).apply {
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

    private fun clearBar() { barWindows.forEach(::remove); barWindows.clear() }
    private fun removeHandle() { remove(handleWindow); handleWindow = null }
    private fun closePanels() { closeMenu(); closeDrawer() }
    private fun clearAll() { clearBar(); removeHandle(); closePanels() }

    // ---- Tucked away --------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    private fun showHandle() {
        if (handleWindow != null) return
        val finish = Prefs.finish(this)
        val tab = View(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(finish.indicator); cornerRadius = dp(3).toFloat()
            }
        }
        val host = FrameLayout(this).apply {
            addView(tab, FrameLayout.LayoutParams(dp(5), dp(64), Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dp(3) })
            contentDescription = "Show taskbar"
            setOnClickListener { it.tick(); Prefs.setTucked(this@TaskbarService, false) }
        }
        add(host, params(Gravity.BOTTOM or Gravity.END, 0, navInset() + dp(96), w = dp(28), h = dp(88)))
        handleWindow = host
    }

    // ---- Actions ------------------------------------------------------------------

    override fun onApp(pkg: String, view: View) {
        closePanels()
        if (onScreen.size == 1 && onScreen.first() == pkg) return
        apps.open(pkg)
    }

    override fun onAppHeld(pkg: String, view: View) = openMenu(pkg, view)

    override fun onAllApps(view: View) {
        if (drawerWindow != null) { closeDrawer(); return }
        if (SystemClock.uptimeMillis() - drawerClosedAt < 350) return
        closeMenu()
        openDrawer()
    }

    override fun onTuck() {
        closePanels()
        Prefs.setTucked(this, true)
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
                scanWindows()
                if (onScreen.size >= 2) {
                    EventLog.add(this, "Split: $label beside $currentLabel (direct launch worked)")
                } else {
                    EventLog.add(this, "Split: direct launch ignored, switching to the split-screen shortcut")
                    p.edit().putString(SPLIT_METHOD, "toggle").apply()
                    toggleThenLaunch(current)
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
                scanWindows()
                EventLog.add(this, "Split shortcut ${if (ok) "accepted" else "refused"}; apps on screen: ${onScreen.size}")
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
            add((if (isPinned) "Unpin from taskbar" else "Pin to taskbar") to { closeMenu(); Prefs.togglePin(this@TaskbarService, pkg) })
            if (isPinned && pinned.indexOf(pkg) > 0) add("Move left" to { closeMenu(); Prefs.movePin(this@TaskbarService, pkg, -1) })
            if (isPinned && pinned.indexOf(pkg) < pinned.lastIndex) add("Move right" to { closeMenu(); Prefs.movePin(this@TaskbarService, pkg, 1) })
            if (!isPinned && pkg in Prefs.recents(this@TaskbarService)) add("Remove from recents" to { closeMenu(); Prefs.forgetRecent(this@TaskbarService, pkg) })
        }
        val menu = ActionMenu(this, finish, label, rows)
        val host = outsideCloses(shadowHost(menu)) { closeMenu() }
        host.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val loc = IntArray(2).also { anchor.getLocationOnScreen(it) }
        val screen = wm.currentWindowMetrics.bounds
        val x = (loc[0] + anchor.width / 2 - host.measuredWidth / 2).coerceIn(0, screen.width() - host.measuredWidth)
        val y = (loc[1] - host.measuredHeight - dp(4)).coerceAtLeast(0)
        add(host, params(Gravity.TOP or Gravity.START, x, y, watchOutside = true))
        menuWindow = host
    }

    private fun closeMenu() { remove(menuWindow); menuWindow = null }

    private fun openDrawer() {
        val screen: Rect = wm.currentWindowMetrics.bounds
        val w = minOf((screen.width() * 0.8f).toInt(), dp(760))
        val h = (screen.height() * 0.6f).toInt()
        val drawer = AppDrawer(
            this, apps, Prefs.finish(this), w,
            onOpen = { closePanels(); apps.open(it) },
            onHold = { pkg, v -> openMenu(pkg, v) },
        )
        val host = outsideCloses(shadowHost(drawer)) { closeDrawer() }
        val bottom = navInset() + dp(Prefs.iconDp(this) + 16 + 6 + 12)
        add(host, params(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, bottom, w = w + dp(20), h = h, watchOutside = true))
        drawerWindow = host
    }

    private fun closeDrawer() {
        if (drawerWindow != null) drawerClosedAt = SystemClock.uptimeMillis()
        remove(drawerWindow); drawerWindow = null
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun outsideCloses(v: View, close: () -> Unit): View = v.apply {
        setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_OUTSIDE) {
                // A tap on the open menu also counts as outside the drawer; leave the drawer open then
                if (menuWindow == null || v === menuWindow) close()
            }
            false
        }
    }

    companion object {
        @Volatile var running = false
        private const val SPLIT_METHOD = "split_method"
    }
}
