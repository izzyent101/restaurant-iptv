package com.restaurant.iptv.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "app_state")

/**
 * Tiny key/value state that must survive restarts and reboots:
 * the last channel played, so the appliance can resume itself.
 */
class Prefs(private val context: Context) {

    private val keyLastChannel = longPreferencesKey("last_channel_id")
    private val keyLastProvider = longPreferencesKey("last_provider_id")
    private val keyServerPort = intPreferencesKey("server_port")
    private val keyTvList = stringPreferencesKey("tv_list_json")
    private val keyUpdateToken = stringPreferencesKey("update_token")
    private val keyUpdateManifestUrl = stringPreferencesKey("update_manifest_url")
    private val keyUpdateRepo = stringPreferencesKey("update_repo")
    private val keyFavorites = stringPreferencesKey("favorites_json")
    private val keyAccessKey = stringPreferencesKey("access_key")
    private val keyRecent = stringPreferencesKey("recent_channels")

    suspend fun setLastChannel(providerId: Long, channelId: Long) {
        context.dataStore.edit {
            it[keyLastProvider] = providerId
            it[keyLastChannel] = channelId
        }
    }

    suspend fun lastChannelId(): Long? =
        context.dataStore.data.map { it[keyLastChannel] }.first()

    suspend fun lastProviderId(): Long? =
        context.dataStore.data.map { it[keyLastProvider] }.first()

    suspend fun serverPort(): Int =
        context.dataStore.data.map { it[keyServerPort] ?: DEFAULT_PORT }.first()

    suspend fun setServerPort(port: Int) {
        context.dataStore.edit { it[keyServerPort] = port }
    }

    /** The central dashboard's list of TVs, stored as a JSON array string. */
    suspend fun tvListJson(): String =
        context.dataStore.data.map { it[keyTvList] ?: "[]" }.first()

    suspend fun setTvListJson(json: String) {
        context.dataStore.edit { it[keyTvList] = json }
    }

    // --- Auto-update settings ---
    suspend fun updateToken(): String =
        context.dataStore.data.map { it[keyUpdateToken] ?: "" }.first()
    suspend fun updateManifestUrl(): String =
        context.dataStore.data.map { it[keyUpdateManifestUrl] ?: "" }.first()
    suspend fun updateRepo(): String =
        context.dataStore.data.map { it[keyUpdateRepo] ?: DEFAULT_REPO }.first()

    suspend fun setUpdateConfig(token: String?, manifestUrl: String?, repo: String?) {
        context.dataStore.edit {
            if (token != null) it[keyUpdateToken] = token
            if (manifestUrl != null) it[keyUpdateManifestUrl] = manifestUrl
            if (repo != null) it[keyUpdateRepo] = repo
        }
    }

    // --- Favorites (JSON map: providerId -> [streamKeys]) ---
    suspend fun favoritesJson(): String =
        context.dataStore.data.map { it[keyFavorites] ?: "{}" }.first()

    suspend fun setFavoritesJson(json: String) {
        context.dataStore.edit { it[keyFavorites] = json }
    }

    // --- Recently watched channels (stream keys, most recent first) ---
    suspend fun recentKeys(): List<String> =
        context.dataStore.data.map { (it[keyRecent] ?: "").split("\n").filter { s -> s.isNotEmpty() } }.first()

    suspend fun setRecentKeys(keys: List<String>) {
        context.dataStore.edit { it[keyRecent] = keys.joinToString("\n") }
    }

    // --- Control-panel password ("" = no protection) ---
    suspend fun accessKey(): String =
        context.dataStore.data.map { it[keyAccessKey] ?: "" }.first()

    suspend fun setAccessKey(key: String) {
        context.dataStore.edit { it[keyAccessKey] = key }
    }

    companion object {
        const val DEFAULT_PORT = 8080
        const val DEFAULT_REPO = "marhabamediterranean-png/restaurant-iptv"
    }
}
