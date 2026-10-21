package com.ckchoi.yesss24

import android.os.Handler
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration

/** A stationary single-finger hold; a short tap, drag or pinch never seeks. */
internal class SentenceHoldGesture(private val view:View,private val handler:Handler,
 private val enabled:()->Boolean,private val onHold:(Float,Float)->Unit) {
 private val slop=ViewConfiguration.get(view.context).scaledTouchSlop.toFloat()
 private var x=0f;private var y=0f;private var downAt=0L
 private var pending=false;private var fired=false
 private val action=Runnable {
  if(pending&&enabled()) {
   pending=false;fired=true;view.cancelLongPress();onHold(x,y)
  }
 }
 fun cancel(){pending=false;handler.removeCallbacks(action)}
 /** Returns true on release after an already dispatched long hold. */
 fun onTouch(event:MotionEvent):Boolean {
  when(event.actionMasked) {
   MotionEvent.ACTION_DOWN->{cancel();fired=false;x=event.x;y=event.y;downAt=event.eventTime
    if(enabled()){pending=true;handler.postDelayed(action,SpeechPosition.HOLD_MILLIS)}}
   MotionEvent.ACTION_MOVE->if(event.pointerCount!=1||kotlin.math.hypot(event.x-x,event.y-y)>slop)cancel()
   MotionEvent.ACTION_POINTER_DOWN,MotionEvent.ACTION_CANCEL->cancel()
   MotionEvent.ACTION_UP->{
    if(pending&&enabled()&&SpeechPosition.held(event.eventTime-downAt,kotlin.math.hypot(event.x-x,event.y-y),slop,event.pointerCount))action.run()
    val consumed=fired;cancel();return consumed
   }
  }
  return false
 }
}
