package com.ckchoi.ckreader

internal data class IconRegion(val left:Int,val top:Int,val right:Int,val bottom:Int)

/** Bounds for drawing an original attachment in an icon tile, preserving its pixels. */
internal object IconContentBounds {
 fun find(pixels:IntArray,width:Int,height:Int):IconRegion {
  var left=width;var top=height;var right=0;var bottom=0
  for(y in 0 until height)for(x in 0 until width) {
   val color=pixels[y*width+x]
   if((color ushr 24)<16)continue
   if(((color ushr 16)and 255)>=248&&((color ushr 8)and 255)>=248&&(color and 255)>=248)continue
   left=minOf(left,x);top=minOf(top,y);right=maxOf(right,x+1);bottom=maxOf(bottom,y+1)
  }
  if(left>=right||top>=bottom)return IconRegion(0,0,width,height)
  val padding=maxOf(1,maxOf(width,height)/60)
  return IconRegion((left-padding).coerceAtLeast(0),(top-padding).coerceAtLeast(0),
   (right+padding).coerceAtMost(width),(bottom+padding).coerceAtMost(height))
 }
}
