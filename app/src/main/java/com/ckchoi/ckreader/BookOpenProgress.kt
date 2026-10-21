package com.ckchoi.ckreader

/** Percent refers to a full-file transfer; ZIP index reads have no whole-file percent. */
internal object BookOpenProgress {
 fun mib(bytes:Long):String {
  if(bytes<0)return "알 수 없음"
  val tenths=bytes.coerceAtMost(20L*1024*1024*1024)*10/(1024L*1024L)
  return (tenths/10).toString()+"."+(tenths%10)+" MB"
 }
 fun label(stage:String,transferred:Long=-1,total:Long=-1):String {
  if(transferred<0)return stage
  val done=mib(transferred)
  if(total<=0)return stage+" · "+done+" 다운로드"
  val percent=(transferred.coerceIn(0,total)*100/total).toInt()
  return stage+" · "+percent+"% ("+done+" / "+mib(total)+")"
 }
}
