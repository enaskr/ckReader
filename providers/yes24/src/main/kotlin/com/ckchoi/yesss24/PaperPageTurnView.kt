package com.ckchoi.yesss24

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/** Turns a page from its lower outside corner, exposing the curved paper back. */
internal class PaperPageTurnView(
 context:Context,
 private val snapshot:Bitmap,
 private val forward:Boolean,
 private val pageColor:Int
):View(context) {
 var progress=0f
  set(value){field=value.coerceIn(0f,1f);invalidate()}

 private val imagePaint=Paint(Paint.FILTER_BITMAP_FLAG)
 private val backPaint=Paint(Paint.ANTI_ALIAS_FLAG)
 private val ghostPaint=Paint(Paint.FILTER_BITMAP_FLAG).apply{alpha=22}
 private val shadowPaint=Paint(Paint.ANTI_ALIAS_FLAG)
 private val foldPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE}
 private val front=Path()
 private val back=Path()
 private val fold=Path()
 private val outside=Path()
 private val shadow=Path()

 override fun onDraw(canvas:Canvas){
  super.onDraw(canvas)
  val w=width.toFloat();val h=height.toFloat()
  if(w<=0f||h<=0f||snapshot.isRecycled)return
  val t=progress
  if(t<=0f){canvas.drawBitmap(snapshot,null,RectF(0f,0f,w,h),imagePaint);return}
  if(t>=1f)return
  fun smooth(start:Float,end:Float):Float{
   val x=((t-start)/(end-start)).coerceIn(0f,1f)
   return x*x*(3f-2f*x)
  }
  // The lower corner moves first. The front folds away before the back.
  val frontTop=w*(1f-smooth(.24f,.76f))
  val frontBottom=w*(1f-smooth(.04f,.91f))*(1f-smooth(.62f,.80f))
  val bulge=w*.31f*sin(Math.PI*smooth(.13f,.76f)).toFloat().coerceAtLeast(0f)
  val frontTip=(frontTop-bulge).coerceIn(0f,w)
  val backTop=w*(1f-smooth(.19f,1f))
  val backBottom=max(frontBottom,w*(1f-smooth(.04f,1f)))
  val shadowSize=w*.055f*sin(Math.PI*t).toFloat().coerceAtLeast(0f)

  front.reset();back.reset();fold.reset();outside.reset();shadow.reset()
  front.moveTo(0f,0f);front.lineTo(frontTop,0f)
  traceFold(front,frontTop,frontTip,frontBottom,w,h)
  front.lineTo(0f,h);front.close()

  fold.moveTo(frontTop,0f)
  traceFold(fold,frontTop,frontTip,frontBottom,w,h)
  outside.moveTo(backTop,0f)
  traceOutside(outside,backTop,backBottom,w,h)

  back.moveTo(frontTop,0f)
  traceFold(back,frontTop,frontTip,frontBottom,w,h)
  back.lineTo(backBottom,h)
  back.cubicTo(backBottom+w*.03f,h*.72f,backTop-w*.07f,h*.30f,backTop,0f)
  back.close()

  shadow.moveTo(backTop,0f)
  traceOutside(shadow,backTop,backBottom,w,h)
  shadow.lineTo((backBottom+shadowSize).coerceAtMost(w),h)
  shadow.lineTo((backTop+shadowSize).coerceAtMost(w),0f)
  shadow.close()

  canvas.save()
  // Mirror only the curl geometry; the book text must keep its orientation.
  if(!forward)canvas.scale(-1f,1f,w/2f,0f)
  if(shadowSize>1f){
   shadowPaint.color=0x33000000
   canvas.drawPath(shadow,shadowPaint)
  }
  canvas.save()
  canvas.clipPath(front)
  if(!forward)canvas.scale(-1f,1f,w/2f,0f)
  canvas.drawBitmap(snapshot,null,RectF(0f,0f,w,h),imagePaint)
  canvas.restore()

  if(t>0f&&t<1f){
   val shade=blend(pageColor,Color.BLACK,.16f)
   val light=blend(pageColor,Color.WHITE,.15f)
   val start=frontTip
   val end=max(backTop,backBottom)
   backPaint.color=pageColor
   backPaint.shader=if(abs(end-start)>1f)
    LinearGradient(start,0f,end,0f,
     intArrayOf(light,pageColor,shade),floatArrayOf(0f,.48f,1f),Shader.TileMode.CLAMP)
   else null
   canvas.drawPath(back,backPaint)
   backPaint.shader=null

   // Faint mirrored print showing through the thin sheet.
   canvas.save()
   canvas.clipPath(back)
   if(forward)canvas.scale(-1f,1f,w/2f,0f)
   canvas.drawBitmap(snapshot,null,RectF(0f,0f,w,h),ghostPaint)
   canvas.restore()

   foldPaint.color=blend(pageColor,Color.BLACK,.26f)
   foldPaint.strokeWidth=resources.displayMetrics.density*1.4f
   canvas.drawPath(fold,foldPaint)
   foldPaint.color=blend(pageColor,Color.WHITE,.36f)
   foldPaint.strokeWidth=resources.displayMetrics.density
   canvas.save()
   canvas.translate(resources.displayMetrics.density*1.5f,0f)
   canvas.drawPath(fold,foldPaint)
   canvas.restore()

   foldPaint.color=0x49000000
   foldPaint.strokeWidth=resources.displayMetrics.density*2f
   canvas.drawPath(outside,foldPaint)
  }
  canvas.restore()
 }

 fun dispose(){if(!snapshot.isRecycled)snapshot.recycle()}

 private fun traceFold(path:Path,top:Float,tip:Float,bottom:Float,w:Float,h:Float){
  path.cubicTo(top-w*.03f,h*.16f,tip+w*.025f,h*.34f,tip,h*.52f)
  path.cubicTo(tip+w*.11f,h*.68f,bottom-w*.025f,h*.83f,bottom,h)
 }

 private fun traceOutside(path:Path,top:Float,bottom:Float,w:Float,h:Float){
  path.cubicTo(top-w*.07f,h*.30f,bottom+w*.03f,h*.72f,bottom,h)
 }

 private fun blend(from:Int,to:Int,amount:Float):Int=Color.rgb(
  (Color.red(from)*(1f-amount)+Color.red(to)*amount).toInt(),
  (Color.green(from)*(1f-amount)+Color.green(to)*amount).toInt(),
  (Color.blue(from)*(1f-amount)+Color.blue(to)*amount).toInt())
}
