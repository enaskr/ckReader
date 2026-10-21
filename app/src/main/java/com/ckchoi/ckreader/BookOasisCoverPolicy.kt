package com.ckchoi.ckreader

/** A series thumbnail belongs to its declared representative, not the first sorted volume. */
internal object BookOasisCoverPolicy {
 fun representative(path:String,books:List<LibraryBook>):LibraryBook? {
  val route=runCatching{BookOasisPaths.parse(path)}.getOrNull()?:return null
  if(route.level!=2)return null
  val id=route.representativeId
  return if(id.isNullOrBlank())books.firstOrNull() else books.firstOrNull{it.bookId==id}
 }
}
