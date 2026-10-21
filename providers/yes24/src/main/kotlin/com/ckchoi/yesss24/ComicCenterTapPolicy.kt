package com.ckchoi.yesss24

/** A short tap within the middle half of the visible comic viewport. */
internal object ComicCenterTapPolicy {
 fun isCenter(x:Float,y:Float,width:Int,height:Int):Boolean =
  width>0 && height>0 &&
   x>=width*0.25f && x<=width*0.75f &&
   y>=height*0.25f && y<=height*0.75f

 fun actionLabel(isFullscreen:Boolean):String =
  if(isFullscreen)"전체화면 종료" else "전체화면 보기"
}
