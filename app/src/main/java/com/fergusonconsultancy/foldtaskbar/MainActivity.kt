package com.fergusonconsultancy.foldtaskbar

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Fold Taskbar's settings, rebuilt to Bellezza Necessaria (23 Oct 2026). Setup appears only
 * while the taskbar is off. Then how the bar rests and looks, how apps arrive, how it behaves,
 * and the doctrine, design notes, guide and log folded away at the foot.
 */
class MainActivity : Activity() {

    private lateinit var screen: Screen
    private lateinit var setup: LinearLayout
    private lateinit var iconsRow: Row
    private lateinit var presenceDetail: TextView
    private lateinit var logText: TextView
    private lateinit var versionText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Kit.GROUND
        window.navigationBarColor = Kit.GROUND
        screen = Screen(this, "Fold Taskbar", "Galaxy Z Fold 8 Ultra")

        // ---- Setup, only while the taskbar is off
        setup = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val steps = Card(this)
        steps.row(Row(this, "1  Turn on Fold Taskbar", "Accessibility settings, Installed apps, Fold Taskbar", endValue("Open  ›", Kit.CHAMPAGNE)) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        steps.row(Row(this, "2  If it says restricted", "App info, the menu in the corner, Allow restricted settings. Then step 1 again.", endValue("Open  ›", Kit.CHAMPAGNE)) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        })
        setup.addView(eyebrow("Switch it on"))
        setup.addView(steps)
        screen.add(setup)

        // ---- How the bar rests
        screen.section("Presence")
        val presences = Presence.entries
        presenceDetail = text("", 13.5f, Kit.MUTED).apply { tag = SECONDARY; setPadding(px(4), px(10), 0, 0) }
        screen.add(Segmented(this, presences.map { it.label }, presences.indexOf(Prefs.presence(this))) {
            Prefs.setPresence(this, presences[it]); paintPresence()
        })
        screen.add(presenceDetail)
        paintPresence()

        // ---- Look
        screen.section("Look")
        screen.add(labelled("Shape", Segmented(this, Shape.entries.map { it.label }, Shape.entries.indexOf(Prefs.shape(this))) {
            Prefs.setShape(this, Shape.entries[it])
        }))
        screen.add(labelled("Profile", Segmented(this, Profile.entries.map { it.label }, Profile.entries.indexOf(Prefs.profile(this))) {
            Prefs.setProfile(this, Profile.entries[it])
        }), px(18))
        screen.add(finishPicker(), px(18))
        val sizes = Prefs.ICON_SIZES
        screen.add(labelled("Icon size", Segmented(this, listOf("Small", "Medium", "Large").take(sizes.size), sizes.indexOf(Prefs.iconDp(this)).coerceAtLeast(0)) {
            Prefs.setIconDp(this, sizes[it])
        }), px(18))
        val counts = Prefs.RECENT_COUNTS
        screen.add(labelled("Recent apps", Segmented(this, counts.map { "$it" }, counts.indexOf(Prefs.recentCount(this)).coerceAtLeast(0)) {
            Prefs.setRecentCount(this, counts[it])
        }), px(18))
        iconsRow = Row(this, "Icons", "", endValue("Change  ›", Kit.CHAMPAGNE)) {
            val options = listOf<String?>(null) + IconPack.installed(this).map { it.first }
            val now = options.indexOf(Prefs.iconPack(this)).coerceAtLeast(0)
            Prefs.setIconPack(this, options[(now + 1) % options.size])
            refresh()
        }
        screen.add(Card(this).apply { row(iconsRow) }, px(18))

        // ---- Motion
        screen.section("App switching")
        val motions = Motion.entries
        screen.add(ChoiceList(this, motions.map { m ->
            val name = m.label.substringBefore(":")
            val detail = m.label.substringAfter(": ", "").replaceFirstChar { it.uppercase() }.ifBlank { "Android's own animation" }
            Triple(name, detail, motionSwatch(m))
        }, motions.indexOf(Prefs.motion(this))) { Prefs.setMotion(this, motions[it]); refresh() })

        // ---- Behaviour
        screen.section("Behaviour")
        screen.add(Card(this).apply {
            row(Row(this@MainActivity, "Membrane", "Lift an app out and its neighbours yield towards the gap",
                Toggle(this@MainActivity, Prefs.membrane(this@MainActivity)) { Prefs.setMembrane(this@MainActivity, it) }))
            row(Row(this@MainActivity, "Concentrato", "The faster you drag or fling, the more everything else falls away",
                Toggle(this@MainActivity, Prefs.concentrato(this@MainActivity)) { Prefs.setConcentrato(this@MainActivity, it) }))
            row(Row(this@MainActivity, "Auto-hide", "Swipe up where the bar sits to call it back",
                Toggle(this@MainActivity, Prefs.autoHide(this@MainActivity)) { Prefs.setAutoHide(this@MainActivity, it) }))
            row(Row(this@MainActivity, "One UI split screen", "Dragging an app hands it to One UI's own drop zones",
                Toggle(this@MainActivity, Prefs.systemDrag(this@MainActivity)) { Prefs.setSystemDrag(this@MainActivity, it) }))
            row(Row(this@MainActivity, "Put the bar back at the bottom", null, endValue("Reset", Kit.CHAMPAGNE)) {
                Prefs.resetSpots(this@MainActivity); refresh()
            })
        })

        // ---- Doctrine, notes, guide, log
        screen.section("Doctrine")
        screen.add(doctrineCard(DOCTRINE_NOTES))
        screen.add(notesCard("Design notes", "Finishes, switching and the bar's behaviour", DESIGN_NOTES), px(12))

        screen.section("Guide")
        screen.add(notesCard("Using the bar", "Taps, drags, handles and docking", GUIDE))

        val diag = Fold(this, "Event log", "For Claude when something misbehaves")
        versionText = text("", 12.5f, Kit.MUTED).apply { typeface = Typeface.MONOSPACE }
        logText = text("", 12f, Kit.MUTED).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true) }
        diag.body.addView(versionText)
        diag.body.addView(logText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(12) })
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(primary("Clear log", filled = false) { EventLog.clear(this); refresh() }, LinearLayout.LayoutParams(0, -2, 1f))
        buttons.addView(primary("Split test", filled = false) {
            val svc = TaskbarService.instance
            if (svc == null) { EventLog.add(this, "Split test: switch the taskbar on first"); refresh() } else svc.runSplitTest()
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = px(10) })
        diag.body.addView(buttons, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(16) })
        screen.add(diag, px(12))

        setContentView(screen.root)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun paintPresence() {
        presenceDetail.text = when (Prefs.presence(this)) {
            Presence.ALWAYS -> "The bar is always there, in full."
            Presence.TRIM -> "The bar rests as a fine line of trim. Touch it and the icons rise around your finger; slide along it and they swell as you pass; lift on one to open it."
            Presence.RECEDE -> "Concentrato for the bar. While you scroll the app beneath, the bar steps back to a faint presence so what you are reading has the screen, and returns, unhurried, once you stop. Touch it and it is back at once."
        }
    }

    private fun refresh() {
        val enabled = serviceEnabled()
        when {
            enabled && TaskbarService.running -> screen.status.set("On", Kit.GOOD)
            enabled -> screen.status.set("Starting", Kit.WARN)
            else -> screen.status.set("Off", Kit.MUTED)
        }
        setup.visibility = if (enabled) View.GONE else View.VISIBLE
        val pack = Prefs.iconPack(this)
        val packs = IconPack.installed(this)
        iconsRow.setSub(when {
            pack != null -> packs.firstOrNull { it.first == pack }?.second ?: pack
            packs.isEmpty() && IconPack.studioInstalled(this) -> "The phone's own. Export your Icon Pack Studio pack first (see Guide)."
            else -> "The phone's own"
        })
        versionText.text = "Version " + runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrDefault("?") +
            "  ·  " + Prefs.motion(this).name.lowercase() + ", " + Prefs.finish(this).name.lowercase() + ", " + Prefs.presence(this).name.lowercase()
        logText.text = EventLog.read(this).ifBlank { "Nothing logged yet." }
    }

    private fun serviceEnabled(): Boolean {
        val mine = ComponentName(this, TaskbarService::class.java).flattenToString()
        val list = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        return list.split(':').any { it.equals(mine, ignoreCase = true) }
    }

    /** A control with its name above it. */
    private fun labelled(name: String, control: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(text(name, 14f, Kit.MUTED, 450).apply { setPadding(px(4), 0, 0, px(8)) })
        addView(control, LinearLayout.LayoutParams(-1, -2))
    }

    /**
     * The finishes as tiles of the glass itself. Each tile is half bright and half dark behind,
     * so the adaptive finishes show both of their faces at once; the chosen one is ringed.
     */
    private fun finishPicker(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(text("Finish", 14f, Kit.MUTED, 450).apply { setPadding(px(4), 0, 0, px(8)) })
        val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
        val tiles = mutableListOf<View>()
        fun paint() = tiles.forEachIndexed { i, t ->
            val on = Finish.entries[i] == Prefs.finish(this@MainActivity)
            t.background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Kit.SURFACE); cornerRadius = px(18).toFloat()
                setStroke(px(if (on) 1.5f else 1f), if (on) Kit.CHAMPAGNE else Kit.HAIRLINE)
            }
        }
        Finish.entries.forEach { f ->
            val tile = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(px(10), px(10), px(10), px(12))
                addView(Swatch(this@MainActivity) { c, r -> finishSwatch(c, r, f) }, LinearLayout.LayoutParams(px(112), px(56)))
                addView(text(f.label.substringBefore(" ("), 13.5f, Kit.INK, 450).apply { setPadding(px(2), px(10), 0, 0) })
                addView(text(if (f.adaptive) "Adaptive" else "Fixed", 11.5f, if (f.adaptive) Kit.CHAMPAGNE else Kit.MUTED, 500).apply { letterSpacing = 0.08f; setPadding(px(2), px(2), 0, 0) })
                setOnClickListener { Prefs.setFinish(this@MainActivity, f); paint(); refresh(); it.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK) }
            }
            tiles += tile
            row.addView(tile, LinearLayout.LayoutParams(-2, -2).apply { if (tiles.size > 1) marginStart = px(10) })
        }
        val detail = text(finishDetail(Prefs.finish(this@MainActivity)), 13.5f, Kit.MUTED).apply { tag = SECONDARY; setPadding(px(4), px(10), 0, 0) }
        tiles.forEachIndexed { i, t ->
            val f = Finish.entries[i]
            t.setOnClickListener { Prefs.setFinish(this@MainActivity, f); paint(); detail.text = finishDetail(f); refresh(); it.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK) }
        }
        paint()
        addView(HorizontalScrollView(this@MainActivity).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(row)
        })
        addView(detail)
    }

    /** One line on each finish's character, under the tiles. The app grid always wears the same. */
    private fun finishDetail(f: Finish) = when (f) {
        Finish.SMOKE -> "Smoked glass: dark over light screens, light over dark, with a trace of the colour beneath."
        Finish.MEMBRANE -> "A thin, living skin: more translucent, taking far more of the colour beneath, as if grown from the app."
        Finish.ROSSO -> "Alfa red that keeps its contrast: oxblood over bright screens, a brighter rosso over dark."
        Finish.AVORIO -> "The Giulietta's ivory leather. Always the light one, deepening to warm stone over bright screens; rosso marks the app in use."
        Finish.CARBONIO -> "Bauhaus restraint. Always the dark one, lifting to charcoal with a brighter edge over dark screens so its outline holds."
        Finish.CERAMICA -> "Gaudí's glazed tile: each stretch takes the hue beneath and fires it as glaze, pale over dark screens, deep over bright. Over grey, cobalt."
        Finish.VISCONTI -> "Visconti green with champagne light, the same everywhere."
        Finish.PAPER -> "Pale paper and green ink, the same everywhere."
    } + " The app grid wears the same finish."

    /** How each finish looks over a bright screen (left) and a dark one (right). */
    private fun finishSwatch(c: Canvas, r: RectF, f: Finish) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val mid = r.centerX()
        p.color = Color.rgb(226, 220, 206); c.drawRect(r.left, r.top, mid, r.bottom, p)
        p.color = Color.rgb(22, 30, 40); c.drawRect(mid, r.top, r.right, r.bottom, p)
        val bar = RectF(r.left + r.width() * 0.08f, r.centerY() - r.height() * 0.17f, r.right - r.width() * 0.08f, r.centerY() + r.height() * 0.17f)
        // Each half of the bar takes the face it would show over that half of the screen
        // The same faces the bar itself wears (faceFor): over a pale page, then over a dark one
        fun face(overLight: Boolean): Pair<Int, Int> =
            if (!f.adaptive) f.fill to f.ink
            else faceFor(f, if (overLight) 0xFFE2DCCE.toInt() else 0xFF1E2A3A.toInt(), light = !overLight).let { it.fill to it.ink }
        val rad = bar.height() / 2
        for (half in 0..1) {
            val (fill, ink) = face(overLight = half == 0)
            c.save()
            if (half == 0) c.clipRect(r.left, r.top, mid, r.bottom) else c.clipRect(mid, r.top, r.right, r.bottom)
            p.color = fill; c.drawRoundRect(bar, rad, rad, p)
            p.color = ink
            for (k in 0..4) c.drawCircle(bar.left + bar.width() * (0.14f + 0.18f * k), bar.centerY(), bar.height() * 0.2f, p)
            c.restore()
        }
    }

    /** Each way of switching as a still: the window arriving from the icon, as that motion draws it. */
    private fun motionSwatch(m: Motion): (Canvas, RectF) -> Unit = { c, r ->
        c.drawColor(Color.rgb(28, 34, 31))
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val icon = RectF(r.right - r.height() * 0.42f, r.centerY() - r.height() * 0.16f, r.right - r.height() * 0.1f, r.centerY() + r.height() * 0.16f)
        val win = RectF(r.left + r.width() * 0.18f, r.top + r.height() * 0.14f, r.right - r.height() * 0.5f, r.bottom - r.height() * 0.14f)
        p.color = Color.rgb(70, 86, 78)
        c.drawRoundRect(icon, 6f, 6f, p)
        when (m) {
            Motion.SYSTEM -> Unit
            Motion.FUOCO -> {
                // The app behind out of focus, the new one rising sharp from the icon
                p.color = Color.argb(140, 120, 132, 126)
                for (k in 0..5) c.drawCircle(r.left + r.width() * (0.1f + 0.15f * k), r.centerY() + (if (k % 2 == 0) -6f else 6f), r.height() * 0.22f, p)
                p.color = Color.rgb(98, 116, 106); c.drawRoundRect(win, 8f, 8f, p)
            }
            Motion.LAMA -> {
                // The slit: the app uncovered between two lips of champagne light, drawn from the icon
                val slit = RectF(win.left, win.centerY() - win.height() * 0.24f, win.right, win.centerY() + win.height() * 0.24f)
                p.color = Color.rgb(98, 116, 106); c.drawRect(slit, p)
                p.color = Kit.CHAMPAGNE
                c.drawRect(win.left, slit.top - 1f, icon.centerX(), slit.top + 1f, p)
                c.drawRect(win.left, slit.bottom - 1f, icon.centerX(), slit.bottom + 1f, p)
            }
            Motion.ONDA -> {
                // A ripple welling up from the icon, its rim a fine meniscus
                c.save(); c.clipRect(r)
                p.color = Color.rgb(98, 116, 106); c.drawCircle(icon.centerX(), icon.centerY(), r.width() * 0.62f, p)
                p.style = Paint.Style.STROKE; p.strokeWidth = 1.6f; p.color = Kit.CHAMPAGNE; c.drawCircle(icon.centerX(), icon.centerY(), r.width() * 0.62f, p); p.style = Paint.Style.FILL
                c.restore()
                p.color = Color.rgb(70, 86, 78); c.drawRoundRect(icon, 6f, 6f, p)
            }
            else -> {
                p.color = Color.rgb(88, 104, 96)
                c.drawRoundRect(win, 8f, 8f, p)
            }
        }
    }

    companion object {
        val DOCTRINE_NOTES = listOf(
            "Membrane" to "The bar is one surface. Lift an app out and its neighbours are drawn towards the gap, nearest most, while the glass swells where it left, then springs back. A tap still ripples outwards. Membrana switching treats the arriving app as a skin whose edges bow against their own speed.",
            "Concentrato" to "While an app is carried out of the bar, everything else on the bar steps back. Drag fast and the drop guide sheds its outline, crease line and words, leaving the app and the place it will land. Fling the grid and its labels fall away, returning as it slows.",
            "The Giulietta principle" to "Presence: Trim lets the bar rest as a fine line of trim. It wakes under your finger, the icons rising outwards from where you touched, and swelling as your finger slides past them. Lift on one to open it. The first touch only ever wakes it, so nothing opens by accident.",
        )
        val DESIGN_NOTES = listOf(
            "Smoked glass" to "Reads the screen beyond each of eight stretches of the bar. Dark over light content, light over dark, with a trace of the colour underneath.",
            "Membrane" to "Adaptive like smoked glass, but thin and translucent, taking far more of the colour beneath, so the bar seems grown from the app under it. Gaudí's rule: nothing exists in isolation.",
            "Rosso lacquer" to "Alfa red that keeps its contrast: oxblood over bright screens, a brighter rosso over dark ones. Ivory ink and a champagne light throughout.",
            "Avorio, Carbonio, Ceramica" to "Three more adaptive finishes. Avorio is the Giulietta's ivory leather, always the light one, deepening to warm stone over bright screens, with rosso marking the app in use. Carbonio is Bauhaus restraint, always the dark one, lifting to charcoal with a brighter edge over dark screens so its outline holds. Ceramica is Gaudí's glazed tile: each stretch of the bar fires the hue beneath it as glaze, pale over dark screens and deep over bright.",
            "One finish, everywhere" to "The app grid wears whatever the bar is wearing, adapted the same way to the screen it opens over. Over a dark screen smoked glass turns to light glass with dark ink in the grid exactly as it does on the bar.",
            "Profile" to "Round is the capsule, its ends fully rounded. Slim is a lower, flatter slab with tighter corners: Bauhaus in its restraint, giving the screen back a few points.",
            "Recede" to "A third presence, and Concentrato for the bar. While you scroll the app beneath, the bar steps back to a faint presence, easing a little smaller, so what you are reading has the screen. It returns unhurried once you stop, and at once if you touch it.",
            "The entrance" to "After an unfold the bar does not simply appear. Once the screen is whole (Fold Motion tells the bar when its reveal has ended) the bar rises a little way from the edge it rests against on a long, soft ease, and its icons settle in from the middle outwards. No bounce: an invitation.",
            "One landing" to "Every switch drawn by the bar lands on one deceleration and stops exactly on the app's own window, pixel for pixel, so the hand-over to the live app is a cut you cannot see. Nothing overshoots and comes back. What you leave recedes into the bar's dark ground as the new app comes forward.",
            "Fuoco and Onda" to "The depth switches. The screen you are leaving falls out of focus behind the arriving app. Fuoco is a focus pull: the app rises soft from the icon and sharpens as it fills the screen. Onda opens as a circle from the icon, its rim a fine meniscus. Cycle within 700ms of the last switch and Concentrato makes each one quicker and shallower.",
            "Lama" to "The Alfa light line as a switch, and the bar's signature. Two points of light race out of the icon along the bar, drawing a fine champagne blade, and the slit opens hard behind them, uncovering the app in place. Each lip carries a thin line of light onto the app and a faint shadow onto the cover. The blade always runs along the bar, wherever it sits.",
            "No colour blocks" to "Until the bar has a picture of an app, its motion is carried by light over the bar's own dark glass, never a block of the app's colour. Pictures are kept privately in the app's own storage, so they survive a restart; secure screens such as banking apps are never kept.",
            "Reveal and Grow" to "Drawn by the bar, because One UI ignores Android's classic launch animations and plays its own task switch. Reveal: nothing scales; a window widens from the icon and uncovers the app in place. Grow: the whole app at its true proportions scales up out of the icon.",
            "No friction" to "Every motion but System launches the app with Android's own animation switched off. The instant you tap, the screen you are leaving is pictured and held still under the motion, so the new app is never glimpsed before its moment, and the hand-over waits until the app has drawn its real screen, never its splash.",
            "The active-app pill" to "Adaptive. It reads the glass beneath it, zone by zone, and takes whichever tone stands out: light champagne with a soft glow and a dark ring over dark glass, deep bronze with a light ring over light glass. It is a little longer and thicker than before, and it still travels between apps as a liquid.",
            "Two handles" to "With the hinge pair, each half has its own handle: the grid button and a grip of six dots. Hold either and slide to move that half alone.",
        )
        val GUIDE = listOf(
            "Switching" to "Tap an app to switch to it. Hold an app for its menu: pin, pair and more.",
            "Split screen and pop-ups" to "Press an app and slide it out of the bar for a split screen, or to the centre for a pop-up. In the grid, hold, then slide.",
            "Moving the bar" to "Hold the grid button or the grip and slide. Let go near an edge and it docks, upright on the sides. Near the crease it centres on it. Throw it mostly off the screen to collapse it to a tab.",
            "Icon packs" to "Icon Pack Studio packs need exporting with the free Icon Pack Studio Exporter before any app other than Smart Launcher can read them.",
        )
    }
}
