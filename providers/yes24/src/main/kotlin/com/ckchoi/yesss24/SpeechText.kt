package com.ckchoi.yesss24

/** Keep source UTF-16 offsets even when the spoken text omits or collapses characters. */
internal object SpeechText {
 data class Part(val text:String,val start:Int,val end:Int,val english:Boolean)
 fun prepare(source:String,offset:Int,readParentheses:Boolean,skipHanja:Boolean,autoLanguage:Boolean):List<Part> {
  val start=offset.coerceIn(0,source.length)
  val omitted=buildList {
   if(!readParentheses)add("\\([^)]*\\)|（[^）]*）")
   if(skipHanja)add("[\\u4e00-\\u9fff]+")
  }.takeIf{it.isNotEmpty()}?.joinToString("|")?.let{Regex(it).findAll(source,start).iterator()}
  var skip=omitted?.let{if(it.hasNext())it.next().range else null}
  val text=StringBuilder();val positions=IntArray(source.length-start)
  var index=start
  while(index<source.length) {
   val excluded=skip
   if(excluded!=null&&index>=excluded.first) {
    index=excluded.last+1;skip=omitted?.let{if(it.hasNext())it.next().range else null};continue
   }
   val char=source[index]
   if(char!=' '&&char!='\t'||text.isEmpty()||text.last()!=' ') {
    positions[text.length]=index;text.append(if(char=='\t')' ' else char)
   }
   index++
  }
  val result=ArrayList<Part>()
  fun clause(from:Int,to:Int) {
   var left=from;var right=to
   while(left<right&&text[left].isWhitespace())left++
   while(right>left&&text[right-1].isWhitespace())right--
   if(left==right)return
   val sentence=text.substring(left,right)
   val english=autoLanguage&&sentence.count{it in 'A'..'Z'||it in 'a'..'z'}>=8&&
    sentence.count{it in '가'..'힣'}*2<sentence.length
   while(left<right) {
    var end=minOf(left+350,right)
    if(end<right) {
     val boundary=(end downTo (left+1)).firstOrNull{text[it-1].isWhitespace()}
     if(boundary!=null&&boundary>left+175)end=boundary
     else {
      val next=(end until minOf(end+80,right)).firstOrNull{text[it].isWhitespace()}
      if(next!=null)end=next+1
     }
    }
    if(text.substring(left,end).isNotBlank())result.add(Part(text.substring(left,end),positions[left],positions[end-1]+1,english))
    left=end
   }
  }
  var from=0
  for(separator in Regex("(?<=[.!?。！？])\\s+|\\n+").findAll(text)) {
   clause(from,separator.range.first);from=separator.range.last+1
  }
  clause(from,text.length)
  return result
 }
}
