package com.ckchoi.ckreader

import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Resumable, low-concurrency metadata lookups. No credentials or document
 * contents are placed in WorkManager data; only the encrypted source id/path.
 */
class BookMetadataWorker(context:Context,parameters:WorkerParameters):Worker(context,parameters) {
 override fun doWork():Result {
  val id=inputData.getString("source")?:return Result.success()
  val path=inputData.getString("path")?:return Result.success()
  val search=inputData.getString("search").orEmpty()
  val repository=LibraryRepository(applicationContext)
  val cache=BookMetadataStore(applicationContext)
  val source=repository.sources().firstOrNull{it.id==id}?:return Result.success()
  val listing=repository.cached(source,path,search)?:return Result.success()
  val eligible=listing.books.filter{needsYaml(it)||cache.eligible(it)}
  if(eligible.isEmpty()) {
   syncFolders(repository,listing,id,path,search)
   return Result.success()
  }
  val engine by lazy {BookMetadataSearch(applicationContext)}
  val siblingCache=HashMap<String,List<FolderYamlStorage.Entry>>()
  val yamlCache=HashMap<String,FolderYamlMetadata.CachedDocument>()
  var processed=0
  var retryYaml=false
  for(book in eligible.take(BATCH)) {
   if(isStopped)return Result.success()
   // A cover may have arrived from a concurrent thumbnail task since enqueue.
   if(!needsYaml(book)&&!cache.eligible(book))continue
   var yamlUpdated=false
   try {
    if(FolderYamlMetadata.supports(book.source.type)) {
     repository.refreshFolderYaml(book,siblingCache,yamlCache)
     yamlUpdated=true
    }
    if(cache.eligible(book))lookupBook(applicationContext,cache,engine,book,automatic=true)
   }catch(e:InterruptedException) {
    Thread.currentThread().interrupt()
    return Result.success()
   }catch(e:Exception) {
    if(needsYaml(book))retryYaml=true
    android.util.Log.w("ckReader","Metadata search failed for a title",e)
    // Do not modify an existing covered title after a failing auto request.
    if(cache.eligible(book))
     runCatching{cache.recordResult(book,null,temporaryFailure=true)}
   }
   // Also refresh cards with no matching result, clearing the pending label.
   // A matching book's cover is prefetched before this notification.
   if(!isStopped)applicationContext.sendBroadcast(Intent(UPDATED)
    .setPackage(applicationContext.packageName)
    .putExtra("source",id).putExtra("path",path).putExtra("search",search)
    .putExtra("yamlUpdated",yamlUpdated))
   processed++
   if(processed<eligible.size){
    try {Thread.sleep(350)}catch(_:InterruptedException){return Result.success()}
   }
  }
  if(retryYaml)return Result.retry()
  if(!isStopped&&eligible.size>BATCH) {
   val next=request(id,path,search,delaySeconds=3)
   // APPEND_OR_REPLACE queues the next bounded batch after this one finishes.
   WorkManager.getInstance(applicationContext)
    .enqueueUniqueWork(workName(id,path,search),ExistingWorkPolicy.APPEND_OR_REPLACE,next)
  }else if(!isStopped) {
   syncFolders(repository,listing,id,path,search)
  }
  return Result.success()
 }
 private fun syncFolders(repository:LibraryRepository,listing:LibraryListing,id:String,path:String,search:String) {
  val cache=BookMetadataStore(applicationContext)
  if(listing.books.none{FolderYamlSync.needs(it,cache)})return
  if(repository.syncFolderMetadata(listing.books))applicationContext.sendBroadcast(Intent(UPDATED)
   .setPackage(applicationContext.packageName).putExtra("source",id).putExtra("path",path)
   .putExtra("search",search).putExtra("yamlUpdated",true))
 }
 companion object {
  const val UPDATED="com.ckchoi.ckreader.METADATA_UPDATED"
  internal fun needsYaml(book:LibraryBook)=FolderYamlMetadata.needsRefresh(book.source.type,book.yamlRevision)

  /** One title at a time, covering metadata and cover cache before notifying UI. */
  private fun lookupBook(context:Context,cache:BookMetadataStore,engine:BookMetadataSearch,
                         book:LibraryBook,automatic:Boolean=false):Boolean {
   if(automatic&&!cache.eligible(book))return false
   val query=MetadataTitleMatcher.lookupTitle(book.title,book.filename)
   if(MetadataTitleMatcher.normalized(query).length<2)return false
   val covers=BookCoverStore(context)
   var receivedCover:ByteArray?=null
   val match=engine.search(query,book.author,book.isbn.ifBlank{book.bookId},
    book.format in setOf("cbz","zip"),acceptCover={candidate->
     val bytes=runCatching{cache.remoteCover(candidate.coverUrl)}.getOrNull()
     if(bytes!=null&&SmallCoverJpeg.encode(bytes)!=null) {
      receivedCover=bytes
      true
     }else false
    })
   // Do not overwrite cached metadata if a manual refresh, embedded cover
   // extraction, or a parallel queue populated a thumbnail mid-request.
   if(automatic&&!cache.eligible(book))return false
   cache.recordResult(book,match,temporaryFailure=match==null&&!engine.sawSuccess)
   if(match!=null) {
    cache.enrich(book)
    if(!covers.has(book))receivedCover?.let{covers.put(book,it)}
   }
   return match!=null
  }

  /** Explicit refresh works even when automatic lookups are disabled. */
  fun refreshOne(context:Context,book:LibraryBook):Boolean {
   if(FolderYamlMetadata.supports(book.source.type))runCatching{LibraryRepository(context).refreshFolderYaml(book)}
   val cache=BookMetadataStore(context)
   return lookupBook(context,cache,BookMetadataSearch(context),book).also{if(it)LibraryRepository(context).requestYamlWriteback(book)}
  }
  private const val BATCH=12
  private fun workName(source:String,path:String,search:String):String {
   val bytes=MessageDigest.getInstance("SHA-256")
    .digest((source+"|"+path+"|"+search).toByteArray())
   return "ckreader-meta-"+bytes.take(12).joinToString(""){"%02x".format(it)}
  }
  private fun request(source:String,path:String,search:String,delaySeconds:Long=0):OneTimeWorkRequest {
   val builder=OneTimeWorkRequest.Builder(BookMetadataWorker::class.java)
    .setInputData(Data.Builder().putString("source",source)
     .putString("path",path).putString("search",search).build())
    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
    .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS)
   if(delaySeconds>0)builder.setInitialDelay(delaySeconds,TimeUnit.SECONDS)
   return builder.build()
  }
  fun enqueue(context:Context,source:LibrarySource,path:String,search:String,
              listing:LibraryListing) {
   // OPDS server metadata is authoritative. Its own SQL index and cover cache
   // replace automatic third-party book searches for this storage type.
   if(source.type=="bookoasis")return
   val cache=BookMetadataStore(context)
   if(listing.books.none{needsYaml(it)||cache.eligible(it)||FolderYamlSync.needs(it,cache)})return
   if(path.length>3000||search.length>220)return
   WorkManager.getInstance(context).enqueueUniqueWork(workName(source.id,path,search),
    ExistingWorkPolicy.KEEP,request(source.id,path,search))
  }
 }
}
