package com.ckchoi.yesss24

import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import android.widget.ImageView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Full-page FIT_CENTER view with anchored pinch zoom, drag, double-tap and paging.
 * Paging is disabled while magnified to prevent accidental page turns during panning.
 */
internal class ZoomablePageView(context:Context):ImageView(context) {
 private val drawMatrix=Matrix()
 private val srcRect=RectF()
 private val viewport=RectF()
 private val display=RectF()
 private var zoom=1f
 private var downX=0f
 private var downY=0f
 private var lastX=0f
 private var lastY=0f
 private var hasPinched=false
 private var didPage=false
 private val swipeThreshold=max(ViewConfiguration.get(context).scaledTouchSlop*5f,55f*resources.displayMetrics.density)

 var onPageRequest:((Int)->Unit)?=null
 /** Short centre tap action for ZIP/CBZ; PDF text hold is unaffected. */
 var onCenterTap:(()->Unit)?=null
 /** Normalized point within the displayed bitmap; return true when consumed. */
 var onTextHold:((Float,Float)->Boolean)?=null
 var textHoldEnabled:(()->Boolean)?=null
 private val hold=SentenceHoldGesture(this,android.os.Handler(android.os.Looper.getMainLooper()),
  {textHoldEnabled?.invoke()==true},{x,y->normalizedPoint(x,y)?.let{onTextHold?.invoke(it.first,it.second)}})
 var onVerticalSwipe:((Float)->Unit)?=null
 var pagingByTouch=true
 /** Called on the UI thread only after a pinch or double-tap has finished. */
 var onMagnificationChanged:((Float)->Unit)?=null

 private val scaleDetector=ScaleGestureDetector(context,object:ScaleGestureDetector.SimpleOnScaleGestureListener() {
  override fun onScaleEnd(detector:ScaleGestureDetector) {
   super.onScaleEnd(detector)
   if(zoom>1.25f)onMagnificationChanged?.invoke(zoom)
  }
  override fun onScale(detector:ScaleGestureDetector):Boolean {
   val proposed=(zoom*detector.scaleFactor).coerceIn(1f,6f)
   val factor=proposed/zoom
   if(factor==1f)return false
   zoom=proposed
   drawMatrix.postScale(factor,factor,detector.focusX,detector.focusY)
   normalize()
   invalidate()
   hasPinched=true
   return true
  }
 })
 private val gesture=GestureDetector(context,object:GestureDetector.SimpleOnGestureListener(){
  override fun onDown(e:MotionEvent):Boolean=true
  override fun onDoubleTap(e:MotionEvent):Boolean {
   val target=if(zoom>1.1f)1f else 2.5f
   val factor=target/zoom;zoom=target
   if(target==1f)reset() else {
    drawMatrix.postScale(factor,factor,e.x,e.y)
    normalize()
   }
   invalidate()
   if(target>1.25f)onMagnificationChanged?.invoke(zoom)
   return true
  }
  override fun onSingleTapConfirmed(e:MotionEvent):Boolean {
   if(hasPinched||didPage)return false
   // Wait for single-tap confirmation so a double-tap still zooms, and a
   // swipe/drag never opens the fullscreen button.
   if(onCenterTap!=null && ComicCenterTapPolicy.isCenter(e.x,e.y,width,height)) {
    onCenterTap?.invoke()
    return true
   }
   if(!pagingByTouch||zoom>1.01f)return false
   if(e.x<width*.20f)dispatchPage(-1)
   else if(e.x>width*.80f)dispatchPage(1)
   return true
  }
  override fun onFling(e1:MotionEvent?,e2:MotionEvent,velocityX:Float,velocityY:Float):Boolean {
   if(zoom>1.01f||hasPinched||!pagingByTouch||didPage)return false
   val dx=e2.x-(e1?.x?:downX)
   val dy=e2.y-(e1?.y?:downY)
   if(abs(dx)>swipeThreshold&&abs(dx)>abs(dy)*1.3f){
    dispatchPage(if(dx<0)1 else -1)
    return true
   }
   return false
  }
 })
 private fun dispatchPage(direction:Int){
  if(!didPage){didPage=true;onPageRequest?.invoke(direction)}
 }

 fun setPageBitmap(bitmap:android.graphics.Bitmap?,preserveZoom:Boolean=false) {
  // The renderer may replace the bitmap with a sharper PDF while zoomed.
  // Preserve the viewport's normalized centre and its magnification.
  val oldDrawable=drawable
  val oldZoom=zoom
  var xFraction=.5f
  var yFraction=.5f
  val canPreserve=preserveZoom&&oldZoom>1.01f&&oldDrawable!=null&&width>0&&height>0&&
   oldDrawable.intrinsicWidth>0&&oldDrawable.intrinsicHeight>0
  if(canPreserve) {
   val inverse=Matrix()
   if(drawMatrix.invert(inverse)) {
    val centre=floatArrayOf(width/2f,height/2f)
    inverse.mapPoints(centre)
    xFraction=(centre[0]/oldDrawable!!.intrinsicWidth).coerceIn(0f,1f)
    yFraction=(centre[1]/oldDrawable.intrinsicHeight).coerceIn(0f,1f)
   }
  }
  super.setImageBitmap(bitmap)
  reset()
  if(canPreserve&&bitmap!=null) {
   zoom=oldZoom
   drawMatrix.postScale(zoom,zoom,width/2f,height/2f)
   val newFocus=floatArrayOf(bitmap.width*xFraction,bitmap.height*yFraction)
   drawMatrix.mapPoints(newFocus)
   drawMatrix.postTranslate(width/2f-newFocus[0],height/2f-newFocus[1])
   normalize()
  }
 }
 fun zoomBy(factor:Float) {
  val next=(zoom*factor).coerceIn(1f,6f)
  if(next==1f){reset();return}
  val applied=next/zoom;zoom=next
  drawMatrix.postScale(applied,applied,width/2f,height/2f)
  normalize();invalidate()
  if(zoom>1.25f)onMagnificationChanged?.invoke(zoom)
 }
 fun isMagnified():Boolean=zoom>1.01f
 fun visibleImageRect():RectF? {
  val image=drawable?:return null
  val inverse=Matrix();if(!drawMatrix.invert(inverse))return null
  val visible=RectF(0f,0f,width.toFloat(),height.toFloat());inverse.mapRect(visible)
  val w=image.intrinsicWidth.toFloat();val h=image.intrinsicHeight.toFloat()
  if(w<=0||h<=0||!visible.intersect(0f,0f,w,h))return null
  return RectF(visible.left/w,visible.top/h,visible.right/w,visible.bottom/h)
 }

 private fun normalizedPoint(x:Float,y:Float):Pair<Float,Float>? {
  val image=drawable?:return null
  val w=image.intrinsicWidth.toFloat()
  val h=image.intrinsicHeight.toFloat()
  if(w<=0f||h<=0f)return null
  val inverted=Matrix()
  if(!drawMatrix.invert(inverted))return null
  val point=floatArrayOf(x,y)
  inverted.mapPoints(point)
  if(point[0]<0f||point[0]>w||point[1]<0f||point[1]>h)return null
  return point[0]/w to point[1]/h
 }

 private fun reset() {
  zoom=1f
  val image=drawable
  if(width<=0||height<=0||image==null||image.intrinsicWidth<=0||image.intrinsicHeight<=0){
   drawMatrix.reset()
  }else{
   srcRect.set(0f,0f,image.intrinsicWidth.toFloat(),image.intrinsicHeight.toFloat())
   viewport.set(0f,0f,width.toFloat(),height.toFloat())
   drawMatrix.setRectToRect(srcRect,viewport,Matrix.ScaleToFit.CENTER)
  }
  imageMatrix=drawMatrix
  invalidate()
 }
 private fun normalize() {
  val image=drawable?:return
  if(image.intrinsicWidth<=0||image.intrinsicHeight<=0||width==0||height==0)return
  display.set(0f,0f,image.intrinsicWidth.toFloat(),image.intrinsicHeight.toFloat())
  drawMatrix.mapRect(display)
  val dx=when {
   display.width()<width->width/2f-display.centerX()
   display.left>0f->-display.left
   display.right<width->width-display.right
   else->0f
  }
  val dy=when {
   display.height()<height->height/2f-display.centerY()
   display.top>0f->-display.top
   display.bottom<height->height-display.bottom
   else->0f
  }
  if(dx!=0f||dy!=0f)drawMatrix.postTranslate(dx,dy)
  imageMatrix=drawMatrix
 }
 override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int) {
  super.onSizeChanged(w,h,oldw,oldh)
  reset()
 }

 override fun onTouchEvent(event:MotionEvent):Boolean {
  val held=hold.onTouch(event)
  if(event.actionMasked==MotionEvent.ACTION_DOWN){
   downX=event.x;downY=event.y;lastX=event.x;lastY=event.y
   hasPinched=false;didPage=false
   parent?.requestDisallowInterceptTouchEvent(true)
  }
  if(event.pointerCount>1)hasPinched=true
  scaleDetector.onTouchEvent(event)
  if(!hasPinched&&!held)gesture.onTouchEvent(event)
  when(event.actionMasked){
   MotionEvent.ACTION_MOVE->{
    if(event.pointerCount==1 && zoom>1.01f && !scaleDetector.isInProgress){
     val dx=event.x-lastX;val dy=event.y-lastY
     drawMatrix.postTranslate(dx,dy)
     normalize()
    }
    lastX=event.x;lastY=event.y
   }
   MotionEvent.ACTION_POINTER_UP->{
    if(event.pointerCount>1){
     val keep=if(event.actionIndex==0)1 else 0
     lastX=event.getX(keep);lastY=event.getY(keep)
    }
   }
   MotionEvent.ACTION_UP->{
    if(zoom<=1.01f&&!hasPinched&&!didPage&&!held){
     val dx=event.x-downX;val dy=event.y-downY
     if(abs(dy)>swipeThreshold&&abs(dy)>abs(dx)*1.4f&&downX>width*.8f)onVerticalSwipe?.invoke(dy)
     else if(pagingByTouch&&abs(dx)>swipeThreshold&&abs(dx)>abs(dy)*1.3f)
      dispatchPage(if(dx<0)1 else -1)
    }
   }
   MotionEvent.ACTION_CANCEL->parent?.requestDisallowInterceptTouchEvent(false)
  }
  return true
 }
 override fun onDetachedFromWindow(){hold.cancel();super.onDetachedFromWindow()}
 init {
  scaleType=ScaleType.MATRIX
  isClickable=true
  contentDescription="이미지 페이지 · 두 손가락으로 확대/축소, 두 번 터치로 확대"
 }
}
