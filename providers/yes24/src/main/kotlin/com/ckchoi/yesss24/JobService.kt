package com.ckchoi.yesss24

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import java.util.concurrent.Executors
import org.json.JSONObject
import java.util.ArrayDeque

class JobService: Service() {
 private val executor = Executors.newSingleThreadExecutor()
 data class Request(val catalogId:String,val op:String,val code:String,val librarySettings:String?,val title:String,val bookTitle:String="",val author:String="")
 companion object {
  @Volatile var busy = false
  @Volatile var status = "준비"
  @Volatile var revision = 0
  private val pending=ArrayDeque<Request>()
  @Volatile var currentCatalogId=""
  private fun displayTitle(kind:String,title:String,author:String,extension:String,id:String)=listOf(kind,title.ifBlank{"제목 없음"},author.ifBlank{"저자 미상"},extension.ifBlank{"epub"},id).joinToString(" - ")
  @Synchronized private fun add(context:android.content.Context,op:String,code:String,librarySettings:String?,title:String,bookTitle:String="",author:String="",cover:String=""):Boolean{
   val key="$op:$code"
   if(pending.any{it.op+":"+it.code==key})return true
   val id=com.ckchoi.elibrary.common.DownloadCenterStore.enqueue(context,"yes24",title,key,cover)
   pending.add(Request(id,op,code,librarySettings,title,bookTitle,author))
   if(!busy){
    busy=true;status="다운로드 대기 중…"
    try{val i=Intent(context,JobService::class.java);if(Build.VERSION.SDK_INT>=26)context.startForegroundService(i)else context.startService(i)}
    catch(e:Exception){busy=false;com.ckchoi.elibrary.common.DownloadCenterStore.fail(context,"yes24",id,"다운로드 서비스를 시작하지 못했습니다.");throw e}
   }
   return true
  }
  @Synchronized fun enqueue(context:android.content.Context,goods:String,librarySettings:String?=null,bookTitle:String="",author:String="",extension:String="epub"):Boolean{
   val title=if(librarySettings==null)displayTitle("Crema eBook",bookTitle,author,extension,goods) else runCatching{JSONObject(goods).optString("title").ifBlank{"전자도서관 eBook"}}.getOrDefault("전자도서관 eBook")
   return add(context,if(librarySettings==null)"download_goods" else "library_download",goods,librarySettings,title,bookTitle,author,if(librarySettings==null)"https://image.yes24.com/goods/$goods/L" else "")
  }
  @Synchronized fun enqueuePurchase(context:android.content.Context,code:String,bookTitle:String="",author:String="",extension:String="epub",cover:String=""):Boolean=add(context,"purchase_download",code,null,displayTitle("YES24 eBook",bookTitle,author,extension,code),bookTitle,author,cover)
  @Synchronized private fun next():Request?{
   val r=if(pending.isEmpty())null else pending.removeFirst()
   if(r==null)busy=false
   return r
  }
 }
 private var running=false
 override fun onBind(intent:Intent?):IBinder?=null
 override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
  if(running)return START_NOT_STICKY
  running=true
  val manager=getSystemService(NotificationManager::class.java)
  if(Build.VERSION.SDK_INT>=26)manager.createNotificationChannel(NotificationChannel("downloads","도서 작업",NotificationManager.IMPORTANCE_LOW))
  val notification=(if(Build.VERSION.SDK_INT>=26)Notification.Builder(this,"downloads")else Notification.Builder(this))
   .setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("예스24").setContentText("도서 작업 진행 중").setOngoing(true)
   .setContentIntent(PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE)).build()
  startForeground(24,notification)
  executor.execute{
   try{
    while(true){
     val req=next()?:break
     currentCatalogId=req.catalogId
     if(com.ckchoi.elibrary.common.DownloadCenterStore.isCancelled(this,"yes24",req.catalogId))continue
     com.ckchoi.elibrary.common.DownloadCenterStore.update(this,"yes24",req.catalogId,"running","다운로드 중")
     status="도서 다운로드 및 저장 중…"
     val publisher=DownloadPublisher(this)
     try{
      status=when(req.op){
       "library_download"->Yes24LibraryNative(this,JSONObject(req.librarySettings?:throw java.io.IOException("전자도서관 설정이 없습니다."))).operate("download",JSONObject(req.code),publisher).toString()
       "purchase_download"->Yes24Client(this,true).downloadPurchase(req.code,publisher)
       "download_goods"->Yes24Client(this).downloadCrema(req.code,publisher)
       else->throw java.io.IOException("지원하지 않는 다운로드 작업입니다.")
      }
      if(!com.ckchoi.elibrary.common.DownloadCenterStore.isCancelled(this,"yes24",req.catalogId)){
       if(req.op!="library_download"){
        val label=displayTitle(if(req.op=="download_goods")"Crema eBook" else "YES24 eBook",publisher.bookTitle.ifBlank{req.bookTitle},publisher.author.ifBlank{req.author},publisher.lastName.substringAfterLast('.',"epub"),req.code)
        com.ckchoi.elibrary.common.DownloadCenterStore.updateTitle(this,"yes24",req.catalogId,label)
       }
       com.ckchoi.elibrary.common.DownloadCenterStore.complete(this,"yes24",req.catalogId,"",publisher.lastUri,"",publisher.lastName)
      }
     }catch(e:Exception){
      status="다운로드 실패 · "+(e.message?:"오류")
      com.ckchoi.elibrary.common.DownloadCenterStore.fail(this,"yes24",req.catalogId,e.message?:"다운로드 실패")
     }finally{revision++}
    }
   }finally{
    currentCatalogId="";running=false;busy=false;stopForeground(STOP_FOREGROUND_REMOVE);stopSelf(startId)
   }
  }
  return START_NOT_STICKY
 }
 override fun onDestroy(){executor.shutdownNow();super.onDestroy()}
}
