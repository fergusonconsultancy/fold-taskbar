package com.fergusonconsultancy.foldtaskbar

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    private val ink = Color.rgb(236, 232, 223)
    private val muted = Color.rgb(160, 168, 160)
    private val champagne = Color.rgb(211, 178, 124)

    private lateinit var status: TextView
    private lateinit var logText: TextView
    private val choiceButtons = mutableListOf<Pair<Button, () -> String>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = dp(24)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }

        root.addView(TextView(this).apply {
            text = "Fold Taskbar"
            textSize = 32f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ink)
        })

        status = TextView(this).apply {
            textSize = 15f
            setTextColor(muted)
            setLineSpacing(0f, 1.3f)
            setPadding(0, pad / 2, 0, 0)
        }
        root.addView(status)

        heading(root, "Switch it on")
        root.addView(note(
            "1. Tap Accessibility settings, find Fold Taskbar under Installed apps and turn it on.\n" +
                "2. If Android says the setting is restricted, tap Allow restricted settings, then the menu " +
                "in the top corner, then Allow restricted settings. Go back and repeat step 1."
        ))
        root.addView(button("Accessibility settings") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        root.addView(button("Allow restricted settings") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        })

        heading(root, "Look")
        root.addView(choice({ "Shape: ${Prefs.shape(this).label}" }) {
            val all = Shape.entries
            Prefs.setShape(this, all[(all.indexOf(Prefs.shape(this)) + 1) % all.size])
        })
        root.addView(choice({ "Finish: ${Prefs.finish(this).label}" }) {
            val all = Finish.entries
            Prefs.setFinish(this, all[(all.indexOf(Prefs.finish(this)) + 1) % all.size])
        })
        root.addView(choice({ "Icon size: ${Prefs.iconDp(this)} dp" }) {
            val all = Prefs.ICON_SIZES
            Prefs.setIconDp(this, all[(all.indexOf(Prefs.iconDp(this)) + 1) % all.size])
        })
        root.addView(choice({ if (Prefs.autoHide(this)) "Auto-hide: on" else "Auto-hide: off (always showing)" }) {
            Prefs.setAutoHide(this, !Prefs.autoHide(this))
        })
        root.addView(choice({ if (Prefs.depth(this)) "App switching: Depth" else "App switching: system animation" }) {
            Prefs.setDepth(this, !Prefs.depth(this))
        })
        root.addView(button("Put the taskbar back at the bottom") {
            Prefs.resetSpots(this); refresh()
        })
        root.addView(choice({ "Recent apps shown: ${Prefs.recentCount(this)}" }) {
            val all = Prefs.RECENT_COUNTS
            Prefs.setRecentCount(this, all[(all.indexOf(Prefs.recentCount(this)) + 1) % all.size])
        })

        heading(root, "Using it")
        root.addView(note(
            "Tap an app to switch to it. Hold an app to open it in split screen or as a pop-up, or to pin it. " +
                "Pinned apps sit on the left, recent apps on the right. The grid opens all apps. " +
                "With auto-hide on, swipe up from just above the bottom edge, where the bar sits, to bring it back; " +
                "it slips away again a few seconds after you stop using it. Hold the grid button to switch auto-hide on or off. " +
                "Recent apps keep their place once they're in the bar; a new one replaces the one used longest ago. " +
                "Hold the bar and slide to carry it anywhere. Let go near an edge and it docks there, standing upright on the sides; near the crease it centres on it. Throw it mostly off the screen, or hold the grid button and choose Collapse to a tab, and it folds into a tab on that edge. Tap the tab to bring the bar back, or slide the tab along its edge. The bar shows on the main screen everywhere, including your home screen, and only moves aside for the keyboard if the keyboard would cover it."
        ))

        heading(root, "Event log")
        root.addView(note("If split screen or pop-ups misbehave, screenshot this and send it to Claude."))
        logText = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(muted)
            setLineSpacing(0f, 1.25f)
            setTextIsSelectable(true)
            setPadding(0, pad / 3, 0, 0)
        }
        root.addView(logText)
        root.addView(button("Clear log") { EventLog.clear(this); refresh() })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.rgb(14, 20, 17))
            addView(root)
        })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val enabled = serviceEnabled()
        status.text = buildString {
            appendLine(
                when {
                    enabled && TaskbarService.running -> "Taskbar: on"
                    enabled -> "Taskbar: switched on, waiting for Android to start it"
                    else -> "Taskbar: off. Follow the two steps below."
                }
            )
            append("App switching: ")
            append(if (Prefs.depth(this@MainActivity)) "Depth" else "system animation")
        }
        choiceButtons.forEach { (b, label) -> b.text = label() }
        logText.text = EventLog.read(this)
    }

    private fun serviceEnabled(): Boolean {
        val mine = ComponentName(this, TaskbarService::class.java).flattenToString()
        val list = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        return list.split(':').any { it.equals(mine, ignoreCase = true) }
    }

    private fun heading(root: LinearLayout, text: String) {
        root.addView(TextView(this).apply {
            this.text = text
            textSize = 13f
            letterSpacing = 0.12f
            isAllCaps = true
            setTextColor(champagne)
            setPadding(0, dp(28), 0, dp(6))
        })
    }

    private fun note(text: String) = TextView(this).apply {
        this.text = text
        textSize = 15f
        setTextColor(ink)
        setLineSpacing(0f, 1.3f)
    }

    private fun choice(label: () -> String, next: () -> Unit): Button =
        button(label()) { next(); refresh() }.also { choiceButtons += it to label }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 16f
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) }
        gravity = Gravity.CENTER
    }
}
