package com.ckchoi.ckreader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log

/**
 * Static image previews bundled with ckReader. They never depend on the separate
 * Font7/Font14 APK packages being installed or on TTF extraction/cache state.
 */
internal object FontPreviewAssets {
 private const val ROOT = "font_previews/"
 private val idPattern = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")

 fun assetName(familyId:String):String? =
  familyId.takeIf{idPattern.matches(it)}?.let { ROOT+it+".webp" }

 fun decode(context:Context, familyId:String, targetWidth:Int):Bitmap? {
  val asset=assetName(familyId)?:return null
  return runCatching {
   val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
   context.assets.open(asset).use { BitmapFactory.decodeStream(it,null,bounds) }
   check(bounds.outWidth>0 && bounds.outHeight>0) { "Invalid font preview image: $asset" }
   var sample=1
   val width=targetWidth.coerceAtLeast(240)
   while(bounds.outWidth/(sample*2)>=width) sample*=2
   val options=BitmapFactory.Options().apply {
    inSampleSize=sample
    inPreferredConfig=Bitmap.Config.RGB_565 // white/black images: lower scroll memory
   }
   context.assets.open(asset).use { BitmapFactory.decodeStream(it,null,options) }
    ?:error("Could not decode font preview: $asset")
  }.onFailure { Log.w("ckReaderFontPreview","Preview unavailable for $familyId",it) }.getOrNull()
 }
}
