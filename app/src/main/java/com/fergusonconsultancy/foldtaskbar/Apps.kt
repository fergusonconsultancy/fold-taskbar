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

    fun icon(pkg: String): Drawable? = icons[pkg] ?: launcher.getActivityList(pkg, user).firstOrNull()
        ?.getBadgedIcon(0)?.also { icons[pkg] = it }

    fun forget() { icons.clear(); entries.clear(); allCache = null }

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
    fun open(pkg: String, source: android.view.View? = null): Boolean {
        val e = entry(pkg) ?: return false
        // Given the icon, the system grows the app out of it rather than sliding it in
        val bounds = source?.takeIf { it.isAttachedToWindow }?.let { v ->
            val loc = IntArray(2).also { v.getLocationOnScreen(it) }
            Rect(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
        }
        val opts = source?.takeIf { it.isAttachedToWindow && Prefs.depth(context) }?.let { v ->
            depthOptions(v)?.toBundle()
        }
        return runCatching { launcher.startMainActivity(e.component, user, bounds, opts) }
            .onFailure { EventLog.add(context, "Open ${e.label} failed: ${it.javaClass.simpleName}") }
            .isSuccess
    }

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
    fun openPopup(pkg: String, screen: Rect): Boolean {
        val e = entry(pkg) ?: return false
        val w = (screen.width() * 0.62f).toInt()
        val h = (screen.height() * 0.62f).toInt()
        val left = screen.centerX() - w / 2
        val top = screen.centerY() - h / 2 - screen.height() / 20
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

    /**
     * Depth: the app you open comes forward out of the bar, from the side its icon sits
     * on, while the one you leave steps back and fades. These are window animations, so
     * the real apps move, not a picture of them. Android reserves animations between apps
     * for itself; the option that lets ours through is hidden, reached the same way as
     * the pop-up window that already works on One UI.
     */
    private fun depthOptions(v: android.view.View): ActivityOptions? {
        val loc = IntArray(2).also { v.getLocationOnScreen(it) }
        val screen = context.resources.displayMetrics
        val fx = (loc[0] + v.width / 2f) / screen.widthPixels
        val top = (loc[1] + v.height / 2f) < screen.heightPixels / 2f
        val enter = when {
            fx < 0.36f -> if (top) R.anim.depth_enter_lt else R.anim.depth_enter_lb
            fx > 0.64f -> if (top) R.anim.depth_enter_rt else R.anim.depth_enter_rb
            else -> if (top) R.anim.depth_enter_ct else R.anim.depth_enter_cb
        }
        val exit = R.anim.depth_exit
        val opts = runCatching {
            HiddenApiBypass.invoke(
                ActivityOptions::class.java, null, "makeCustomTaskAnimation",
                context, enter, exit, null, null, null,
            ) as ActivityOptions
        }.getOrNull() ?: runCatching {
            ActivityOptions.makeCustomAnimation(context, enter, exit).also { o ->
                HiddenApiBypass.getInstanceFields(ActivityOptions::class.java)
                    .first { it.name == "mOverrideTaskTransition" }
                    .apply { isAccessible = true }
                    .setBoolean(o, true)
            }
        }.getOrNull()
        if (!depthLogged) {
            depthLogged = true
            EventLog.add(context, if (opts != null) "Depth transition: ready" else "Depth transition: One UI refused it; using the system animation")
        }
        return opts ?: ActivityOptions.makeScaleUpAnimation(v, 0, 0, v.width, v.height)
    }

    private var depthLogged = false

    private fun remember(info: LauncherActivityInfo): AppEntry {
        val pkg = info.applicationInfo.packageName
        return AppEntry(pkg, info.label.toString(), info.componentName).also { entries[pkg] = it }
    }

    companion object {
        private const val WINDOWING_MODE_FREEFORM = 5
    }
}
