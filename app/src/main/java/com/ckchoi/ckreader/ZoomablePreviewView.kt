package com.ckchoi.ckreader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.min

/** One-finger pan, pinch zoom (1x-6x), double tap and reset for offline font previews. */
internal class ZoomablePreviewView(context:Context,private val artwork:Bitmap):View(context) {
 private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
 private var fitScale=1f
 private var zoom=1f
 private var panX=0f
 private var panY=0f
 private var lastX=0f
 private var lastY=0f
 private var activePointer=false
 private val scaler=ScaleGestureDetector(context,object:ScaleGestureDetector.SimpleOnScaleGestureListener() {
  override fun onScale(detector:ScaleGestureDetector):Boolean {
   val next=(zoom*detector.scaleFactor).coerceIn(1f,6f)
   val factor=next/zoom
   if(factor!=1f) {
    panX=detector.focusX+(panX-detector.focusX)*factor
    panY=detector.focusY+(panY-detector.focusY)*factor
    zoom=next;limitPan();invalidate()
   }
   return true
  }
 })
 private val tapper=GestureDetector(context,object:GestureDetector.SimpleOnGestureListener() {
  override fun onDown(e:MotionEvent)=true
  override fun onDoubleTap(e:MotionEvent):Boolean {
   if(zoom>1.02f)reset() else {
    val factor=2.5f/zoom
    panX=e.x+(panX-e.x)*factor
    panY=e.y+(panY-e.y)*factor
    zoom=2.5f;limitPan();invalidate()
   }
   return true
  }
 })
 init {setBackgroundColor(Color.WHITE);contentDescription="글꼴 미리보기, 두 손가락 확대와 축소, 한 손가락 이동, 두 번 탭해 초기화"}
 private fun layoutFit() {
  if(width==0||height==0)return
  fitScale=min(width.toFloat()/artwork.width,height.toFloat()/artwork.height)
  reset()
 }
 override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int){super.onSizeChanged(w,h,oldw,oldh);layoutFit()}
 fun reset(){zoom=1f;panX=width/2f;panY=height/2f;invalidate()}
 private fun limitPan(){
  val drawnW=artwork.width*fitScale*zoom
  val drawnH=artwork.height*fitScale*zoom
  panX=if(drawnW<=width)width/2f else panX.coerceIn(width-drawnW/2f,drawnW/2f)
  panY=if(drawnH<=height)height/2f else panY.coerceIn(height-drawnH/2f,drawnH/2f)
 }
 override fun onDraw(canvas:Canvas){
  super.onDraw(canvas)
  if(fitScale<=0f)return
  canvas.save()
  canvas.translate(panX,panY)
  canvas.scale(fitScale*zoom,fitScale*zoom)
  canvas.drawBitmap(artwork,-artwork.width/2f,-artwork.height/2f,paint)
  canvas.restore()
 }
 override fun onTouchEvent(event:MotionEvent):Boolean {
  scaler.onTouchEvent(event)
  tapper.onTouchEvent(event)
  when(event.actionMasked){
   MotionEvent.ACTION_DOWN->{lastX=event.x;lastY=event.y;activePointer=true;parent?.requestDisallowInterceptTouchEvent(true)}
   MotionEvent.ACTION_POINTER_DOWN->{activePointer=false;parent?.requestDisallowInterceptTouchEvent(true)}
   MotionEvent.ACTION_MOVE->{
    if(!scaler.isInProgress&&event.pointerCount==1&&activePointer&&zoom>1f){
     panX+=event.x-lastX;panY+=event.y-lastY;limitPan();invalidate()
    }
    if(event.pointerCount==1){lastX=event.x;lastY=event.y}
   }
   MotionEvent.ACTION_POINTER_UP->{activePointer=false}
   MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{activePointer=false;parent?.requestDisallowInterceptTouchEvent(false)}
  }
  return true
 }
}
