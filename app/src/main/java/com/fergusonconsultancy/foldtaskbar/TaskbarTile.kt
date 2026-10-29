package com.fergusonconsultancy.foldtaskbar

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * The Quick Settings tile (29 Oct 2026): the one place that always answers "is the bar
 * running?" and always brings it back. Lit when the bar is running; tap it and the bar returns
 * from its tab, from auto-hide or from receding. Dim when Android has stopped it or it is
 * switched off; tap it and Accessibility settings open, where it is switched back on.
 */
class TaskbarTile : TileService() {

    override fun onStartListening() = paint()

    override fun onClick() {
        val svc = TaskbarService.instance
        if (svc != null) {
            svc.summon()
            // Close the shade so the bar is there to be seen
            runCatching { svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE) }
            paint()
            return
        }
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION") startActivityAndCollapse(intent)
        }
    }

    private fun paint() {
        val tile = qsTile ?: return
        val running = TaskbarService.instance != null
        val enabled = enabled()
        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Taskbar"
        if (android.os.Build.VERSION.SDK_INT >= 29) tile.subtitle = when {
            running -> "Tap to bring back"
            enabled -> "Stopped: tap to restart"
            else -> "Off: tap to turn on"
        }
        tile.updateTile()
    }

    private fun enabled(): Boolean {
        val mine = ComponentName(this, TaskbarService::class.java).flattenToString()
        val list = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        return list.split(':').any { it.equals(mine, ignoreCase = true) }
    }
}
