package com.restaurant.iptv.data.net

import com.restaurant.iptv.data.entity.ChannelEntity
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText

/**
 * Extended-M3U parser for plain playlist providers. Handles the common
 * #EXTINF attributes (tvg-id, tvg-logo, group-title) and channel name.
 */
class M3uParser(
    private val http: HttpClient = HttpClient(CIO)
) {
    suspend fun fetch(providerId: Long, url: String): List<ChannelEntity> {
        val text = http.get(url.trim()).bodyAsText()
        return parse(providerId, text)
    }

    fun parse(providerId: Long, content: String): List<ChannelEntity> {
        val out = ArrayList<ChannelEntity>()
        var pendingName: String? = null
        var pendingGroup = ""
        var pendingLogo: String? = null
        var pendingEpg: String? = null
        var sort = 0

        content.lineSequence().forEach { raw ->
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    pendingGroup = attr(line, "group-title") ?: "Uncategorized"
                    pendingLogo = attr(line, "tvg-logo")
                    pendingEpg = attr(line, "tvg-id")
                    pendingName = line.substringAfter(",", "").trim().ifEmpty { attr(line, "tvg-name") ?: "Channel" }
                }
                line.isEmpty() || line.startsWith("#") -> { /* skip other tags */ }
                else -> {
                    val name = pendingName
                    if (name != null) {
                        out.add(
                            ChannelEntity(
                                providerId = providerId,
                                streamKey = line,          // URL is the stable key for M3U
                                name = name,
                                groupTitle = pendingGroup,
                                logoUrl = pendingLogo?.takeIf { it.isNotBlank() },
                                streamUrl = line,
                                epgChannelId = pendingEpg?.takeIf { it.isNotBlank() },
                                number = 0,
                                sortIndex = sort++
                            )
                        )
                    }
                    pendingName = null
                    pendingGroup = ""
                    pendingLogo = null
                    pendingEpg = null
                }
            }
        }
        return out
    }

    private fun attr(line: String, key: String): String? {
        val idx = line.indexOf("$key=\"", ignoreCase = true)
        if (idx < 0) return null
        val start = idx + key.length + 2
        val end = line.indexOf('"', start)
        if (end < 0) return null
        return line.substring(start, end)
    }
}
