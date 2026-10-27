package com.marhaba.launcher

import android.graphics.*
import android.graphics.drawable.Drawable
import kotlin.math.sin

/** One continuous landscape: sky, distant architecture, then foreground palms and dunes. */
class MarhabaBackdrop : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun draw(c: Canvas) {
        val saved = c.save(); c.scale(bounds.width()/960f, bounds.height()/540f)
        val time=android.os.SystemClock.uptimeMillis()/1000.0
        val phase=Daylight.phase(); val night=phase=="night"
        val colors=when(phase) {
            "sunrise" -> intArrayOf(0xff172b33.toInt(),0xff61534c.toInt(),0xffc5956d.toInt())
            "sunset" -> intArrayOf(0xff252d40.toInt(),0xff79544e.toInt(),0xffc98760.toInt())
            "day" -> intArrayOf(0xff183b48.toInt(),0xff527c82.toInt(),0xffb3b39b.toInt())
            else -> intArrayOf(0xff0b1827.toInt(),0xff203548.toInt(),0xff43535c.toInt())
        }
        p.reset();p.isAntiAlias=true
        p.shader=LinearGradient(0f,0f,0f,450f,colors,floatArrayOf(0f,.6f,1f),Shader.TileMode.CLAMP)
        c.drawRect(0f,0f,960f,540f,p);p.shader=null
        val sunX=735f; val sunY=if(phase=="sunrise"||phase=="sunset") 314f else 172f
        p.shader=RadialGradient(sunX,sunY,130f,intArrayOf(Color.argb(if(night)12 else 40,255,213,154),Color.TRANSPARENT),null,Shader.TileMode.CLAMP)
        c.drawCircle(sunX,sunY,130f,p);p.shader=null
        p.color=if(night) 0xffe2e5da.toInt() else 0xffefd4a0.toInt()
        c.drawCircle(sunX,sunY,if(night)22f else 38f,p)
        if(night) for(i in 0..46) {
            p.color=Color.argb(100+(55*sin(time*.5+i)).toInt(),235,241,235)
            c.drawCircle(24f+i*131%912,25f+i*67%260,if(i%4==0)1.1f else .6f,p)
        }
        fun shape(color:Int, draw:Path.()->Unit) {
            p.shader=null;p.style=Paint.Style.FILL;p.color=color;c.drawPath(Path().apply(draw),p)
        }
        // Soft distant sand establishes a single ground plane behind every landmark.
        shape(if(night)0xff39474a.toInt() else 0xff8b8b73.toInt()) {
            moveTo(0f,392f);cubicTo(170f,374f,310f,407f,477f,390f);cubicTo(640f,375f,800f,392f,960f,373f)
            lineTo(960f,540f);lineTo(0f,540f);close()
        }
        fun pyramid(left:Float, base:Float, width:Float, height:Float, lit:Int, shade:Int) {
            val apex=left+width*.55f;val top=base-height;val seam=left+width*.72f
            shape(lit) { moveTo(left,base);lineTo(apex,top);lineTo(seam,base);close() }
            shape(shade) { moveTo(apex,top);lineTo(left+width,base);lineTo(seam,base);close() }
        }
        val lit=if(night)0xff526061.toInt() else if(phase=="day")0xffb8aa80.toInt() else 0xffb19a70.toInt()
        val shade=if(night)0xff344449.toInt() else 0xff797c62.toInt()
        // Separated silhouettes with the same vanishing plane and light direction.
        pyramid(764f,411f,150f,96f,lit,shade)
        pyramid(563f,420f,234f,173f,lit,shade)
        pyramid(411f,421f,168f,116f,lit,shade)
        shape(if(night)0xff233a3d.toInt() else 0xff526751.toInt()) {
            moveTo(0f,425f);cubicTo(210f,396f,300f,453f,504f,429f);cubicTo(710f,406f,821f,439f,960f,410f)
            lineTo(960f,540f);lineTo(0f,540f);close()
        }
        fun palm(x:Float,y:Float,scale:Float,lean:Float) {
            val state=c.save();c.translate(x,y);c.scale(scale,scale)
            c.rotate(lean+(sin(time*.32+x)*.65).toFloat(),0f,190f)
            val foliage=if(night)0xff112b2e.toInt() else 0xff203d32.toInt()
            shape(foliage) { moveTo(-7f,204f);cubicTo(7f,122f,6f,57f,0f,0f);cubicTo(18f,67f,18f,135f,8f,204f);close() }
            // Feathered fronds, with a shared crown and tapered leaflets.
            for(side in intArrayOf(-1,1)) for(frond in 0..3) {
                val endX=side*(95f-frond*14f);val endY=-47f+frond*31f
                val controlX=endX*.58f;val controlY=endY-42f
                p.color=foliage;p.style=Paint.Style.STROKE;p.strokeWidth=2.4f;p.strokeCap=Paint.Cap.ROUND
                c.drawPath(Path().apply { moveTo(0f,0f);quadTo(controlX,controlY,endX,endY) },p)
                for(j in 1..10) {
                    val t=j/11f;val xx=2*(1-t)*t*controlX+t*t*endX;val yy=2*(1-t)*t*controlY+t*t*endY
                    val length=18f*(1-t)+4f
                    p.strokeWidth=2.1f*(1-t)+.5f
                    c.drawLine(xx,yy,xx-side*length*.3f,yy+length,p)
                    c.drawLine(xx,yy,xx+side*length*.5f,yy+length*.55f,p)
                }
            }
            p.style=Paint.Style.FILL;c.restoreToCount(state)
        }
        // Foreground framing stays out of the app and weather regions.
        palm(-9f,307f,.86f,-5f)
        palm(935f,239f,1.2f,5f)
        shape(if(night)0xff102b2c.toInt() else 0xff263f33.toInt()) {
            moveTo(0f,454f);cubicTo(190f,433f,320f,471f,493f,455f);cubicTo(667f,435f,808f,472f,960f,439f)
            lineTo(960f,540f);lineTo(0f,540f);close()
        }
        // Gentle left-side shade supports the launcher without a visible panel edge.
        p.shader=LinearGradient(0f,0f,490f,0f,intArrayOf(Color.argb(55,5,18,21),Color.TRANSPARENT),null,Shader.TileMode.CLAMP)
        p.color=Color.WHITE;c.drawRect(0f,0f,490f,540f,p);p.shader=null
        c.restoreToCount(saved)
    }
    override fun setAlpha(alpha:Int) { p.alpha=alpha }
    override fun setColorFilter(filter:ColorFilter?) { p.colorFilter=filter }
    @Deprecated("Drawable opacity") override fun getOpacity()=PixelFormat.OPAQUE
}
