package com.ckchoi.ckreader

internal enum class MetadataSearchStage { ALADIN, NATIONAL_LIBRARY, DATA4LIBRARY, WEB }

/** Keep provider precedence while filling missing fields for the same book. */
internal object MetadataSearchPipeline {
 private fun sources(first:String,next:String):String=(first.split(" · ")+next.split(" · "))
  .filter{it.isNotBlank()}.distinct().joinToString(" · ")
 private fun compatible(a:WebBookMetadata,b:WebBookMetadata):Boolean {
  val left=a.isbn.filter(Char::isDigit)
  val right=b.isbn.filter(Char::isDigit)
  if(left.length==13&&right.length==13)return left==right
  if(MetadataTitleMatcher.normalized(a.title)!=MetadataTitleMatcher.normalized(b.title))return false
  val authorA=MetadataTitleMatcher.normalized(a.author)
  val authorB=MetadataTitleMatcher.normalized(b.author)
  return authorA.isBlank()||authorB.isBlank()||authorA.contains(authorB)||authorB.contains(authorA)
 }
 fun fillMissing(first:WebBookMetadata,next:WebBookMetadata):WebBookMetadata {
  if(!compatible(first,next))return first
  val merged=first.copy(author=first.author.ifBlank{next.author},
   publisher=first.publisher.ifBlank{next.publisher},description=first.description.ifBlank{next.description},
   genre=first.genre.ifBlank{next.genre},coverUrl=first.coverUrl.ifBlank{next.coverUrl},
   sourceUrl=first.sourceUrl.ifBlank{next.sourceUrl},isbn=first.isbn.ifBlank{next.isbn},
   seriesName=first.seriesName.ifBlank{next.seriesName},
   seriesTotal=first.seriesTotal.takeIf{it>0}?:next.seriesTotal,
   seriesIndex=first.seriesIndex.takeIf{it>0}?:next.seriesIndex,
   translator=first.translator.ifBlank{next.translator},
   publishedDate=first.publishedDate.ifBlank{next.publishedDate},price=first.price.ifBlank{next.price})
  return if(merged==first)first else merged.copy(source=sources(first.source,next.source))
 }
 fun candidates(query:String,author:String,isbn:String,exhaustive:Boolean,
  stages:List<MetadataSearchStage> = MetadataSearchStage.entries,
  fetch:(MetadataSearchStage,String)->List<WebBookMetadata>):List<WebBookMetadata> {
  val results=ArrayList<WebBookMetadata>()
  for(stage in stages) {
   if(Thread.currentThread().isInterrupted)throw InterruptedException("도서정보 검색 취소")
   val items=MetadataTitleMatcher.rank(query,author,isbn,fetch(stage,isbn))
   results.addAll(items)
   if(!exhaustive&&items.any{MetadataTitleMatcher.score(query,author,isbn,it)>=72&&
      it.coverUrl.isNotBlank()&&it.description.isNotBlank()})break
  }
  return results.take(if(exhaustive)100 else 40)
 }
 fun resolve(query:String,author:String,isbn:String,
  fetch:(MetadataSearchStage,String)->List<WebBookMetadata>,
  acceptCover:(WebBookMetadata)->Boolean,
  stages:List<MetadataSearchStage> = MetadataSearchStage.entries):WebBookMetadata? {
  var preferred:WebBookMetadata?=null
  var coverAccepted=false
  val attempted=HashSet<String>()
  for(stage in stages) {
   if(Thread.currentThread().isInterrupted)throw InterruptedException("도서정보 검색 취소")
   val hint=isbn.ifBlank{preferred?.isbn.orEmpty()}
   val matches=MetadataTitleMatcher.rank(query,author,hint,fetch(stage,hint))
    .filter{MetadataTitleMatcher.score(query,author,hint,it)>=72}.take(12)
   for(item in matches) {
    val current=preferred
    if(current!=null&&!compatible(current,item))continue
    var merged=if(current==null)item.copy(coverUrl="") else fillMissing(current,item.copy(coverUrl=""))
    if(!coverAccepted&&item.coverUrl.isNotBlank()&&attempted.add(item.coverUrl)) {
     val proposal=merged.copy(coverUrl=item.coverUrl,source=sources(merged.source,item.source))
     if(acceptCover(proposal)){merged=proposal;coverAccepted=true}
    }
    preferred=merged
    if(coverAccepted&&merged.description.isNotBlank())return merged
   }
  }
  return preferred
 }
}
