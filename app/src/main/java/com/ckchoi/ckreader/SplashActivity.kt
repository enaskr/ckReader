package com.ckchoi.ckreader

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.graphics.Color
import android.graphics.Typeface
import com.ckchoi.elibrary.common.GlobalConfig
import com.ckchoi.elibrary.common.ReaderDesign

/**
 * Cold-launch splash. Keep this Activity independent of storage/database
 * initialization so the app icon appears before the library starts loading.
 */
class SplashActivity:Activity() {
 private val mainHandler=Handler(Looper.getMainLooper())
 private var launched=false
 private val showLibrary=Runnable {
  if(!isFinishing&&!isDestroyed&&!launched) {
   launched=true
   startActivity(Intent(this,LibraryActivity::class.java).apply {
    intent?.extras?.let(::putExtras)
   })
   finish()
   overridePendingTransition(0,0)
  }
 }
 override fun onCreate(state:Bundle?) {
  setTheme(if(GlobalConfig.isDark(this))
   com.ckchoi.elibrary.common.R.style.Theme_CkReader_Cobalt
   else com.ckchoi.elibrary.common.R.style.Theme_CkReader_Heyclicky)
  super.onCreate(state)
  // The new full-screen splash artwork has a dark indigo border in both
  // themes; match system bars so no pale flash appears at cold start.
  val eink=GlobalConfig.isEInk(this)
  val splashBar=if(eink)Color.WHITE else Color.rgb(9,5,64)
  window.statusBarColor=splashBar
  window.navigationBarColor=splashBar
  @Suppress("DEPRECATION")
  window.decorView.systemUiVisibility=if(eink)View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0
  val frame=FrameLayout(this).apply {
   setBackgroundColor(splashBar)
  }
  // A dedicated, full-resolution portrait artwork is kept under drawable-nodpi,
  // so Android will not upscale the small launcher icon or scale by density.
  if(eink) {
   frame.addView(TextView(this).apply {
    text="ckReader";textSize=29f;setTextColor(Color.BLACK)
    typeface=Typeface.DEFAULT_BOLD;gravity=Gravity.CENTER
   },FrameLayout.LayoutParams(-1,-1,Gravity.CENTER))
  } else frame.addView(ImageView(this).apply {
   setImageResource(R.drawable.splash_highres)
   scaleType=ImageView.ScaleType.CENTER_CROP
   contentDescription=getString(R.string.app_name)
  },FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
   FrameLayout.LayoutParams.MATCH_PARENT,Gravity.CENTER))
  setContentView(frame)
  mainHandler.postDelayed(showLibrary,if(eink)100L else 500L)
 }
 override fun onDestroy() {
  mainHandler.removeCallbacks(showLibrary)
  super.onDestroy()
 }
}
