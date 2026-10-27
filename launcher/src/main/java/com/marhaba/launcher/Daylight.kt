package com.marhaba.launcher

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.*

/** Approximate solar elevation for Minneapolis (55408), independent of TV time zone. */
object Daylight {
    fun phase(now: Long = System.currentTimeMillis()): String {
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = now }
        val hour = c.get(Calendar.HOUR_OF_DAY) + c.get(Calendar.MINUTE) / 60.0
        val g = 2 * PI / 365 * (c.get(Calendar.DAY_OF_YEAR) - 1 + (hour - 12) / 24)
        val eq = 229.18 * (.000075 + .001868*cos(g) - .032077*sin(g) - .014615*cos(2*g) - .040849*sin(2*g))
        val dec = .006918 - .399912*cos(g) + .070257*sin(g) - .006758*cos(2*g) + .000907*sin(2*g) - .002697*cos(3*g) + .00148*sin(3*g)
        val solarMinutes = ((hour * 60 + eq + 4 * -93.278) % 1440 + 1440) % 1440
        val angle = Math.toRadians(solarMinutes / 4 - 180)
        val lat = Math.toRadians(44.95)
        val elevation = Math.toDegrees(asin(sin(lat)*sin(dec) + cos(lat)*cos(dec)*cos(angle)))
        return when {
            elevation < -6 -> "night"
            elevation < 8 -> if (solarMinutes < 720) "sunrise" else "sunset"
            else -> "day"
        }
    }
}
