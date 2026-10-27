package com.restaurant.iptv.epg

import android.util.Log
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.PushbackInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.zip.GZIPInputStream

/**
 * Downloads an XMLTV guide and parses it with a streaming pull parser
 * (memory-safe for large guides). Handles gzip transparently.
 */
object EpgFetcher {
    private const val TAG = "EpgFetcher"

    suspend fun fetch(url: String): Map<String, List<Programme>> = withContext(Dispatchers.IO) {
        val conn = (URL(url.trim()).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 60000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "MarhabaIPTV")
        }
        try {
            maybeGunzip(PushbackInputStream(conn.inputStream, 2)).use { stream ->
                parse(stream)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "EPG fetch/parse failed", t)
            emptyMap()
        } finally {
            conn.disconnect()
        }
    }

    private fun maybeGunzip(pb: PushbackInputStream): java.io.InputStream {
        val b1 = pb.read()
        val b2 = pb.read()
        if (b1 != -1 && b2 != -1) pb.unread(byteArrayOf(b1.toByte(), b2.toByte()))
        return if (b1 == 0x1f && b2 == 0x8b) GZIPInputStream(pb) else pb
    }

    private fun parse(stream: java.io.InputStream): Map<String, List<Programme>> {
        val map = HashMap<String, MutableList<Programme>>()
        val parser = Xml.newPullParser()
        parser.setInput(stream, null)

        val timestamps = XmlTvTimestamps()
        var channel: String? = null
        var start = 0L
        var stop = 0L
        var title: StringBuilder? = null
        var desc: StringBuilder? = null
        var inTitle = false
        var inDesc = false

        val nowMs = System.currentTimeMillis()
        val windowStart = nowMs - 2 * 60 * 60_000L        // keep the current programme
        val windowEnd = nowMs + 36 * 60 * 60_000L         // ~1.5 days ahead

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "programme" -> {
                        channel = parser.getAttributeValue(null, "channel")
                        start = timestamps.parse(parser.getAttributeValue(null, "start"))
                        stop = timestamps.parse(parser.getAttributeValue(null, "stop"))
                        title = null
                        desc = null
                    }
                    "title" -> { inTitle = true; title = StringBuilder() }
                    "desc" -> { inDesc = true; desc = StringBuilder() }
                }
                XmlPullParser.TEXT -> {
                    if (inTitle) title?.append(parser.text)
                    if (inDesc) desc?.append(parser.text)
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "title" -> inTitle = false
                    "desc" -> inDesc = false
                    "programme" -> {
                        val c = channel
                        val t = title?.toString()?.trim()
                        // Keep only a bounded window/count per channel. A full XMLTV
                        // dump can hold weeks of data for thousands of channels, which
                        // is 100s of MB of RAM on a TV — enough to destabilise the
                        // whole device. We only ever show now/next + today.
                        val fresh = stop <= 0L || stop > windowStart
                        val soon = start < windowEnd
                        if (!c.isNullOrBlank() && !t.isNullOrEmpty() && start > 0 && fresh && soon) {
                            val list = map.getOrPut(c) { ArrayList() }
                            if (list.size < MAX_PER_CHANNEL) {
                                list.add(Programme(t, desc?.toString()?.trim()?.takeIf { it.isNotEmpty() }, start, stop))
                            }
                        }
                    }
                }
            }
            event = parser.next()
        }
        map.values.forEach { it.sortBy { p -> p.startMs } }
        return map
    }

    private const val MAX_PER_CHANNEL = 60

}
