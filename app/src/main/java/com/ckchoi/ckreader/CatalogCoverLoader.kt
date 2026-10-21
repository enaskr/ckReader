package com.ckchoi.ckreader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.LruCache
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import java.lang.ref.WeakReference
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Lifecycle-aware RecyclerView thumbnail loader.
 *
 * Loads cache BLOBs on a dedicated fast executor; slow book extraction/network
 * never blocks cached covers. Only ATTACHED views trigger requests, and newest
 * visible requests are first in bounded queues. Dropped queued jobs are
 * removed from the in-flight map and can be re-requested on attach/scroll idle.
 *
 * Uses a stable source/book or source/folder key, NOT mutable cover URLs.
 * Bitmaps in the LRU are never recycled while an ImageView may display them.
 */
internal class CatalogCoverLoader(
 private val ui:Handler=Handler(Looper.getMainLooper()),
 private val time:()->Long={SystemClock.uptimeMillis()}
) {
 private class Binding(
  val key:String,
  val cached:()->ByteArray?,
  val resolve:()->ByteArray?
 )
 private class Job(
  val key:String,
  val phase:Int,
  val binding:Binding,
  val onResult:(Job,Bitmap?)->Unit
 ):Runnable {
  val waiting=ArrayList<WeakReference<ImageView>>()
  override fun run() {
   val bitmap=runCatching {
    val bytes=if(phase==0)binding.cached() else binding.resolve()
    if(bytes==null||bytes.isEmpty())null
    else BitmapFactory.decodeByteArray(bytes,0,bytes.size,
     BitmapFactory.Options().apply{inPreferredConfig=Bitmap.Config.RGB_565})
   }.getOrNull()
   onResult(this,bitmap)
  }
 }
 private fun pool(threads:Int,capacity:Int):ThreadPoolExecutor {
  val queue=LinkedBlockingDeque<Runnable>(capacity)
  return ThreadPoolExecutor(threads,threads,0,TimeUnit.MILLISECONDS,queue).apply{
   prestartAllCoreThreads()
  }
 }
 // UI thread owns the in-flight maps and LRU.
 private val cachedImages=object:LruCache<String,Bitmap>(9*1024*1024){
  override fun sizeOf(key:String,value:Bitmap)=value.byteCount.coerceAtLeast(1)
 }
 private val recentMiss=HashMap<String,Long>()
 private val active=HashMap<String,Job>()
 private val cachePool=pool(3,128)
 private val remotePool=pool(2,64)
 private var closed=false

 fun bind(target:ImageView,key:String,cached:()->ByteArray?,resolve:()->ByteArray?) {
  val binding=Binding(key,cached,resolve)
  target.tag=binding
  // Populate a freshly bound row immediately from memory, before attach.
  // This also avoids a one-frame placeholder flash while scrolling back.
  getBitmap(key)?.let{bitmap->
   target.imageTintList=null
   target.setImageBitmap(bitmap)
   target.contentDescription="도서 표지"
  }
  // Always register attach callbacks BEFORE returning; a new ViewHolder
  // can be bound while detached and must still check durable SQLite on attach.
  target.addOnAttachStateChangeListener(object:View.OnAttachStateChangeListener {
   override fun onViewAttachedToWindow(view:View) {request(target)}
   override fun onViewDetachedFromWindow(view:View) {
    // Keep in-flight work: it may still populate the durable SQLite cache.
    // Detached ImageViews are never updated by callbacks.
   }
  })
  if(target.isAttachedToWindow)request(target)
 }

 fun invalidate(key:String) {
  cachedImages.remove(key)
  recentMiss.remove(key)
  // Never allow an old in-flight bitmap to replace an explicit refreshed cover.
  active.remove(key)
 }

 fun forgetMisses() {recentMiss.clear()}
 fun clearMemory() {
  // Explicit cache reset/rebuild may replace thumbnails for the same identity.
  cachedImages.evictAll()
  recentMiss.clear()
  active.clear()
 }

 fun retryVisible(root:ViewGroup) {
  for(index in 0 until root.childCount) {
   val child=root.getChildAt(index)
   when(child) {
    is ImageView -> if(child.isAttachedToWindow && child.tag is Binding)request(child)
    is ViewGroup -> retryVisible(child)
   }
  }
 }

 private fun getBitmap(key:String):Bitmap?=cachedImages.get(key)
 private fun apply(target:ImageView,binding:Binding,bitmap:Bitmap) {
  if(target.tag!==binding||!target.isAttachedToWindow)return
  target.imageTintList=null
  target.setImageBitmap(bitmap)
  target.contentDescription="도서 표지"
 }

 private fun request(target:ImageView) {
  if(closed||!target.isAttachedToWindow)return
  val binding=target.tag as? Binding?:return
  getBitmap(binding.key)?.let{apply(target,binding,it);return}
  // Always check SQLite again: another screen or WorkManager may have
  // stored a cover since the most recent failed remote request.
  active[binding.key]?.let{job->
   if(job.waiting.none{it.get()===target})job.waiting.add(WeakReference(target))
   return
  }
  schedule(binding,0,listOf(target))
 }

 private fun schedule(binding:Binding,phase:Int,targets:List<ImageView>) {
  if(closed||targets.isEmpty())return
  val job=Job(binding.key,phase,binding){done,bitmap->
   ui.post{complete(done,bitmap)}
  }
  targets.forEach{job.waiting.add(WeakReference(it))}
  active[binding.key]=job
  val executor=if(phase==0)cachePool else remotePool
  val queue=executor.queue as LinkedBlockingDeque<Runnable>
  // Worker threads consume from the head; prioritize newly attached rows.
  if(!queue.offerFirst(job)) {
   val removed=queue.pollLast() as? Job
   if(removed!=null && active[removed.key]===removed)active.remove(removed.key)
   if(!queue.offerFirst(job))active.remove(binding.key,job)
  }
 }

 private fun complete(done:Job,bitmap:Bitmap?) {
  if(closed||active[done.key]!==done) {
   bitmap?.recycle()
   return
  }
  active.remove(done.key)
  val targets=done.waiting.mapNotNull{it.get()}
   .filter{it.isAttachedToWindow && (it.tag as? Binding)?.key==done.key}
  if(bitmap!=null) {
   recentMiss.remove(done.key)
   cachedImages.put(done.key,bitmap)
   targets.forEach{target->
    val binding=target.tag as? Binding
    if(binding!=null)apply(target,binding,bitmap)
   }
  } else if(done.phase==0) {
   // Remote failures are throttled, but NEVER suppress a fresh SQLite hit.
   val recentRemoteFailure=time()-(recentMiss[done.key]?:Long.MIN_VALUE/2)<30_000L
   if(targets.isNotEmpty()&&!recentRemoteFailure)schedule(done.binding,1,targets)
  } else {
   recentMiss[done.key]=time()
  }
 }

 fun shutdown() {
  closed=true
  active.clear()
  cachePool.shutdownNow()
  remotePool.shutdownNow()
  recentMiss.clear()
  cachedImages.evictAll()
 }
}
