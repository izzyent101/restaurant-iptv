package com.restaurant.iptv.epg

import java.text.SimpleDateFormat
import java.util.Locale

/** One instance per parser: SimpleDateFormat is mutable and not thread safe. */
class XmlTvTimestamps {
    private val zoned = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)
    private val local = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
    fun parse(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        val s = raw.trim()
        return try {
            if (s.length > 14 && (s.contains('+') || s.contains('-')))
                zoned.parse(s.substring(0, 14) + " " + s.substring(14).trim())?.time ?: 0L
            else local.parse(s.substring(0, minOf(14, s.length)))?.time ?: 0L
        } catch (_: Exception) { 0L }
    }
}
