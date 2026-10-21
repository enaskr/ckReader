package com.ckchoi.ckreader

import android.content.Context
import java.io.File
import java.security.MessageDigest
import android.util.LruCache

/**
 * Durable first-level-partitioned SQLite BLOB cache. Android cacheDir JPEGs
 * from old releases are migrated when encountered, never used as the only
 * persistent store. The database owns a <=50x50px JPEG per book.
 */
internal class BookCoverStore(context:Context) {
 private val persistent=PersistentLibraryCache(context)
 private val oldRoot=File(context.cacheDir,"book-cover-thumbnails-v1")
 private val memory=object:LruCache<String,ByteArray>(6*1024*1024){
  override fun sizeOf(key:String,value:ByteArray)=value.size
 }
 private fun memoKey(book:LibraryBook)=book.source.id+"|"+book.id+"|"+book.path
 private fun sha(value:String)=MessageDigest.getInstance("SHA-256")
  .digest(value.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
 private fun oldFile(book:LibraryBook):File {
  val s=book.source
  val identity=sha(s.id+"|"+s.type+"|"+s.location+"|"+s.user+"|"+
   sha(s.secret)+"|"+sha(s.extra)+"|"+book.id+"|"+book.path+"|"+book.bookId)
  return File(File(oldRoot,sha(s.id).take(24)),identity+".jpg")
 }
 fun has(book:LibraryBook):Boolean =
  synchronized(memory){memory.get(memoKey(book))!=null}||
   persistent.hasCover(book)||oldFile(book).isFile
 fun isMissing(book:LibraryBook)=persistent.isMissing(book)
 fun read(book:LibraryBook):ByteArray? {
  val key=memoKey(book)
  synchronized(memory){memory.get(key)?.let{return it}}
  persistent.readCover(book)?.let{
   synchronized(memory){memory.put(key,it)}
   return it
  }
  val legacy=oldFile(book)
  if(legacy.isFile&&legacy.length() in 24L..750_000L) {
   val bytes=runCatching{legacy.readBytes()}.getOrNull()?:return null
   val compressed=SmallCoverJpeg.encode(bytes)?:return null
   persistent.putCover(book,compressed)
   synchronized(memory){memory.put(key,compressed)}
   return compressed
  }
  return null
 }
 fun put(book:LibraryBook,raw:ByteArray):Boolean {
  val small=SmallCoverJpeg.encode(raw)?:return false
  persistent.putCover(book,small)
  synchronized(memory){memory.put(memoKey(book),small)}
  return true
 }
 fun markMissing(book:LibraryBook) {
  synchronized(memory){memory.remove(memoKey(book))}
  persistent.putCover(book,null)
 }
 fun forgetMemory(){synchronized(memory){memory.evictAll()}}
 fun clearForSource(id:String,strict:Boolean=false) {
  // Public SQLite data is cleared only by explicit cache deletion or source removal.
  val directory=File(oldRoot,sha(id).take(24))
  if(directory.exists()&&!directory.deleteRecursively()&&strict)
   error("표지 캐시를 삭제하지 못했습니다.")
  synchronized(memory){memory.evictAll()}
 }
}

internal object BookCoverPolicy {
 fun supported(format:String):Boolean =
  format.lowercase() in setOf("epub","pdf","zip","cbz")
}
