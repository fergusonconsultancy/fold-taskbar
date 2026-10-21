package com.fergusonconsultancy.foldtaskbar

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.HapticFeedbackConstants
import android.view.View

fun Context.dp(v: Number): Int = (v.toFloat() * resources.displayMetrics.density + 0.5f).toInt()

/** A filled shape with a hairline edge, the one surface every part of the bar is made from. */
fun surface(context: Context, finish: Finish, radiusDp: Float): GradientDrawable =
    GradientDrawable().apply {
        setColor(finish.fill)
        cornerRadius = context.dp(radiusDp).toFloat()
        setStroke(context.dp(1).coerceAtLeast(1), finish.edge)
    }

fun View.tick() = performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
fun View.thud() = performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
