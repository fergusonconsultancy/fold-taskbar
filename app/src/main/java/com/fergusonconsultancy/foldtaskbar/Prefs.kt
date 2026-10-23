package com.fergusonconsultancy.foldtaskbar

import android.content.Context
import android.content.SharedPreferences

/**
 * The ways Android lets an ordinary app shape a switch between apps. Anything richer is
 * reserved for the system (Depth asked for it, and One UI refused).
 */
enum class Motion(val label: String) {
    FANALE("Fanale: a tail-light ring lights round the icon and the app arrives on a beaded edge of light (drawn by the bar)"),
    MEMBRANA("Membrana: the app arrives as a membrane, its edges bowing against their own speed and snapping taut (drawn by the bar)"),
    POUR("Pour: the app swells out of its icon like syrup (drawn by the bar)"),
    ZOOM("Zoom: the app's last screen grows out of its icon"),
    REVEAL("Reveal: the app opens out from its icon"),
    GROW("Grow: the app scales up from its icon"),
    SYSTEM("System animation"),
}

enum class Shape(val label: String) { PAIR("Hinge pair"), CAPSULE("Capsule") }

enum class Finish(val label: String, val fill: Int, val ink: Int, val edge: Int, val indicator: Int, val adaptive: Boolean = false) {
    /** Smoked glass reads what is behind it and turns dark over light screens, light over dark. */
    SMOKE("Smoked glass (adaptive)", 0xDB121614.toInt(), 0xFFE6E8E2.toInt(), 0x14FFFFFF, 0xFFD3B27C.toInt(), adaptive = true),
    /**
     * Membrane (23 Oct 2026): a thin, living skin. Like smoked glass it flips light over dark
     * and dark over light, but it is more translucent and takes far more of the colour beneath
     * each stretch of the bar, so the bar seems grown from the app under it.
     */
    MEMBRANE("Membrane (adaptive)", 0xC8141816.toInt(), 0xFFEDEAE2.toInt(), 0x1AFFFFFF, 0xFFD3B27C.toInt(), adaptive = true),
    /**
     * Rosso lacquer (23 Oct 2026): Alfa red that keeps its contrast with whatever is behind it,
     * deepening to oxblood over bright screens and lifting to a bright rosso over dark ones,
     * ivory ink and a champagne light throughout.
     */
    ROSSO("Rosso lacquer (adaptive)", 0xF0861018.toInt(), 0xFFF6F1E6.toInt(), 0x40D9BB86, 0xFFD9BB86.toInt(), adaptive = true),
    VISCONTI("Visconti", 0xF01A3F31.toInt(), 0xFFEFE6D3.toInt(), 0x73D3B27C, 0xFFD3B27C.toInt()),
    PAPER("Paper", 0xF2F6F4EE.toInt(), 0xFF1D2620.toInt(), 0x14000000, 0xFF1F4A3A.toInt()),
}

/** How the bar rests (Bellezza Necessaria, principle 3). */
enum class Presence(val label: String) { ALWAYS("Always"), TRIM("Trim") }

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
    private const val AUTO_HIDE = "auto_hide"
    private const val MOTION = "motion"
    private const val HANDOVER = "handover2"
    private const val PAIRS = "pairs"
    private const val COLLAPSED = "collapsed"

    val ICON_SIZES = listOf(40, 44, 52)
    val RECENT_COUNTS = listOf(3, 4, 5, 6)

    fun of(c: Context): SharedPreferences = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun shape(c: Context) = runCatching { Shape.valueOf(of(c).getString(SHAPE, null)!!) }.getOrDefault(Shape.PAIR)
    fun setShape(c: Context, v: Shape) = of(c).edit().putString(SHAPE, v.name).apply()

    fun finish(c: Context) = runCatching { Finish.valueOf(of(c).getString(FINISH, null)!!) }.getOrDefault(Finish.SMOKE)
    fun setFinish(c: Context, v: Finish) = of(c).edit().putString(FINISH, v.name).apply()

    fun iconDp(c: Context) = of(c).getInt(ICON_DP, 44)
    fun setIconDp(c: Context, v: Int) = of(c).edit().putInt(ICON_DP, v).apply()

    /** Where a capsule sits: its centre as fractions of the screen, and whether it stands upright. */
    data class Spot(val fx: Float, val fy: Float, val vertical: Boolean)

    fun spot(c: Context, shape: Shape, index: Int): Spot? = of(c).getString("spot_${shape}_$index", null)
        ?.split(',')?.takeIf { it.size == 3 }
        ?.let { runCatching { Spot(it[0].toFloat(), it[1].toFloat(), it[2] == "v") }.getOrNull() }

    fun setSpot(c: Context, shape: Shape, index: Int, s: Spot) =
        of(c).edit().putString("spot_${shape}_$index", "${s.fx},${s.fy},${if (s.vertical) "v" else "h"}").apply()

    fun resetSpots(c: Context) = of(c).edit().apply {
        of(c).all.keys.filter { it.startsWith("spot_") }.forEach { remove(it) }
        remove(COLLAPSED)
    }.apply()

    /** Collapsed to a tab: which edge (L, T, R, B) and how far along it, 0 to 1. Null when expanded. */
    fun collapsed(c: Context): Pair<Char, Float>? = of(c).getString(COLLAPSED, null)
        ?.split(',')?.takeIf { it.size == 2 }
        ?.let { runCatching { it[0][0] to it[1].toFloat() }.getOrNull() }

    fun setCollapsed(c: Context, v: Pair<Char, Float>?) = of(c).edit().apply {
        if (v == null) remove(COLLAPSED) else putString(COLLAPSED, "${v.first},${v.second}")
    }.apply()

    /** How an app arrives when you switch to it. */
    /** Experimental: hand dragged apps to Android's own split-screen drop zones. */
    fun systemDrag(c: Context) = of(c).getBoolean("system_drag", true)
    fun setSystemDrag(c: Context, v: Boolean) = of(c).edit().putBoolean("system_drag", v).apply()

    /**
     * The icon pack whose icons the bar and grid use: a package name, or null for the
     * phone's own icons. Until chosen, the first icon pack installed on the phone.
     */
    fun iconPack(c: Context): String? {
        val v = of(c).getString(ICON_PACK, null)
        if (v == "") return null
        val packs = IconPack.installed(c).map { it.first }
        // A chosen pack that is still installed; otherwise the first pack on the phone
        return v?.takeIf { it in packs } ?: packs.firstOrNull()
    }
    fun setIconPack(c: Context, v: String?) = of(c).edit().putString(ICON_PACK, v ?: "").apply()
    const val ICON_PACK = "icon_pack"

    fun motion(c: Context) = runCatching { Motion.valueOf(of(c).getString(MOTION, null)!!) }.getOrDefault(Motion.FANALE)
    fun setMotion(c: Context, v: Motion) = of(c).edit().putString(MOTION, v.name).apply()

    /** Once (23 Oct 2026): Pour was the default before Fanale, so a saved Pour moves to Fanale. */
    fun offerFanale(c: Context) {
        val p = of(c)
        if (p.getBoolean("fanale_offered", false)) return
        p.edit().putBoolean("fanale_offered", true).apply {
            if (p.getString(MOTION, null) == Motion.POUR.name) putString(MOTION, Motion.FANALE.name)
        }.apply()
    }

    /** Which way of filling the left half of a split last worked (see SplitActivity). */
    fun handover(c: Context) = of(c).getInt(HANDOVER, 0)
    fun setHandover(c: Context, v: Int) = of(c).edit().putInt(HANDOVER, v).apply()

    /** App pairs, left app first. */
    fun pairs(c: Context): List<Pair<String, String>> = list(c, PAIRS)
        .mapNotNull { it.split('|').takeIf { p -> p.size == 2 }?.let { p -> p[0] to p[1] } }

    fun addPair(c: Context, left: String, right: String) {
        val now = pairs(c).filter { it != (left to right) }
        save(c, PAIRS, (now + (left to right)).map { "${it.first}|${it.second}" })
    }

    fun removePair(c: Context, left: String, right: String) =
        save(c, PAIRS, pairs(c).filter { it != (left to right) }.map { "${it.first}|${it.second}" })

    /**
     * Presence: Always shows the bar at all times. Trim (the Giulietta principle) lets it rest as
     * a fine line of trim; touch it and the icons materialise around your finger, nearest first.
     */
    fun presence(c: Context) = runCatching { Presence.valueOf(of(c).getString("presence", null)!!) }.getOrDefault(Presence.ALWAYS)
    fun setPresence(c: Context, v: Presence) = of(c).edit().putString("presence", v.name).apply()

    /** Membrane: neighbours yield and lean when an app is pressed or lifted out of the bar. */
    fun membrane(c: Context) = of(c).getBoolean("membrane", true)
    fun setMembrane(c: Context, v: Boolean) = of(c).edit().putBoolean("membrane", v).apply()

    /** Concentrato: the faster you drag or scroll, the more the surroundings fall away. */
    fun concentrato(c: Context) = of(c).getBoolean("concentrato", true)
    fun setConcentrato(c: Context, v: Boolean) = of(c).edit().putBoolean("concentrato", v).apply()

    fun autoHide(c: Context) = of(c).getBoolean(AUTO_HIDE, false)
    fun setAutoHide(c: Context, v: Boolean) = of(c).edit().putBoolean(AUTO_HIDE, v).apply()

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
