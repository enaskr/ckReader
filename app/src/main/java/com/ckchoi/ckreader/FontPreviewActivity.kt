package com.ckchoi.ckreader

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.ckchoi.elibrary.common.ReaderFonts

/** Full-screen, live-typeface preview for bundled and user-imported fonts. */
class FontPreviewActivity:Activity() {
 companion object { const val EXTRA_FAMILY_ID="family_id" }

 override fun onCreate(savedInstanceState:Bundle?) {
  super.onCreate(savedInstanceState)
  val id=intent.getStringExtra(EXTRA_FAMILY_ID).orEmpty()
  val family=ReaderFonts.allFamilies(this).firstOrNull{it.id==id&&ReaderFonts.installed(this,it.id)}
  val face=family?.let{ReaderFonts.font(this,it.id)}
  if(family==null||face==null){finish();return}

  val root=LinearLayout(this).apply {
   orientation=LinearLayout.VERTICAL
   setBackgroundColor(Color.rgb(248,250,255))
  }
  window.statusBarColor=Color.WHITE
  window.navigationBarColor=Color.rgb(248,250,255)
  @Suppress("DEPRECATION")
  window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
   View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
  ViewCompat.setOnApplyWindowInsetsListener(root){view,insets->
   val bars=insets.getInsets(WindowInsetsCompat.Type.systemBars() or
    WindowInsetsCompat.Type.displayCutout())
   view.setPadding(bars.left,bars.top,bars.right,bars.bottom)
   WindowInsetsCompat.CONSUMED
  }
  val header=LinearLayout(this).apply {
   orientation=LinearLayout.HORIZONTAL
   gravity=Gravity.CENTER_VERTICAL
   setPadding(dp(12),dp(8),dp(12),dp(8))
   setBackgroundColor(Color.WHITE)
  }
  header.addView(Button(this).apply{text="닫기";setOnClickListener{finish()}})
  header.addView(TextView(this).apply {
   text=family.label
   textSize=19f
   setTextColor(Color.rgb(23,36,60))
   maxLines=1
   gravity=Gravity.CENTER
  },LinearLayout.LayoutParams(0,dp(48),1f))
  val reset=Button(this).apply{text="크기 초기화"}
  header.addView(reset)
  root.addView(header)

  root.addView(TextView(this).apply {
   text="기미독립선언서 · 한글 표기 일부"
   textSize=14f
   setTextColor(Color.rgb(64,91,137))
   setPadding(dp(24),dp(16),dp(24),dp(8))
  })
  val sample=TextView(this).apply {
   text=FontDeclarationPreview.excerpt
   typeface=face
   setTextSize(TypedValue.COMPLEX_UNIT_SP,24f)
   setTextColor(Color.rgb(22,30,45))
   setLineSpacing(dp(8).toFloat(),1.08f)
   setPadding(dp(24),dp(16),dp(24),dp(32))
   includeFontPadding=true
  }
  val scroller=PinchZoomFontScrollView(this,sample).apply {
   isFillViewport=true
   setBackgroundColor(Color.WHITE)
   addView(sample,FrameLayout.LayoutParams(-1,-2))
  }
  root.addView(scroller,LinearLayout.LayoutParams(-1,0,1f))
  reset.setOnClickListener{scroller.resetZoom()}
  root.addView(TextView(this).apply {
   text="두 손가락으로 확대·축소 · 한 손가락으로 스크롤 · 출처: 국가보훈부 교육자료"
   textSize=12f
   setTextColor(Color.rgb(84,100,126))
   setPadding(dp(20),dp(12),dp(20),dp(16))
  })
  setContentView(root)
 }

 private fun dp(value:Int)=(value*resources.displayMetrics.density+0.5f).toInt()
}

/** Pinch changes the real text size, so glyphs stay sharp and lines reflow. */
internal class PinchZoomFontScrollView(context:Context,private val sample:TextView):ScrollView(context) {
 private var sizeSp=24f
 private var pinching=false
 private val scaler=ScaleGestureDetector(context,object:ScaleGestureDetector.SimpleOnScaleGestureListener() {
  override fun onScale(detector:ScaleGestureDetector):Boolean {
   val next=(sizeSp*detector.scaleFactor).coerceIn(14f,72f)
   if(kotlin.math.abs(next-sizeSp)<0.15f)return true
   val anchor=(scrollY+detector.focusY)/sample.height.coerceAtLeast(1)
   sizeSp=next
   sample.setTextSize(TypedValue.COMPLEX_UNIT_SP,sizeSp)
   post{scrollTo(0,(sample.height*anchor-detector.focusY).toInt().coerceAtLeast(0))}
   return true
  }
 })
 fun resetZoom(){sizeSp=24f;sample.setTextSize(TypedValue.COMPLEX_UNIT_SP,sizeSp);smoothScrollTo(0,0)}
 override fun dispatchTouchEvent(event:MotionEvent):Boolean {
  scaler.onTouchEvent(event)
  if(event.pointerCount>1||scaler.isInProgress)pinching=true
  if(pinching){
   if(event.actionMasked==MotionEvent.ACTION_UP||event.actionMasked==MotionEvent.ACTION_CANCEL)
    pinching=false
   return true
  }
  return super.dispatchTouchEvent(event)
 }
}
