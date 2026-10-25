package com.fergusonconsultancy.foldtaskbar

import android.content.Context
import android.content.SharedPreferences

/**
 * The ways Android lets an ordinary app shape a switch between apps. Anything richer is
 * reserved for the system (Depth asked for it, and One UI refused).
 */
enum class Motion(val label: String) {
    // Drawn by the bar itself (see Pour.kt), in the order offered
    FUOCO("Fuoco: the app behind falls out of focus while the new one rises from its icon, soft, and sharpens as it fills the screen (drawn by the bar)"),
    ONDA("Onda: the app wells up from its icon in a widening circle as the app behind falls out of focus (drawn by the bar)"),
    LAMA("Lama: two points of light race out of the icon drawing a blade along the bar, and the app is uncovered from it, light falling from each lip onto the app (drawn by the bar)"),
    REVEAL("Reveal: nothing moves but the opening. The app is already in place and a window widens from the icon to uncover it (drawn by the bar)"),
    GROW("Grow: the whole app, at its true proportions, scales up out of the icon (drawn by the bar)"),
    SYSTEM("System animation"),
    ;

    /** The motions the bar draws itself, and how. */
    val look: Look? get() = when (this) {
        FUOCO -> Look.FUOCO
        ONDA -> Look.ONDA; LAMA -> Look.LAMA
        REVEAL -> Look.REVEAL; GROW -> Look.GROW; SYSTEM -> null
    }
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
    /**
     * Avorio (25 Oct 2026): the Giulietta's ivory leather. Always the light one: ivory over dark
     * screens, deepening to warm stone over bright ones so it never disappears into white. Espresso
     * ink, and the active app marked in rosso.
     */
    AVORIO("Avorio (adaptive)", 0xF0F3ECDD.toInt(), 0xFF2A211A.toInt(), 0x22000000, 0xFF9E1B22.toInt(), adaptive = true),
    /**
     * Carbonio (25 Oct 2026): Bauhaus restraint. Always the dark one: graphite over bright screens,
     * lifting to charcoal with a brighter edge over dark ones so its outline holds. Ivory ink,
     * champagne light.
     */
    CARBONIO("Carbonio (adaptive)", 0xF216181A.toInt(), 0xFFE9E6DF.toInt(), 0x14FFFFFF, 0xFFD3B27C.toInt(), adaptive = true),
    /**
     * Ceramica (25 Oct 2026): Gaudí's glazed tile. Each stretch of the bar takes the hue beneath
     * it and fires it as glaze: a pale glaze over dark screens, a deep one over bright, with a
     * glaze highlight along the edge. Over grey, Gaudí's cobalt.
     */
    CERAMICA("Ceramica (adaptive)", 0xF2264E8C.toInt(), 0xFFF4F1EA.toInt(), 0x40FFFFFF, 0xFFE9CF9E.toInt(), adaptive = true),
    VISCONTI("Visconti", 0xF01A3F31.toInt(), 0xFFEFE6D3.toInt(), 0x73D3B27C, 0xFFD3B27C.toInt()),
    PAPER("Paper", 0xF2F6F4EE.toInt(), 0xFF1D2620.toInt(), 0x14000000, 0xFF1F4A3A.toInt()),
}

/** One face of a finish: the glass, its ink, its edge and its light. */
data class Face(val fill: Int, val ink: Int, val edge: Int, val glow: Int)

/**
 * How [finish] looks over a stretch of screen whose colour is [colour], when the bar should be
 * the light one ([light], over a dark screen) or the dark one. One definition, shared by the
 * bar and the app grid, so the grid always wears what the bar is wearing (25 Oct 2026).
 */
fun faceFor(finish: Finish, colour: Int, light: Boolean): Face {
    fun mixWith(base: Int, tint: Float, shift: Int) = ((((base shr shift) and 255) * (1 - tint)) + (((colour shr shift) and 255) * tint)).toInt()
    fun tinted(base: Int, tint: Float, alpha: Int) = (alpha shl 24) or (mixWith(base, tint, 16) shl 16) or (mixWith(base, tint, 8) shl 8) or mixWith(base, tint, 0)
    return when (finish) {
        Finish.MEMBRANE -> if (light) Face(tinted(0xF2EEE6, 0.42f, 0xC4), 0xFF141A16.toInt(), 0x26000000, 0xFF7A5A28.toInt())
            else Face(tinted(0x0E1210, 0.45f, 0xB8), 0xFFF4F1EA.toInt(), 0x24FFFFFF, 0xFFE2C48E.toInt())
        Finish.ROSSO -> if (light) Face(tinted(0xB8141E, 0.06f, 0xF0), 0xFFF6F1E6.toInt(), 0x55F6D9A8, 0xFFE9CF9E.toInt())
            else Face(tinted(0x6E0A12, 0.05f, 0xF2), 0xFFF6F1E6.toInt(), 0x40D9BB86, 0xFFE9CF9E.toInt())
        Finish.AVORIO -> if (light) Face(tinted(0xF3ECDD, 0.06f, 0xF0), 0xFF2A211A.toInt(), 0x22000000, 0xFF9E1B22.toInt())
            else Face(tinted(0xCFC3AE, 0.08f, 0xF2), 0xFF231B14.toInt(), 0x33000000, 0xFF8E1820.toInt())
        Finish.CARBONIO -> if (light) Face(tinted(0x2C2F31, 0.06f, 0xF0), 0xFFF1EEE7.toInt(), 0x40FFFFFF, 0xFFE2C48E.toInt())
            else Face(tinted(0x16181A, 0.04f, 0xF2), 0xFFE9E6DF.toInt(), 0x14FFFFFF, 0xFFD3B27C.toInt())
        Finish.CERAMICA -> {
            val hsv = FloatArray(3).also { android.graphics.Color.colorToHSV(colour, it) }
            val grey = hsv[1] < 0.08f
            val h = if (grey) 215f else hsv[0]
            val s = if (grey) 0.62f else hsv[1].coerceIn(0.45f, 0.72f)
            val glaze = android.graphics.Color.HSVToColor(0xF2, floatArrayOf(h, if (light) s * 0.55f else s, if (light) 0.86f else 0.34f))
            if (light) Face(glaze, 0xFF14181A.toInt(), 0x66FFFFFF, 0xFF6E4A16.toInt())
            else Face(glaze, 0xFFF4F1EA.toInt(), 0x40FFFFFF, 0xFFE9CF9E.toInt())
        }
        Finish.SMOKE -> if (light) Face(tinted(0xF4F0E8, 0.10f, 0xE8), 0xFF1A201C.toInt(), 0x1F000000, 0xFF8C6A33.toInt())
            else Face(tinted(0x101412, 0.14f, 0xDB), 0xFFEDEAE2.toInt(), 0x1AFFFFFF, 0xFFD3B27C.toInt())
        else -> Face(finish.fill, finish.ink, finish.edge, finish.indicator)
    }
}

/** Whether the bar should be the light one over a screen of luminance [lum], with hysteresis around [was]. */
fun lightOver(lum: Float, was: Boolean?): Boolean = when {
    lum < 0.36f -> true
    lum > 0.54f -> false
    else -> was ?: false
}

/** How the bar rests (Bellezza Necessaria, principle 3). */
enum class Presence(val label: String) { ALWAYS("Always"), TRIM("Trim"), RECEDE("Recede") }

/**
 * The bar's profile (25 Oct 2026). Round: the capsule, ends fully rounded. Slim: a lower, flatter
 * slab with tighter corners, Bauhaus in its restraint, giving the screen back a few points.
 */
enum class Profile(val label: String) { ROUND("Round"), SLIM("Slim") }

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

    /** Pour and Cascata were retired on 24 Oct 2026: a saved Pour becomes Membrana, a saved Cascata becomes Lama. */
    fun motion(c: Context): Motion = when (val s = of(c).getString(MOTION, null)) {
        "POUR", "MEMBRANA" -> Motion.LAMA // Membrana retired from the bar, 25 Oct 2026
        "ZOOM" -> Motion.GROW // Zoom retired from the bar, 25 Oct 2026
        "CASCATA" -> Motion.LAMA
        "FANALE" -> Motion.LAMA // Fanale retired from the bar, 25 Oct 2026
        null -> Motion.LAMA
        else -> runCatching { Motion.valueOf(s) }.getOrDefault(Motion.LAMA)
    }
    fun setMotion(c: Context, v: Motion) = of(c).edit().putString(MOTION, v.name).apply()

    /** Once (23 Oct 2026): Pour was the default before Fanale, so a saved Pour moves to Fanale. */
    fun offerFanale(c: Context) {
        val p = of(c)
        if (p.getBoolean("fanale_offered", false)) return
        p.edit().putBoolean("fanale_offered", true).apply {
            if (p.getString(MOTION, null) == "POUR") putString(MOTION, Motion.LAMA.name)
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
    fun profile(c: Context) = runCatching { Profile.valueOf(of(c).getString("profile", null)!!) }.getOrDefault(Profile.ROUND)
    fun setProfile(c: Context, v: Profile) = of(c).edit().putString("profile", v.name).apply()

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
