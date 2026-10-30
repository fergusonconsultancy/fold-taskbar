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
class TaskbarService : AccessibilityService(), CapsuleActions, DragListener, GridDrag,
    SharedPreferences.OnSharedPreferenceChangeListener, android.hardware.SensorEventListener {

    private lateinit var wm: WindowManager
    private lateinit var apps: Apps
    private val main = Handler(Looper.getMainLooper())
    private val worker = HandlerThread("taskbar-scan").apply { start() }
    private val bg = Handler(worker.looper)

    private val capsules = mutableListOf<Capsule>()
    private val hosts = mutableListOf<BarHost>()
    private var layoutKey = ""
    private var layoutLook = ""
    private var pendingKey: String? = null
    private var pendingKeyAt = 0L
    /** The screen the bar was laid out for: what every placement measures against, so a passing change cannot move it. */
    private var layoutBounds: Rect? = null
    private var barOn = false
    private var menuWindow: View? = null
    private lateinit var drawer: AppDrawer
    private lateinit var pour: Pour
    private var lastIconSummary: String? = null

    /** Picking the second app of a new pair from the grid. */
    private var pairingFirst: String? = null

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
    /** Left edge of each app's window, to tell which side of a split each app is on. */
    @Volatile private var onScreenLeft: Map<String, Int> = emptyMap()
    @Volatile private var onScreenBounds: Map<String, Rect> = emptyMap()
    @Volatile private var keyboardTop: Int? = null
    @Volatile private var systemInFront = false
    private var hidePackages: Set<String> = emptySet()
    private var noRecents: Set<String> = emptySet()

    /** Folding: true from the moment the hinge starts to close until the main screen is back. */
    @Volatile private var folding = false
    private lateinit var guide: DragGuide
    private var dragFromCurrent: String? = null

    /** Zoom's pictures of each app's last screen, kept in memory only, never saved. */
    private val snapshots = object : LinkedHashMap<String, Bitmap>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?) = size > 8
    }
    /** Each picture's soft copy, for Fuoco's focus pull and depth: made once, off the main thread. */
    private val softs = object : LinkedHashMap<String, Bitmap>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?) = size > 8
    }

    /** Where each picture's window was on screen when it was taken, so a switch lands exactly on it. */
    private val snapshotBounds = HashMap<String, Rect>()

    private var pendingOpen: String? = null
    private var pendingOpenAt = 0L
    private var dragFrom = IntArray(2)

    // ---- Lifecycle ----------------------------------------------------------------

    override fun onServiceConnected() {
        // If anything ever does take the service down, the reason is written to the event log
        // first, so it can be found and fixed rather than guessed at (29 Oct 2026)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val at = e.stackTrace.firstOrNull { it.className.startsWith("com.fergusonconsultancy") }
                EventLog.addNow(this, "Stopped by an error: ${e.javaClass.simpleName}: ${e.message?.take(120)} at ${at?.fileName}:${at?.lineNumber}")
                getSharedPreferences("fold_taskbar_health", MODE_PRIVATE).edit().putLong("crashed_at", System.currentTimeMillis()).commit()
            }
            previous?.uncaughtException(t, e)
        }
        wm = getSystemService(WindowManager::class.java)
        apps = Apps(this)
        Prefs.offerFanale(this)
        listenForScrolls()
        val filter = android.content.IntentFilter("com.fergusonconsultancy.foldmotion.REVEAL")
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(revealReceiver, filter, RECEIVER_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(revealReceiver, filter)
        // Locking and unlocking are system broadcasts: no flag needed, and nothing else sends them
        registerReceiver(lockReceiver, android.content.IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT)
        })
        preloadPictures()
        guide = DragGuide(this, wm)
        getSystemService(android.hardware.SensorManager::class.java)?.let { sm ->
            sm.getDefaultSensor(android.hardware.Sensor.TYPE_HINGE_ANGLE)?.let { sm.registerListener(this, it, android.hardware.SensorManager.SENSOR_DELAY_UI, main) }
        }
        pour = Pour(this, wm)
        drawer = AppDrawer(this, wm, apps, onOpenApp = { pkg, v -> fromGrid(pkg, v) }, onHoldApp = { pkg, v -> openMenu(pkg, v) }, drag = this)
        // The bar now stays on the home screen too; only system surfaces such as the
        // notification shade push it aside.
        hidePackages = setOf("com.android.systemui")
        noRecents = homePackages() + hidePackages
        Prefs.of(this).registerOnSharedPreferenceChangeListener(this)
        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, main)
        running = true
        instance = this
        apps.onPackagesChanged { warmApps(rebuildList = true) }
        warmApps(rebuildList = false)
        EventLog.add(this, "Taskbar started. Home app: ${apps.homePackage() ?: "unknown"}")
        useAccessibilityButton()
        runCatching { android.service.quicksettings.TileService.requestListeningState(this, android.content.ComponentName(this, TaskbarTile::class.java)) }
        scheduleScan()
    }

    // ---- Calling the bar back (29 Oct 2026) ------------------------------------------------
    // However the bar has gone from view (tucked into its tab, auto-hidden, receded), these
    // bring it back in one move: the Quick Settings tile, or Android's accessibility button or
    // gesture once it is assigned to Fold Taskbar. The tile also shows whether the bar is
    // actually running, so "has it stopped?" always has an answer.

    /** Brings the bar back into full view, from wherever it has gone, and says so in the log. */
    fun summon() {
        main.post {
            // From the lock screen's Quick Settings or the accessibility button: never while locked
            if (locked()) { EventLog.add(this, "Call ignored: the phone is locked"); return@post }
            val was = when {
                Prefs.collapsed(this) != null -> "its tab"
                Prefs.autoHide(this) && !revealed -> "auto-hide"
                receded -> "receding"
                !barOn -> "hidden (${hideReason()})"
                else -> "already in view"
            }
            if (Prefs.collapsed(this) != null) expand()
            returnFromRecede(gentle = false)
            if (Prefs.autoHide(this)) { revealed = true; scheduleAutoHide() }
            layoutKey = ""
            scheduleScan()
            apply()
            capsules.forEach { it.setResting(false) }
            scheduleRest()
            EventLog.add(this, "Called back from $was")
        }
    }

    /** Why the bar is not showing, in plain words, for the log. */
    private fun hideReason(): String = when {
        !isMainScreen() -> "not on the main screen"
        locked() -> "phone locked"
        systemInFront -> "a system panel is in front"
        onScreen.isEmpty() -> "no app seen on screen yet"
        else -> "front app ${onScreen.first()} is excluded"
    }

    private fun useAccessibilityButton() {
        runCatching {
            val info = serviceInfo ?: return
            info.flags = info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_ACCESSIBILITY_BUTTON
            serviceInfo = info
            accessibilityButtonController.registerAccessibilityButtonCallback(object : android.accessibilityservice.AccessibilityButtonController.AccessibilityButtonCallback() {
                override fun onClicked(controller: android.accessibilityservice.AccessibilityButtonController) = summon()
            })
        }
    }

    override fun onDestroy() {
        running = false
        instance = null
        runCatching { getSystemService(android.hardware.SensorManager::class.java)?.unregisterListener(this) }
        runCatching { Prefs.of(this).unregisterOnSharedPreferenceChangeListener(this) }
        runCatching { getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener) }
        runCatching { unregisterReceiver(revealReceiver) }
        runCatching { unregisterReceiver(lockReceiver) }
        runCatching { android.service.quicksettings.TileService.requestListeningState(this, android.content.ComponentName(this, TaskbarTile::class.java)) }
        removeBar()
        removeTab()
        closePanels()
        worker.quitSafely()
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    private var lastConfig: Configuration? = null

    /**
     * Opening an app often changes the configuration for a moment (One UI passes the new app's
     * window configuration around), and rebuilding the bar on every one of those made it flash
     * in the wrong place mid-switch (24 Oct 2026). Only changes that alter how the bar looks
     * (density, font scale, language, dark mode) rebuild it now; anything that
     * moves it waits until the screen has settled (see apply).
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val diff = lastConfig?.diff(newConfig) ?: -1
        lastConfig = Configuration(newConfig)
        val looks = android.content.pm.ActivityInfo.CONFIG_DENSITY or android.content.pm.ActivityInfo.CONFIG_FONT_SCALE or
            android.content.pm.ActivityInfo.CONFIG_LOCALE or android.content.pm.ActivityInfo.CONFIG_UI_MODE
        if (diff and looks != 0) {
            apps.forget()
            layoutKey = ""
            removeTab()
            warmApps(rebuildList = false)
        }
        scheduleScan()
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (::wm.isInitialized && !isMainScreen()) vanish()
            scheduleScan()
        }
    }

    override fun onSharedPreferenceChanged(p: SharedPreferences?, key: String?) {
        if (key == "presence") main.post { restIfTrim(); listenForScrolls() }
        if (key == Prefs.ICON_PACK) {
            // New icons everywhere: rebuild the bar and reload the grid
            layoutKey = ""
            warmApps(rebuildList = true)
        }
        main.removeCallbacks(applyNow)
        main.post(applyNow)
    }

    // ---- Watching the screen ------------------------------------------------------

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Scrolls are only listened for when the bar's presence is Recede, and never rescan the screen
        if (event?.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            if (event.packageName?.toString() == onScreen.firstOrNull()) main.post { recede() }
            return
        }
        scheduleScan()
    }

    // ---- Recede (25 Oct 2026): Concentrato for the bar --------------------------------------
    // While the app beneath is scrolled, the bar steps back: it fades to a faint presence and
    // eases a little towards its edge, so what you are reading has the screen. When the scrolling
    // stops it returns, unhurried. A touch on it brings it back at once.
    private var receded = false
    private val comeBack = Runnable { returnFromRecede(gentle = true) }

    // 30 Oct 2026: the bar receded on the scrolls a new app makes as it opens, so having picked
    // one app, it had stepped back before you could pick the next. Now it stays in full for a
    // chosen time after you last used it or an app arrived, and only then will a scroll (or,
    // if chosen, a tap elsewhere) send it back. Receded by a tap, it stays back until touched.
    @Volatile private var usedAt = 0L
    private var recededByTap = false

    private fun markUsed() { usedAt = SystemClock.uptimeMillis() }

    /** Whether the bar is still within its time in full. A new app always gets a moment to settle. */
    private fun stillInFull(): Boolean {
        val hold = maxOf(RECEDE_SETTLE_MS, Prefs.RECEDE_AFTER_MS[Prefs.recedeAfter(this)])
        return SystemClock.uptimeMillis() - usedAt < hold
    }

    private fun recede(byTap: Boolean = false) {
        if (Prefs.presence(this) != Presence.RECEDE || !barOn || (::drawer.isInitialized && drawer.isOpen) || menuWindow != null) return
        if (!receded && stillInFull()) return
        if (byTap) { recededByTap = true; main.removeCallbacks(comeBack) }
        else if (!recededByTap) { main.removeCallbacks(comeBack); main.postDelayed(comeBack, RECEDE_HOLD_MS) }
        if (receded) return
        receded = true
        hosts.forEach { h ->
            if (h.visibility != View.VISIBLE || h.tag != null) return@forEach
            h.animate().cancel()
            h.animate().alpha(0.22f).scaleX(0.95f).scaleY(0.95f).setStartDelay(0).setDuration(240).setInterpolator(Curves.OPEN).start()
        }
    }

    private fun returnFromRecede(gentle: Boolean) {
        main.removeCallbacks(comeBack)
        if (!receded) return
        receded = false
        recededByTap = false
        hosts.forEach { h ->
            if (h.visibility != View.VISIBLE || h.tag != null) return@forEach
            h.animate().cancel()
            h.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(if (gentle) 460 else 160)
                .setInterpolator(android.view.animation.PathInterpolator(0.25f, 0.1f, 0.25f, 1f)).start()
        }
    }

    /** Listens for scrolls only while the presence is Recede, so no other presence pays for them. */
    private fun listenForScrolls() {
        val info = serviceInfo ?: return
        val want = Prefs.presence(this) == Presence.RECEDE
        val has = info.eventTypes and AccessibilityEvent.TYPE_VIEW_SCROLLED != 0
        if (want == has) return
        info.eventTypes = if (want) info.eventTypes or AccessibilityEvent.TYPE_VIEW_SCROLLED else info.eventTypes and AccessibilityEvent.TYPE_VIEW_SCROLLED.inv()
        serviceInfo = info
        if (!want) returnFromRecede(gentle = false)
    }

    private val scan = Runnable {
        runCatching { scanWindows() }.onFailure { EventLog.add(this, "Recovered from ${it.javaClass.simpleName} while reading the screen") }
        main.post(applyNow)
    }
    private val applyNow = Runnable {
        // One bad moment must never take the whole bar down: log it and carry on
        runCatching { apply() }.onFailure { EventLog.add(this, "Recovered from ${it.javaClass.simpleName} while laying out the bar: ${it.message?.take(100)}") }
    }

    private fun scheduleScan() {
        bg.removeCallbacks(scan)
        bg.postDelayed(scan, 40)
    }

    /** Runs on the worker thread. */
    private fun scanWindows() {
        val list = runCatching { windows }.getOrDefault(emptyList())
        val found = mutableListOf<String>()
        val lefts = HashMap<String, Int>()
        val boxes = HashMap<String, Rect>()
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
                    w.getBoundsInScreen(r); lefts[pkg] = r.left; boxes[pkg] = Rect(r)
                }
            }
        }
        keyboardTop = imeTop
        systemInFront = system
        // An empty list is a transition in progress, not a real state. Keep the last one.
        if (found.isNotEmpty()) {
            if (found.first() != onScreen.firstOrNull()) markUsed()
            onScreen = found.distinct(); onScreenLeft = lefts; onScreenBounds = boxes
        }
    }

    private fun homePackages(): Set<String> =
        packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_ALL
        ).map { it.activityInfo.packageName }.toSet()

    /**
     * Asks the display itself, not this service's copy of its size, which can lag the
     * switch to the cover screen by seconds.
     */
    private fun isMainScreen(): Boolean {
        if (folding) return false
        val d = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val m = android.util.DisplayMetrics()
        @Suppress("DEPRECATION") d?.getRealMetrics(m)
        if (m.widthPixels == 0) {
            val b = wm.currentWindowMetrics.bounds
            return minOf(b.width(), b.height()) / resources.displayMetrics.density >= 600
        }
        return minOf(m.widthPixels, m.heightPixels) / m.density >= 600
    }

    /**
     * The hinge. The bar leaves the instant the phone starts to close, before the cover
     * screen even wakes, and comes back once it is open and the main screen is on.
     */
    override fun onSensorChanged(event: android.hardware.SensorEvent) {
        val angle = event.values.firstOrNull() ?: return
        val closing = angle < 150f
        if (closing && !folding) {
            folding = true
            vanish()
        } else if (!closing && folding) {
            folding = false
            scheduleScan()
        }
    }

    override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) = Unit

    /** Gone this frame: no fade, no panels, no tab left behind on either screen. */
    private fun vanish() {
        main.removeCallbacks(hideNow)
        barOn = false
        arriving = true
        hosts.forEach { Tuning.cancel(it); it.animate().cancel(); it.tag = null; it.visibility = View.GONE; it.alpha = 1f; it.scaleX = 1f; it.scaleY = 1f }
        closeMenu()
        if (::drawer.isInitialized) drawer.close(animated = false)
        if (::pour.isInitialized) pour.cancel()
        guide.end()
        removeStrips()
        removeTab()
        revealed = false
    }

    private fun shouldShow(): Boolean {
        val front = onScreen.firstOrNull()
        return isMainScreen() && !locked() && !systemInFront && front != null && front !in hidePackages
    }

    /**
     * Locked, in every form (30 Oct 2026): the screen off, the lock screen showing, or the
     * phone still locked beneath something shown over the lock screen (the camera from the
     * lock screen, a call). Whatever its presence, the bar is never there while any holds.
     */
    private fun locked(): Boolean {
        val power = getSystemService(android.os.PowerManager::class.java)
        val keyguard = getSystemService(KeyguardManager::class.java)
        return !power.isInteractive || keyguard.isKeyguardLocked || keyguard.isDeviceLocked
    }

    /**
     * The bar used to learn of a lock only when the screen next changed, and then waited a
     * moment to be sure, so it could linger on the lock screen. Now locking takes it away in
     * the same frame, like folding, and unlocking brings it back with its soft entrance.
     */
    private val lockReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context?, i: Intent?) {
            when (i?.action) {
                Intent.ACTION_SCREEN_OFF -> if (::wm.isInitialized) vanish()
                Intent.ACTION_SCREEN_ON -> if (::wm.isInitialized && locked()) vanish()
                Intent.ACTION_USER_PRESENT -> scheduleScan()
            }
        }
    }

    // ---- Drawing the bar ----------------------------------------------------------

    private val hideNow = Runnable { setBarOn(false) }

    /** Main thread. Cheap when nothing has changed. */
    private fun apply() {
        if (!::wm.isInitialized) return
        recordRecents()
        reportOpenTime()
        sampleSoon()

        if (!isMainScreen() || locked()) { vanish(); return }
        if (!shouldShow()) {
            // Anything other than folding waits a moment to be sure.
            val delay = 220L
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
        val bounds = liveBounds()
        val geometry = "${bounds.width()}x${bounds.height()}|${resources.configuration.densityDpi}"
        val profile = Prefs.profile(this)
        val key = "$shape|$finish|$iconDp|$profile|$geometry"
        if (key != layoutKey || hosts.isEmpty()) {
            val onlyGeometry = hosts.isNotEmpty() && layoutLook == "$shape|$finish|$iconDp|$profile"
            val now = SystemClock.uptimeMillis()
            // Waiting only protects a bar that is on screen; one arriving after an unfold has
            // nothing to protect, so it is laid out at once
            if (onlyGeometry && barOn && (pendingKey != key || now - pendingKeyAt < SETTLE_MS)) {
                // The screen changed shape or insets: wait until it holds still before moving the
                // bar, so a passing change during an app switch never shows (24 Oct 2026)
                if (pendingKey != key) { pendingKey = key; pendingKeyAt = now }
                main.removeCallbacks(applyNow); main.postDelayed(applyNow, SETTLE_MS)
            } else {
                pendingKey = null
                removeBar()
                layoutBounds = Rect(bounds)
                buildBar(shape, finish, iconDp)
                layoutKey = key
                layoutLook = "$shape|$finish|$iconDp|$profile"
                if (onlyGeometry) EventLog.add(this, "Screen settled at $geometry: bar laid out again")
            }
        } else pendingKey = null

        val groups = slotGroups(shape, iconDp, bounds.width())
        val active = onScreen.toSet() + Prefs.pairs(this)
            .filter { (l, r) -> l in onScreen && r in onScreen }
            .map { (l, r) -> Slot.AppPair(l, r).key }
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

    // ---- The entrance (25 Oct 2026) -----------------------------------------------------
    // After an unfold the bar does not simply appear: once the screen is whole (Fold Motion
    // tells us when its reveal ends; without it, at once) the bar rises a little way from the
    // edge it rests against, its glass coming into view on a long, soft ease, and its icons
    // settle in from the middle outwards. Unhurried, never bouncing: an invitation.

    /** Set when the bar vanished for a fold; the next arrival is an entrance. */
    private var arriving = false
    /** True while an entrance is being played by showHost. */
    private var entering = false
    /** Until when Fold Motion is covering the screen with its opening (0 when it is not). */
    private var coveredUntil = 0L

    private val revealReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context?, i: Intent?) {
            when (i?.getStringExtra("phase")) {
                "covering" -> {
                    coveredUntil = SystemClock.uptimeMillis() + COVER_FAILSAFE_MS
                    main.removeCallbacks(coverEnded); main.postDelayed(coverEnded, COVER_FAILSAFE_MS)
                }
                "revealed" -> { main.removeCallbacks(coverEnded); coverEnded.run() }
            }
        }
    }
    private val coverEnded = Runnable { coveredUntil = 0L; main.removeCallbacks(applyNow); main.post(applyNow) }

    private fun covered() = SystemClock.uptimeMillis() < coveredUntil

    private fun entrance(host: BarHost) {
        val i = hosts.indexOf(host)
        val spot = spotFor(Prefs.shape(this), i.coerceAtLeast(0))
        val rise = dp(16).toFloat()
        // From the edge it rests against: up from the bottom, down from the top, in from a side
        val (dx, dy) = if (spot.vertical) (if (spot.fx < 0.5f) -rise else rise) to 0f
            else 0f to (if (spot.fy < 0.4f) -rise else rise)
        Tuning.cancel(host)
        host.alpha = 0f
        host.translationX = dx; host.translationY = dy
        host.scaleX = 0.97f; host.scaleY = 0.97f
        host.animate().alpha(1f).translationX(0f).translationY(0f).scaleX(1f).scaleY(1f)
            .setStartDelay(0).setDuration(ENTRANCE_MS)
            .setInterpolator(android.view.animation.PathInterpolator(0.25f, 0.1f, 0.25f, 1f)).start()
        capsules.getOrNull(i)?.enter(120, dx, dy)
    }

    private fun setBarOn(on: Boolean) {
        // Arriving after a fold: hold the bar back while Fold Motion's reveal covers the screen,
        // then make the entrance
        if (on && arriving && covered()) {
            barOn = true
            hosts.forEach { Tuning.cancel(it); it.animate().cancel(); it.visibility = View.GONE }
            return
        }
        entering = on && arriving
        if (entering) arriving = false
        hosts.forEachIndexed { i, host ->
            val want = hostWanted(i)
            if (want && host.visibility != View.VISIBLE) place(i)
            showHost(host, want)
        }
        entering = false
        if (!on) closePanels()
    }

    /** Whether the bar at [host] stands at a side, and whether it rests against the top or left edge. */
    private fun restingEdge(host: BarHost): Pair<Boolean, Boolean> {
        val spot = spotFor(Prefs.shape(this), hosts.indexOf(host).coerceAtLeast(0))
        return spot.vertical to (if (spot.vertical) spot.fx < 0.5f else spot.fy < 0.4f)
    }

    private fun showHost(host: BarHost, show: Boolean) {
        val shown = host.visibility == View.VISIBLE && host.tag != "leaving"
        if (show == shown) return
        host.animate().cancel()
        Tuning.cancel(host)
        val toward = morphTarget
        if (show) {
            host.tag = null
            host.visibility = View.VISIBLE
            if (entering) {
                entrance(host)
            } else if (toward != null) {
                // Expanding: the capsule grows back out of the tab
                val (tx, ty) = offsetTo(host, toward)
                host.translationX = tx * 0.7f; host.translationY = ty * 0.7f
                host.scaleX = 0.3f; host.scaleY = 0.3f; host.alpha = 0f
                host.animate().translationX(0f).translationY(0f).scaleX(1f).scaleY(1f).alpha(1f)
                    .setDuration(Curves.OPEN_MS).setInterpolator(Curves.OPEN).start()
            } else {
                // Advanced tuning (30 Oct 2026): from auto-hide, a recall or switching on, the bar
                // rises out of the edge it rests against on the tuned spring
                val (vertical, nearStart) = restingEdge(host)
                Tuning.show(host, vertical, nearStart)
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
                val (vertical, nearStart) = restingEdge(host)
                Tuning.hide(host, vertical, nearStart, end)
            }
        }
    }

    private fun offsetTo(host: View, target: Pair<Float, Float>): Pair<Float, Float> {
        val lp = host.layoutParams as WindowManager.LayoutParams
        return (target.first - (lp.x + host.width / 2f)) to (target.second - (lp.y + host.height / 2f))
    }

    private fun buildBar(shape: Shape, finish: Finish, iconDp: Int) {
        // The switch animation lives under the bar: the bar is the constant across apps, never
        // dimmed, blurred or covered while the next app arrives beneath it
        pour.ensure()
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
            // Watches touches outside it, for the infinite holding time
            add(host, params(Gravity.TOP or Gravity.START, 0, 0, watchOutside = true))
            host.setOnDragListener(dragEnd)
            capsules += capsule
            hosts += host
        }
        main.postDelayed(restNow, 1200)
        attachDrawer()
    }

    // ---- Where the bar sits -------------------------------------------------------

    private fun screen(): Rect = layoutBounds?.takeIf { hosts.isNotEmpty() } ?: liveBounds()

    /**
     * The main screen's size, read from the display itself (25 Oct 2026). This service's own
     * window metrics can lag an unfold by seconds; laying the bar out from them meant waiting,
     * or laying it out for the cover screen and then again for the main one.
     */
    private fun liveBounds(): Rect {
        val d = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val m = android.util.DisplayMetrics()
        @Suppress("DEPRECATION") d?.getRealMetrics(m)
        return if (m.widthPixels > 0) Rect(0, 0, m.widthPixels, m.heightPixels) else wm.currentWindowMetrics.bounds
    }
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

    private fun creaseAcross(): Boolean = runCatching {
        val r = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).rotation
        r == android.view.Surface.ROTATION_90 || r == android.view.Surface.ROTATION_270
    }.getOrDefault(false)

    /** On a flat bar, the crease is a magnet: centre on it, or on either half for the pair. */
    private fun creaseSnap(shape: Shape, i: Int, cx: Float): Float {
        // The crease runs down the inner screen held upright; on its side it runs across, and a
        // flat bar has no crease along it to centre on (29 Oct 2026)
        if (creaseAcross()) return cx
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
                        sampleSoon(120)
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
        sampleSoon(120)
    }

    private fun removeTab() {
        remove(tab); tab = null
    }

    // ---- Reading the screen behind the bar ------------------------------------------
    //
    // One screenshot serves three needs: the tone of smoked glass behind each capsule, the
    // tone of the collapsed tab, and Zoom's picture of the app you are in. Only small
    // regions are read; the screenshot is thrown away at once, and only Zoom's reduced
    // picture is kept, in memory. It runs after anything changes on screen and every two
    // seconds while there is something to adapt, so scrolling a page re-tunes the glass.

    private var lastShot = 0L
    private var ticking = false
    private var lastFront: String? = null
    private var frontSince = 0L
    private val snapshotAt = HashMap<String, Long>()
    private var loggedShotFailure = false

    private val sampleOnce = Runnable { sampleNow() }
    private val sampleTick = object : Runnable {
        override fun run() {
            if (!needsSampling()) { ticking = false; return }
            sampleNow()
            main.postDelayed(this, SAMPLE_EVERY_MS)
        }
    }

    /** Something changed on screen: read it again shortly, and keep reading while needed. */
    private fun sampleSoon(delay: Long = 350) {
        val front = onScreen.firstOrNull()
        if (front != lastFront) { lastFront = front; frontSince = SystemClock.uptimeMillis() }
        main.removeCallbacks(sampleOnce)
        main.postDelayed(sampleOnce, delay)
        if (!ticking && needsSampling()) { ticking = true; main.postDelayed(sampleTick, SAMPLE_EVERY_MS) }
    }

    private fun adaptiveGlass() = Prefs.finish(this).adaptive

    private fun needsSampling(): Boolean {
        if (folding || !::wm.isInitialized || !isMainScreen()) return false
        val barShowing = hosts.any { it.visibility == View.VISIBLE && it.tag != "leaving" }
        return (barShowing && adaptiveGlass()) || tab != null || snapshotWanted() != null
    }

    /** The app to picture for Zoom: alone on screen for a moment, not pictured recently. */
    private fun snapshotWanted(): String? {
        if (Prefs.motion(this).look == null) return null
        val front = onScreen.singleOrNull()?.takeIf { it !in noRecents && it != packageName } ?: return null
        val now = SystemClock.uptimeMillis()
        if (now - frontSince < 900) return null
        return if (now - (snapshotAt[front] ?: 0L) > 15_000) front else null
    }

    private fun sampleNow() {
        if (!needsSampling()) return
        val now = SystemClock.uptimeMillis()
        if (now - lastShot < 450) return
        lastShot = now

        // Decide what to read while the bar's layout is known, on this thread
        val pad = dp(10)
        val capsuleRects = if (adaptiveGlass()) hosts.mapIndexedNotNull { i, h ->
            val lp = h.layoutParams as? WindowManager.LayoutParams ?: return@mapIndexedNotNull null
            if (h.visibility != View.VISIBLE) null
            else i to Rect(lp.x + pad, lp.y + pad, lp.x + h.width - pad, lp.y + h.height - pad)
        } else emptyList()
        val tabR = Prefs.collapsed(this)?.takeIf { tab != null }?.let { tabRect(it.first, it.second) }
        val wanted = snapshotWanted()
        // 24 Oct 2026: a full-screen shot pictures the bar too, wherever it was at the time, so
        // a switch could show the bar in an old place inside the arriving app. On Android 14 and
        // later the app is pictured on its own, from its window alone, with nothing of ours in it.
        if (wanted != null && android.os.Build.VERSION.SDK_INT >= 34 && pictureWindow(wanted)) return
        val snapPkg = if (android.os.Build.VERSION.SDK_INT >= 34) null else wanted
        val ring = dp(28)
        val step = dp(4).coerceAtLeast(1)

        runCatching {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val hb = result.hardwareBuffer
                    bg.post {
                        runCatching {
                            val full = Bitmap.wrapHardwareBuffer(hb, result.colorSpace) ?: return@runCatching
                            val soft = full.copy(Bitmap.Config.ARGB_8888, false)
                            full.recycle()
                            val tones = capsuleRects.map { (i, r) -> i to zonesAround(soft, r, ring, step) }
                            val tabTone = tabR?.let { averageAround(soft, it, ring, step) }
                            snapPkg?.let { pkg -> storePicture(pkg, soft, null) }
                            soft.recycle()
                            main.post {
                                tones.forEach { (i, z) -> capsules.getOrNull(i)?.adaptTo(z) }
                                tabTone?.let { (_, lum) -> tab?.setBackdropLight(lum > 0.5f) }
                            }
                        }
                        hb.close()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    if (!loggedShotFailure) EventLog.add(this@TaskbarService, "Screen reading refused (code $errorCode): glass and tab keep their default tones")
                    loggedShotFailure = true
                }
            })
        }.onFailure {
            if (!loggedShotFailure) EventLog.add(this, "Screen reading unavailable (${it.javaClass.simpleName}): turn Fold Taskbar off and on in Accessibility to grant it")
            loggedShotFailure = true
        }
    }

    /**
     * Pictures survive a restart (25 Oct 2026): kept as small JPEGs in the app's private cache,
     * which no other app can read and which Android clears on uninstall. Without this, every
     * update or reboot left the bar with no pictures, and the first switch to each app had
     * nothing to show. Blank (secure) screens are never kept, as before.
     */
    private fun snapshotFile(pkg: String) = java.io.File(java.io.File(cacheDir, "switch").apply { mkdirs() }, "$pkg.jpg")

    /**
     * 25 Oct 2026: pictures were kept 540 pixels wide and stretched across a screen four times
     * that, so every switch looked soft until the live app took over. Now they are kept at three
     * quarters of the screen's own resolution, as hardware bitmaps (they live in graphics memory
     * and draw on the first frame with no upload, so sharpness costs the motion nothing), with
     * a small soft copy made here, once, for Fuoco. Runs on the worker thread; [full] is not kept.
     */
    private fun storePicture(pkg: String, full: Bitmap, bounds: Rect?) {
        runCatching {
            val w = (full.width * 0.75f).toInt().coerceAtLeast(1)
            val h = (full.height * 0.75f).toInt().coerceAtLeast(1)
            val sharp = Bitmap.createScaledBitmap(full, w, h, true)
            // A private screen (banking, passwords) comes back black: never keep those
            if (isBlank(sharp)) { if (sharp !== full) sharp.recycle(); return }
            keepSnapshot(pkg, sharp, bounds)
            val hw = sharp.copy(Bitmap.Config.HARDWARE, false) ?: sharp
            val soft = softCopy(sharp)
            if (hw !== sharp && sharp !== full) sharp.recycle()
            main.post {
                snapshots[pkg] = hw
                soft?.let { softs[pkg] = it }
                if (bounds != null) snapshotBounds[pkg] = bounds else snapshotBounds.remove(pkg)
                snapshotAt[pkg] = SystemClock.uptimeMillis()
            }
        }
    }

    /** A soft copy of [b]: shrunk hard and drawn back up with filtering, as a hardware bitmap. */
    private fun softCopy(b: Bitmap): Bitmap? = runCatching {
        val small = Bitmap.createScaledBitmap(b, (b.width / 14).coerceAtLeast(2), (b.height / 14).coerceAtLeast(2), true)
        val back = Bitmap.createScaledBitmap(small, (b.width / 3).coerceAtLeast(2), (b.height / 3).coerceAtLeast(2), true)
        small.recycle()
        back.copy(Bitmap.Config.HARDWARE, false)?.also { back.recycle() } ?: back
    }.getOrNull()

    /** Loads the kept pictures once at start, off the main thread, so no switch waits on a decode. */
    private fun preloadPictures() {
        bg.post {
            val files = java.io.File(cacheDir, "switch").listFiles()?.sortedByDescending { it.lastModified() }?.take(8) ?: return@post
            val prefs = getSharedPreferences("switch_pictures", MODE_PRIVATE)
            for (f in files.reversed()) runCatching {
                val pkg = f.name.removeSuffix(".jpg")
                val sw = android.graphics.BitmapFactory.decodeFile(f.path) ?: return@runCatching
                val hw = sw.copy(Bitmap.Config.HARDWARE, false) ?: sw
                val soft = softCopy(sw)
                if (hw !== sw) sw.recycle()
                val bounds = prefs.getString(pkg, null)?.let { Rect.unflattenFromString(it) }
                main.post {
                    if (pkg !in snapshots) { snapshots[pkg] = hw; soft?.let { softs[pkg] = it }; bounds?.let { snapshotBounds[pkg] = it } }
                }
            }
        }
    }

    private fun keepSnapshot(pkg: String, bmp: Bitmap, bounds: Rect?) {
        runCatching {
            snapshotFile(pkg).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            getSharedPreferences("switch_pictures", MODE_PRIVATE).edit()
                .putString(pkg, bounds?.flattenToString()).apply()
        }
    }

    private fun loadSnapshot(pkg: String): Bitmap? = runCatching {
        val f = snapshotFile(pkg)
        if (!f.exists()) return null
        val b = android.graphics.BitmapFactory.decodeFile(f.path) ?: return null
        snapshots[pkg] = b
        getSharedPreferences("switch_pictures", MODE_PRIVATE).getString(pkg, null)?.let { Rect.unflattenFromString(it) }?.let { snapshotBounds[pkg] = it }
        b
    }.getOrNull()

    /** Pictures [pkg] from its own window (Android 14 and later), so nothing of the bar is in it. */
    private fun pictureWindow(pkg: String): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 34) return false
        val win = runCatching { windows }.getOrDefault(emptyList()).firstOrNull { w ->
            w.type == AccessibilityWindowInfo.TYPE_APPLICATION && runCatching { w.root?.packageName?.toString() }.getOrNull() == pkg
        } ?: return false
        val bounds = Rect().also { win.getBoundsInScreen(it) }
        return runCatching {
            takeScreenshotOfWindow(win.id, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val hb = result.hardwareBuffer
                    bg.post {
                        runCatching {
                            val full = Bitmap.wrapHardwareBuffer(hb, result.colorSpace) ?: return@runCatching
                            // A hardware bitmap cannot be drawn in software: copy it first, then reduce
                            val soft = full.copy(Bitmap.Config.ARGB_8888, false)
                            full.recycle()
                            storePicture(pkg, soft, bounds)
                            soft.recycle()
                        }
                        hb.close()
                    }
                }
                override fun onFailure(errorCode: Int) = Unit
            })
            true
        }.getOrDefault(false)
    }

    /**
     * The colour behind each stretch of a capsule: the capsule is cut into [ZONES] equal
     * slices along its length, and for each slice the screen just beyond its two long
     * edges is averaged. So each part of the bar answers to what is right next to it.
     */
    private fun zonesAround(b: Bitmap, r: Rect, ring: Int, step: Int): List<Pair<Int, Float>?> {
        val upright = r.height() > r.width()
        return (0 until ZONES).map { z ->
            val slice = if (upright) {
                val t = r.top + r.height() * z / ZONES; val btm = r.top + r.height() * (z + 1) / ZONES
                Rect(r.left - ring, t, r.right + ring, btm)
            } else {
                val l = r.left + r.width() * z / ZONES; val rt = r.left + r.width() * (z + 1) / ZONES
                Rect(l, r.top - ring, rt, r.bottom + ring)
            }
            averageIn(b, slice, r, step)
        }
    }

    private fun averageIn(b: Bitmap, area: Rect, skip: Rect, step: Int): Pair<Int, Float>? {
        if (!area.intersect(0, 0, b.width, b.height)) return null
        var rs = 0L; var gs = 0L; var bs = 0L; var n = 0
        var y = area.top
        while (y < area.bottom) {
            var x = area.left
            while (x < area.right) {
                if (!skip.contains(x, y)) {
                    val p = b.getPixel(x, y)
                    rs += (p shr 16) and 255; gs += (p shr 8) and 255; bs += p and 255; n++
                }
                x += step
            }
            y += step
        }
        if (n == 0) return null
        val rr = (rs / n).toInt(); val gg = (gs / n).toInt(); val bb = (bs / n).toInt()
        return ((rr shl 16) or (gg shl 8) or bb) to (0.2126f * rr + 0.7152f * gg + 0.0722f * bb) / 255f
    }

    /** Average colour and brightness of the band around [r], not including [r] itself. */
    private fun averageAround(b: Bitmap, r: Rect, ring: Int, step: Int): Pair<Int, Float>? {
        val area = Rect(r.left - ring, r.top - ring, r.right + ring, r.bottom + ring)
        if (!area.intersect(0, 0, b.width, b.height)) return null
        var rs = 0L; var gs = 0L; var bs = 0L; var n = 0
        var y = area.top
        while (y < area.bottom) {
            var x = area.left
            while (x < area.right) {
                if (!r.contains(x, y)) {
                    val p = b.getPixel(x, y)
                    rs += (p shr 16) and 255; gs += (p shr 8) and 255; bs += p and 255; n++
                }
                x += step
            }
            y += step
        }
        if (n == 0) return null
        val rr = (rs / n).toInt(); val gg = (gs / n).toInt(); val bb = (bs / n).toInt()
        val lum = (0.2126f * rr + 0.7152f * gg + 0.0722f * bb) / 255f
        return ((rr shl 16) or (gg shl 8) or bb) to lum
    }

    private fun isBlank(b: Bitmap): Boolean {
        var sum = 0L
        var n = 0
        var y = 0
        while (y < b.height) {
            var x = 0
            while (x < b.width) {
                val p = b.getPixel(x, y)
                sum += ((p shr 16) and 255) + ((p shr 8) and 255) + (p and 255)
                n++
                x += 24
            }
            y += 24
        }
        return n == 0 || sum / (n * 3) < 6
    }

    // ---- Auto-hide ----------------------------------------------------------------

    private val autoHideNow = Runnable {
        if (drawer.isOpen || menuWindow != null || hosts.any { it.dragging }) { scheduleAutoHide(); return@Runnable }
        revealed = false
        apply()
    }

    private fun scheduleAutoHide() {
        main.removeCallbacks(autoHideNow)
        // Advanced tuning: the holding time. Infinite keeps the bar until a touch lands outside it
        val hold = Tuning.holdMs(this) ?: return
        if (Prefs.autoHide(this)) main.postDelayed(autoHideNow, hold)
    }

    /** A touch landed outside the bar. With an infinite holding time, that is what hides it. */
    override fun onOutside() {
        // Every capsule hears a touch outside itself, including a touch on the other capsule of a
        // pair. So the touch is judged a moment later: if it landed on the bar after all, it is
        // a use of the bar, not a tap elsewhere.
        val at = SystemClock.uptimeMillis()
        main.removeCallbacks(outsideNow)
        outsideAt = at
        main.postDelayed(outsideNow, 60)
    }

    private var outsideAt = 0L
    private val outsideNow = Runnable {
        if (usedAt >= outsideAt - 20) return@Runnable
        // Recede: a tap elsewhere sends the bar back, once its time in full is over
        if (Prefs.presence(this) == Presence.RECEDE && Prefs.recedeOnTap(this)) recede(byTap = true)
        // Auto-hide with an infinite holding time: a tap elsewhere is what hides it
        if (!Prefs.autoHide(this) || !revealed || Tuning.holdMs(this) != null) return@Runnable
        if (drawer.isOpen || menuWindow != null || hosts.any { it.dragging }) return@Runnable
        revealed = false
        apply()
    }

    // Advanced tuning: the reaction delay, held once per swipe however many moves it reports
    private var revealQueued = false
    private var revealFrom: View? = null
    private val revealQueuedNow = Runnable { revealQueued = false; if (!revealed) { revealFrom?.tick(); reveal() }; revealFrom = null }
    private fun revealSoon(from: View) {
        if (revealed || revealQueued) return
        revealFrom = from
        val d = Tuning.delayMs(this)
        if (d <= 0) { revealQueuedNow.run(); return }
        revealQueued = true
        main.postDelayed(revealQueuedNow, d)
    }

    private fun reveal() {
        revealed = true
        apply()
        scheduleAutoHide()
    }

    /** Any touch on the bar keeps it up for a few more seconds. */
    private fun onBarTouched() {
        markUsed()
        returnFromRecede(gentle = false)
        if (Prefs.autoHide(this) && revealed) scheduleAutoHide()
        scheduleRest()
    }

    /** Invisible strips over each hidden capsule's footprint. Swipe or tap one to bring the bar back. */
    @SuppressLint("ClickableViewAccessibility")
    private fun showStrips() {
        // 30 Oct 2026, calling the bar back was hit and miss. Three causes, all fixed here:
        // the strips were laid where the capsule's window last was, which after a rebuild while
        // hidden was the corner of the screen; once made they never moved with the bar; and each
        // covered only the icons, so a swipe begun a little below them missed. Now each capsule
        // is placed first, the strips are rebuilt whenever their place changes, and each reaches
        // from just past the bar to the edge it rests against, stopping short of the system's
        // own gesture areas so home and back still work.
        val s = screen()
        val boxes = hosts.indices.mapNotNull { i ->
            val host = hosts[i]
            place(i)
            val lp = host.layoutParams as? WindowManager.LayoutParams ?: return@mapNotNull null
            host.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            val w = host.measuredWidth.takeIf { it > 0 } ?: host.width
            val h = host.measuredHeight.takeIf { it > 0 } ?: host.height
            if (w <= 0 || h <= 0) return@mapNotNull null
            val pad = dp(10)
            val r = Rect(lp.x + pad, lp.y + pad, lp.x + w - pad, lp.y + h - pad)
            val (vertical, nearStart) = restingEdge(host)
            val reach = dp(20); val inward = dp(16); val edgeSafe = dp(24)
            if (!vertical) {
                r.left -= reach; r.right += reach
                if (nearStart) { r.top = statusInset(); r.bottom += inward } else { r.top -= inward; r.bottom = s.height() - navInset() }
            } else {
                r.top -= reach; r.bottom += reach
                if (nearStart) { r.left = edgeSafe; r.right += inward } else { r.left -= inward; r.right = s.width() - edgeSafe }
            }
            r.intersect(0, statusInset(), s.width(), s.height() - navInset())
            r.takeIf { it.width() > 0 && it.height() > 0 }
        }
        val key = boxes.joinToString("|") { it.flattenToString() }
        if (key == stripKey && stripWindows.isNotEmpty()) return
        removeStrips()
        stripKey = key
        boxes.forEach { r ->
            val strip = View(this).apply {
                contentDescription = "Show taskbar"
                var downX = 0f; var downY = 0f
                setOnTouchListener { _, e ->
                    when (e.actionMasked) {
                        MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY }
                        // Advanced tuning: call sensitivity sets how far the swipe must travel
                        MotionEvent.ACTION_MOVE -> if (abs(downY - e.rawY) + abs(downX - e.rawX) > dp(Tuning.callThresholdDp(this@TaskbarService))) revealSoon(this)
                        MotionEvent.ACTION_UP -> revealSoon(this)
                    }
                    true
                }
            }
            add(strip, params(Gravity.TOP or Gravity.START, r.left, r.top, w = r.width(), h = r.height()))
            stripWindows += strip
        }
    }

    private var stripKey = ""

    private fun removeStrips() { stripWindows.forEach(::remove); stripWindows.clear(); stripKey = "" }

    // ---- Contents -----------------------------------------------------------------

    private fun recordRecents() {
        val eligible = { p: String -> p !in noRecents && p != packageName && p !in Prefs.pinned(this) && apps.isLaunchable(p) }
        val front = onScreen.firstOrNull() ?: return
        if (front in noRecents || front == packageName || !apps.isLaunchable(front)) return
        Prefs.pushRecent(this, front)
        if (front !in Prefs.pinned(this)) Prefs.trayAdd(this, front, Prefs.recentCount(this), eligible)
    }

    /**
     * Pictures the screen being left: every app window on screen (never the bar or the grid),
     * composed at half size in their stacking order. Calls [done] once, on the main thread,
     * within 140ms at most; with no picture (older Android, or refused) it passes nulls.
     */
    private var backdropSoft: Bitmap? = null

    private fun captureLeaving(done: (Bitmap?, Rect?) -> Unit) {
        var finished = false
        fun finish(b: Bitmap?, r: Rect?) { if (finished) { b?.recycle(); return }; finished = true; done(b, r) }
        val area = Rect(screen())
        if (android.os.Build.VERSION.SDK_INT < 34) { finish(null, null); return }
        val wins = runCatching { windows }.getOrDefault(emptyList())
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .sortedBy { it.layer }
        if (wins.isEmpty()) { finish(null, null); return }
        main.postDelayed({ finish(null, null) }, 140)
        val shots = arrayOfNulls<Pair<Bitmap, Rect>>(wins.size)
        var pending = wins.size
        val scale = 0.75f
        fun compose() {
            bg.post {
                val out = runCatching {
                    val b = Bitmap.createBitmap((area.width() * scale).toInt().coerceAtLeast(1), (area.height() * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                    val c = android.graphics.Canvas(b)
                    c.drawColor(android.graphics.Color.BLACK)
                    val p = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
                    for (s in shots) {
                        val (bmp, r) = s ?: continue
                        val dst = android.graphics.RectF((r.left - area.left) * scale, (r.top - area.top) * scale, (r.right - area.left) * scale, (r.bottom - area.top) * scale)
                        c.drawBitmap(bmp, null, dst, p)
                        bmp.recycle()
                    }
                    b
                }.getOrNull()
                val soft = out?.let { softCopy(it) }
                val hw = out?.let { o -> o.copy(Bitmap.Config.HARDWARE, false)?.also { o.recycle() } ?: o }
                main.post { if (hw != null) { backdropSoft = soft; finish(hw, area) } else { soft?.recycle(); finish(null, null) } }
            }
        }
        wins.forEachIndexed { i, w ->
            val r = Rect().also { w.getBoundsInScreen(it) }
            val ok = runCatching {
                takeScreenshotOfWindow(w.id, mainExecutor, object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        val hb = result.hardwareBuffer
                        // Copied off the main thread: a full-screen copy would cost the motion a frame
                        bg.post {
                            val soft = runCatching {
                                Bitmap.wrapHardwareBuffer(hb, result.colorSpace)?.let { hw ->
                                    val half = Bitmap.createScaledBitmap(hw.copy(Bitmap.Config.ARGB_8888, false), (hw.width * scale).toInt().coerceAtLeast(1), (hw.height * scale).toInt().coerceAtLeast(1), true)
                                    hw.recycle(); half
                                }
                            }.getOrNull()
                            hb.close()
                            main.post { shots[i] = soft?.let { it to r }; if (--pending == 0) compose() }
                        }
                    }
                    override fun onFailure(errorCode: Int) { if (--pending == 0) compose() }
                })
            }.isSuccess
            if (!ok && --pending == 0) compose()
        }
    }

    /**
     * The picture hands over only once the app has really drawn its screen: a window with
     * content, not the splash a cold start shows first. Checked each frame, for up to 700ms.
     */
    private fun handOverWhenDrawn(pkg: String, since: Long) {
        val ready = runCatching { windows }.getOrDefault(emptyList()).any { w ->
            w.type == AccessibilityWindowInfo.TYPE_APPLICATION && runCatching {
                val root = w.root
                root?.packageName?.toString() == pkg && root.childCount > 0
            }.getOrDefault(false)
        }
        if (ready || SystemClock.uptimeMillis() - since > 700) { pour.arrived(); return }
        main.postDelayed({ handOverWhenDrawn(pkg, since) }, 16)
    }

    private fun reportOpenTime() {
        val p = pendingOpen ?: return
        if (onScreen.firstOrNull() == p) {
            if (::pour.isInitialized) handOverWhenDrawn(p, SystemClock.uptimeMillis())
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
        val pairs = Prefs.pairs(this).filter { (l, r) -> apps.isLaunchable(l) && apps.isLaunchable(r) }
            .map { (l, r) -> Slot.AppPair(l, r) }
        val tray = Prefs.tray(this)
            .filter { it !in pinned && it !in noRecents && apps.isLaunchable(it) }
            .take(Prefs.recentCount(this))
        return when (shape) {
            Shape.PAIR -> listOf(
                (listOf(Slot.AllApps) + pinned.map { Slot.App(it) } + pairs).take(maxSlots),
                // The second capsule gets its own handle at its outer end, mirroring the grid
                // button, so in split screen each half of the bar can sit over its own app
                tray.take(maxSlots - 1).map { Slot.App(it) } + Slot.Grip,
            )
            Shape.CAPSULE -> {
                val p = pinned.take((maxSlots - 1) / 2 + 1)
                val r = tray.take((maxSlots - 1 - p.size - pairs.size).coerceAtLeast(1))
                val pr = pairs.take((maxSlots - 1 - p.size).coerceAtLeast(0))
                listOf(
                    listOf(Slot.AllApps) + p.map { Slot.App(it) } + pr +
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
        if (::pour.isInitialized) pour.detach()
        hosts.forEach(::remove); hosts.clear(); capsules.clear()
    }

    private fun closePanels() { closeMenu(); if (::drawer.isInitialized) drawer.close() }

    // ---- Actions ------------------------------------------------------------------

    override fun onApp(pkg: String, view: View) = launch(pkg, view)

    private fun launch(pkg: String, source: View? = null) {
        if (onScreen.size == 1 && onScreen.first() == pkg) { closePanels(); return }
        pendingOpen = pkg
        pendingOpenAt = SystemClock.uptimeMillis()
        markUsed()
        val pic = snapshots[pkg]
        val motion = Prefs.motion(this)
        val look = motion.look
        // The bar's motion goes up first, on this frame, so the quietly launched app appears
        // underneath it and is never seen arriving on its own
        val across = hosts.firstOrNull { it.visibility == View.VISIBLE && it.width > 0 }?.let { it.width >= it.height } ?: true
        val drawn = look != null && source != null && source.isAttachedToWindow && isMainScreen() &&
            runCatching { pour.play(source, screen(), pic, apps.icon(pkg), look = look, concentrato = Prefs.concentrato(this), land = snapshotBounds[pkg], barAcross = across, softPicture = softs[pkg]) }
                .onFailure { EventLog.add(this, "Pour motion failed: ${it.javaClass.simpleName}") }.isSuccess
        // The screen being left is pictured first and held under the motion; only then does the
        // new app launch beneath it, so it is never glimpsed early (see Pour.setBackdrop)
        // Opened from the grid: the grid is not part of that picture, so as the picture fades in
        // over 90ms the grid fades away with it, then is put away at once with no hidden drain
        val fromGrid = drawn && ::drawer.isInitialized && drawer.isOpen
        if (drawn) captureLeaving { shot, area ->
            pour.setBackdrop(shot, area, backdropSoft); backdropSoft = null; apps.openQuiet(pkg)
            if (fromGrid) main.postDelayed({ drawer.close(animated = false) }, 110)
        }
        else apps.open(pkg, source, pic)
        if (fromGrid) closeMenu() else closePanels()
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
            *(splitPair()?.let { (l, r) ->
                arrayOf("Save ${apps.entry(l)?.label} + ${apps.entry(r)?.label} as a pair" to {
                    closeMenu(); Prefs.addPair(this, l, r)
                    EventLog.add(this, "Pair saved from the screen: ${apps.entry(l)?.label} + ${apps.entry(r)?.label}")
                })
            } ?: emptyArray()),
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

    /** The two apps currently sharing the screen, left first, if there are two. */
    private fun splitPair(): Pair<String, String>? {
        val two = onScreen.filter { it !in noRecents && apps.isLaunchable(it) }.take(2)
        if (two.size < 2) return null
        val (a, b) = two.sortedBy { onScreenLeft[it] ?: 0 }
        return a to b
    }

    override fun onAllApps(view: View) {
        closeMenu()
        pairingFirst = null
        if (drawer.isOpen) drawer.close() else { attachDrawer(); readDrawerBackdrop(); drawer.open(view as? GlyphView) }
        onBarTouched()
    }

    /** Reads the screen the grid is about to open over, so its glass can take that colour. */
    private fun readDrawerBackdrop() {
        if (!Prefs.finish(this).adaptive) return
        runCatching {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val hb = result.hardwareBuffer
                    bg.post {
                        runCatching {
                            val full = Bitmap.wrapHardwareBuffer(hb, result.colorSpace) ?: return@runCatching
                            val soft = full.copy(Bitmap.Config.ARGB_8888, false)
                            full.recycle()
                            val tone = averageIn(soft, Rect(0, 0, soft.width, soft.height), Rect(), dp(12).coerceAtLeast(1))
                            soft.recycle()
                            if (tone != null) main.post { drawer.setBackdrop(tone.first, tone.second) }
                        }
                        hb.close()
                    }
                }
                override fun onFailure(errorCode: Int) = Unit
            })
        }
    }

    /**
     * Opens [pkg] beside the app on screen, through [SplitActivity]: Android only splits
     * the screen for a request from a full-screen app.
     */
    private fun splitWith(pkg: String) {
        closePanels()
        val label = apps.entry(pkg)?.label ?: pkg
        val target = apps.entry(pkg)?.component ?: return
        val current = onScreen.firstOrNull()
        if (current == null || current in noRecents || current == pkg) {
            EventLog.add(this, "Split: needs another app on screen to sit beside; opened $label instead")
            apps.open(pkg)
            return
        }
        // Already split: the new app replaces the other half, so nothing to hand back
        val left = if (onScreen.count { it !in noRecents } >= 2) null else apps.entry(current)?.component
        startSplit(left, target)
    }

    /** Opens a saved pair: [left] on the left half, [right] on the right. */
    /**
     * Opens a pair. One UI only builds a true split from a drag, so a tap opens the first
     * app and invites the drag that brings in the second; dragging the pair itself does
     * both in one movement (see [onAppDragStart]).
     */
    private fun openPair(left: String, right: String, from: View? = null) {
        closePanels()
        if (onScreen.firstOrNull() != left) apps.open(left)
        val key = Slot.AppPair(left, right).key
        val anchor = from?.takeIf { it.isAttachedToWindow } ?: capsules.firstNotNullOfOrNull { it.viewFor(key) }
        main.postDelayed({
            hint("Drag the pair to either half to add ${apps.entry(right)?.label ?: "the other app"}", anchor)
        }, 450)
    }

    // ---- Hints --------------------------------------------------------------------

    private var hintWindow: View? = null
    private val hideHint = Runnable { dismissHint() }

    /**
     * A short line of guidance beside the bar, pointing at [anchor], which pulses twice so
     * your eye finds it. It never takes a touch and leaves by itself.
     */
    private fun hint(text: String, anchor: View?) {
        dismissHint()
        val finish = Prefs.finish(this)
        val label = android.widget.TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(finish.ink)
            background = surface(this@TaskbarService, finish, 999f)
            setPadding(dp(18), dp(10), dp(18), dp(10))
            elevation = dp(6).toFloat()
            alpha = 0f
            translationY = dp(6).toFloat()
        }
        val host = shadowHost(label)
        host.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val s = screen()
        val loc = IntArray(2)
        val ax: Int; val ay: Int; val ah: Int
        if (anchor != null && anchor.isAttachedToWindow) {
            anchor.getLocationOnScreen(loc); ax = loc[0] + anchor.width / 2; ay = loc[1]; ah = anchor.height
        } else {
            ax = s.width() / 2; ay = s.height() - navInset() - dp(80); ah = 0
        }
        val x = (ax - host.measuredWidth / 2).coerceIn(0, maxOf(0, s.width() - host.measuredWidth))
        val above = ay - host.measuredHeight - dp(6)
        val y = if (above >= statusInset()) above else ay + ah + dp(6)
        val p = params(Gravity.TOP or Gravity.START, x, y).apply {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        add(host, p)
        hintWindow = host
        label.animate().alpha(1f).translationY(0f).setDuration(Curves.OPEN_MS).setInterpolator(Curves.OPEN).start()
        anchor?.let { a ->
            fun pulse(times: Int) {
                if (times == 0 || !a.isAttachedToWindow) return
                a.animate().scaleX(1.16f).scaleY(1.16f).setDuration(180).setInterpolator(Curves.OPEN).withEndAction {
                    a.animate().scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(Curves.OPEN).withEndAction { pulse(times - 1) }.start()
                }.start()
            }
            pulse(2)
        }
        main.removeCallbacks(hideHint)
        main.postDelayed(hideHint, 3600)
    }

    private fun dismissHint() {
        main.removeCallbacks(hideHint)
        val h = hintWindow ?: return
        hintWindow = null
        h.animate().alpha(0f).setDuration(160).withEndAction { remove(h) }.start()
    }

    /**
     * Splits the screen between [first] (left or top) and [second] (right or bottom).
     *
     * One UI keeps the real controls for split screen to itself. Three routes might still
     * produce a true split; the split-screen test in settings tries each and keeps the one
     * that works. If none does, the fallback is a pop-up filling the second half, which is
     * reported honestly as a pop-up, never as a split.
     */
    private var lastSplitAt = 0L

    private fun startSplit(first: android.content.ComponentName?, second: android.content.ComponentName) {
        // One drop, one attempt: a second request while the first is underway trips both up
        val now = SystemClock.uptimeMillis()
        if (now - lastSplitAt < 2500) return
        lastSplitAt = now
        val method = if (first == null) 1 else Prefs.handover(this).coerceIn(0, POPUP_FALLBACK)
        attemptSplit(method, first, second)
        if (first == null || method == POPUP_FALLBACK) return
        judgeSplit(first.packageName, second.packageName, 2800) { ok, names ->
            if (ok) EventLog.add(this, "True split (${SPLIT_METHODS[method]}): $names")
            else EventLog.add(this, "No split (${SPLIT_METHODS[method]}; on screen: $names). Run the split-screen test in settings")
        }
    }

    /** Carries out one route. */
    private fun attemptSplit(method: Int, first: android.content.ComponentName?, second: android.content.ComponentName) {
        val s = screen()
        when (method) {
            0 -> {
                // The app on screen is docked by Samsung; the other comes into the free half
                val f = first ?: return
                if (onScreen.firstOrNull() != f.packageName) apps.open(f.packageName)
                main.postDelayed({
                    val ok = performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
                    EventLog.add(this, "Samsung split shortcut ${if (ok) "accepted" else "refused"}")
                    main.postDelayed({
                        bg.post {
                            scanWindows()
                            val names = onScreen.joinToString(" + ") { apps.entry(it)?.label ?: it }
                            main.post {
                                EventLog.add(this, "After the shortcut, on screen: $names")
                                apps.open(second.packageName)
                            }
                        }
                    }, 900)
                }, 900)
            }
            1, 2 -> runCatching {
                startActivity(SplitActivity.intent(this, first, second, if (method == 1) 1 else 0, picker = first == null))
            }.onFailure { EventLog.add(this, "Split: could not start (${it.javaClass.simpleName})") }
            else -> {
                val f = first ?: return
                if (onScreen.firstOrNull() != f.packageName) apps.open(f.packageName)
                val portrait = s.height() > s.width() * 1.15f
                val g = dp(4)
                val half = if (portrait) Rect(0, s.height() / 2 + g, s.width(), s.height() - navInset())
                else Rect(s.width() / 2 + g, statusInset(), s.width(), s.height() - navInset())
                main.postDelayed({ apps.openPopupAt(second.packageName, half) }, 500)
                EventLog.add(this, "Pop-up in the half: ${apps.entry(second.packageName)?.label} over ${apps.entry(f.packageName)?.label}")
            }
        }
    }

    /**
     * A true split, not just two apps visible: both windows are on screen, each takes a
     * real share of it, and they do not overlap (a pop-up overlaps the app beneath it).
     */
    private fun judgeSplit(a: String, b: String, after: Long, verdict: (Boolean, String) -> Unit) {
        main.postDelayed({
            bg.post {
                scanWindows()
                val seen = onScreen
                val boxes = onScreenBounds
                main.post {
                    val names = seen.joinToString(" + ") { apps.entry(it)?.label ?: it }
                    val ra = boxes[a]; val rb = boxes[b]
                    val s = screen()
                    val area = s.width().toLong() * s.height()
                    fun share(r: Rect) = r.width().toLong() * r.height() > area * 0.3
                    val overlap = if (ra != null && rb != null) Rect(ra).let { it.intersect(rb) && it.width().toLong() * it.height() > area * 0.05 } else true
                    verdict(ra != null && rb != null && share(ra) && share(rb) && !overlap, names)
                }
            }
        }, after)
    }

    /**
     * The split-screen test. With two of your recent apps, tries each route that could
     * give a true split, returning home between tries, and records a plain verdict for
     * each. The first route that works is kept; if none does, the pop-up fallback is kept
     * and the log says so.
     */
    fun runSplitTest() {
        val pick = (Prefs.tray(this) + Prefs.pinned(this) + Prefs.recents(this))
            .distinct().filter { it != packageName && it !in noRecents && apps.isLaunchable(it) }.take(2)
        if (pick.size < 2) { EventLog.add(this, "Split test: open two apps first so there are two to test with"); return }
        val (a, b) = pick
        val ca = apps.entry(a)?.component ?: return
        val cb = apps.entry(b)?.component ?: return
        EventLog.add(this, "Split test starting with ${apps.entry(a)?.label} + ${apps.entry(b)?.label}. Please don't touch the phone for about 30 seconds")
        val results = arrayOfNulls<Boolean>(3)
        fun step(m: Int) {
            if (m > 2) {
                val winner = results.indexOfFirst { it == true }
                if (winner >= 0) {
                    Prefs.setHandover(this, winner)
                    EventLog.add(this, "Split test result: ${SPLIT_METHODS[winner]} gives a true split. Using it from now on")
                } else {
                    Prefs.setHandover(this, POPUP_FALLBACK)
                    EventLog.add(this, "Split test result: One UI allowed no true split by any route. Using pop-up in the half")
                }
                performGlobalAction(GLOBAL_ACTION_HOME)
                return
            }
            // Start each try from a clean screen with the first app full screen
            performGlobalAction(GLOBAL_ACTION_HOME)
            main.postDelayed({
                apps.open(a)
                main.postDelayed({
                    EventLog.add(this, "Split test ${m + 1} of 3: ${SPLIT_METHODS[m]}")
                    attemptSplit(m, ca, cb)
                    judgeSplit(a, b, if (m == 0) 4200 else 3200) { ok, names ->
                        results[m] = ok
                        EventLog.add(this, "Split test ${m + 1}: ${if (ok) "TRUE SPLIT" else "no split"} (on screen: $names)")
                        step(m + 1)
                    }
                }, 1500)
            }, 900)
        }
        step(0)
    }

    // ---- Dragging an app out of the bar --------------------------------------------

    override fun appAt(host: BarHost, x: Float, y: Float): String? =
        capsules.getOrNull(hosts.indexOf(host))?.appAt(x, y)?.first

    override fun isHandle(host: BarHost, x: Float, y: Float): Boolean =
        capsules.getOrNull(hosts.indexOf(host))?.isHandle(x, y) == true

    override fun onAppDragStart(host: BarHost, pkg: String, x: Float, y: Float) {
        dismissHint()
        focusOn(capsules.getOrNull(hosts.indexOf(host)), pkg)
        if (pkg.startsWith("pair:")) {
            // Dragging a pair: the first app opens now, the second rides the drag into a half
            val (left, right) = pkg.removePrefix("pair:").split('|').let { it[0] to it[1] }
            if (onScreen.firstOrNull() != left) apps.open(left)
            if (systemDrag(host, right)) return
            beginDrag(right, x, y)
            return
        }
        if (systemDrag(host, pkg)) return
        beginDrag(pkg, x, y)
    }

    override fun onGridDragStart(pkg: String, x: Float, y: Float) {
        val src = drawer.dragSource
        if (src != null && systemDrag(src, pkg)) { drawer.close(animated = false); focusOn(null, pkg); return }
        focusOn(null, pkg)
        beginDrag(pkg, x, y)
    }

    // ---- Bellezza Necessaria: membrane, Concentrato, Trim --------------------------------

    private var focusAnim: ValueAnimator? = null
    private var focused: String? = null
    private val unfocusLater = Runnable { unfocus() }

    /**
     * An app has been lifted out of the bar or grid. Membrane: its neighbours yield towards the
     * gap. Concentrato: everything else on the bar steps back until the drag ends.
     */
    private fun focusOn(capsule: Capsule?, pkg: String) {
        if (Prefs.membrane(this)) capsule?.yieldTo(pkg)
        if (!Prefs.concentrato(this)) return
        focused = pkg
        animateFocus(1f)
        // Android carries system drags; its end is heard by the bar's drag listener, with a backstop
        main.removeCallbacks(unfocusLater)
        main.postDelayed(unfocusLater, 9000)
    }

    private fun unfocus() {
        main.removeCallbacks(unfocusLater)
        // Whatever happened during the drag, every icon comes back exactly to rest
        main.postDelayed({ capsules.forEach { it.settleAll() } }, 360)
        if (focused == null) return
        animateFocus(0f)
    }

    private fun animateFocus(to: Float) {
        focusAnim?.cancel()
        val key = focused
        focusAnim = ValueAnimator.ofFloat(if (to > 0f) 0f else 1f, to).apply {
            duration = if (to > 0f) 200 else 320
            addUpdateListener { a -> capsules.forEach { it.purge(key, a.animatedValue as Float) } }
            doOnEnd { if (to == 0f) focused = null }
            start()
        }
    }

    /** Hears Android's own drag ending (system drags), to bring the bar back into focus. */
    private val dragEnd = View.OnDragListener { _, e ->
        when (e.action) {
            android.view.DragEvent.ACTION_DRAG_STARTED -> true
            android.view.DragEvent.ACTION_DRAG_ENDED -> { unfocus(); true }
            // Let go over the bar: nothing opens
            android.view.DragEvent.ACTION_DROP -> false
            else -> true
        }
    }

    private val restNow = Runnable { restIfTrim() }

    /** Presence: Trim. Rests every capsule as trim unless something of the bar is open. */
    private fun restIfTrim() {
        main.removeCallbacks(restNow)
        if (Prefs.presence(this) != Presence.TRIM) { capsules.forEach { it.setResting(false) }; return }
        if ((::drawer.isInitialized && drawer.isOpen) || menuWindow != null || focused != null || hosts.any { it.dragging }) {
            main.postDelayed(restNow, 1500); return
        }
        capsules.forEach { it.setResting(true) }
    }

    private fun scheduleRest() {
        main.removeCallbacks(restNow)
        if (Prefs.presence(this) == Presence.TRIM) main.postDelayed(restNow, 3200)
    }

    override fun isResting(host: BarHost) = capsules.getOrNull(hosts.indexOf(host))?.resting == true

    override fun onWake(host: BarHost, x: Float, y: Float) {
        capsules.getOrNull(hosts.indexOf(host))?.setResting(false, x, y)
        scheduleRest()
    }

    override fun onScrub(host: BarHost, x: Float, y: Float) {
        capsules.getOrNull(hosts.indexOf(host))?.magnify(x, y)
        scheduleRest()
    }

    override fun onScrubEnd(host: BarHost, x: Float, y: Float, slid: Boolean) {
        val capsule = capsules.getOrNull(hosts.indexOf(host)) ?: return
        capsule.unmagnify()
        if (slid) {
            // Slid along the trim and lifted on an app: open it, as a tap would
            capsule.appAt(x, y)?.let { (key, v) ->
                if (key.startsWith("pair:")) key.removePrefix("pair:").split('|').let { openPair(it[0], it[1], v) }
                else launch(key, v)
            }
        }
        scheduleRest()
    }

    /** True while Android, not the bar, is carrying a dragged app. */
    private var systemDragging = false

    /**
     * Samsung's taskbar and Google's launcher do not open a dragged app themselves: they
     * hand Android an "app" drag, and Android's own split-screen drop zones take over and
     * open a true split where you let go. This tries the same. If One UI refuses it from
     * an app that is not the launcher, the bar says so once, switches the experiment off,
     * and uses its own guide instead.
     */
    private fun systemDrag(from: View, pkg: String): Boolean {
        if (!Prefs.systemDrag(this)) return false
        val e = apps.entry(pkg) ?: return false
        val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(e.component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = android.app.PendingIntent.getActivity(this, pkg.hashCode(), launch,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
        val data = Intent()
            .putExtra("android.intent.extra.PENDING_INTENT", pi)
            .putExtra(Intent.EXTRA_USER, android.os.Process.myUserHandle())
            .putExtra(Intent.EXTRA_PACKAGE_NAME, pkg)
        val clip = android.content.ClipData(
            android.content.ClipDescription(e.label, arrayOf("application/vnd.android.activity")),
            android.content.ClipData.Item(data),
        )
        val icon = apps.icon(pkg)
        val size = dp(64)
        val shadow = object : View.DragShadowBuilder(from) {
            override fun onProvideShadowMetrics(outShadowSize: android.graphics.Point, outShadowTouchPoint: android.graphics.Point) {
                outShadowSize.set(size, size); outShadowTouchPoint.set(size / 2, size / 2)
            }
            override fun onDrawShadow(canvas: android.graphics.Canvas) {
                icon?.let { it.setBounds(0, 0, size, size); it.draw(canvas) }
            }
        }
        var flags = View.DRAG_FLAG_GLOBAL or View.DRAG_FLAG_OPAQUE
        if (android.os.Build.VERSION.SDK_INT >= 34) flags = flags or View.DRAG_FLAG_START_INTENT_SENDER_ON_UNHANDLED_DRAG
        val started = runCatching { from.startDragAndDrop(clip, shadow, null, flags) }
        val ok = started.getOrDefault(false)
        if (!ok) {
            Prefs.setSystemDrag(this, false)
            EventLog.add(this, "Android's split drop zones: refused (${started.exceptionOrNull()?.javaClass?.simpleName ?: "not started"}). Using the bar's own guide")
            return false
        }
        systemDragging = true
        closeMenu()
        // Hear how the drag ended, wherever it was dropped
        from.setOnDragListener { _, ev ->
            if (ev.action == android.view.DragEvent.ACTION_DRAG_ENDED) {
                // Hand the bar back its own listener (24 Oct 2026: clearing it left the bar deaf to
                // every later drag), and bring every icon back to rest straight away
                from.setOnDragListener(if (from is BarHost) dragEnd else null)
                systemDragging = false
                unfocus()
                val handled = ev.result
                main.postDelayed({
                    bg.post {
                        scanWindows()
                        val names = onScreen.joinToString(" + ") { apps.entry(it)?.label ?: it }
                        main.post { EventLog.add(this, "System drag ended (${if (handled) "Android opened it" else "not taken"}); on screen: $names") }
                    }
                }, 1500)
            }
            // Let go over the bar: nothing opens
            ev.action != android.view.DragEvent.ACTION_DROP
        }
        EventLog.add(this, "Android's split drop zones: drag handed over for ${e.label}")
        return true
    }
    override fun onGridDrag(x: Float, y: Float) = onAppDrag(x, y)
    override fun onGridDragEnd(x: Float, y: Float, cancelled: Boolean) = onAppDragEnd(x, y, cancelled)

    /** An app has left the bar or the grid under your finger; show where it can land. */
    private fun beginDrag(pkg: String, x: Float, y: Float) {
        closeMenu()
        main.removeCallbacks(autoHideNow)
        dragFromCurrent = onScreen.firstOrNull { it !in noRecents }
        // Letting go back over the bar cancels
        val cancel = android.graphics.RectF()
        hosts.filter { it.visibility == View.VISIBLE }.forEach { h ->
            val lp = h.layoutParams as WindowManager.LayoutParams
            val r = android.graphics.RectF(lp.x.toFloat(), lp.y.toFloat(), (lp.x + h.width).toFloat(), (lp.y + h.height).toFloat())
            if (cancel.isEmpty) cancel.set(r) else cancel.union(r)
        }
        guide.start(apps.icon(pkg), x, y, cancel)
        draggedApp = pkg
    }

    private var draggedApp: String? = null

    override fun onAppDrag(x: Float, y: Float) { if (!systemDragging) guide.move(x, y) }

    /** Left or right: split with the app on screen. Middle: a pop-up where you let go. */
    override fun onAppDragEnd(x: Float, y: Float, cancelled: Boolean) {
        if (!systemDragging) unfocus()
        if (systemDragging) {
            // Android carries it from here; its drop zones decide what opens where
            systemDragging = false
            return
        }
        val pkg = draggedApp
        draggedApp = null
        val zone = if (cancelled) Drop.NONE else guide.zone
        val point = guide.dropPoint()
        guide.end()
        if (pkg == null) return
        val dragged = apps.entry(pkg)?.component ?: return
        val other = splitPair()?.let { (l, r) -> if (zone == Drop.LEFT || zone == Drop.TOP) r else l }?.takeIf { it != pkg }
            ?: dragFromCurrent?.takeIf { it != pkg }
        val otherComp = other?.let { apps.entry(it)?.component }
        when (zone) {
            // Left or top: the dragged app comes first. Right or bottom: second.
            Drop.LEFT, Drop.TOP -> if (otherComp != null) startSplit(dragged, otherComp) else startSplit(null, dragged)
            Drop.RIGHT, Drop.BOTTOM -> if (otherComp != null) startSplit(otherComp, dragged) else startSplit(null, dragged)
            Drop.POPUP -> {
                val (px, py) = point ?: (screen().exactCenterX() to screen().exactCenterY())
                apps.openPopup(pkg, screen(), px.toInt(), py.toInt())
            }
            Drop.NONE -> Unit
        }
        if (Prefs.autoHide(this) && revealed) scheduleAutoHide()
    }

    override fun onPair(left: String, right: String, view: View) = openPair(left, right, view)

    override fun onPairHeld(left: String, right: String, view: View) {
        closePanels()
        val name = "${apps.entry(left)?.label} + ${apps.entry(right)?.label}"
        showMenu(name, listOf(
            "Open pair" to { openPair(left, right) },
            "Swap sides" to { closeMenu(); Prefs.removePair(this, left, right); Prefs.addPair(this, right, left) },
            "Remove pair" to { closeMenu(); Prefs.removePair(this, left, right) },
        ), view)
    }

    /** A tap in the grid opens the app, or completes a pair when one is being made. */
    private fun fromGrid(pkg: String, view: View) {
        val first = pairingFirst
        pairingFirst = null
        if (first == null) { launch(pkg, view); return }
        if (first == pkg) { closePanels(); return }
        Prefs.addPair(this, first, pkg)
        EventLog.add(this, "Pair saved: ${apps.entry(first)?.label} + ${apps.entry(pkg)?.label}")
        openPair(first, pkg)
    }

    /**
     * Opens [pkg] as a pop-up and then checks what One UI actually did. Asking for a window mode
     * is a request, not a guarantee: an app that refuses resizing, or One UI declining the mode,
     * opens it full screen instead. The check reads the app's real window after it settles and,
     * if it covers the screen, says so plainly in the log and offers the reliable route.
     */
    private fun openPopupChecked(pkg: String) {
        val screen = liveBounds()
        if (!apps.openPopup(pkg, screen)) { hint("${apps.entry(pkg)?.label ?: "This app"} couldn't open as a pop-up", null); return }
        main.postDelayed({
            val win = runCatching { windows }.getOrDefault(emptyList()).firstOrNull { w ->
                w.type == AccessibilityWindowInfo.TYPE_APPLICATION && runCatching { w.root?.packageName?.toString() }.getOrNull() == pkg
            } ?: return@postDelayed
            val r = Rect().also { win.getBoundsInScreen(it) }
            val label = apps.entry(pkg)?.label ?: pkg
            val full = r.width() >= screen.width() * 0.95f && r.height() >= screen.height() * 0.9f
            EventLog.add(this, "Pop-up $label: landed ${r.width()}x${r.height()}${if (full) ", full screen (One UI declined)" else ""}")
            if (full) hint("$label won't open as a pop-up. Drag it from the bar to the centre instead", null)
        }, 900)
    }

    private fun openMenu(pkg: String, anchor: View) {
        val label = apps.entry(pkg)?.label ?: pkg
        val pinned = Prefs.pinned(this)
        val isPinned = pkg in pinned
        val rows = buildList<Pair<String, () -> Unit>> {
            add("Open in split screen" to {
                closeMenu()
                // A true split only comes from a drag, so show where to drag from
                val inGrid = drawer.isOpen
                val label = apps.entry(pkg)?.label ?: "this app"
                hint(
                    if (inGrid) "Hold $label, then drag it to either half" else "Drag $label out of the bar to either half",
                    if (inGrid) anchor else capsules.firstNotNullOfOrNull { it.viewFor(pkg) } ?: anchor,
                )
            })
            add("Open as pop-up" to { closePanels(); openPopupChecked(pkg) })
            add("Pair with another app" to {
                closeMenu()
                pairingFirst = pkg
                drawer.open(null, "Pair ${apps.entry(pkg)?.label ?: "this app"} with…")
            })
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
            val summary = apps.iconSummary()
            main.post {
                drawer.refreshApps()
                if (summary != lastIconSummary) {
                    // A pack arrived or left: redraw the bar with the new icons
                    if (lastIconSummary != null) { layoutKey = ""; main.removeCallbacks(applyNow); main.post(applyNow) }
                    lastIconSummary = summary
                    EventLog.add(this, "Icons: $summary")
                }
            }
        }
    }

    /**
     * Attaches the grid's windows beside wherever the grid button's capsule sits, opening
     * towards the most room: above or below a lying bar, left or right of a standing one.
     * The panel lines up with the button along the bar, kept clear of the screen's edges.
     */
    private fun attachDrawer() {
        if (!::drawer.isInitialized) return
        val s = screen()
        val spot = spotFor(Prefs.shape(this), 0)
        val host = hosts.firstOrNull()
        val (bw, bh) = host?.let { hostSize(it) } ?: (dp(Prefs.iconDp(this) + 42) to dp(Prefs.iconDp(this) + 42))
        val cx = spot.fx * s.width(); val cy = spot.fy * s.height()
        val bar = android.graphics.RectF(cx - bw / 2f, cy - bh / 2f, cx + bw / 2f, cy + bh / 2f)
        val gap = dp(2).toFloat()   // the capsule's window already carries ~10 dp of clear space
        val margin = dp(12)
        val top = statusInset() + margin
        val bottom = s.height() - navInset() - margin
        val roomAbove = bar.top - gap - top
        val roomBelow = bottom - (bar.bottom + gap)
        val roomLeft = bar.left - gap - margin
        val roomRight = s.width() - margin - (bar.right + gap)
        val side = if (spot.vertical) {
            if (roomRight >= roomLeft) AppDrawer.Side.LEFT else AppDrawer.Side.RIGHT
        } else {
            if (roomAbove >= roomBelow) AppDrawer.Side.BOTTOM else AppDrawer.Side.TOP
        }
        val free = when (side) {
            AppDrawer.Side.BOTTOM, AppDrawer.Side.TOP -> {
                val w = minOf((s.width() * 0.8f).toInt(), dp(760))
                val room = if (side == AppDrawer.Side.BOTTOM) roomAbove else roomBelow
                val h = minOf((s.height() * 0.6f).toInt(), room.toInt()).coerceAtLeast(dp(240))
                val l = (cx - w / 2f).toInt().coerceIn(margin, (s.width() - w - margin).coerceAtLeast(margin))
                val t = if (side == AppDrawer.Side.BOTTOM) (bar.top - gap - h).toInt() else (bar.bottom + gap).toInt()
                Rect(l, t, l + w, t + h)
            }
            else -> {
                val room = if (side == AppDrawer.Side.LEFT) roomRight else roomLeft
                val w = minOf(room.toInt(), dp(760), (s.width() * 0.8f).toInt()).coerceAtLeast(dp(300))
                val h = minOf((s.height() * 0.78f).toInt(), bottom - top).coerceAtLeast(dp(240))
                val t = (cy - h / 2f).toInt().coerceIn(top, (bottom - h).coerceAtLeast(top))
                val l = if (side == AppDrawer.Side.LEFT) (bar.right + gap).toInt() else (bar.left - gap - w).toInt()
                Rect(l, t, l + w, t + h)
            }
        }
        // Flush with the bar (25 Oct 2026): the grid's edges meet the capsule's ends, widened
        // evenly about the bar only when the bar is too short to hold a useful grid
        val lat = capsules.firstOrNull()?.lattice()
        val rect = if (lat == null) free else when (side) {
            AppDrawer.Side.BOTTOM, AppDrawer.Side.TOP -> {
                val minW = dp(460); val maxW = s.width() - 2 * margin
                var l = lat.bar.left; var r = lat.bar.right
                if (r - l < minW) { val c = (l + r) / 2; l = c - minW / 2; r = c + minW / 2 }
                if (r - l > maxW) { l = margin; r = s.width() - margin }
                if (l < margin) { r += margin - l; l = margin }
                if (r > s.width() - margin) { l -= r - (s.width() - margin); r = s.width() - margin }
                Rect(l, free.top, r, free.bottom)
            }
            else -> {
                val minH = dp(380)
                var t = lat.bar.top; var b = lat.bar.bottom
                if (b - t < minH) { val c = (t + b) / 2; t = c - minH / 2; b = c + minH / 2 }
                if (t < top) { b += top - t; t = top }
                if (b > bottom) { t -= b - bottom; b = bottom }
                Rect(free.left, t.coerceAtLeast(top), free.right, b)
            }
        }
        val rebuilt = drawer.attach(Prefs.finish(this), rect, side, lat)
        // The pour sits above the grid, so an app opened from the grid swells over it, and
        // under the bar, which is lifted back above both
        if (rebuilt) {
            pour.detach()
            pour.ensure()
            hosts.forEach { h ->
                val lp = h.layoutParams as? WindowManager.LayoutParams ?: return@forEach
                runCatching { wm.removeViewImmediate(h); wm.addView(h, lp) }
            }
        } else pour.ensure()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun outsideCloses(v: View, close: () -> Unit): View = v.apply {
        setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_OUTSIDE) close()
            false
        }
    }

    companion object {
        /** How long a change of screen shape or insets must hold before the bar moves for it. */
        const val SETTLE_MS = 320L
        /** How long after the last scroll a receded bar waits before returning. */
        const val RECEDE_HOLD_MS = 700L
        /** However Recede is set, a newly arrived app's own opening scrolls never send the bar back. */
        const val RECEDE_SETTLE_MS = 1500L
        /** The bar's entrance after an unfold: long and gentle. */
        const val ENTRANCE_MS = 560L
        /** Longest the bar waits for Fold Motion to say its reveal is over. */
        const val COVER_FAILSAFE_MS = 2200L

        @Volatile var running = false
        @Volatile var instance: TaskbarService? = null
        private const val SAMPLE_EVERY_MS = 2000L
        val SPLIT_METHODS = listOf("Samsung split shortcut", "fresh window", "relaunch", "pop-up in the half")
        const val POPUP_FALLBACK = 3
    }
}
