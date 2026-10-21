package com.ckchoi.ckreader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Auth-scoped, bounded cached thumbnails. Network folder inspection is
 * performed only by the caller's background executor, never on the UI thread.
 * Only downsampled JPEGs are persisted (not full book files or credentials).
 */
internal class FolderThumbnailStore(context:Context) {
 companion object {private val mutations=Array(64){Any()}}
 // v2 invalidates cached "missing" entries from releases without embedded covers.
 private val root=File(context.cacheDir,"folder-thumbnails-v2")
 private val memory=object:LruCache<String,ByteArray>(3*1024*1024) {
  override fun sizeOf(key:String,value:ByteArray)=value.size
 }
 private val locks=ConcurrentHashMap<String,Any>()
 private val writes=AtomicInteger()
 private val durable=PersistentLibraryCache(context)

 private fun hash(value:String):String=MessageDigest.getInstance("SHA-256")
  .digest(value.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
 private fun folder(source:LibrarySource):File=File(root,hash(source.id).take(24))
 private fun key(source:LibrarySource,path:String,coverHint:String):String =
  hash(source.id+"|"+source.type+"|"+source.location+"|"+source.user+"|"+
   hash(source.secret)+"|"+hash(source.extra)+"|"+path+"|"+coverHint)
 private fun mutationLock(source:LibrarySource,path:String)=mutations[(key(source,path,"").hashCode() and Int.MAX_VALUE)%mutations.size]
 private fun fresh(file:File,maxAge:Long):Boolean =
  file.isFile && file.length()<1_000_000L &&
   System.currentTimeMillis()-file.lastModified() in 0..maxAge

 private fun jpeg(bytes:ByteArray):ByteArray? {
  if(bytes.size !in 24..5_000_000)return null
  val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
  BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
  if(bounds.outWidth !in 1..20_000 || bounds.outHeight !in 1..20_000)return null
  var sample=1
  while(bounds.outWidth/sample>240 || bounds.outHeight/sample>320)sample*=2
  val options=BitmapFactory.Options().apply{inSampleSize=sample}
  val original=BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)?:return null
  return try {
   val scale=minOf(100f/original.width,140f/original.height,1f)
   val w=maxOf(1,(original.width*scale).toInt())
   val h=maxOf(1,(original.height*scale).toInt())
   val small=if(w==original.width&&h==original.height)original
    else Bitmap.createScaledBitmap(original,w,h,true)
   try {
    ByteArrayOutputStream().use { out ->
     if(!small.compress(Bitmap.CompressFormat.JPEG,82,out))null else out.toByteArray()
    }
   } finally {
    if(small!==original)small.recycle()
   }
  } finally {original.recycle()}
 }

 /** Cheap cache-only lookup for visible RecyclerView rows. */
 fun cached(source:LibrarySource,path:String,coverHint:String=""):ByteArray? {
  if(source.type=="bookoasis"||FolderYamlMetadata.supports(source.type))durable.getFolderCover(source,path,"")?.let{return it}
  durable.getFolderCover(source,path,coverHint)?.let{return it}
  if(coverHint.isNotBlank())durable.getFolderCover(source,path,"")?.let{return it}
  return synchronized(memory){memory.get(key(source,path,coverHint))}
 }
 fun retain(source:LibrarySource,path:String,coverHint:String,image:ByteArray):Boolean = synchronized(mutationLock(source,path)) {
  // The book's existing 50x50 DB cover is already normalized.
  if(durable.getFolderCover(source,path,coverHint)?.contentEquals(image)==true)return@synchronized false
  durable.putFolderCover(source,path,coverHint,image)
  synchronized(memory){memory.put(key(source,path,coverHint),image)}
  true
 }

 fun thumbnail(source:LibrarySource,path:String,coverHint:String="",
               load:()->ByteArray?):ByteArray? {
  // Durable SQLite is authoritative and never expires unless explicitly cleared.
  if(source.type=="bookoasis"||FolderYamlMetadata.supports(source.type))durable.getFolderCover(source,path,"")?.let{return it}
  durable.getFolderCover(source,path,coverHint)?.let{return it}
  if(coverHint.isNotBlank())durable.getFolderCover(source,path,"")?.let {
   durable.putFolderCover(source,path,coverHint,it)
   return it
  }
  val id=key(source,path,coverHint)
  synchronized(memory) {memory.get(id)?.let{return it}}
  val cacheDir=folder(source)
  val image=File(cacheDir,"$id.jpg")
  val empty=File(cacheDir,"$id.missing")
  fun read():ByteArray? {
   if(image.isFile && image.length() in 24L..1_000_000L) {
    val data=SmallCoverJpeg.encode(image.readBytes())
    if(data!=null) {
     durable.putFolderCover(source,path,coverHint,data)
     synchronized(memory){memory.put(id,data)}
     return data
    }
   }
   return null
  }
  read()?.let{return it}
  // An earlier miss must not hide a cover newly cached from a book detail.
  if(!FolderCoverPolicy.retryWhenNegativeCached(source.type) && fresh(empty,60L*60*1000))return null
  val mutex=locks.computeIfAbsent(id){Any()}
  try {
   return synchronized(mutex) {
    read()?.let{return@synchronized it}
    if(!FolderCoverPolicy.retryWhenNegativeCached(source.type) && fresh(empty,60L*60*1000))
     return@synchronized null
    // Exceptions (timeouts, auth failures) are not saved as permanent misses.
    val loaded=load()?.let(SmallCoverJpeg::encode)
    synchronized(mutationLock(source,path)) {
    // A YAML refresh may finish while this older network job is in flight.
    // Keep its current BLOB instead of replacing it with the job's old image.
    val bytes=if(source.type=="bookoasis"||FolderYamlMetadata.supports(source.type))durable.getFolderCover(source,path,"")?:loaded else loaded
    if(bytes!=null)durable.putFolderCover(source,path,coverHint,bytes)
    cacheDir.mkdirs()
    val target=if(bytes==null)empty else image
    val temp=File(cacheDir,"$id.part")
    try {
     temp.writeBytes(bytes?:byteArrayOf(0))
     if(!temp.renameTo(target)) {
      target.delete()
      if(!temp.renameTo(target))error("폴더 썸네일 캐시 저장 실패")
     }
     if(bytes==null)image.delete() else empty.delete()
    } finally {temp.delete()}
    if(bytes!=null)synchronized(memory){memory.put(id,bytes)}
    if(writes.incrementAndGet()%24==0)prune(cacheDir)
    bytes
    }
   }
  } finally {locks.remove(id,mutex)}
 }

 private fun prune(dir:File) {
  val files=dir.listFiles()?.filter{it.isFile}?.sortedBy{it.lastModified()}.orEmpty()
  var bytes=files.sumOf{it.length()}
  var count=files.size
  for(file in files) {
   if(bytes<=20L*1024*1024 && count<=1000)break
   val size=file.length()
   if(file.delete()){bytes-=size;count--}
  }
 }

 fun clearSource(sourceId:String) {
  synchronized(memory){memory.evictAll()}
  folder(LibrarySource(sourceId,"","","")).deleteRecursively()
  durable.clearFolderCovers(sourceId)
 }
}

/** Outside BookOasis, a thumbnail is permitted only for a leaf directory. */
internal object FolderCoverPolicy {
 /** Old missing markers cannot suppress newly available BookOasis artwork. */
 fun retryWhenNegativeCached(sourceType:String):Boolean=sourceType=="bookoasis"
 private val supported=setOf("drive","ftp","webdav")

 /** Explicit Kavita artwork can represent its folder even with child folders.
  * An unlisted/uncovered first file must not hide another authored cover. */
 fun yamlBooks(sourceType:String,books:List<LibraryBook>):List<LibraryBook> =
  if(FolderYamlMetadata.supports(sourceType))LibraryTitleOrder.books(books.filter{
   it.yamlPreferred&&LibraryVisibility.visible(it.path)&&
    FolderYamlStorage.hasManifest(listOf(it.metadataSource.substringAfterLast(" · ")))
  }) else emptyList()

 /** Resolve an actual, matching sibling image for a book before fetching it. */
 fun companionCover(name:String,available:Collection<String>):String? {
  val stem=name.substringBeforeLast('.',name)
  val candidates=listOf("$stem.jpg","$stem.jpeg","$stem.png","$stem.webp",
   "$stem.cover.jpg","$stem.cover.png","cover.jpg","cover.jpeg",
   "cover.png","cover.webp","folder.jpg","folder.png","front.jpg")
  return candidates.firstNotNullOfOrNull { expected ->
   available.firstOrNull { it.equals(expected,true) }
  }
 }
 fun firstBook(sourceType:String,listing:LibraryListing):LibraryBook? =
  if(sourceType in supported && listing.folders.isEmpty())
   LibraryTitleOrder.books(listing.books).firstOrNull()
  else null
}
