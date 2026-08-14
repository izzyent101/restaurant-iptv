package com.restaurant.iptv.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One IPTV provider account. This TV owns exactly one (or a few) of these,
 * stored locally. Credentials never leave the device.
 */
@Entity(tableName = "providers")
data class ProviderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** "xtream" or "m3u" */
    val type: String,
    // Xtream Codes fields
    val xtreamServer: String? = null,   // e.g. http://host:port
    val xtreamUsername: String? = null,
    val xtreamPassword: String? = null,
    // Plain M3U fields
    val m3uUrl: String? = null,
    val epgUrl: String? = null,
    // Status / metadata (filled in after a refresh)
    val expiresAt: Long? = null,        // epoch seconds, from Xtream account info
    val maxConnections: Int? = null,
    val channelCount: Int = 0,
    val lastUpdated: Long = 0,
    val lastError: String? = null,
    val active: Boolean = true
)

/**
 * A single live channel belonging to a provider. Rebuilt on every refresh,
 * but stable identity is (providerId + streamKey) so local prefs survive.
 */
@Entity(
    tableName = "channels",
    indices = [
        Index(value = ["providerId"]),
        Index(value = ["providerId", "streamKey"], unique = true)
    ]
)
data class ChannelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val providerId: Long,
    /** Stable per-provider key: Xtream stream_id, or the stream URL for M3U. */
    val streamKey: String,
    val name: String,
    val groupTitle: String = "",
    val logoUrl: String? = null,
    val streamUrl: String,
    val epgChannelId: String? = null,
    val number: Int = 0,
    val sortIndex: Int = 0
)

/**
 * Groups the user has permanently hidden for a provider. Preserved across
 * refreshes and applied as a filter so hidden groups never reappear.
 */
@Entity(
    tableName = "hidden_groups",
    primaryKeys = ["providerId", "groupTitle"]
)
data class HiddenGroupEntity(
    val providerId: Long,
    val groupTitle: String
)

/** A channel joined with whether its group is hidden — for list building. */
data class ChannelRow(
    val id: Long,
    val providerId: Long,
    val name: String,
    val groupTitle: String,
    val logoUrl: String?,
    val streamUrl: String,
    val number: Int
)
