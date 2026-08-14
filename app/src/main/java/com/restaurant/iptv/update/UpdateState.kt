package com.restaurant.iptv.update

import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow

/** A downloaded, ready-to-install update. */
data class ReadyUpdate(val file: File, val versionName: String, val versionCode: Int)

/**
 * Update state. IMPORTANT: updates never install themselves — installing
 * restarts the app and would drop the live stream. The checker only downloads
 * and marks an update "available"; the install is fired ONLY when the operator
 * clicks "Install now" in the web UI (which sets installRequested).
 */
object UpdateState {
    /** Downloaded and ready, but NOT installed. */
    val available = MutableStateFlow<ReadyUpdate?>(null)
    /** Operator asked to install now (observed by the foreground Activity). */
    val installRequested = MutableStateFlow<ReadyUpdate?>(null)
    val lastMessage = MutableStateFlow("")

    fun setAvailable(u: ReadyUpdate?) { available.value = u }
    fun requestInstall() { installRequested.value = available.value }
    fun clearInstall() { installRequested.value = null }
    fun message(m: String) { lastMessage.value = m }
}
