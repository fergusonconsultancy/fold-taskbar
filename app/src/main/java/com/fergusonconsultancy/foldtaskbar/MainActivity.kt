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
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Fold Taskbar's settings (redesigned 23 Oct 2026). Setup appears only while the taskbar is
 * off; then how the bar looks, how apps arrive, how it behaves, and the guide and diagnostics
 * folded away at the foot.
 */
class MainActivity : Activity() {

    private lateinit var screen: Screen
    private lateinit var setup: LinearLayout
    private lateinit var iconsRow: Row
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

        // ---- Look
        screen.section("Look")
        screen.add(labelled("Shape", Segmented(this, Shape.entries.map { it.label }, Shape.entries.indexOf(Prefs.shape(this))) {
            Prefs.setShape(this, Shape.entries[it])
        }))
        screen.add(FinishPicker(), px(18))
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
            row(Row(this@MainActivity, "Auto-hide", "Swipe up where the bar sits to call it back",
                Toggle(this@MainActivity, Prefs.autoHide(this@MainActivity)) { Prefs.setAutoHide(this@MainActivity, it) }))
            row(Row(this@MainActivity, "One UI split screen", "Dragging an app hands it to One UI's own drop zones",
                Toggle(this@MainActivity, Prefs.systemDrag(this@MainActivity)) { Prefs.setSystemDrag(this@MainActivity, it) }))
            row(Row(this@MainActivity, "Put the bar back at the bottom", null, endValue("Reset", Kit.CHAMPAGNE)) {
                Prefs.resetSpots(this@MainActivity); refresh()
            })
        })

        // ---- Guide and diagnostics
        screen.section("Guide")
        val guide = Fold(this, "Using the bar")
        listOf(
            "Tap an app to switch to it. Hold an app for its menu: pin, pair and more.",
            "Press an app and slide it out of the bar for a split screen, or to the centre for a pop-up. In the grid, hold, then slide.",
            "Hold the grid button and slide to carry the bar. With the hinge pair, the second half has its own grip: hold it to move that half alone, so each can sit over its own app in split screen.",
            "Let go near an edge and it docks, upright on the sides. Near the crease it centres on it. Throw it mostly off the screen to collapse it to a tab.",
            "Icon Pack Studio packs need exporting with the free Icon Pack Studio Exporter before any app other than Smart Launcher can read them.",
        ).forEachIndexed { i, s ->
            guide.body.addView(text(s, 14.5f, Kit.INK, 400), LinearLayout.LayoutParams(-1, -2).apply { if (i > 0) topMargin = px(12) })
        }
        screen.add(guide)

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
            "  ·  switching: " + Prefs.motion(this).name.lowercase()
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

    /** The three finishes as tiles of the glass itself, the chosen one ringed in champagne. */
    private fun FinishPicker(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(text("Finish", 14f, Kit.MUTED, 450).apply { setPadding(px(4), 0, 0, px(8)) })
        val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
        val tiles = mutableListOf<View>()
        fun paint() = tiles.forEachIndexed { i, t ->
            t.background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Kit.SURFACE); cornerRadius = px(18).toFloat()
                setStroke(px(if (Finish.entries[i] == Prefs.finish(this@MainActivity)) 1.5f else 1f),
                    if (Finish.entries[i] == Prefs.finish(this@MainActivity)) Kit.CHAMPAGNE else Kit.HAIRLINE)
            }
        }
        Finish.entries.forEachIndexed { i, f ->
            val tile = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(px(10), px(10), px(10), px(12))
                addView(Swatch(this@MainActivity) { c, r ->
                    c.drawColor(if (f == Finish.PAPER) Color.rgb(120, 130, 124) else Color.rgb(46, 58, 52))
                    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = f.fill }
                    val bar = RectF(r.left + r.width() * 0.12f, r.centerY() - r.height() * 0.16f, r.right - r.width() * 0.12f, r.centerY() + r.height() * 0.16f)
                    c.drawRoundRect(bar, bar.height() / 2, bar.height() / 2, p)
                    p.color = f.ink
                    for (k in 0..3) c.drawCircle(bar.left + bar.width() * (0.2f + 0.2f * k), bar.centerY(), bar.height() * 0.2f, p)
                    p.color = f.indicator
                    c.drawRoundRect(bar.left + bar.width() * 0.33f, bar.bottom - bar.height() * 0.2f, bar.left + bar.width() * 0.47f, bar.bottom - bar.height() * 0.1f, 4f, 4f, p)
                }, LinearLayout.LayoutParams(-1, px(52)))
                addView(text(f.label.substringBefore(" ("), 13.5f, Kit.INK, 450).apply { setPadding(px(2), px(10), 0, 0) })
                setOnClickListener { Prefs.setFinish(this@MainActivity, f); paint(); it.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK) }
            }
            tiles += tile
            row.addView(tile, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) marginStart = px(10) })
        }
        paint()
        addView(row)
    }

    /** Each way of switching as a still: the window arriving from the icon, lit as that motion lights it. */
    private fun motionSwatch(m: Motion): (Canvas, RectF) -> Unit = { c, r ->
        c.drawColor(Color.rgb(28, 34, 31))
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val icon = RectF(r.right - r.height() * 0.42f, r.centerY() - r.height() * 0.16f, r.right - r.height() * 0.1f, r.centerY() + r.height() * 0.16f)
        val win = RectF(r.left + r.width() * 0.18f, r.top + r.height() * 0.14f, r.right - r.height() * 0.5f, r.bottom - r.height() * 0.14f)
        p.color = Color.rgb(70, 86, 78)
        c.drawRoundRect(icon, 6f, 6f, p)
        if (m != Motion.SYSTEM) {
            p.color = if (m == Motion.FANALE) Color.rgb(36, 30, 32) else Color.rgb(88, 104, 96)
            c.drawRoundRect(win, 8f, 8f, p)
        }
        if (m == Motion.FANALE) {
            p.color = Color.rgb(255, 128, 112)
            for (k in 0..7) c.drawCircle(win.left + 2f, win.top + win.height() * (0.08f + 0.12f * k), 2.2f, p)
            for (k in 0 until 10) {
                val a = Math.PI * 2 * k / 10
                c.drawCircle(icon.centerX() + icon.width() * 0.75f * Math.cos(a).toFloat(), icon.centerY() + icon.width() * 0.75f * Math.sin(a).toFloat(), 1.6f, p)
            }
        }
    }
}
