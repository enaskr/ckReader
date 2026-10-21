package com.ckchoi.ckreader

import java.util.concurrent.CancellationException

/**
 * Collects every page of a BookOasis series listing without silently dropping
 * pages. Network fetching and UI callbacks are injected for JVM testing.
 */
internal object BookOasisPagination {
 data class Slice<T>(val items:List<T>,val hasMore:Boolean)

 fun <T,K> collect(
  firstPage:Int=1,
  existing:Collection<T> = emptyList(),
  key:(T)->K,
  fetch:(Int)->Slice<T>,
  onPage:(Int,List<T>,Boolean)->Unit,
  cancelled:()->Boolean={false},
  maxPage:Int=10_000
 ):List<T> {
  require(firstPage in 1..maxPage) { "시작 페이지가 올바르지 않습니다." }
  val collected=LinkedHashMap<K,T>()
  existing.forEach { collected.putIfAbsent(key(it),it) }
  var page=firstPage
  while(true) {
   if(cancelled())throw CancellationException("북오아시스 목록 탐색이 취소됐습니다.")
   val next=fetch(page)
   if(cancelled())throw CancellationException("북오아시스 목록 탐색이 취소됐습니다.")
   var added=0
   next.items.forEach { item ->
    val id=key(item)
    if(!collected.containsKey(id)) {
     collected[id]=item
     added++
    }
   }
   // Never declare success when a buggy endpoint loops over an old page.
   if(next.hasMore && added==0)
    error("북오아시스가 중복 또는 빈 페이지를 반환했습니다 (페이지 $page).")
   val snapshot=collected.values.toList()
   onPage(page,snapshot,next.hasMore)
   if(!next.hasMore)return snapshot
   if(page>=maxPage)error("북오아시스의 페이지 제한에 도달했습니다 ($maxPage).")
   page++
  }
 }
}
