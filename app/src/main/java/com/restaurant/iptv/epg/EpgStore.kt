package com.restaurant.iptv.epg

/** A single EPG programme. */
data class Programme(
    val title: String,
    val desc: String?,
    val startMs: Long,
    val endMs: Long
)

/**
 * In-memory EPG, keyed by providerId -> tvg-id -> programmes (sorted by start).
 * Kept in memory (not the DB) so adding EPG needs no schema migration and can't
 * wipe provider logins on update. Re-fetched on demand and on startup.
 */
object EpgStore {
    @Volatile
    private var data: Map<Long, Map<String, List<Programme>>> = emptyMap()

    fun put(providerId: Long, map: Map<String, List<Programme>>) {
        data = data.toMutableMap().apply { put(providerId, map) }
    }

    fun channelsWithData(providerId: Long): Int = data[providerId]?.size ?: 0

    /** (now, next) for a channel, or (null, null) if unknown. */
    fun nowNext(providerId: Long, channelId: String?): Pair<Programme?, Programme?> {
        if (channelId.isNullOrBlank()) return null to null
        val list = data[providerId]?.get(channelId) ?: return null to null
        val now = System.currentTimeMillis()
        var current: Programme? = null
        var next: Programme? = null
        for (p in list) {
            if (p.startMs <= now && p.endMs > now) current = p
            else if (p.startMs > now) { next = p; break }
        }
        return current to next
    }
}
