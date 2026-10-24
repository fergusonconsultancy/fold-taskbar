package com.fergusonconsultancy.foldtaskbar

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Process
import org.lsposed.hiddenapibypass.HiddenApiBypass

data class AppEntry(val pkg: String, val label: String, val component: ComponentName)

/** Finds apps, their icons and labels, and opens them in the three ways the bar offers. */
class Apps(private val context: Context) {

    private val launcher = context.getSystemService(LauncherApps::class.java)
    private val user = Process.myUserHandle()
    private val icons = java.util.concurrent.ConcurrentHashMap<String, Drawable>()
    private val entries = java.util.concurrent.ConcurrentHashMap<String, AppEntry>()
    @Volatile private var allCache: List<AppEntry>? = null

    /** The full app list, from memory once [preload] has run. */
    fun all(): List<AppEntry> = allCache ?: load()

    /** Loads every app and icon ahead of time, off the main thread, so the grid opens instantly. */
    fun preload() {
        val list = load()
        list.forEach { icon(it.pkg) }
        allCache = list
    }

    private fun load(): List<AppEntry> =
        launcher.getActivityList(null, user)
            .distinctBy { it.applicationInfo.packageName }
            .map { remember(it) }
            .filter { it.pkg != context.packageName }
            .sortedBy { it.label.lowercase() }

    fun entry(pkg: String): AppEntry? = entries[pkg] ?: launcher.getActivityList(pkg, user).firstOrNull()?.let { remember(it) }

    /** An app's icon: from your icon pack when one is chosen, so bar and grid match your home screen. */
    /**
     * The app's icon. Every call returns its own copy (sharing the same pixels), so one user
     * resizing it can never change it for another. 24 Oct 2026: the split-screen drag image
     * resized the one shared icon to 64dp, and the bar's icons drew at that size inside their
     * slots, cut off on the right, until the bar was rebuilt.
     */
    fun icon(pkg: String): Drawable? {
        val cached = icons[pkg] ?: run {
            val info = launcher.getActivityList(pkg, user).firstOrNull() ?: return null
            val base = info.getBadgedIcon(0)
            val themed = pack()?.let { p -> runCatching { p.iconFor(info.componentName, base) }.getOrNull() } ?: base
            themed?.also { icons[pkg] = it }
        } ?: return null
        return cached.constantState?.newDrawable(context.resources)?.mutate() ?: cached
    }

    @Volatile private var iconPack: IconPack? = null
    @Volatile private var packLoaded = false

    private fun pack(): IconPack? {
        if (!packLoaded) {
            packLoaded = true
            iconPack = Prefs.iconPack(context)?.let { runCatching { IconPack(context, it) }.getOrNull() }
        }
        return iconPack
    }

    /** For the event log: which icons are in use. */
    fun iconSummary(): String {
        val packs = IconPack.installed(context)
        val p = pack()
        if (p != null) {
            val label = packs.firstOrNull { it.first == p.pkg }?.second ?: p.pkg
            return "$label, ${p.size} icons" + (if (p.size == 0) ". The pack has no icon list Fold Taskbar can read" else "") +
                (if (packs.size > 1) ". Packs on the phone: ${packs.joinToString { it.second }}" else "")
        }
        if (packs.isNotEmpty()) return "the phone's own (chosen in settings). Packs on the phone: ${packs.joinToString { it.second }}"
        return if (IconPack.studioInstalled(context))
            "the phone's own. Icon Pack Studio is installed but none of its packs are exported, so other apps cannot see them yet"
        else "the phone's own. No icon packs installed"
    }

    fun forget() { icons.clear(); entries.clear(); allCache = null; packLoaded = false; IconPack.forgetInstalled() }

    fun onPackagesChanged(listener: () -> Unit) {
        launcher.registerCallback(object : LauncherApps.Callback() {
            override fun onPackageRemoved(packageName: String, user: android.os.UserHandle) = listener()
            override fun onPackageAdded(packageName: String, user: android.os.UserHandle) = listener()
            override fun onPackageChanged(packageName: String, user: android.os.UserHandle) = listener()
            override fun onPackagesAvailable(packageNames: Array<out String>, user: android.os.UserHandle, replacing: Boolean) = listener()
            override fun onPackagesUnavailable(packageNames: Array<out String>, user: android.os.UserHandle, replacing: Boolean) = listener()
        })
    }

    fun isLaunchable(pkg: String) = entry(pkg) != null

    fun homePackage(): String? = context.packageManager.resolveActivity(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY
    )?.activityInfo?.packageName

    /** Brings the app forward, reusing its existing window if it has one, as a launcher would. */
    /**
     * Brings the app forward. The motion is a request: if One UI refuses it, the app opens
     * anyway with the system animation. An animation must never stop an app opening.
     */
    /**
     * 25 Oct 2026: when the bar draws the switch, the app is started with no system animation.
     * Until now One UI played its own task switch underneath ours (the old app shrinking away
     * on grey, the new one sliding in and settling on a spring), and when our picture handed
     * over, that settling was still going: the bounce. With FLAG_ACTIVITY_NO_ANIMATION the app
     * simply appears, still, under the bar's picture. Falls back to the normal launch if refused.
     */
    fun openQuiet(pkg: String): Boolean {
        val e = entry(pkg) ?: return false
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(e.component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        val quiet = runCatching { context.startActivity(intent) }
        if (quiet.isSuccess) return true
        if (!loggedQuietRefused) { loggedQuietRefused = true; EventLog.add(context, "Quiet launch refused (${quiet.exceptionOrNull()?.javaClass?.simpleName}): using the normal launch") }
        return runCatching { launcher.startMainActivity(e.component, user, null, null) }.isSuccess
    }
    private var loggedQuietRefused = false

    fun open(pkg: String, source: android.view.View? = null, snapshot: android.graphics.Bitmap? = null): Boolean {
        val e = entry(pkg) ?: return false
        val v = source?.takeIf { it.isAttachedToWindow && it.width > 0 }
        val bounds = v?.let {
            val loc = IntArray(2).also { l -> it.getLocationOnScreen(l) }
            Rect(loc[0], loc[1], loc[0] + it.width, loc[1] + it.height)
        }
        val motion = Prefs.motion(context)
        val opts = v?.let { runCatching { motionOptions(it, motion, snapshot) }.getOrNull() }
        val first = runCatching { launcher.startMainActivity(e.component, user, bounds, opts?.toBundle()) }
        if (first.isSuccess) return true
        if (opts != null && motion !in refused) {
            refused += motion
            EventLog.add(context, "${motion.name.lowercase().replaceFirstChar { it.uppercase() }} motion refused by One UI (${first.exceptionOrNull()?.javaClass?.simpleName}); using the system animation")
        }
        return runCatching { launcher.startMainActivity(e.component, user, bounds, null) }
            .onFailure { EventLog.add(context, "Open ${e.label} failed: ${it.javaClass.simpleName}") }
            .isSuccess
    }

    private val refused = mutableSetOf<Motion>()

    /**
     * System animation only. Every other motion is drawn by the bar over a quiet launch, because
     * One UI ignores the classic launch animations (scale-up, clip-reveal, thumbnail) for app
     * switches and plays its own task switch instead: the reason Zoom, Reveal and Grow all
     * looked like Android's own animation.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun motionOptions(v: android.view.View, motion: Motion, snapshot: android.graphics.Bitmap?): ActivityOptions? = null

    /** Opens the app beside whatever is already on screen. */
    fun openAdjacent(pkg: String): Boolean {
        val e = entry(pkg) ?: return false
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(e.component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
        return runCatching { context.startActivity(intent) }
            .onFailure { EventLog.add(context, "Split ${e.label} failed: ${it.javaClass.simpleName}") }
            .isSuccess
    }

    /**
     * Experimental. Asks for a floating window using a mode Android keeps hidden from
     * ordinary apps. One UI may ignore it and open the app full screen instead.
     */
    fun openPopup(pkg: String, screen: Rect, centreX: Int = screen.centerX(), centreY: Int = screen.centerY() - screen.height() / 20): Boolean {
        val e = entry(pkg) ?: return false
        val w = (screen.width() * 0.62f).toInt()
        val h = (screen.height() * 0.62f).toInt()
        val left = (centreX - w / 2).coerceIn(0, screen.width() - w)
        val top = (centreY - h / 2).coerceIn(0, screen.height() - h)
        val options = ActivityOptions.makeBasic().setLaunchBounds(Rect(left, top, left + w, top + h))
        val modeSet = runCatching {
            HiddenApiBypass.invoke(ActivityOptions::class.java, options, "setLaunchWindowingMode", WINDOWING_MODE_FREEFORM)
        }.isSuccess
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(e.component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent, options.toBundle()) }
            .onSuccess { EventLog.add(context, "Pop-up ${e.label}: requested (window mode ${if (modeSet) "set" else "refused"})") }
            .onFailure { EventLog.add(context, "Pop-up ${e.label} failed: ${it.javaClass.simpleName}") }
            .isSuccess
    }


    /** A pop-up window placed exactly at [bounds]. */
    fun openPopupAt(pkg: String, bounds: Rect): Boolean {
        val e = entry(pkg) ?: return false
        val options = ActivityOptions.makeBasic().setLaunchBounds(bounds)
        runCatching { HiddenApiBypass.invoke(ActivityOptions::class.java, options, "setLaunchWindowingMode", WINDOWING_MODE_FREEFORM) }
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(e.component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent, options.toBundle()) }.isSuccess
    }

    /**
     * The picture Zoom grows is given rounded, icon-like corners, so the app swells out of
     * a soft window shaped like the icon you touched rather than a hard-edged rectangle.
     * The corners grow with it and melt into the app as it dissolves in.
     */
    private fun rounded(src: android.graphics.Bitmap, radius: Float): android.graphics.Bitmap {
        val out = android.graphics.Bitmap.createBitmap(src.width, src.height, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(out)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG).apply {
            shader = android.graphics.BitmapShader(src, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
        }
        c.drawRoundRect(0f, 0f, src.width.toFloat(), src.height.toFloat(), radius, radius, p)
        return out
    }

    private fun remember(info: LauncherActivityInfo): AppEntry {
        val pkg = info.applicationInfo.packageName
        return AppEntry(pkg, info.label.toString(), info.componentName).also { entries[pkg] = it }
    }

    companion object {
        private const val WINDOWING_MODE_FREEFORM = 5
    }
}
