package com.ckchoi.ckreader

import java.util.Base64

/** Reversible routes for BookOasis category, series and representative book. */
internal object BookOasisPaths {
 data class Route(val libraryId:String?,val seriesName:String?,val level:Int,
                  val representativeId:String?=null)
 private fun code(s:String):String=Base64.getUrlEncoder().withoutPadding()
  .encodeToString(s.toByteArray(Charsets.UTF_8))
 private fun decode(s:String):String=String(Base64.getUrlDecoder().decode(s),Charsets.UTF_8)

 fun library(id:String,name:String):String="l~"+code(id)+"~"+code(name)
 // The representative ID distinguishes identically named series in different folders.
 // A missing ID retains the old three-segment format for saved navigation paths.
 fun series(libraryId:String,name:String,representativeId:String=""):String=
  "s~"+code(libraryId)+"~"+code(name)+
   (if(representativeId.isNotBlank())"~"+code(representativeId) else "")

 fun display(segment:String):String=runCatching{
  val parts=segment.split('~')
  if((parts[0]=="l"&&parts.size==3)||(parts[0]=="s"&&parts.size in 3..4)) {
   val name=decode(parts[2])
   if(parts[0]=="s"&&name=="__empty_series__")"기타" else name
  }else segment
 }.getOrDefault(segment)

 fun parse(path:String):Route {
  if(path.isEmpty())return Route(null,null,0)
  val parts=path.split('/')
  require(parts.size in 1..2 && parts.none{it=="."||it==".."||it.length>3000})
  data class Segment(val libraryId:String,val displayName:String,val representativeId:String?)
  fun entry(raw:String,kind:String):Segment {
   val fields=raw.split('~')
   val valid=if(kind=="l")fields.size==3 else fields.size in 3..4
   require(valid && fields[0]==kind){"북오아시스 목록 경로가 올바르지 않습니다."}
   val rep=fields.getOrNull(3)?.let(::decode)?.takeIf{it.isNotBlank()}
   require(rep==null || (rep.length<=30 && rep.all{it.isDigit()})) {
    "북오아시스 대표 도서 ID가 올바르지 않습니다."
   }
   return Segment(decode(fields[1]),decode(fields[2]),rep)
  }
  if(parts.size==1) {
   return if(parts[0].startsWith("l~")) {
    val cat=entry(parts[0],"l")
    Route(cat.libraryId,null,1)
   } else {
    val item=entry(parts[0],"s")
    Route(item.libraryId,item.displayName,2,item.representativeId)
   }
  }
  val cat=entry(parts[0],"l")
  val item=entry(parts[1],"s")
  require(cat.libraryId=="home"||cat.libraryId==item.libraryId){"잘못된 카테고리 경로"}
  return Route(if(cat.libraryId=="home")item.libraryId else cat.libraryId,
   item.displayName,2,item.representativeId)
 }
}
