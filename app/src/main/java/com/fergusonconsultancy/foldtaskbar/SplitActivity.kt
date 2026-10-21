package com.fergusonconsultancy.foldtaskbar

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper

/**
 * An invisible stepping stone for split screen.
 *
 * Android only honours "open beside me" when the request comes from an app running full
 * screen. The taskbar is a background service, so its requests were ignored and apps
 * opened full screen. This screen is ours and is full screen for a moment, so its request
 * counts: it opens the chosen app beside itself, then hands its own half to the app you
 * were using and quietly closes.
 */
class SplitActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)

        @Suppress("DEPRECATION") val target = intent.getParcelableExtra<ComponentName>(EXTRA_TARGET)
        @Suppress("DEPRECATION") val back = intent.getParcelableExtra<ComponentName>(EXTRA_BACK)
        if (target == null) { finish(); return }

        runCatching {
            startActivity(launcherIntent(target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT))
        }.onFailure { EventLog.add(this, "Split stepping stone: open beside failed (${it.javaClass.simpleName})") }

        Handler(Looper.getMainLooper()).postDelayed({
            if (back != null) runCatching {
                // Launched from inside the split, the previous app takes this screen's half
                startActivity(launcherIntent(back).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure { EventLog.add(this, "Split stepping stone: bringing back the previous app failed (${it.javaClass.simpleName})") }
            finish()
            @Suppress("DEPRECATION") overridePendingTransition(0, 0)
        }, HANDOVER_MS)
    }

    private fun launcherIntent(c: ComponentName) =
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(c)

    companion object {
        const val EXTRA_TARGET = "target"
        const val EXTRA_BACK = "back"
        private const val HANDOVER_MS = 320L
    }
}
