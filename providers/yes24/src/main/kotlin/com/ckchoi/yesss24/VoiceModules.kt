package com.ckchoi.yesss24

import android.content.Context
import android.content.pm.PackageManager

internal object VoiceModules {
 const val YES24_PACKAGE="com.ckchoi.ckreader.voice.yes24"
 const val SELVY_PACKAGE="com.ckchoi.ckreader.voice.selvy"
 const val WILLA_PACKAGE="com.ckchoi.ckreader.voice.willa"
 const val PERMISSION="com.ckchoi.ckreader.permission.VOICE"

 fun installed(context:Context,source:String):Boolean {
  val packageName=when(source){"ridi"->SELVY_PACKAGE;"willa"->WILLA_PACKAGE;else->YES24_PACKAGE}
  return try { context.packageManager.getPackageInfo(packageName,0);true }
  catch(_:PackageManager.NameNotFoundException){false}
 }

 fun service(source:String)=when(source){
  "ridi"->android.content.ComponentName(SELVY_PACKAGE,"com.ckchoi.yesss24.RidiVoiceService")
  "willa"->android.content.ComponentName(WILLA_PACKAGE,"com.ckchoi.yesss24.WillaVoiceService")
  else->android.content.ComponentName(YES24_PACKAGE,"com.ckchoi.yesss24.Yes24VoiceService")
 }
}

