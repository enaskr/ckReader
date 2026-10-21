package com.ckchoi.ckreader

/**
 * Bounded in-memory index for the current and previously visited directories.
 * All entries are also persisted by LibraryRepository; an evicted entry can be
 * restored from the snapshot without requesting the server again.
 */
internal class LibraryListingMemoryCache(
 private val maxBytes:Long=32L*1024*1024,
 private val maxEntries:Int=24
) {
 private data class Stored(val listing:LibraryListing,val bytes:Long)
 private val values=LinkedHashMap<String,Stored>(16,0.75f,true)
 private var totalBytes=0L

 private fun estimate(listing:LibraryListing):Long {
  val folderBytes=listing.folders.sumOf{it.length.toLong()*2+72}
  val bookBytes=listing.books.sumOf{book->
   400L+2L*(book.path.length+book.filename.length+book.title.length+
    book.author.length+book.publisher.length+book.cover.length+
    book.summary.length+book.genre.length+book.isbn.length)
  }
  return 256L+folderBytes+bookBytes
 }

 @Synchronized fun get(key:String):LibraryListing?=values[key]?.listing

 @Synchronized fun put(key:String,listing:LibraryListing) {
  values.remove(key)?.let{totalBytes-=it.bytes}
  val bytes=estimate(listing)
  if(bytes>maxBytes)return
  values[key]=Stored(listing,bytes)
  totalBytes+=bytes
  while(values.size>maxEntries || totalBytes>maxBytes) {
   val oldest=values.entries.iterator()
   if(!oldest.hasNext())break
   totalBytes-=oldest.next().value.bytes
   oldest.remove()
  }
 }

 @Synchronized fun removeSource(sourceId:String) {
  val prefix="$sourceId:"
  val iterator=values.entries.iterator()
  while(iterator.hasNext()) {
   val entry=iterator.next()
   if(entry.key.startsWith(prefix)) {
    totalBytes-=entry.value.bytes
    iterator.remove()
   }
  }
 }
}

/** Folder navigation is cache-first; only manual refresh forces a server scan. */
internal object LibraryNavigationPolicy {
 fun needsFetch(cached:LibraryListing?):Boolean=cached==null
}
