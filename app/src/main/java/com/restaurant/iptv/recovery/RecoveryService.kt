package com.restaurant.iptv.recovery

import android.app.*
import android.content.*
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import com.restaurant.iptv.R
import com.restaurant.iptv.ui.MainActivity

/** Separate process so an app crash or blocked main looper cannot disable recovery. */
class RecoveryService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var lastPulse = 0L
    private var mainPid = 0
    private val prefs by lazy { getSharedPreferences("recovery_guard", MODE_PRIVATE) }
    private val check = object : Runnable {
        override fun run() {
            if (lastPulse > 0 && SystemClock.elapsedRealtime() - lastPulse > 45_000 &&
                getSystemService(PowerManager::class.java).isInteractive) {
                val now = System.currentTimeMillis()
                val attempts = prefs.getString("attempts", "").orEmpty().split(',').mapNotNull { it.toLongOrNull() }
                if (!RecoveryBudget.mayRestart(attempts, now)) {
                    Log.e(TAG, "Recovery paused: three failures within 15 minutes")
                    stopSelf(); return
                }
                val recent = RecoveryBudget.recent(attempts, now) + now
                if (!prefs.edit().putString("attempts", recent.joinToString(",")).commit()) { stopSelf(); return }
                Log.w(TAG, "Playback process unresponsive; recovery attempt ${recent.size}")
                // Same UID only; no other package can submit a PID to this unexported service.
                if (mainPid > 0 && mainPid != Process.myPid()) Process.killProcess(mainPid)
                lastPulse = SystemClock.elapsedRealtime()
                handler.postDelayed({
                    runCatching { startActivity(Intent(this@RecoveryService, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        .onFailure { Log.e(TAG, "Recovery launch denied", it) }
                }, 1500)
            }
            handler.postDelayed(this, 5000)
        }
    }
    override fun onCreate() {
        super.onCreate()
        promote()
        handler.post(check)
    }
    private fun promote() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
            NotificationChannel("recovery", "Playback recovery", NotificationManager.IMPORTANCE_LOW))
        startForeground(1002, NotificationCompat.Builder(this, "recovery")
            .setSmallIcon(R.mipmap.ic_launcher).setContentTitle("MMG TV")
            .setContentText("Playback recovery active").setOngoing(true).build())
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getBooleanExtra("arm", false) == true) promote()
        val pid = intent?.getIntExtra("pid", 0) ?: 0
        if (pid > 0) { mainPid = pid; lastPulse = SystemClock.elapsedRealtime() }
        return START_NOT_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy() }
    companion object {
        private const val TAG = "PlaybackRecovery"
        private var armed = false
        fun arm(context: Context) {
            runCatching {
                val intent = Intent(context, RecoveryService::class.java).putExtra("arm", true).putExtra("pid", Process.myPid())
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
                armed = true
            }.onFailure { Log.e(TAG, "Cannot arm recovery", it) }
        }
        fun pulse(context: Context) {
            if (armed) runCatching { context.startService(Intent(context, RecoveryService::class.java).putExtra("pid", Process.myPid())) }
        }
    }
}
