package com.ckchoi.ckreader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

/** An explicit, audited 50 by 50 pixel upper bound for every SQLite BLOB. */
internal object SmallCoverJpeg {
 const val MAX_EDGE=50
 fun encode(bytes:ByteArray):ByteArray? {
  if(bytes.size !in 24..5_000_000)return null
  val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
  BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
  if(bounds.outWidth !in 1..20_000||bounds.outHeight !in 1..20_000)return null
  var factor=1
  while(bounds.outWidth/factor>100||bounds.outHeight/factor>100)factor*=2
  val decoded=BitmapFactory.decodeByteArray(bytes,0,bytes.size,
   BitmapFactory.Options().apply{inSampleSize=factor})?:return null
  return try {
   val scale=minOf(MAX_EDGE.toFloat()/decoded.width,
    MAX_EDGE.toFloat()/decoded.height,1f)
   val w=maxOf(1,(decoded.width*scale).toInt()).coerceAtMost(MAX_EDGE)
   val h=maxOf(1,(decoded.height*scale).toInt()).coerceAtMost(MAX_EDGE)
   val scaled=if(w==decoded.width&&h==decoded.height)decoded
    else Bitmap.createScaledBitmap(decoded,w,h,true)
   try {
    ByteArrayOutputStream().use{out->
     if(!scaled.compress(Bitmap.CompressFormat.JPEG,82,out))null
     else out.toByteArray().takeIf{it.size in 24..500_000}
    }
   }finally{if(scaled!==decoded)scaled.recycle()}
  }finally{decoded.recycle()}
 }
}