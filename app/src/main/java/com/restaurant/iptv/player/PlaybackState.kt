package com.restaurant.iptv.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Coarse playback state, surfaced to the web dashboard and on-TV status line. */
enum class PlayState { NO_PROVIDER, IDLE, BUFFERING, PLAYING, RECOVERING, ERROR }

data class PlaybackStatus(
    val state: PlayState = PlayState.IDLE,
    val channelId: Long? = null,
    val channelName: String? = null,
    val providerId: Long? = null,
    val stateSince: Long = System.currentTimeMillis(),
    val lastHeartbeat: Long = System.currentTimeMillis(),
    val lastError: String? = null,
    val retryCount: Int = 0,
    val recreateCount: Int = 0
)

/**
 * Process-wide observable playback status. The service updates it; the web
 * server and UI read it. Kept as a singleton so any component can observe
 * without binding.
 */
object PlaybackState {
    private val _status = MutableStateFlow(PlaybackStatus())
    val status: StateFlow<PlaybackStatus> = _status

    fun update(transform: (PlaybackStatus) -> PlaybackStatus) {
        _status.value = transform(_status.value)
    }

    fun set(status: PlaybackStatus) { _status.value = status }
}
