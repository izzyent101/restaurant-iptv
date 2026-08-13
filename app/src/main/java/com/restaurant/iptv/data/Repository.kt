package com.restaurant.iptv.data

import android.content.Context
import com.restaurant.iptv.data.entity.ChannelEntity
import com.restaurant.iptv.data.entity.HiddenGroupEntity
import com.restaurant.iptv.data.entity.ProviderEntity
import com.restaurant.iptv.data.net.M3uParser
import com.restaurant.iptv.data.net.XtreamClient
import com.restaurant.iptv.epg.EpgFetcher
import com.restaurant.iptv.epg.EpgStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Single point of truth for provider config + channel data. Used by both
 * the web control server and the on-TV UI.
 */
class Repository(context: Context) {

    private val dao = AppDatabase.get(context).dao()
    private val prefs = Prefs(context)
    private val json = Json { ignoreUnknownKeys = true }

    private val http = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 30_000
        }
    }
    private val xtream = XtreamClient(http)
    private val m3u = M3uParser(http)

    suspend fun getProviders(): List<ProviderEntity> = dao.getProviders()
    suspend fun getActiveProvider(): ProviderEntity? = dao.getActiveProvider()
    suspend fun getProvider(id: Long): ProviderEntity? = dao.getProvider(id)

    suspend fun saveProvider(p: ProviderEntity): Long = dao.upsertProvider(p)
    suspend fun deleteProvider(id: Long) {
        dao.clearChannels(id)
        dao.deleteProvider(id)
    }

    suspend fun getVisibleChannels(providerId: Long): List<ChannelEntity> = dao.getVisibleChannels(providerId)
    suspend fun getChannel(id: Long): ChannelEntity? = dao.getChannel(id)
    suspend fun getGroups(providerId: Long): List<String> = dao.getGroups(providerId)
    suspend fun getHiddenGroups(providerId: Long): List<String> =
        dao.getHiddenGroups(providerId).map { it.groupTitle }

    suspend fun hideGroup(providerId: Long, group: String) =
        dao.hideGroup(HiddenGroupEntity(providerId, group))

    suspend fun unhideGroup(providerId: Long, group: String) =
        dao.unhideGroup(providerId, group)

    data class RefreshResult(val ok: Boolean, val channelCount: Int, val error: String?)

    /** Re-fetch a provider's channels. Hidden-group prefs are preserved. */
    suspend fun refreshProvider(providerId: Long): RefreshResult {
        val p = dao.getProvider(providerId) ?: return RefreshResult(false, 0, "Provider not found")
        return try {
            val channels: List<ChannelEntity>
            var expiresAt: Long? = p.expiresAt
            var maxConn: Int? = p.maxConnections

            when (p.type) {
                "xtream" -> {
                    val server = p.xtreamServer.orEmpty()
                    val user = p.xtreamUsername.orEmpty()
                    val pass = p.xtreamPassword.orEmpty()
                    val acct = xtream.authenticate(server, user, pass)
                    if (!acct.ok) {
                        dao.updateProviderStatus(providerId, 0, null, null, now(), acct.message ?: "Auth failed")
                        return RefreshResult(false, 0, acct.message ?: "Auth failed")
                    }
                    expiresAt = acct.expiresAt
                    maxConn = acct.maxConnections
                    channels = xtream.fetchLiveChannels(providerId, server, user, pass)
                }
                "m3u" -> {
                    channels = m3u.fetch(providerId, p.m3uUrl.orEmpty())
                }
                else -> return RefreshResult(false, 0, "Unknown provider type ${p.type}")
            }

            dao.replaceChannels(providerId, channels)
            dao.updateProviderStatus(providerId, channels.size, expiresAt, maxConn, now(), null)
            RefreshResult(true, channels.size, null)
        } catch (t: Throwable) {
            val msg = t.message ?: t.javaClass.simpleName
            dao.updateProviderStatus(providerId, 0, p.expiresAt, p.maxConnections, now(), msg)
            RefreshResult(false, 0, msg)
        }
    }

    private fun now() = System.currentTimeMillis()

    // ---------- Central dashboard: TV list ----------

    suspend fun getTvs(): List<TvEndpoint> =
        runCatching { json.decodeFromString<List<TvEndpoint>>(prefs.tvListJson()) }.getOrElse { emptyList() }

    private suspend fun saveTvs(list: List<TvEndpoint>) =
        prefs.setTvListJson(json.encodeToString(list))

    suspend fun addTv(name: String, address: String) {
        val addr = normalizeAddress(address)
        val current = getTvs().filterNot { it.address == addr }
        saveTvs(current + TvEndpoint(name.trim().ifEmpty { addr }, addr))
    }

    suspend fun removeTv(address: String) {
        val addr = normalizeAddress(address)
        saveTvs(getTvs().filterNot { it.address == addr })
    }

    /** Strip any scheme and trailing slash; default the port to 8080. */
    private fun normalizeAddress(raw: String): String {
        var a = raw.trim().removePrefix("http://").removePrefix("https://").trimEnd('/')
        if (!a.contains(":")) a = "$a:8080"
        return a
    }

    // ---------- EPG ----------

    /** Fetch the XMLTV guide for a provider into the in-memory EpgStore. */
    suspend fun refreshEpg(providerId: Long): Boolean {
        val p = dao.getProvider(providerId) ?: return false
        val url = when (p.type) {
            "xtream" -> {
                val server = p.xtreamServer?.trim()?.trimEnd('/') ?: return false
                val u = enc(p.xtreamUsername.orEmpty())
                val pw = enc(p.xtreamPassword.orEmpty())
                "$server/xmltv.php?username=$u&password=$pw"
            }
            "m3u" -> p.epgUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return false
            else -> return false
        }
        return try {
            val map = EpgFetcher.fetch(url)
            EpgStore.put(providerId, map)
            map.isNotEmpty()
        } catch (t: Throwable) {
            false
        }
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    // ---------- Favorites (per provider, stored in prefs) ----------

    private suspend fun favMap(): MutableMap<String, MutableList<String>> =
        runCatching {
            json.decodeFromString<Map<String, List<String>>>(prefs.favoritesJson())
                .mapValues { it.value.toMutableList() }.toMutableMap()
        }.getOrElse { mutableMapOf() }

    suspend fun getFavorites(providerId: Long): Set<String> =
        favMap()[providerId.toString()]?.toSet() ?: emptySet()

    /** Toggle a favorite; returns the new favorite state. */
    suspend fun toggleFavorite(providerId: Long, streamKey: String): Boolean {
        val map = favMap()
        val set = map.getOrPut(providerId.toString()) { mutableListOf() }
        val nowFav = if (set.contains(streamKey)) { set.remove(streamKey); false } else { set.add(streamKey); true }
        prefs.setFavoritesJson(json.encodeToString(map as Map<String, List<String>>))
        return nowFav
    }
}
