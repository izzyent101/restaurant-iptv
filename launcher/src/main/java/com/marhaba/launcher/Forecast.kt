package com.marhaba.launcher

import org.json.JSONObject
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import java.text.SimpleDateFormat
import java.util.Locale

object Forecast {
    fun fetch(): String {
        val c = URL("https://api.weather.gov/gridpoints/MPX/108,71/forecast").openConnection() as HttpsURLConnection
        try {
            c.connectTimeout = 12000; c.readTimeout = 12000
            c.setRequestProperty("User-Agent", "MarhabaHome/1.0 (marhabagrill.com)")
            c.setRequestProperty("Accept", "application/geo+json")
            check(c.responseCode == 200)
            return c.inputStream.bufferedReader().use { it.readText() }.also { check(JSONObject(it).getJSONObject("properties").getJSONArray("periods").length() > 0) }
        } finally { c.disconnect() }
    }
    fun display(raw: String?, fetched: Long, failed: Boolean): String {
        if (raw == null) return "MINNEAPOLIS · 55408\nForecast unavailable · retrying when connected"
        return try {
            val periods = JSONObject(raw).getJSONObject("properties").getJSONArray("periods")
            val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
            val valid = (0 until periods.length()).map { periods.getJSONObject(it) }
                .filter { (parser.parse(it.getString("endTime"))?.time ?: 0) > System.currentTimeMillis() }.take(3)
            if (valid.isEmpty()) return "MINNEAPOLIS · 55408\nForecast expired · waiting for an update"
            val rows = valid.joinToString("\n") { "${it.getString("name")}  ${it.getInt("temperature")}°${it.getString("temperatureUnit")}  ·  ${it.getString("shortForecast")}" }
            val time = SimpleDateFormat("h:mm a", Locale.US).format(java.util.Date(fetched))
            "MINNEAPOLIS · 55408\n$rows\nNWS · ${if (failed || System.currentTimeMillis()-fetched > 3600000) "Cached" else "Updated"} $time"
        } catch (_: Exception) { "MINNEAPOLIS · 55408\nForecast unavailable" }
    }
}
