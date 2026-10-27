package com.marhaba.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import java.util.Calendar

/** Crisp vector seven-segment display, scaled for television viewing. */
class DigitalClock(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var active = false
    fun setActive(value: Boolean) { active = value; invalidate() }
    private val masks = intArrayOf(0x3f,0x06,0x5b,0x4f,0x66,0x6d,0x7d,0x07,0x7f,0x6f)
    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val state=c.save();c.scale(width/190f,height/64f)
        val time=Calendar.getInstance()
        val use24=android.text.format.DateFormat.is24HourFormat(context)
        val hour=if(use24) time.get(Calendar.HOUR_OF_DAY) else time.get(Calendar.HOUR).let { if(it==0) 12 else it }
        val minute=time.get(Calendar.MINUTE)
        val digits=intArrayOf(hour/10,hour%10,minute/10,minute%10)
        val segments=arrayOf(floatArrayOf(4f,3f,22f,3f),floatArrayOf(25f,6f,25f,22f),floatArrayOf(25f,28f,25f,44f),floatArrayOf(4f,47f,22f,47f),floatArrayOf(1f,28f,1f,44f),floatArrayOf(1f,6f,1f,22f),floatArrayOf(4f,25f,22f,25f))
        paint.strokeWidth=3.2f;paint.strokeCap=Paint.Cap.ROUND
        digits.forEachIndexed { index,digit ->
            val x=6f+index*35f+if(index>1) 12f else 0f
            segments.forEachIndexed { bit,s ->
                paint.color=if(masks[digit] and (1 shl bit)!=0) Color.rgb(246,244,225) else Color.argb(28,220,235,220)
                c.drawLine(x+s[0],5+s[1],x+s[2],5+s[3],paint)
            }
        }
        paint.color=Color.rgb(246,244,225)
        c.drawCircle(75f,23f,1.8f,paint);c.drawCircle(75f,38f,1.8f,paint)
        paint.textSize=10f;paint.typeface=android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL)
        c.drawText(if(use24) "24H" else if(time.get(Calendar.AM_PM)==Calendar.AM) "AM" else "PM",163f,51f,paint)
        c.restoreToCount(state)
        contentDescription=android.text.format.DateFormat.getTimeFormat(context).format(time.time)
        if(active && isShown) postInvalidateDelayed(1000)
    }
}
