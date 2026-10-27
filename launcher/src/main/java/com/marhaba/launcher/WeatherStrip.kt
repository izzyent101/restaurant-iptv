package com.marhaba.launcher

import android.content.Context
import android.graphics.*
import android.view.View
import android.os.SystemClock
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.*

/** Seven vector cards, one shared 12 fps redraw only while the home is visible. */
class WeatherStrip(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var days = Forecast.days(null)
    private var stamp = "Loading forecast"
    private var animate = false
    fun setActive(value: Boolean) { animate = value; invalidate() }
    fun update(raw: String?, fetched: Long, failed: Boolean) {
        days = runCatching { Forecast.days(raw) }.getOrElse { Forecast.days(null) }
        stamp = if (raw == null) "Forecast unavailable" else {
            val stale = failed || System.currentTimeMillis() - fetched > 3600000
            "${if (stale) "Cached" else "Updated"} ${SimpleDateFormat("h:mm a", Locale.US).format(java.util.Date(fetched))}"
        }
        contentDescription = "Minneapolis seven day forecast. " + days.joinToString(". ") { "${it.label}, ${it.condition}, high ${it.high ?: "unavailable"}, low ${it.low ?: "unavailable"}" }
        invalidate()
    }
    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val save = c.save(); c.scale(width / 868f, height / 158f)
        val t = (SystemClock.uptimeMillis() % 120000) / 1000f
        fun text(value: String, x: Float, y: Float, size: Float, color: Int, center: Boolean = false) {
            paint.color = color; paint.style = Paint.Style.FILL; paint.textSize = size
            paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            paint.textAlign = if (center) Paint.Align.CENTER else Paint.Align.LEFT
            c.drawText(value, x, y, paint)
        }
        val cream = Color.rgb(248, 240, 219); val muted = Color.rgb(180, 191, 178)
        text("MINNEAPOLIS  ·  7 DAYS", 0f, 14f, 12f, cream)
        paint.textAlign = Paint.Align.RIGHT; paint.textSize = 9f; paint.color = muted
        c.drawText("NWS  ·  $stamp  ·  °F", 868f, 14f, paint)
        days.forEachIndexed { i, d ->
            val x = i * 125f
            paint.color = if (i == 0) Color.argb(242, 43, 62, 45) else Color.argb(237, 18, 34, 28)
            c.drawRoundRect(x, 25f, x + 118f, 157f, 12f, 12f, paint)
            if (i == 0) { paint.style = Paint.Style.STROKE; paint.strokeWidth = 1f; paint.color = Color.rgb(191, 163, 101); c.drawRoundRect(x,25f,x+118f,157f,12f,12f,paint); paint.style = Paint.Style.FILL }
            text(d.label, x+59f, 43f, 11f, cream, true)
            icon(c, x+59f, 65f, d.condition, d.nightOnly, t + i * .4f)
            text("${d.high?.let { "$it°" } ?: "—"}  /  ${d.low?.let { "$it°" } ?: "—"}", x+59f, 113f, 16f, cream, true)
            val condition = shortLabel(d.condition)
            text(condition, x+59f, 130f, 9f, muted, true)
            text(d.rain?.let { "$it% precip." } ?: "—", x+59f, 146f, 9f, Color.rgb(145, 193, 210), true)
        }
        c.restoreToCount(save)
        if (animate && isShown) postInvalidateDelayed(83)
    }
    private fun shortLabel(s: String): String {
        val v = s.lowercase()
        return when {
            "unavailable" in v -> "Unavailable"
            "thunder" in v -> "Thunderstorms"
            "snow" in v -> "Snow"
            "sleet" in v || "freezing" in v -> "Wintry mix"
            "rain" in v || "shower" in v || "drizzle" in v -> "Showers"
            "fog" in v -> "Fog"
            "partly" in v || "mostly sunny" in v -> "Partly cloudy"
            "cloud" in v -> "Cloudy"
            "wind" in v -> "Windy"
            "clear" in v -> "Clear"
            else -> "Sunny"
        }
    }
    private fun icon(c: Canvas, x: Float, y: Float, condition: String, night: Boolean, t: Float) {
        val label = shortLabel(condition)
        val rain = label in listOf("Showers", "Thunderstorms", "Wintry mix")
        val snow = label == "Snow"
        val cloudy = rain || snow || label in listOf("Cloudy", "Partly cloudy", "Fog", "Windy")
        paint.style = Paint.Style.FILL
        if (label == "Unavailable") { paint.color = Color.GRAY; paint.textSize = 20f; paint.textAlign = Paint.Align.CENTER; c.drawText("—", x, y+8, paint); return }
        if (!cloudy || label == "Partly cloudy") {
            val sx = if (cloudy) x-12 else x
            paint.color = if (night) Color.rgb(227,230,206) else Color.rgb(242,194,91)
            c.drawCircle(sx,y-3,12f,paint)
            if (!night) {
                paint.strokeWidth = 2f; paint.strokeCap = Paint.Cap.ROUND
                for (i in 0..7) { val a = i*PI/4 + t*.12; c.drawLine(sx+cos(a).toFloat()*16,y-3+sin(a).toFloat()*16,sx+cos(a).toFloat()*20,y-3+sin(a).toFloat()*20,paint) }
            }
        }
        if (cloudy) {
            val drift = sin(t*.9f)*2.5f
            paint.color = Color.rgb(178,193,192)
            c.drawRoundRect(x-23+drift,y-1,x+24+drift,y+14,8f,8f,paint)
            c.drawCircle(x-9+drift,y-2,12f,paint); c.drawCircle(x+6+drift,y-5,14f,paint)
        }
        if (rain || snow) {
            paint.color = if (snow) Color.WHITE else Color.rgb(112,184,220)
            paint.strokeWidth = 2f; paint.strokeCap = Paint.Cap.ROUND
            for (i in 0..3) {
                val yy = y+17 + ((t*12+i*4)%12)
                val xx = x-17+i*11
                if (snow) c.drawCircle(xx+sin(t+i)*2,yy,1.7f,paint) else c.drawLine(xx,yy,xx-2,yy+4,paint)
            }
        }
        if (label == "Thunderstorms") {
            paint.color = Color.rgb(241,199,92)
            c.drawPath(Path().apply { moveTo(x+2,y+10); lineTo(x-5,y+23); lineTo(x+1,y+22); lineTo(x-3,y+32); lineTo(x+10,y+16); lineTo(x+4,y+17); close() },paint)
        }
        if (label == "Fog" || label == "Windy") {
            paint.color = Color.rgb(166,186,180); paint.strokeWidth=2f
            for (i in 0..2) { val shift=sin(t+i)*4; c.drawLine(x-23+shift,y+18+i*5,x+23+shift,y+18+i*5,paint) }
        }
    }
}
