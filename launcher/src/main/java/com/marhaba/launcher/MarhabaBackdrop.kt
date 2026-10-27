package com.marhaba.launcher

import android.graphics.*
import android.graphics.drawable.Drawable

/** Static vector scenery: no bitmap allocation, network or animation loop. */
class MarhabaBackdrop : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun draw(canvas: Canvas) {
        val save = canvas.save()
        canvas.scale(bounds.width() / 960f, bounds.height() / 540f)
        val phase = Daylight.phase()
        val night = phase == "night"
        val sky = when (phase) {
            "sunrise" -> Color.rgb(148, 97, 73)
            "sunset" -> Color.rgb(150, 67, 47)
            "day" -> Color.rgb(77, 135, 139)
            else -> Color.rgb(23, 33, 64)
        }
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(0f, 0f, 960f, 540f, intArrayOf(Color.rgb(12, 25, 23), Color.rgb(18, 34, 28), sky), null, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, 960f, 540f, paint)
        paint.shader = null
        // A restrained gold sun behind the desert skyline.
        paint.color = if (night) Color.rgb(220, 224, 206) else if (phase == "day") Color.rgb(235, 210, 141) else Color.rgb(223, 144, 88)
        val sunY = if (phase == "sunrise" || phase == "sunset") 330f else 230f
        canvas.drawCircle(753f, sunY, if (night) 36f else 65f, paint)
        if (night) {
            paint.color = Color.argb(190, 229, 232, 216)
            for (i in 0..28) canvas.drawCircle(490f + (i * 79 % 440), 100f + (i * 43 % 190), if (i % 3 == 0) 1.3f else .7f, paint)
        }
        fun shape(color: Int, block: Path.() -> Unit) {
            paint.style = Paint.Style.FILL; paint.color = if (night) Color.rgb(Color.red(color)*2/3, Color.green(color)*2/3, Color.blue(color)*3/4) else color
            canvas.drawPath(Path().apply(block), paint)
        }
        shape(Color.rgb(88, 78, 48)) { moveTo(558f, 414f); lineTo(707f, 239f); lineTo(806f, 414f); close() }
        shape(Color.rgb(55, 56, 37)) { moveTo(707f, 239f); lineTo(743f, 414f); lineTo(806f, 414f); close() }
        shape(Color.rgb(118, 99, 56)) { moveTo(700f, 431f); lineTo(823f, 294f); lineTo(931f, 431f); close() }
        shape(Color.rgb(70, 66, 39)) { moveTo(823f, 294f); lineTo(851f, 431f); lineTo(931f, 431f); close() }
        shape(Color.rgb(30, 43, 31)) { moveTo(420f, 446f); cubicTo(565f, 384f, 703f, 470f, 960f, 414f); lineTo(960f, 540f); lineTo(420f, 540f); close() }
        fun palm(x: Float, y: Float, scale: Float) {
            val state = canvas.save(); canvas.translate(x, y); canvas.scale(scale, scale)
            val palmColor = Color.rgb(117, 119, 67)
            shape(palmColor) { moveTo(-8f, 192f); quadTo(8f, 95f, 0f, 0f); quadTo(25f, 91f, 7f, 192f); close() }
            for (side in intArrayOf(-1, 1)) {
                val mirror = canvas.save(); canvas.scale(side.toFloat(), 1f)
                shape(palmColor) { moveTo(0f, 0f); cubicTo(38f, -35f, 76f, -21f, 105f, 15f); cubicTo(66f, -3f, 37f, -8f, 0f, 0f); close() }
                shape(palmColor) { moveTo(0f, 0f); cubicTo(40f, -4f, 72f, 25f, 74f, 66f); cubicTo(53f, 29f, 30f, 15f, 0f, 0f); close() }
                shape(palmColor) { moveTo(0f, 0f); cubicTo(7f, -40f, 33f, -62f, 60f, -60f); cubicTo(31f, -44f, 18f, -20f, 0f, 0f); close() }
                canvas.restoreToCount(mirror)
            }
            canvas.restoreToCount(state)
        }
        palm(584f, 237f, .88f)
        palm(910f, 201f, 1.15f)
        paint.color = Color.argb(55, 208, 184, 121); paint.strokeWidth = .7f
        canvas.drawLine(46f, 145f, 914f, 145f, paint)
        canvas.restoreToCount(save)
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter }
    @Deprecated("Drawable opacity")
    override fun getOpacity() = PixelFormat.OPAQUE
}
