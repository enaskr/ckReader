package com.ckchoi.yesss24

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/** The reader remains the speech owner; this foreground media service exposes
 * controls while the Activity is in the background and keeps the process live.
 */
class ReaderTtsNotificationService:Service() {
 companion object {
  const val ACTION_COMMAND="com.ckchoi.ckreader.TTS_COMMAND"
  const val COMMAND_PAUSE="pause"
  const val COMMAND_RESUME="resume"
  const val COMMAND_STOP="stop"
  const val EXTRA_COMMAND="command"
  const val EXTRA_JUMP_TO_SPEECH="com.ckchoi.ckreader.JUMP_TO_SPEECH"
  private const val ACTION_UPDATE="com.ckchoi.ckreader.TTS_NOTIFICATION_UPDATE"
  private const val ACTION_CLOSE="com.ckchoi.ckreader.TTS_NOTIFICATION_CLOSE"
  private const val CHANNEL="ckreader_tts_media"
  private const val NOTIFICATION_ID=5271
  @Volatile private var foregroundActive=false

  fun update(context:Context,playing:Boolean,position:String) {
   val intent=Intent(context,ReaderTtsNotificationService::class.java)
    .setAction(ACTION_UPDATE).putExtra("playing",playing)
    .putExtra("position",position.take(100))
   if(foregroundActive||Build.VERSION.SDK_INT<26)context.startService(intent)
   else context.startForegroundService(intent)
  }
  fun close(context:Context) {
   context.startService(Intent(context,ReaderTtsNotificationService::class.java)
    .setAction(ACTION_CLOSE))
  }
 }
 private lateinit var manager:NotificationManager
 private var started=false
 override fun onCreate() {
  super.onCreate()
  manager=getSystemService(NotificationManager::class.java)
  manager.createNotificationChannel(NotificationChannel(CHANNEL,"TTS 도서 읽기",
   NotificationManager.IMPORTANCE_LOW).apply {
    description="도서 낭독 진행 상태와 일시정지 및 정지"
    setSound(null,null)
   })
 }
 override fun onBind(intent:Intent?):IBinder?=null
 override fun onDestroy(){
  foregroundActive=false
  super.onDestroy()
 }
 override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
  when(intent?.action) {
   ACTION_CLOSE ->{
    if(started) {
     stopForeground(STOP_FOREGROUND_REMOVE)
     started=false
     foregroundActive=false
    }
    stopSelf()
    return START_NOT_STICKY
   }
   ACTION_UPDATE -> {
    val playing=intent.getBooleanExtra("playing",false)
    val position=intent.getStringExtra("position").orEmpty()
    val notification=createNotification(playing,position)
    if(!started) {
     if(Build.VERSION.SDK_INT>=29)
      startForeground(NOTIFICATION_ID,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
     else startForeground(NOTIFICATION_ID,notification)
     started=true
     foregroundActive=true
    } else manager.notify(NOTIFICATION_ID,notification)
   }
   COMMAND_PAUSE,COMMAND_RESUME,COMMAND_STOP ->{
    val actual=when(intent.action){
     COMMAND_PAUSE->COMMAND_PAUSE
     COMMAND_RESUME->COMMAND_RESUME
     else->COMMAND_STOP
    }
    sendBroadcast(Intent(ACTION_COMMAND).setPackage(packageName)
     .putExtra(EXTRA_COMMAND,actual))
   }
  }
  return START_NOT_STICKY
 }

 private fun commandIntent(command:String,code:Int):PendingIntent {
  return PendingIntent.getService(this,code,
   Intent(this,ReaderTtsNotificationService::class.java).setAction(command),
   PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
 }

 private fun createNotification(playing:Boolean,position:String):Notification {
  val open=PendingIntent.getActivity(this,100,
   Intent(this,StandaloneReaderActivity::class.java)
    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    .putExtra(EXTRA_JUMP_TO_SPEECH,true),
   PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  val info=if(position.isBlank())"현재 읽고 있는 문장" else position
  val builder=Notification.Builder(this,CHANNEL)
   .setSmallIcon(android.R.drawable.ic_media_play)
   .setContentTitle(if(playing)"ckReader · TTS 재생 중" else "ckReader · TTS 일시정지")
   .setContentText(info)
   .setStyle(Notification.BigTextStyle().bigText(info))
   .setContentIntent(open)
   .setVisibility(Notification.VISIBILITY_PUBLIC)
   .setOnlyAlertOnce(true)
   .setOngoing(playing)
   .setCategory(Notification.CATEGORY_TRANSPORT)
  if(playing)builder.addAction(Notification.Action.Builder(
   android.R.drawable.ic_media_pause,"일시정지",commandIntent(COMMAND_PAUSE,1)).build())
  else builder.addAction(Notification.Action.Builder(
   android.R.drawable.ic_media_play,"재생",commandIntent(COMMAND_RESUME,1)).build())
  builder.addAction(Notification.Action.Builder(
   android.R.drawable.ic_menu_close_clear_cancel,"정지",commandIntent(COMMAND_STOP,2)).build())
  return builder.build()
 }
}
