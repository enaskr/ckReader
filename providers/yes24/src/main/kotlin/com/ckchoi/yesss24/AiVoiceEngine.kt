package com.ckchoi.yesss24

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log

/** Keeps native Selvy playback outside the reader process. */
internal class AiVoiceEngine(context:Context,private val onDone:()->Unit,private val onError:(String)->Unit) {
 private val app=context.applicationContext
 private var remote:Messenger?=null
 private var bound=false
 private var closed=false
 private var prepared=false
 private var source=""
 private var afterPrepare:((Boolean)->Unit)?=null
 private val callbacks=Messenger(object:Handler(Looper.getMainLooper()){
  override fun handleMessage(message:Message){
   if(closed)return
   when(message.what){
    VoiceProtocol.READY->{prepared=true;afterPrepare?.invoke(true);afterPrepare=null}
    VoiceProtocol.DONE->onDone()
    VoiceProtocol.ERROR->{
     val error=message.data.getString("error")?:"Selvy 음성 엔진 오류"
     if(afterPrepare!=null){afterPrepare?.invoke(false);afterPrepare=null}
     onError(error)
    }
   }
  }
 })
 private val connection=object:ServiceConnection{
  override fun onServiceConnected(name:ComponentName?,binder:IBinder?){
   Log.i("CremaAiVoice","client connected source=$source binder=${binder!=null}")
   if(closed)return
   remote=Messenger(binder)
   send(VoiceProtocol.PREPARE)
  }
  override fun onServiceDisconnected(name:ComponentName?){
   remote=null;prepared=false
   if(!closed){afterPrepare?.invoke(false);afterPrepare=null;onError("Selvy 음성 서비스 연결이 끊겼습니다.")}
  }
 }
 fun prepare(chosenSource:String,after:(Boolean)->Unit){
  Log.i("CremaAiVoice","client prepare source=$chosenSource bound=$bound prepared=$prepared")
  if(closed){after(false);return}
  if(prepared&&source==chosenSource){after(true);return}
  if(bound){try{send(VoiceProtocol.STOP);app.unbindService(connection)}catch(_:Exception){};bound=false;remote=null;prepared=false}
  source=chosenSource;afterPrepare=after
  if(!VoiceModules.installed(app,source)){
   afterPrepare=null;after(false);onError("선택한 AI 음성 모듈을 먼저 설치해주세요.");return
  }
  bound=app.bindService(Intent().setComponent(VoiceModules.service(source)),connection,Context.BIND_AUTO_CREATE)
  Log.i("CremaAiVoice","client bind source=$source result=$bound")
  if(!bound){afterPrepare=null;after(false);onError("Selvy 음성 서비스에 연결하지 못했습니다.")}
 }
 fun play(text:String,voice:Int,rate:Float,pitch:Float,readBrackets:Boolean,skipHanja:Boolean){
  val code=when(source){
   "ridi"->RidiVoiceData.codes[voice.coerceIn(0,RidiVoiceData.codes.lastIndex)]
   "willa"->WillaVoices.codes[voice.coerceIn(0,WillaVoices.codes.lastIndex)]
   else->AiVoiceData.codes[voice.coerceIn(0,AiVoiceData.codes.lastIndex)]
  }
  send(VoiceProtocol.PLAY){message->message.data=android.os.Bundle().apply{
   putString("text",text);putInt("code",code);putFloat("rate",rate);putFloat("pitch",pitch)
   putBoolean("brackets",readBrackets);putBoolean("skip_hanja",skipHanja)
  }}
 }
 fun stop(){send(VoiceProtocol.STOP)}
 fun close(){
  if(closed)return
  closed=true;afterPrepare=null
  if(bound){try{send(VoiceProtocol.STOP);app.unbindService(connection)}catch(_:Exception){};bound=false}
  remote=null;prepared=false
 }
 private fun send(what:Int,configure:(Message)->Unit={}){
  val message=Message.obtain(null,what)
  message.replyTo=callbacks;configure(message)
  try{remote?.send(message)}catch(error:Exception){if(!closed)onError(error.message?:"Selvy 음성 서비스 오류")}
 }
}

internal object VoiceProtocol {
 const val PREPARE=1
 const val PLAY=2
 const val STOP=3
 const val READY=4
 const val DONE=5
 const val ERROR=6
}
