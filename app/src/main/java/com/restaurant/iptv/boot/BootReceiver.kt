package com.restaurant.iptv.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.restaurant.iptv.data.Prefs
import com.restaurant.iptv.player.PlaybackService
import com.restaurant.iptv.ui.MainActivity
import kotlinx.coroutines.runBlocking

/**
 * Auto-start on power-on for Fire TV / Google TV — with crash-loop protection.
 *
 * Some TV firmware (notably TCL/MediaTek) can hard-reboot under sustained
 * hardware decode. Auto-start would then resume the same stream and crash the
 * TV again, forever. So each boot is recorded; 3 boots within 15 minutes flips
 * the app into compatibility mode (software-lean decode + HLS + small buffers)
 * before resuming, which breaks the loop.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON" -> {
                runCatching { recordBootAndMaybeProtect(context) }
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

    private fun recordBootAndMaybeProtect(context: Context) = runBlocking {
        val prefs = Prefs(context)
        val now = System.currentTimeMillis()
        val recent = (prefs.bootStamps() + now).filter { now - it < WINDOW_MS }.takeLast(6)
        prefs.setBootStamps(recent)
        if (recent.size >= 3 && !prefs.compatMode()) {
            Log.w(TAG, "Crash-loop detected (${recent.size} boots in 15 min) — enabling compatibility mode")
            prefs.setCompatMode(true)
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
        private const val WINDOW_MS = 15 * 60_000L
    }
}
