package com.ckchoi.yesss24

/** Offsets use UTF-16 throughout Android layouts, JavaScript and speech text. */
internal object SpeechPosition {
 const val HOLD_MILLIS=500L
 fun sentenceStart(text:String,offset:Int):Int {
  var start=offset.coerceIn(0,text.length)
  while(start>0) {
   val previous=text[start-1]
   val decimal=previous=='.'&&start>1&&start<text.length&&text[start-2].isDigit()&&text[start].isDigit()
   if(previous=='\n'||previous in ".!?。！？"&&!decimal)break
   start--
  }
  while(start<text.length&&(text[start].isWhitespace()||text[start] in "\"'”’」』"))start++
  return start
 }
 fun held(duration:Long,distance:Float,slop:Float,pointers:Int=1)=duration>=HOLD_MILLIS&&distance<=slop&&pointers==1
}
