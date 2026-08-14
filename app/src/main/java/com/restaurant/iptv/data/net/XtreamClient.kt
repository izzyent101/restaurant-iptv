package com.restaurant.iptv.data.net

import com.restaurant.iptv.data.entity.ChannelEntity
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Minimal, defensive Xtream Codes client. Xtream servers are wildly
 * inconsistent about JSON types, so we parse into JsonElement and read
 * fields loosely rather than with strict data classes.
 */
class XtreamClient(
    private val http: HttpClient = HttpClient(CIO)
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    data class Account(
        val ok: Boolean,
        val expiresAt: Long?,
        val maxConnections: Int?,
        val message: String?
    )

    private fun base(server: String) = server.trim().trimEnd('/')

    private fun api(server: String, user: String, pass: String, action: String? = null): String {
        val b = base(server)
        val a = if (action != null) "&action=$action" else ""
        return "$b/player_api.php?username=${enc(user)}&password=${enc(pass)}$a"
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    /** Authenticate and read account info (expiry, connection limit). */
    suspend fun authenticate(server: String, user: String, pass: String): Account {
        val text = http.get(api(server, user, pass)).bodyAsText()
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: return Account(false, null, null, "Invalid server response")
        val userInfo = root["user_info"]?.jsonObject
        val auth = userInfo?.get("auth")?.jsonPrimitive?.intOrNull ?: 0
        val status = userInfo?.get("status")?.jsonPrimitive?.contentOrNull
        val exp = userInfo?.get("exp_date")?.jsonPrimitive?.longOrNull
        val maxConn = userInfo?.get("max_connections")?.jsonPrimitive?.contentOrNull?.toIntOrNull()
        val ok = auth == 1 && (status == null || status.equals("Active", true))
        return Account(ok, exp, maxConn, if (ok) null else (status ?: "Authentication failed"))
    }

    /** Fetch all live channels, joining category names, as ready-to-store rows. */
    suspend fun fetchLiveChannels(providerId: Long, server: String, user: String, pass: String): List<ChannelEntity> {
        val b = base(server)
        val categories = fetchCategoryMap(server, user, pass)
        val streamsText = http.get(api(server, user, pass, "get_live_streams")).bodyAsText()
        val arr: JsonArray = runCatching { json.parseToJsonElement(streamsText).jsonArray }.getOrElse { JsonArray(emptyList()) }

        val out = ArrayList<ChannelEntity>(arr.size)
        var sort = 0
        for (el in arr) {
            val o = el as? JsonObject ?: continue
            val streamId = o["stream_id"]?.jsonPrimitive?.contentOrNull ?: continue
            val name = o["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (name.isEmpty()) continue
            val catId = o["category_id"]?.jsonPrimitive?.contentOrNull
            val group = categories[catId] ?: "Uncategorized"
            val logo = o["stream_icon"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            val num = o["num"]?.jsonPrimitive?.intOrNull ?: 0
            val epgId = o["epg_channel_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            val streamUrl = "$b/live/${enc(user)}/${enc(pass)}/$streamId.$LIVE_EXT"
            out.add(
                ChannelEntity(
                    providerId = providerId,
                    streamKey = streamId,
                    name = name,
                    groupTitle = group,
                    logoUrl = logo,
                    streamUrl = streamUrl,
                    epgChannelId = epgId,
                    number = num,
                    sortIndex = sort++
                )
            )
        }
        return out
    }

    private suspend fun fetchCategoryMap(server: String, user: String, pass: String): Map<String, String> {
        val text = http.get(api(server, user, pass, "get_live_categories")).bodyAsText()
        val arr = runCatching { json.parseToJsonElement(text).jsonArray }.getOrElse { return emptyMap() }
        val map = HashMap<String, String>()
        for (el in arr) {
            val o = el as? JsonObject ?: continue
            val id = o["category_id"]?.jsonPrimitive?.contentOrNull ?: continue
            val nameCat = o["category_name"]?.jsonPrimitive?.contentOrNull?.trim() ?: continue
            map[id] = nameCat
        }
        return map
    }

    companion object {
        /** Xtream live streams default to MPEG-TS. Change to "m3u8" if a
         *  provider only serves HLS for live. */
        const val LIVE_EXT = "ts"
    }
}
