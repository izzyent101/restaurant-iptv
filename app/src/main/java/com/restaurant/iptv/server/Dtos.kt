package com.restaurant.iptv.server

import kotlinx.serialization.Serializable

@Serializable
data class ProviderDto(
    val id: Long,
    val name: String,
    val type: String,
    val server: String? = null,
    val username: String? = null,
    val hasPassword: Boolean = false,
    val m3uUrl: String? = null,
    val epgUrl: String? = null,
    val channelCount: Int = 0,
    val expiresAt: Long? = null,
    val maxConnections: Int? = null,
    val lastUpdated: Long = 0,
    val lastError: String? = null,
    val active: Boolean = true
)

@Serializable
data class StatusDto(
    val state: String,
    val channelId: Long? = null,
    val channelName: String? = null,
    val providerId: Long? = null,
    val secondsInState: Long = 0,
    val retryCount: Int = 0,
    val recreateCount: Int = 0,
    val lastError: String? = null,
    val provider: ProviderDto? = null
)

@Serializable
data class ChannelDto(
    val id: Long,
    val name: String,
    val group: String,
    val number: Int,
    val logo: String? = null,
    val favorite: Boolean = false,
    val epgNow: String? = null,
    val epgNext: String? = null,
    val epgProgress: Int = -1
)

@Serializable
data class GroupDto(val name: String, val hidden: Boolean)

@Serializable
data class ApiResult(val ok: Boolean, val message: String? = null, val id: Long? = null)

@Serializable
data class VersionDto(
    val versionName: String,
    val versionCode: Int,
    val updateRepo: String,
    val manifestUrl: String,
    val updateMessage: String,
    val updateAvailable: Boolean,
    val availableVersion: String
)
