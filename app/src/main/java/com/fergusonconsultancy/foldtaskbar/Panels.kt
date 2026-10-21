package com.fergusonconsultancy.foldtaskbar

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** The small menu that opens when you hold an app in the bar or the drawer. */
@SuppressLint("ViewConstructor")
class ActionMenu(
    context: Context,
    finish: Finish,
    title: String,
    rows: List<Pair<String, () -> Unit>>,
) : LinearLayout(context) {
    init {
        orientation = VERTICAL
        background = surface(context, finish, 18f)
        elevation = context.dp(8).toFloat()
        val pad = context.dp(6)
        setPadding(pad, pad, pad, pad)
        addView(TextView(context).apply {
            text = title
            textSize = 13f
            setTextColor(finish.ink)
            alpha = 0.6f
            setPadding(context.dp(14), context.dp(8), context.dp(14), context.dp(4))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        rows.forEach { (label, action) ->
            addView(TextView(context).apply {
                text = label
                textSize = 16f
                setTextColor(finish.ink)
                minHeight = context.dp(44)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(context.dp(14), 0, context.dp(20), 0)
                setOnClickListener { it.tick(); action() }
            })
        }
    }
}

/** Every app, alphabetically. Tap to open; hold for the same menu as the bar. */
@SuppressLint("ViewConstructor")
class AppDrawer(
    context: Context,
    apps: Apps,
    finish: Finish,
    widthPx: Int,
    onOpen: (String) -> Unit,
    onHold: (String, android.view.View) -> Unit,
) : LinearLayout(context) {
    init {
        orientation = VERTICAL
        background = surface(context, finish, 26f)
        elevation = context.dp(10).toFloat()
        val pad = context.dp(18)
        setPadding(pad, pad, pad, pad)

        addView(TextView(context).apply {
            text = "All apps"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(finish.ink)
            setPadding(context.dp(6), 0, 0, context.dp(12))
        })

        val cell = context.dp(84)
        val columns = ((widthPx - pad * 2) / cell).coerceIn(4, 8)
        val grid = GridLayout(context).apply { columnCount = columns }
        apps.all().forEach { app ->
            grid.addView(LinearLayout(context).apply {
                orientation = VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, context.dp(6), 0, context.dp(10))
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 0
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                }
                addView(ImageView(context).apply {
                    setImageDrawable(apps.icon(app.pkg))
                    layoutParams = LayoutParams(context.dp(48), context.dp(48))
                })
                addView(TextView(context).apply {
                    text = app.label
                    textSize = 12f
                    setTextColor(finish.ink)
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(context.dp(2), context.dp(6), context.dp(2), 0)
                })
                contentDescription = app.label
                setOnClickListener { it.tick(); onOpen(app.pkg) }
                setOnLongClickListener { it.thud(); onHold(app.pkg, it); true }
            })
        }
        addView(ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            addView(grid)
        }, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }
}
