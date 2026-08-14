package com.restaurant.iptv.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.restaurant.iptv.player.PlaybackService
import com.restaurant.iptv.ui.MainActivity

/**
 * Auto-start on power-on for Fire TV / Google TV.
 *
 * Two-step, so something always comes up:
 *  1. Start the foreground PlaybackService — always allowed from a boot
 *     broadcast. That brings up stream resume, the watchdog, and the web
 *     control server even if the UI can't be shown yet.
 *  2. Try to launch the full-screen UI. On Android 10+/Fire OS 8+ this only
 *     works when the user has granted "Display over other apps" (MainActivity
 *     prompts for it once); on Fire OS 7 and older it works out of the box.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON" -> {
                runCatching { PlaybackService.start(context) }
                runCatching {
                    context.startActivity(
                        Intent(context, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
        }
    }
}
