package com.restaurant.iptv.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.restaurant.iptv.ui.MainActivity

/**
 * On device boot, relaunch the app UI, which starts the playback service and
 * resumes the last channel. Launching the Activity (rather than starting the
 * foreground service directly) is the reliable path across Android 12+ /
 * Fire OS background-start restrictions.
 *
 * Note: some Fire TV models block third-party BOOT_COMPLETED auto-launch; in
 * that case pair this with a boot/auto-start utility as the handoff allowed.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            val launch = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(launch)
        }
    }
}
