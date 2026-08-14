package com.restaurant.iptv.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.restaurant.iptv.R
import com.restaurant.iptv.data.Prefs
import com.restaurant.iptv.data.Repository
import com.restaurant.iptv.data.entity.ChannelEntity
import com.restaurant.iptv.server.WebServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The appliance heart. Owns the ExoPlayer, an independent watchdog that
 * recovers dead/frozen streams forever, and the embedded web server.
 * Runs as a mediaPlayback foreground service so it survives the Activity
 * being torn down and keeps the CPU/Wi-Fi awake for 10+ hours unattended.
 */
class PlaybackService : Service(), PlaybackCommands {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var repo: Repository
    private lateinit var prefs: Prefs
    private var wakeLock: PowerManager.WakeLock? = null

    private var player: ExoPlayer? = null
    private var webServer: WebServer? = null

    private var currentChannel: ChannelEntity? = null
    private var recovering = false
    private var recoveryJob: Job? = null

    // Watchdog progress tracking
    private var lastPosition = 0L
    private var lastProgressAt = 0L
    private var bufferingSince = 0L
    private var lastRenderedFrames = -1
    private var lastRenderedAt = 0L

    /** Activity sets this while bound so it can re-attach its surface if we
     *  recreate the player mid-recovery. */
    @Volatile var onPlayerReady: ((ExoPlayer) -> Unit)? = null

    override fun onBind(intent: Intent?): IBinder = LocalBinder()
    inner class LocalBinder : android.os.Binder() {
        val service: PlaybackService get() = this@PlaybackService
    }

    val activePlayer: ExoPlayer? get() = player

    override fun onCreate() {
        super.onCreate()
        instance = this
        repo = Repository(applicationContext)
        prefs = Prefs(applicationContext)

        startForeground(NOTIF_ID, buildNotification("Starting…"))
        acquireWakeLock()
        compat = kotlinx.coroutines.runBlocking {
            runCatching {
                // First run on a TCL panel: default compatibility mode ON as the
                // safeguard (their decoder firmware hard-reboots under sustained
                // hardware decode). Explicit web-toggle choices always win.
                if (!prefs.compatModeSet() && isFragilePanel()) {
                    Log.w(TAG, "TCL panel detected — defaulting compatibility mode ON")
                    prefs.setCompatMode(true)
                }
                prefs.compatMode()
            }.getOrDefault(false)
        }
        buildPlayer()
        startWebServer()
        startWatchdog()

        scope.launch { resumeLastOrIdle() }
        // Load EPG for the active provider in the background on startup.
        scope.launch {
            repo.getActiveProvider()?.let { runCatching { repo.refreshEpg(it.id) } }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_CHANNEL -> {
                val id = intent.getLongExtra(EXTRA_CHANNEL_ID, -1L)
                if (id > 0) cmdPlayChannel(id)
            }
            ACTION_RESUME -> scope.launch { resumeLastOrIdle() }
            ACTION_STOP -> cmdStop()
        }
        return START_STICKY
    }

    // ---------------- Player setup ----------------

    /** Compatibility mode: gentler decode path for TVs whose firmware crashes
     *  under sustained hardware decode (seen on TCL/MediaTek panels). */
    @Volatile private var compat = false

    /** Panels whose firmware is known to reboot under sustained hardware decode. */
    private fun isFragilePanel(): Boolean {
        val m = Build.MANUFACTURER.orEmpty().lowercase()
        val b = Build.BRAND.orEmpty().lowercase()
        return m.contains("tcl") || b.contains("tcl")
    }

    private fun buildPlayer() {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
            .setKeepPostFor302Redirects(true)

        // Generous buffers favour stability over latency for unattended TV.
        // Compat: smaller buffers to reduce memory pressure on low-RAM panels.
        val loadControl = (
            if (compat) DefaultLoadControl.Builder()
                .setBufferDurationsMs(10_000, 30_000, 1_500, 3_000)
                .setBackBuffer(0, false)
            else DefaultLoadControl.Builder()
                .setBufferDurationsMs(15_000, 60_000, 2_500, 5_000)
            ).build()

        // Always allow decoder fallback (if the primary hardware decoder fails,
        // try the next one instead of dying). Compat keeps HARDWARE decoding —
        // these panels run TiviMate smoothly on it; software decode lags. The
        // gentler part of compat is the feeding path: HLS container + smaller
        // buffers (see loadControl above and loadIntoPlayer below).
        val renderers = androidx.media3.exoplayer.DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)

        val p = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
            .setLoadControl(loadControl)
            .build()
        p.setWakeMode(C.WAKE_MODE_NETWORK) // ExoPlayer holds CPU+Wi-Fi during playback
        p.setHandleAudioBecomingNoisy(false)
        p.repeatMode = Player.REPEAT_MODE_OFF
        p.playWhenReady = true
        p.addListener(playerListener)
        player = p
        onPlayerReady?.invoke(p)
    }

    private fun recreatePlayer() {
        Log.w(TAG, "Hard-recreating player instance")
        try { player?.release() } catch (_: Throwable) {}
        buildPlayer()
        PlaybackState.update { it.copy(recreateCount = it.recreateCount + 1) }
    }

    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "Player error: ${error.errorCodeName}", error)
            PlaybackState.update { it.copy(lastError = error.errorCodeName) }
            requestRecovery("player_error:${error.errorCodeName}")
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_BUFFERING) {
                if (bufferingSince == 0L) bufferingSince = System.currentTimeMillis()
                setState(PlayState.BUFFERING)
            } else {
                bufferingSince = 0L
            }
            if (state == Player.STATE_READY) {
                lastProgressAt = System.currentTimeMillis()
                if (!recovering) setState(PlayState.PLAYING)
            }
            if (state == Player.STATE_ENDED) {
                // Live streams should not end; treat as a failure.
                requestRecovery("stream_ended")
            }
        }
    }

    // ---------------- Commands (PlaybackCommands) ----------------

    override fun cmdPlayChannel(channelId: Long) {
        scope.launch {
            val ch = repo.getChannel(channelId) ?: return@launch
            playChannel(ch)
        }
    }

    override fun cmdStop() {
        scope.launch {
            currentChannel = null
            cancelRecovery()
            player?.stop()
            player?.clearMediaItems()
            setState(PlayState.IDLE)
            updateNotification("Stopped")
        }
    }

    override fun cmdRetryNow() {
        scope.launch { currentChannel?.let { requestRecovery("manual_retry") } }
    }

    override fun cmdApplyCompat() {
        scope.launch {
            compat = runCatching { prefs.compatMode() }.getOrDefault(false)
            recreatePlayer()
            currentChannel?.let { playChannel(it) } ?: resumeLastOrIdle()
        }
    }

    override fun cmdRefreshAndResume() {
        scope.launch {
            val prov = repo.getActiveProvider() ?: return@launch
            repo.refreshProvider(prov.id)
            resumeLastOrIdle()
            launch { runCatching { repo.refreshEpg(prov.id) } }
        }
    }

    // ---------------- Core playback ----------------

    private suspend fun playChannel(ch: ChannelEntity) {
        cancelRecovery()
        currentChannel = ch
        loadIntoPlayer(ch)
        setState(PlayState.BUFFERING, ch)
        updateNotification("▶ ${ch.name}")
        prefs.setLastChannel(ch.providerId, ch.id)
        PlaybackState.update { it.copy(retryCount = 0) }
    }

    private fun loadIntoPlayer(ch: ChannelEntity) {
        val p = player ?: return
        // Compat: use the HLS container instead of raw TS — a different demux
        // path that avoids the code TCL firmware tends to crash in.
        val url = if (compat && ch.streamUrl.endsWith(".ts"))
            ch.streamUrl.removeSuffix(".ts") + ".m3u8" else ch.streamUrl
        val item = MediaItem.fromUri(url)
        p.setMediaItem(item)
        p.prepare()
        p.playWhenReady = true
        lastPosition = 0L
        lastProgressAt = System.currentTimeMillis()
        bufferingSince = 0L
        lastRenderedFrames = -1
        lastRenderedAt = System.currentTimeMillis()
    }

    private suspend fun resumeLastOrIdle() {
        val lastCh = prefs.lastChannelId()?.let { repo.getChannel(it) }
        if (lastCh != null) {
            playChannel(lastCh)
            return
        }
        // No remembered channel: fall back to the first visible channel of the active provider.
        val prov = repo.getActiveProvider()
        if (prov != null) {
            val first = repo.getVisibleChannels(prov.id).firstOrNull()
            if (first != null) {
                playChannel(first)
                return
            }
            setState(PlayState.IDLE)
            updateNotification("No channels — refresh in web UI")
        } else {
            setState(PlayState.NO_PROVIDER)
            updateNotification("Set up a provider in the web UI")
        }
    }

    // ---------------- Watchdog + recovery ----------------

    private fun startWatchdog() {
        scope.launch {
            while (true) {
                delay(WATCHDOG_INTERVAL_MS)
                try { watchdogTick() } catch (t: Throwable) { Log.e(TAG, "watchdog", t) }
            }
        }
    }

    private fun watchdogTick() {
        val p = player ?: return
        val ch = currentChannel ?: return
        if (recovering) { PlaybackState.update { it.copy(lastHeartbeat = System.currentTimeMillis()) }; return }

        val now = System.currentTimeMillis()
        when (p.playbackState) {
            Player.STATE_BUFFERING -> {
                if (bufferingSince != 0L && now - bufferingSince > STALL_TIMEOUT_MS) {
                    requestRecovery("buffering_timeout")
                }
            }
            Player.STATE_READY -> {
                if (p.isPlaying) {
                    val pos = p.currentPosition
                    if (pos != lastPosition) {
                        lastPosition = pos
                        lastProgressAt = now
                        setState(PlayState.PLAYING, ch)
                    } else if (now - lastProgressAt > FROZEN_TIMEOUT_MS) {
                        requestRecovery("no_progress")
                        return
                    }
                    // Position advancing is driven by the AUDIO clock — video can
                    // go black/frozen while it keeps ticking. So also verify the
                    // video renderer is actually outputting frames.
                    if (p.videoFormat != null) {
                        val rendered = try {
                            p.videoDecoderCounters?.renderedOutputBufferCount ?: -1
                        } catch (_: Throwable) { -1 }
                        if (rendered >= 0) {
                            if (rendered != lastRenderedFrames) {
                                lastRenderedFrames = rendered
                                lastRenderedAt = now
                            } else if (lastRenderedAt != 0L && now - lastRenderedAt > VIDEO_FROZEN_TIMEOUT_MS) {
                                requestRecovery("video_frozen")
                            }
                        }
                    }
                }
            }
            Player.STATE_IDLE -> {
                // Idle while we have a channel means playback fell over.
                requestRecovery("idle_with_channel")
            }
        }
        PlaybackState.update { it.copy(lastHeartbeat = now) }
    }

    private fun requestRecovery(reason: String) {
        if (recovering) return
        val ch = currentChannel ?: return
        recovering = true
        setState(PlayState.RECOVERING, ch)
        Log.w(TAG, "Recovery started: $reason")
        recoveryJob = scope.launch {
            var localTry = 0
            while (currentChannel != null) {
                localTry++
                PlaybackState.update { it.copy(retryCount = it.retryCount + 1, lastError = reason) }
                val backoff = backoffMs(localTry)
                updateNotification("Reconnecting ${ch.name} (try $localTry)…")
                delay(backoff)
                if (currentChannel == null) break

                // Escalate to a full player recreate every few attempts.
                if (localTry % RECREATE_EVERY == 0) {
                    recreatePlayer()
                }
                currentChannel?.let { loadIntoPlayer(it) }

                // Probe: give it time to come back.
                delay(RECOVERY_PROBE_MS)
                val p = player
                if (p != null && p.playbackState == Player.STATE_READY && p.isPlaying) {
                    Log.i(TAG, "Recovery succeeded after $localTry attempt(s)")
                    lastProgressAt = System.currentTimeMillis()
                    lastRenderedFrames = -1
                    lastRenderedAt = System.currentTimeMillis()
                    recovering = false
                    setState(PlayState.PLAYING, ch)
                    updateNotification("▶ ${ch.name}")
                    PlaybackState.update { it.copy(retryCount = 0) }
                    return@launch
                }
                // else loop again — no practical retry limit.
            }
            recovering = false
        }
    }

    private fun cancelRecovery() {
        recovering = false
        recoveryJob?.cancel()
        recoveryJob = null
    }

    /** 1s, 2s, 4s, 8s, 15s, then capped at 30s, with light jitter. */
    private fun backoffMs(attempt: Int): Long {
        val base = when {
            attempt <= 1 -> 1_000L
            attempt == 2 -> 2_000L
            attempt == 3 -> 4_000L
            attempt == 4 -> 8_000L
            attempt == 5 -> 15_000L
            else -> 30_000L
        }
        val jitter = (attempt * 137L) % 800L
        return base + jitter
    }

    // ---------------- State + notification ----------------

    private fun setState(state: PlayState, ch: ChannelEntity? = currentChannel) {
        PlaybackState.update {
            val changed = it.state != state
            it.copy(
                state = state,
                channelId = ch?.id,
                channelName = ch?.name,
                providerId = ch?.providerId,
                stateSince = if (changed) System.currentTimeMillis() else it.stateSince,
                lastHeartbeat = System.currentTimeMillis()
            )
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$TAG:wake").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun startWebServer() {
        scope.launch {
            val port = prefs.serverPort()
            withContext(Dispatchers.IO) {
                webServer = WebServer(applicationContext, this@PlaybackService, repo, port).also { it.start() }
            }
        }
    }

    private fun buildNotification(text: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Playback", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MMG TV")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.notify(NOTIF_ID, buildNotification(text))
    }

    override fun onDestroy() {
        cancelRecovery()
        try { webServer?.stop() } catch (_: Throwable) {}
        try { player?.release() } catch (_: Throwable) {}
        try { wakeLock?.release() } catch (_: Throwable) {}
        scope.cancel()
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "PlaybackService"
        private const val CHANNEL_ID = "playback"
        private const val NOTIF_ID = 1001
        private const val USER_AGENT = "RestaurantIPTV/1.0 (Android)"

        private const val WATCHDOG_INTERVAL_MS = 4_000L
        private const val STALL_TIMEOUT_MS = 20_000L
        private const val FROZEN_TIMEOUT_MS = 15_000L
        private const val VIDEO_FROZEN_TIMEOUT_MS = 20_000L
        private const val RECOVERY_PROBE_MS = 8_000L
        private const val RECREATE_EVERY = 4

        const val ACTION_PLAY_CHANNEL = "com.restaurant.iptv.PLAY_CHANNEL"
        const val ACTION_RESUME = "com.restaurant.iptv.RESUME"
        const val ACTION_STOP = "com.restaurant.iptv.STOP"
        const val EXTRA_CHANNEL_ID = "channel_id"

        @Volatile
        var instance: PlaybackService? = null
            private set

        fun start(context: Context) {
            val i = Intent(context, PlaybackService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }
    }
}
