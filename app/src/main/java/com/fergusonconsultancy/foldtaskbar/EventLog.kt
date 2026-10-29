package com.fergusonconsultancy.foldtaskbar

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A short rolling log of what the bar tried and what happened, shown on the setup
 * screen. It exists so a screenshot of it can tell us what One UI allowed.
 */
object EventLog {
    private const val FILE = "fold_taskbar_log"
    private const val KEY = "event_log"
    private const val MAX_LINES = 60

    /** Writes at once, for a crash report: the process may not live to finish an ordinary write. */
    @Synchronized
    fun addNow(context: Context, line: String) {
        val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.UK).format(Date())
        val lines = (p.getString(KEY, "").orEmpty().lines().filter { it.isNotBlank() } + "$stamp  $line").takeLast(MAX_LINES)
        p.edit().putString(KEY, lines.joinToString("\n")).commit()
    }

    @Synchronized
    fun add(context: Context, line: String) {
        val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.UK).format(Date())
        val lines = (p.getString(KEY, "").orEmpty().lines().filter { it.isNotBlank() } + "$stamp  $line")
            .takeLast(MAX_LINES)
        p.edit().putString(KEY, lines.joinToString("\n")).apply()
    }

    fun read(context: Context): String =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()
            .ifBlank { "Nothing logged yet" }

    fun clear(context: Context) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}
