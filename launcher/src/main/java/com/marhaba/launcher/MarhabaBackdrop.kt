package com.marhaba.launcher

import android.graphics.*
import android.graphics.drawable.Drawable

/** Resolution-independent architectural ornament; drawn only when invalidated. */
class MarhabaBackdrop : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun draw(canvas: Canvas) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        paint.shader = LinearGradient(0f, 0f, w, h, intArrayOf(Color.rgb(20, 40, 30), Color.rgb(16, 30, 24), Color.rgb(41, 48, 30)), null, Shader.TileMode.CLAMP)
        canvas.drawRect(bounds, paint)
        paint.shader = null
        // Repeated brass arch outlines, deliberately quiet behind the content.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = w / 850f
        for (i in 0..4) {
            val x = w * .77f
            val top = h * (.14f + i * .035f)
            val rx = w * (.20f - i * .022f)
            val path = Path().apply {
                moveTo(x - rx, h * 1.05f)
                lineTo(x - rx, top + rx)
                cubicTo(x - rx, top + rx * .35f, x - rx * .4f, top + rx * .20f, x, top)
                cubicTo(x + rx * .4f, top + rx * .20f, x + rx, top + rx * .35f, x + rx, top + rx)
                lineTo(x + rx, h * 1.05f)
            }
            paint.color = Color.argb(28, 204, 177, 112)
            canvas.drawPath(path, paint)
        }
        paint.color = Color.argb(75, 204, 177, 112)
        canvas.drawLine(w * .048f, h * .145f, w * .95f, h * .145f, paint)
        paint.style = Paint.Style.FILL
        canvas.drawCircle(w * .95f, h * .145f, w * .002f, paint)
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter }
    @Deprecated("Drawable opacity")
    override fun getOpacity() = PixelFormat.OPAQUE
}
