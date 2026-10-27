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
    data class Day(val label: String, val high: Int?, val low: Int?, val rain: Int?, val condition: String, val nightOnly: Boolean = false)
    fun days(raw: String?, now: Long = System.currentTimeMillis()): List<Day> {
        val zone = java.util.TimeZone.getTimeZone("America/Chicago")
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = zone }
        val label = SimpleDateFormat("EEE", Locale.US).apply { timeZone = zone }
        val cal = java.util.Calendar.getInstance(zone).apply { timeInMillis = now }
        val periods = runCatching { JSONObject(raw ?: "").getJSONObject("properties").getJSONArray("periods") }.getOrNull()
        val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        return (0..6).map { index ->
            val key = date.format(cal.time)
            val matches = if (periods == null) emptyList() else (0 until periods.length()).map { periods.getJSONObject(it) }
                .filter { it.getString("startTime").take(10) == key && (parser.parse(it.getString("endTime"))?.time ?: 0) > now }
            val daytime = matches.firstOrNull { it.optBoolean("isDaytime") }
            val nighttime = matches.firstOrNull { !it.optBoolean("isDaytime") }
            val primary = daytime ?: nighttime
            val chance = matches.mapNotNull { it.optJSONObject("probabilityOfPrecipitation")?.let { v -> if (v.isNull("value")) null else v.optInt("value") } }.maxOrNull()
            Day(if (index == 0) "TODAY" else label.format(cal.time).uppercase(Locale.US), daytime?.getInt("temperature"), nighttime?.getInt("temperature"), chance, primary?.getString("shortForecast") ?: "Unavailable", daytime == null && nighttime != null)
                .also { cal.add(java.util.Calendar.DAY_OF_YEAR, 1) }
        }
    }
}
