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
    init { setLayerType(LAYER_TYPE_SOFTWARE, null) }
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
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(0f, 0f, 868f, 158f, intArrayOf(Color.argb(220, 53, 73, 63), Color.argb(235, 18, 32, 28)), null, Shader.TileMode.CLAMP)
        c.drawRoundRect(0f, 0f, 868f, 158f, 20f, 20f, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE; paint.strokeWidth = .8f; paint.color = Color.argb(65, 220, 231, 224)
        c.drawRoundRect(1f, 1f, 867f, 157f, 20f, 20f, paint)
        paint.style = Paint.Style.FILL
        val t = (SystemClock.uptimeMillis() % 120000) / 1000f
        fun text(value: String, x: Float, y: Float, size: Float, color: Int, center: Boolean = false) {
            paint.color = color; paint.style = Paint.Style.FILL; paint.textSize = size
            paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            paint.textAlign = if (center) Paint.Align.CENTER else Paint.Align.LEFT
            c.drawText(value, x, y, paint)
        }
        val cream = Color.rgb(244, 246, 244); val muted = Color.rgb(180, 191, 178)
        text("Minneapolis", 18f, 22f, 12f, cream)
        paint.textAlign = Paint.Align.RIGHT; paint.textSize = 9f; paint.color = muted
        c.drawText("7-day forecast  ·  NWS  ·  $stamp  ·  °F", 850f, 22f, paint)
        days.forEachIndexed { i, d ->
            val x = 10f + i * 121.5f
            if (i == 0) {
                paint.style = Paint.Style.FILL; paint.color = Color.argb(26, 235, 240, 231)
                c.drawRoundRect(x, 33f, x + 118f, 151f, 14f, 14f, paint)
            } else {
                paint.color = Color.argb(26, 221, 234, 223); paint.strokeWidth = .7f
                c.drawLine(x-2f, 47f, x-2f, 139f, paint)
            }
            text(d.label, x+59f, 49f, 10f, cream, true)
            icon(c, x+59f, 73f, d.condition, d.nightOnly, t + i * .4f)
            text("${d.high?.let { "$it°" } ?: "—"}  /  ${d.low?.let { "$it°" } ?: "—"}", x+59f, 117f, 16f, cream, true)
            val condition = shortLabel(d.condition)
            text(condition, x+59f, 133f, 8.5f, muted, true)
            text(d.rain?.let { "$it% precip." } ?: "—", x+59f, 146f, 8f, Color.rgb(145, 193, 210), true)
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
        val state = c.save(); c.scale(.8f, .8f, x, y)
        val label = shortLabel(condition)
        val rain = label in listOf("Showers", "Thunderstorms", "Wintry mix")
        val snow = label == "Snow"
        val cloudy = rain || snow || label in listOf("Cloudy", "Partly cloudy", "Fog", "Windy")
        paint.style = Paint.Style.FILL
        if (label == "Unavailable") { paint.color = Color.GRAY; paint.textSize = 20f; paint.textAlign = Paint.Align.CENTER; c.drawText("—", x, y+8, paint); c.restoreToCount(state); return }
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
        c.restoreToCount(state)
    }
}
