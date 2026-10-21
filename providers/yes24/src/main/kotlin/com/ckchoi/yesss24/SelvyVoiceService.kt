package com.ckchoi.yesss24

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.util.Log
import com.selvasai.selvy.deeptts.OnxVoiceStatusListener
import com.selvasai.selvy.deeptts.xVoice
import java.util.concurrent.Executors

/** Each model runs in its own process because the native decoder has global state. */
abstract class SelvyVoiceService:Service() {
 protected abstract val source:String
 private val worker=Executors.newSingleThreadExecutor()
 private var engine:xVoice?=null
 private var ready=false
 private var voiceDirectory=""
 private var loadedSpeaker:Int?=null
 private var client:Messenger?=null
 private val incoming=Messenger(object:Handler(Looper.getMainLooper()){
  override fun handleMessage(message:Message){
   val command=message.what
   val data=message.data
   Log.i("CremaAiVoice","service=$source received=$command")
   client=message.replyTo
   worker.execute { when(command){
    VoiceProtocol.PREPARE->prepare()
    VoiceProtocol.PLAY->play(data)
    VoiceProtocol.STOP->try{engine?.PTTS_StopTTS()}catch(_:Throwable){}
   }}
  }
 })
 override fun onBind(intent:Intent?):IBinder{Log.i("CremaAiVoice","service=$source bound");return incoming.binder}
 private fun prepare(){
  if(ready){send(VoiceProtocol.READY);return}
  try{
   val directory=if(source=="ridi"){
    RidiVoiceData.install(this);RidiLicenseUpdate.ensureCurrent(this);RidiVoiceData.voiceDir(this)
   }else{
    AiVoiceData.install(this){_,_->};AiVoiceData.voiceDir(this)
   }
   val native=xVoice()
   native.setOnxVoiceStatusListener(object:OnxVoiceStatusListener{
    override fun onStart(){}
    override fun onFinish(){send(VoiceProtocol.DONE)}
    override fun onStop(){}
    override fun onPause(){}
    override fun onContinue(){}
    override fun onError(code:Int){send(VoiceProtocol.ERROR,"Selvy 음성 엔진 오류: $code")}
    override fun onSynthesize(type:Int,samples:ShortArray?,count:Short,id:Long){}
    override fun onPlayList(id:Long){}
    override fun onFileStart(path:String?){}
    override fun onFileEnd(path:String?){}
   })
   val init=native.PTTS_Initialize()
   // Selvy II uses a separate load for each speaker, including newer voices
   // which are not part of PTTS_LoadEngine's default set.
   val load=if(source=="ridi")0 else native.PTTS_LoadEngine(0,directory.absolutePath,0)
   Log.i("CremaAiVoice","source=$source initialize=$init load=$load directory=${directory.absolutePath}")
   check(load==0){"Selvy 음성 데이터를 열 수 없습니다. (로드 $load)"}
   voiceDirectory=directory.absolutePath
   engine=native;ready=true;send(VoiceProtocol.READY)
  }catch(error:Throwable){Log.e("CremaAiVoice","$source prepare failed",error);send(VoiceProtocol.ERROR,error.message?:"Selvy 음성 준비 오류")}
 }
 private fun play(data:Bundle){
  try{
   val native=engine?:throw IllegalStateException("Selvy 음성 엔진이 준비되지 않았습니다.")
   val code=data.getInt("code")
   if(source=="ridi"&&loadedSpeaker!=code){
    native.PTTS_StopTTS()
    loadedSpeaker?.let{native.PTTS_UnLoadEngineEx(0,it)}
    loadedSpeaker=null
    var load=native.PTTS_LoadEngineEx(0,code,voiceDirectory,0)
    if(load == -102){
     RidiLicenseUpdate.ensureCurrent(this,forceRefresh=true)
     load=native.PTTS_LoadEngineEx(0,code,voiceDirectory,0)
    }
    val name=RidiVoiceData.names.getOrNull(RidiVoiceData.codes.indexOf(code))?:code.toString()
    check(load==0){"Selvy II '$name' 음성을 불러오지 못했습니다. 호환되는 음성 데이터와 라이선스를 확인해주세요. (로드 $load)"}
    loadedSpeaker=code
   }
   native.PTTS_SetSpeed((data.getFloat("rate",1f)*100).toInt().coerceIn(50,200))
   native.PTTS_SetPitch((data.getFloat("pitch",1f)*100).toInt().coerceIn(80,120))
   native.PTTS_SetVolume(200)
   native.PTTS_SetReadingBracket(data.getBoolean("brackets",true))
   native.PTTS_SetSkipHanja(data.getBoolean("skip_hanja",false))
   val result=native.PTTS_PlayTTS(data.getString("text").orEmpty(),"",0,code)
   Log.i("CremaAiVoice","source=$source play code=$code result=$result")
   if(result!=0)send(VoiceProtocol.ERROR,"Selvy 음성을 재생하지 못했습니다. ($result)")
  }catch(error:Throwable){Log.e("CremaAiVoice","$source play failed",error);send(VoiceProtocol.ERROR,error.message?:"Selvy 음성 재생 오류")}
 }
 private fun send(what:Int,error:String?=null){
  val message=Message.obtain(null,what)
  if(error!=null)message.data=Bundle().apply{putString("error",error)}
  try{client?.send(message)}catch(_:Exception){}
 }
 override fun onDestroy(){
  super.onDestroy()
  worker.shutdownNow()
  // The native decoder can abort while unloading a model. Process exit releases it safely.
  Process.killProcess(Process.myPid())
 }
}

class Yes24VoiceService:SelvyVoiceService(){override val source="yes24"}
class RidiVoiceService:SelvyVoiceService(){override val source="ridi"}
