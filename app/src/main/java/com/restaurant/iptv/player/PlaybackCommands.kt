package com.restaurant.iptv.player

/**
 * Thread-safe command surface the web server uses to drive playback.
 * Implemented by PlaybackService; all methods marshal to the main thread.
 */
interface PlaybackCommands {
    fun cmdPlayChannel(channelId: Long)
    fun cmdStop()
    fun cmdRetryNow()
    fun cmdRefreshAndResume()
}
