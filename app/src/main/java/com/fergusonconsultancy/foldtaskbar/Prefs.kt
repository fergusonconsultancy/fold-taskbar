package com.fergusonconsultancy.foldtaskbar

import android.content.Context
import android.content.SharedPreferences

enum class Shape(val label: String) { PAIR("Hinge pair"), CAPSULE("Capsule") }

enum class Finish(val label: String, val fill: Int, val ink: Int, val edge: Int, val indicator: Int) {
    SMOKE("Smoked glass", 0xDB121614.toInt(), 0xFFE6E8E2.toInt(), 0x14FFFFFF, 0xFFD3B27C.toInt()),
    VISCONTI("Visconti", 0xF01A3F31.toInt(), 0xFFEFE6D3.toInt(), 0x73D3B27C, 0xFFD3B27C.toInt()),
    PAPER("Paper", 0xF2F6F4EE.toInt(), 0xFF1D2620.toInt(), 0x14000000, 0xFF1F4A3A.toInt()),
}

/** Every setting and list the bar keeps, in one place. */
object Prefs {
    const val FILE = "fold_taskbar"
    private const val SHAPE = "shape"
    private const val FINISH = "finish"
    private const val ICON_DP = "icon_dp"
    private const val RECENT_COUNT = "recent_count"
    private const val PINNED = "pinned"
    private const val RECENTS = "recents"
    private const val TRAY = "tray"

    val ICON_SIZES = listOf(40, 44, 52)
    val RECENT_COUNTS = listOf(3, 4, 5, 6)

    fun of(c: Context): SharedPreferences = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun shape(c: Context) = runCatching { Shape.valueOf(of(c).getString(SHAPE, null)!!) }.getOrDefault(Shape.PAIR)
    fun setShape(c: Context, v: Shape) = of(c).edit().putString(SHAPE, v.name).apply()

    fun finish(c: Context) = runCatching { Finish.valueOf(of(c).getString(FINISH, null)!!) }.getOrDefault(Finish.SMOKE)
    fun setFinish(c: Context, v: Finish) = of(c).edit().putString(FINISH, v.name).apply()

    fun iconDp(c: Context) = of(c).getInt(ICON_DP, 44)
    fun setIconDp(c: Context, v: Int) = of(c).edit().putInt(ICON_DP, v).apply()

    fun recentCount(c: Context) = of(c).getInt(RECENT_COUNT, 4)
    fun setRecentCount(c: Context, v: Int) = of(c).edit().putInt(RECENT_COUNT, v).apply()

    fun pinned(c: Context): List<String> = list(c, PINNED)

    /**
     * The recent apps tray, in display order. An app keeps its place once it is in the
     * tray, so icons never slide under your finger. A new app takes the slot of the one
     * used longest ago.
     */
    fun tray(c: Context): List<String> = list(c, TRAY)

    /** Returns true when the tray changed. */
    fun trayAdd(c: Context, pkg: String, capacity: Int, eligible: (String) -> Boolean): Boolean {
        val recency = recents(c)
        val now = tray(c).filter(eligible).toMutableList()
        while (now.size > capacity) now.removeAt(now.indices.maxBy { recency.indexOf(now[it]).let { r -> if (r < 0) Int.MAX_VALUE else r } })
        if (pkg in now) {
            if (now == tray(c)) return false
        } else if (now.size < capacity) {
            now.add(pkg)
        } else {
            val oldest = now.indices.maxBy { recency.indexOf(now[it]).let { r -> if (r < 0) Int.MAX_VALUE else r } }
            now[oldest] = pkg
        }
        save(c, TRAY, now)
        return true
    }

    fun trayRemove(c: Context, pkg: String) = save(c, TRAY, tray(c) - pkg)
    fun recents(c: Context): List<String> = list(c, RECENTS)

    fun togglePin(c: Context, pkg: String) {
        val now = pinned(c)
        save(c, PINNED, if (pkg in now) now - pkg else now + pkg)
    }

    fun movePin(c: Context, pkg: String, step: Int) {
        val now = pinned(c).toMutableList()
        val i = now.indexOf(pkg)
        val j = (i + step).coerceIn(0, now.lastIndex)
        if (i < 0 || i == j) return
        now.removeAt(i); now.add(j, pkg)
        save(c, PINNED, now)
    }

    /** Puts [pkg] at the front of the recents. Returns true when the list changed. */
    fun pushRecent(c: Context, pkg: String): Boolean {
        val now = recents(c)
        if (now.firstOrNull() == pkg) return false
        save(c, RECENTS, (listOf(pkg) + now.filter { it != pkg }).take(12))
        return true
    }

    fun forgetRecent(c: Context, pkg: String) {
        save(c, RECENTS, recents(c) - pkg)
        trayRemove(c, pkg)
    }

    private fun list(c: Context, key: String) =
        of(c).getString(key, "").orEmpty().split(',').filter { it.isNotBlank() }

    private fun save(c: Context, key: String, v: List<String>) =
        of(c).edit().putString(key, v.joinToString(",")).apply()
}
