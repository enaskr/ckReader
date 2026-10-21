package com.ckchoi.ckreader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * User-initiated, serialized foreground cache rebuilds. New requests either
 * wait behind the current job or cancel/replace all existing work.
 * Cancellation is cooperative at folder/book boundaries so a DB write already
 * in progress can finish safely.
 */
class CacheRebuildService:Service() {
 data class Selection(val sourceId:String,val depth:String)
 enum class StartMode { WAIT, REPLACE }
 private class Job(val selections:List<Selection>,val forceRebuild:Boolean) {
  val cancelled=AtomicBoolean(false)
  val seen=HashSet<String>()
  var processed=0
  var success=0
  var failure=0
  var skipped=0
  var startedAt=0L
 }
 companion object {
  const val FINISHED="com.ckchoi.ckreader.CACHE_REBUILD_FINISHED"
  private const val ACTION_CANCEL="com.ckchoi.ckreader.action.CANCEL_CACHE_REBUILD"
  private const val CHANNEL="ckreader_cache_rebuild"
  private const val ID=4402
  private const val RESULT_ID=4403
  private const val EXTRA_TOKEN="token"
  private const val EXTRA_MODE="mode"
  private const val EXTRA_FORCE="forceRebuild"
  @Volatile var running=false
   private set

  fun start(context:Context,selections:List<Selection>,mode:StartMode=StartMode.WAIT,
            forceRebuild:Boolean=false) {
   val distinct=selections.distinct()
   require(distinct.isNotEmpty()&&distinct.size<=500)
   val token=UUID.randomUUID().toString()
   val file=File(context.filesDir,"rebuild-$token.json")
   val rows=JSONArray()
   distinct.forEach {rows.put(JSONObject().put("source",it.sourceId).put("depth",it.depth))}
   file.writeText(rows.toString())
   try {
    ContextCompat.startForegroundService(context,
     Intent(context,CacheRebuildService::class.java)
      .putExtra(EXTRA_TOKEN,token).putExtra(EXTRA_MODE,mode.name)
      .putExtra(EXTRA_FORCE,forceRebuild))
   } catch (error:Exception) {
    file.delete()
    throw error
   }
  }

  fun cancel(context:Context) {
   context.startService(Intent(context,CacheRebuildService::class.java).setAction(ACTION_CANCEL))
  }
 }

 private val executor=Executors.newSingleThreadExecutor()
 private val gate=Any()
 private val pending=ArrayDeque<Job>()
 private var active:Job?=null
 private var draining=false
 private var lastStartId=0
 private var lock:PowerManager.WakeLock?=null
 private lateinit var notifications:NotificationManager
 private lateinit var progress:CacheRebuildProgressStore
 private val main=Handler(Looper.getMainLooper())
 private var lastProgressNotification=0L

 override fun onBind(intent:Intent?):IBinder?=null

 override fun onCreate() {
  super.onCreate()
  progress=CacheRebuildProgressStore(applicationContext)
  notifications=getSystemService(NotificationManager::class.java)
  notifications.createNotificationChannel(NotificationChannel(CHANNEL,
   "도서 캐시 일괄 재생성",NotificationManager.IMPORTANCE_DEFAULT).apply {
    description="진행 상태 및 작업 결과"
  })
 }

 private fun notice(title:String,message:String,ongoing:Boolean):Notification {
  val open=PendingIntent.getActivity(this,0,Intent(this,SplashActivity::class.java),
   PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
  val builder=NotificationCompat.Builder(this,CHANNEL)
   .setSmallIcon(R.drawable.ic_book)
   .setContentTitle(title).setContentText(message)
   .setStyle(NotificationCompat.BigTextStyle().bigText(message))
   .setContentIntent(open).setOngoing(ongoing).setAutoCancel(!ongoing)
   .setOnlyAlertOnce(true)
  if(ongoing) {
   val cancel=PendingIntent.getService(this,1,
    Intent(this,CacheRebuildService::class.java).setAction(ACTION_CANCEL),
    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
   builder.addAction(android.R.drawable.ic_menu_close_clear_cancel,"취소",cancel)
  }
  return builder.build()
 }

 override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
  lastStartId=startId
  if(intent?.action==ACTION_CANCEL) {
   cancelAll()
   return START_NOT_STICKY
  }
  val token=intent?.getStringExtra(EXTRA_TOKEN)
   ?.takeIf{it.matches(Regex("[a-f0-9-]{36}"))} ?:return START_NOT_STICKY
  val file=File(filesDir,"rebuild-$token.json")
  val entries=runCatching {
   val array=JSONArray(file.readText())
   require(array.length() in 1..500)
   (0 until array.length()).mapNotNull { index ->
    val row=array.optJSONObject(index) ?:return@mapNotNull null
    val source=row.optString("source")
    val depth=row.optString("depth").take(3000)
    if(source.isBlank()||source.length>300||depth.isBlank())null
    else Selection(source,depth)
   }.distinct()
  }.getOrNull().orEmpty()
  file.delete()
  if(entries.isEmpty())return START_NOT_STICKY

  val mode=runCatching{
   StartMode.valueOf(intent.getStringExtra(EXTRA_MODE).orEmpty())
  }.getOrDefault(StartMode.WAIT)
  var shouldStart=false
  val queued:Int
  val cancelling:Boolean
  synchronized(gate) {
   if(mode==StartMode.REPLACE) {
    active?.cancelled?.set(true)
    pending.clear()
   }
   pending.addLast(Job(entries,intent.getBooleanExtra(EXTRA_FORCE,false)))
   queued=pending.size
   cancelling=active?.cancelled?.get()==true
   if(!draining) {
    draining=true
    running=true
    shouldStart=true
   }
  }
  progress.update {
   it.copy(phase=if(cancelling)"cancelling" else if(shouldStart)"waiting" else it.phase,
    waiting=queued,updatedAt=System.currentTimeMillis())
  }
  if(shouldStart) {
   notifications.cancel(RESULT_ID)
   val foreground=notice("도서 캐시 생성 중","선택한 저장소를 검색할 준비 중입니다.",true)
   if(Build.VERSION.SDK_INT>=29)
    startForeground(ID,foreground,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
   else startForeground(ID,foreground)
   if(lock?.isHeld!=true)lock=(getSystemService(POWER_SERVICE) as PowerManager)
    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"ckReader:cache-rebuild").apply {
     setReferenceCounted(false)
     acquire(5*60*60*1000L)
    }
   executor.execute{drain()}
  } else {
   updateNotice("캐시 작업 대기열 "+queued+"개")
  }
  return START_NOT_STICKY
 }

 private fun cancelAll() {
  val hadWork:Boolean
  val hasActive:Boolean
  synchronized(gate) {
   hadWork=draining
   hasActive=active!=null
   active?.cancelled?.set(true)
   pending.clear()
  }
  if(!hadWork)return
  progress.update { it.copy(phase=if(hasActive)"cancelling" else "cancelled",
   waiting=0,updatedAt=System.currentTimeMillis()) }
  updateNotice("취소 요청 · 현재 처리 중인 파일이 끝나면 중단됩니다.")
 }

 private fun drain() {
  try {
   while(true) {
    val job=synchronized(gate) {
     val next=pending.pollFirst()
     if(next==null) {
      active=null
      draining=false
      running=false
     } else active=next
     next
    } ?:break
    job.startedAt=System.currentTimeMillis()
    val waiting=synchronized(gate){pending.size}
    progress.update {
     it.copy(phase="running",storage="",path="",file="",
      processed=0,success=0,failure=0,skipped=0,waiting=waiting,
      startedAt=job.startedAt,updatedAt=job.startedAt)
    }
    updateNotice("선택한 저장소를 순차적으로 검색합니다.")
    try {
     processJob(job)
    } catch(error:Exception) {
     if(!job.cancelled.get())job.failure++
     Log.w("ckReaderRebuild","Cache job failed",error)
    } finally {
     val cancelled=job.cancelled.get()
     val waitingNow=synchronized(gate){pending.size}
     progress.update{
      it.copy(phase=if(cancelled)"cancelled" else "completed",
       storage="",path="",file="",processed=job.processed,
       success=job.success,failure=job.failure,skipped=job.skipped,
       waiting=waitingNow,updatedAt=System.currentTimeMillis())
     }
     sendFinished()
    }
   }
  } finally {
   main.post {
    if(!running) {
     lock?.takeIf{it.isHeld}?.release()
     lock=null
     val snapshot=progress.read()
     val elapsed=((snapshot.updatedAt-snapshot.startedAt).coerceAtLeast(0))/1000
     val result="성공 ${snapshot.success}권 · 실패 ${snapshot.failure}권 · "+
      "소요시간 ${elapsed/60}분 ${elapsed%60}초"
     val resultWithSkipped=result+" · 건너뜀 "+snapshot.skipped+"권"
     // Some devices retain foreground flags when the same notification ID is
     // reused. Remove/cancel it, then publish the result under a separate ID.
     stopForeground(STOP_FOREGROUND_REMOVE)
     runCatching {
      notifications.cancel(ID)
      notifications.notify(RESULT_ID,notice(
      if(snapshot.phase=="cancelled")"캐시 재생성 취소" else "캐시 재생성 완료",resultWithSkipped,false))}
      .onFailure{Log.w("ckReaderRebuild","Completion notification unavailable",it)}
     sendFinished()
     stopSelfResult(lastStartId)
    }
   }
  }
 }

 private fun processJob(job:Job) {
  val repo=LibraryRepository(applicationContext)
  for(selection in job.selections) {
   if(job.cancelled.get()||Thread.currentThread().isInterrupted)break
   val source=repo.sources().firstOrNull {it.id==selection.sourceId}
   if(source==null) {
    job.failure++
    continue
   }
   runCatching{processSelection(repo,source,selection.depth,job)}
    .onFailure {
     if(!job.cancelled.get())job.failure++
     Log.w("ckReaderRebuild","Selection failed (no credential data logged)",it)
    }
  }
 }

 private fun processSelection(repo:LibraryRepository,source:LibrarySource,depth:String,job:Job) {
  if(!LibraryVisibility.visible(depth))return
  if(source.type=="local") {
   reportFolder(source,"/")
   val listing=repo.browse(source,"")
   val books=listing.books.filter { book ->
    val segment=book.path.substringBefore('/')
    depth=="__root__"||depth=="root"||
     (if('/' in book.path)segment else "__root__")==depth
   }
   val manifestCache=HashMap<String,Boolean>()
   for(book in books) {
    if(job.cancelled.get())break
    val folder=book.path.substringBeforeLast('/',"")
    processBook(repo,book,job) {
     !manifestCache.getOrPut(folder){folderManifest(repo,source,folder)}
    }
   }
   return
  }
  val seed=if(depth=="__root__"||depth=="root")"" else depth
  val paths=ArrayDeque<Pair<String,Int>>()
  paths.addLast(seed to 0)
  val visited=HashSet<String>()
  val manifestCache=HashMap<String,Boolean>()
  while(paths.isNotEmpty()&&!job.cancelled.get()&&!Thread.currentThread().isInterrupted) {
   val (folder,level)=paths.removeFirst()
   require(level<=128&&visited.size<100_000) { "폴더 탐색 범위를 초과했습니다." }
   if(!visited.add(folder))continue
   reportFolder(source,folder.ifBlank{"/"})
   val listing=if(source.type=="bookoasis")repo.browseBookOasisComplete(
    source,folder,"",repo.cached(source,folder)?.takeIf{it.hasMore},
    onProgress={},cancelled={job.cancelled.get()})
   else repo.browse(source,folder)
   for(book in listing.books) {
    if(job.cancelled.get())break
    processBook(repo,book,job) {
     !manifestCache.getOrPut(folder){folderManifest(repo,source,folder)}
    }
   }
   for(name in listing.folders) {
    if(job.cancelled.get())break
    // OPDS home contains every library; avoid double-scanning all folders.
    if(source.type=="bookoasis"&&folder.isBlank()&&
      BookOasisPaths.parse(name).libraryId=="home")continue
    paths.addLast((if(folder.isBlank())name else folder+"/"+name) to level+1)
   }
  }
 }

 private fun folderManifest(repo:LibraryRepository,source:LibrarySource,folder:String):Boolean {
  if(!FolderYamlMetadata.supports(source.type))return false
  // If checking the directory fails, do not assume the manifest is absent.
  return runCatching {
   FolderYamlStorage.hasManifest(FolderYamlStorage(applicationContext,repo)
    .list(source,folder).filter{!it.directory}.map{it.name})
  }.getOrDefault(true)
 }

 private fun reportFolder(source:LibrarySource,path:String) {
  progress.update {
   it.copy(phase="running",storage=source.name,path=path,file="",
    updatedAt=System.currentTimeMillis())
  }
 }

 private fun processBook(repo:LibraryRepository,book:LibraryBook,job:Job,
                         allowEpubOpen:()->Boolean) {
  if(job.cancelled.get()||Thread.currentThread().isInterrupted)return
  val identity=book.source.id+"|"+book.path
  if(!job.seen.add(identity))return
  val path=book.path.substringBeforeLast('/',"").ifEmpty{"/"}
  val name=book.path.substringAfterLast('/').ifBlank{book.path}
  progress.update{
   it.copy(phase="running",storage=book.source.name,path=path,file=name,
    updatedAt=System.currentTimeMillis())
  }
  // A real persisted cover or successfully saved metadata is a completed
  // book cache. The directory listing alone does not mean completion.
  val alreadyGenerated=if(job.forceRebuild)false else
   runCatching{repo.hasGeneratedBookCache(book)}
    .onFailure{Log.w("ckReaderRebuild","Cache lookup failed; rebuilding",it)}
    .getOrDefault(false)
  val needsDriveWriteback=book.source.type=="drive"&&!book.source.readOnly&&
   !MetadataSourceBadge.isYaml(book.metadataSource)
  if(CacheRebuildPolicy.shouldSkip(job.forceRebuild,alreadyGenerated,needsDriveWriteback)) {
   job.skipped++
   progress.update{
    it.withResult(CacheRebuildProgressStore.FileResult(
     book.source.name,path,name,true,System.currentTimeMillis(),skipped=true))
     .copy(storage="",path="",file="")
   }
   updateProgressNotice(job,book.source.name,name)
   return
  }
  // Only for uncached/forced EPUBs, check the folder manifest.
  // Skipped books do not trigger another remote directory read.
  val mayOpenEpub=book.format.equals("epub",true)&&allowEpubOpen()
  // A Drive rebuild must use the same authored-YAML / A-N-L lookup and
  // immediate folder writeback as the book refresh button. The ordinary
  // reindex path may select an unrelated catalog, which cannot be exported.
  val ok=runCatching{
   val refreshed=if(book.source.type=="drive")repo.refreshBookCardMetadata(book).updated else false
   refreshed||repo.reindexBook(book,force=true,allowEmbeddedEpub=mayOpenEpub)
  }
   .onFailure{Log.w("ckReaderRebuild","Book lookup failed",it)}
   .getOrDefault(false)
  job.processed++
  if(ok)job.success++ else job.failure++
  progress.update{
   it.withResult(CacheRebuildProgressStore.FileResult(
    book.source.name,path,name,ok,System.currentTimeMillis()))
    .copy(storage="",path="",file="")
  }
  updateProgressNotice(job,book.source.name,name)
 }

 private fun updateProgressNotice(job:Job,storage:String,filename:String) {
  val now=SystemClock.elapsedRealtime()
  if(now-lastProgressNotification>=700L) {
   lastProgressNotification=now
   updateNotice("처리 "+job.processed+"권 · 건너뜀 "+job.skipped+"권 · "+
    "성공 "+job.success+"권 · 실패 "+job.failure+"권\n"+storage+" · "+filename)
  }
 }

 private fun updateNotice(message:String) {
  runCatching {notifications.notify(ID,notice("도서 캐시 생성 중",message,true))}
   .onFailure{Log.w("ckReaderRebuild","Progress notification unavailable",it)}
 }

 private fun sendFinished() {
  sendBroadcast(Intent(FINISHED).setPackage(packageName))
 }

 override fun onDestroy() {
  synchronized(gate) {
   active?.cancelled?.set(true)
   pending.clear()
   draining=false
   running=false
  }
  executor.shutdownNow()
  lock?.takeIf{it.isHeld}?.release()
  lock=null
  super.onDestroy()
 }
}
