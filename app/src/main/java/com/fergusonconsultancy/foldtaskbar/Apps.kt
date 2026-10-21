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
    private val icons = HashMap<String, Drawable>()
    private val entries = HashMap<String, AppEntry>()

    fun all(): List<AppEntry> =
        launcher.getActivityList(null, user)
            .distinctBy { it.applicationInfo.packageName }
            .map { remember(it) }
            .filter { it.pkg != context.packageName }
            .sortedBy { it.label.lowercase() }

    fun entry(pkg: String): AppEntry? = entries[pkg] ?: launcher.getActivityList(pkg, user).firstOrNull()?.let { remember(it) }

    fun icon(pkg: String): Drawable? = icons[pkg] ?: launcher.getActivityList(pkg, user).firstOrNull()
        ?.getBadgedIcon(0)?.also { icons[pkg] = it }

    fun forget() { icons.clear(); entries.clear() }

    fun isLaunchable(pkg: String) = entry(pkg) != null

    fun homePackage(): String? = context.packageManager.resolveActivity(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY
    )?.activityInfo?.packageName

    /** Brings the app forward, reusing its existing window if it has one, as a launcher would. */
    fun open(pkg: String): Boolean {
        val e = entry(pkg) ?: return false
        return runCatching { launcher.startMainActivity(e.component, user, null, null) }
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

    private fun remember(info: LauncherActivityInfo): AppEntry {
        val pkg = info.applicationInfo.packageName
        return AppEntry(pkg, info.label.toString(), info.componentName).also { entries[pkg] = it }
    }

    companion object {
        private const val WINDOWING_MODE_FREEFORM = 5
    }
}
