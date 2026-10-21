package com.ckchoi.ckreader

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Builds a complete OPDS metadata-only local index after connecting a server.
 * One small bounded batch per WorkManager invocation keeps the job resumable;
 * book files are NEVER downloaded as part of catalog indexing.
 */
class BookOasisCatalogWorker(context:Context,params:WorkerParameters):Worker(context,params) {
 override fun doWork():Result {
  val sourceId=inputData.getString(SOURCE)?:return Result.success()
  val repository=LibraryRepository(applicationContext)
  val source=repository.sources().firstOrNull{it.id==sourceId&&it.type=="bookoasis"}
   ?:return Result.success()
  val categoryIndex=inputData.getInt(CATEGORY,0)
  val seriesIndex=inputData.getInt(SERIES,0)
  if(categoryIndex<0||seriesIndex<0)return Result.failure()
  try {
   if(isStopped)return Result.success()
   val root=repository.cached(source,"")?:repository.browseBookOasisComplete(
    source,"","",onProgress={},cancelled={isStopped})
   val categories=root.folders.filter{folder->
    val route=runCatching{BookOasisPaths.parse(folder)}.getOrNull()
    route?.level==1&&route.libraryId?.toLongOrNull()!=null
   }
   var consumed=0
   var category=categoryIndex
   var series=seriesIndex
   val deadline=System.currentTimeMillis()+70_000L
   while(category<categories.size && !isStopped) {
    val folder=categories[category]
    val previous=repository.cached(source,folder)
    val catalog=previous?.takeIf{!it.hasMore}
     ?:repository.browseBookOasisComplete(source,folder,"",onProgress={},cancelled={isStopped})
    while(series<catalog.folders.size && !isStopped) {
     val nested=folder+"/"+catalog.folders[series]
     if(repository.cached(source,nested)==null)
      repository.browseBookOasisComplete(source,nested,"",onProgress={},cancelled={isStopped})
     series++
     consumed++
     if(consumed>=16 || System.currentTimeMillis()>=deadline) {
      enqueueNext(sourceId,category,series)
      return Result.success()
     }
    }
    if(isStopped)return Result.success()
    category++;series=0
   }
   return Result.success()
  }catch(_:java.util.concurrent.CancellationException) {
   return Result.success()
  }catch(error:Exception) {
   android.util.Log.w("ckReader","BookOasis OPDS indexing paused",error)
   return Result.retry()
  }
 }
 private fun enqueueNext(id:String,category:Int,series:Int) {
  WorkManager.getInstance(applicationContext).enqueueUniqueWork(name(id),
   ExistingWorkPolicy.APPEND_OR_REPLACE,request(id,category,series))
 }
 companion object {
  private const val SOURCE="source"
  private const val CATEGORY="category"
  private const val SERIES="series"
  private fun name(id:String)="ckreader-opds-metadata-$id"
  private fun request(id:String,category:Int,series:Int):OneTimeWorkRequest =
   OneTimeWorkRequest.Builder(BookOasisCatalogWorker::class.java)
    .setInputData(Data.Builder().putString(SOURCE,id).putInt(CATEGORY,category)
     .putInt(SERIES,series).build())
    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,45,TimeUnit.SECONDS)
    .build()
  fun enqueue(context:Context,source:LibrarySource) {
   if(source.type!="bookoasis")return
   WorkManager.getInstance(context).enqueueUniqueWork(name(source.id),
    ExistingWorkPolicy.KEEP,request(source.id,0,0))
  }
  fun cancel(context:Context,sourceId:String) {
   WorkManager.getInstance(context).cancelUniqueWork(name(sourceId))
  }
 }
}
