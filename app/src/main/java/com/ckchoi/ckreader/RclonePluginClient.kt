package com.ckchoi.ckreader

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** Signature-restricted external rclone. Large payloads use file-descriptor pipes. */
internal class RclonePluginClient(private val context:Context) {
 companion object {
  const val PACKAGE="com.ckchoi.ckreader.rclone"
  private val ENDPOINT=Uri.parse("content://com.ckchoi.ckreader.rclone.service")
  private const val CHUNK=256*1024
  fun available(context:Context):Boolean=runCatching {
   context.packageManager.getPackageInfo(PACKAGE,0)
   val data=context.contentResolver.call(ENDPOINT,"ping",null,null)
   data!=null&&data.getString("error")==null&&data.getInt("protocol")>=1&&
    !data.getString("instance").isNullOrBlank()
  }.getOrDefault(false)
 }
 private fun call(method:String,args:Bundle=Bundle()):Bundle {
  val response=try{context.contentResolver.call(ENDPOINT,method,null,args)}
   catch(e:Exception){throw IOException("rclone 확장앱을 실행할 수 없습니다. 설치 상태를 확인하세요.",e)}
   ?:throw IOException("rclone 확장앱이 설치되지 않았습니다. 확장 APK를 외부에서 설치하세요.")
  response.getString("error")?.let{throw IOException("rclone: "+it)}
  return response
 }
 private fun fd(data:Bundle):ParcelFileDescriptor {
  val descriptor=if(Build.VERSION.SDK_INT>=33)data.getParcelable("fd",ParcelFileDescriptor::class.java)
   else {
    @Suppress("DEPRECATION")
    data.getParcelable<ParcelFileDescriptor>("fd")
   }
  return descriptor?:error("rclone 데이터 전송 채널이 없습니다.")
 }
 fun sessionId():String {
  val response=call("ping")
  require(response.getInt("protocol")>=1){"지원하지 않는 rclone 버전입니다."}
  return response.getString("instance")?:error("rclone 실행 상태 오류")
 }
 fun initialize(secret:String){call("initialize",Bundle().apply{putString("password",secret)})}
 data class Rpc(val status:Long,val output:String)
 fun rpc(method:String,args:JSONObject):Rpc {
  val reply=call("rpc",Bundle().apply{
   putString("method",method);putString("params",args.toString())
  })
  val output=if(reply.containsKey("fd")) {
   val buffer=ByteArrayOutputStream()
   ParcelFileDescriptor.AutoCloseInputStream(fd(reply)).use{input->
    val chunk=ByteArray(32768)
    while(true){
     val count=input.read(chunk);if(count<0)break
     require(buffer.size()+count<=32*1024*1024){"rclone 응답이 너무 큽니다."}
     buffer.write(chunk,0,count)
    }
   }
   buffer.toString(Charsets.UTF_8.name())
  } else reply.getString("output").orEmpty()
  return Rpc(reply.getLong("status",-1L),output)
 }
 fun stat(root:String,path:String):Long=call("stat",Bundle().apply{
  putString("fs",root);putString("remote",path)
 }).getLong("size",-1L)
 fun readRange(root:String,path:String,offset:Long,length:Int):ByteArray {
  require(offset>=0&&length in 1..2_097_152)
  val result=ByteArrayOutputStream(length)
  while(result.size()<length) {
   val count=minOf(CHUNK,length-result.size())
   val bytes=call("range",Bundle().apply {
    putString("fs",root);putString("remote",path)
    putLong("offset",offset+result.size());putInt("count",count)
   }).getByteArray("bytes")?:error("rclone 읽기 응답이 없습니다.")
   require(bytes.size<=count){"rclone 읽기 크기가 올바르지 않습니다."}
   if(bytes.isEmpty())break
   result.write(bytes)
   if(bytes.size<count)break
  }
  return result.toByteArray()
 }
 /**
  * Staged native transfer progress is available from rclone plugin 1.0.2.
  * Legacy plugin releases still download successfully; transferred bytes are
  * reported once their pipe begins flowing.
  */
 fun download(root:String,remote:String,target:File,expectedSize:Long=-1,
  onProgress:(String,Long,Long)->Unit={_,_,_->}) {
  onProgress("Google Drive 원본 다운로드 준비 중",-1,expectedSize)
  val response=call("download",Bundle().apply {
   putString("fs",root);putString("remote",remote)
   putLong("expectedSize",expectedSize)
  })
  val parent=target.parentFile?:error("다운로드 폴더가 없습니다.")
  require(parent.isDirectory||parent.mkdirs()){ "다운로드 폴더 생성 실패" }
  val stage=File.createTempFile("rclone-transfer-",".part",parent)
  val job=response.getString("job").orEmpty()
  val running=java.util.concurrent.atomic.AtomicBoolean(true)
  val monitor=if(job.isNotBlank())Thread {
   while(running.get()&&!Thread.currentThread().isInterrupted){
    val result=runCatching {
     call("downloadProgress",Bundle().apply{putString("job",job)})
    }.getOrNull()
    if(result!=null){
     val phase=result.getString("phase").orEmpty()
     val bytes=result.getLong("bytes",-1L)
     val total=result.getLong("total",expectedSize)
     if(phase=="remote")
      onProgress("Google Drive 원본 다운로드 중",bytes,total)
     else if(phase=="copy")
      onProgress("Google Drive 파일 수신 중",bytes,total)
    }
    try{Thread.sleep(500)}catch(_:InterruptedException){return@Thread}
   }
  }.apply{isDaemon=true;start()} else null
  try {
   ParcelFileDescriptor.AutoCloseInputStream(fd(response)).use{input->
    FileOutputStream(stage).use{output->
     val buffer=ByteArray(128*1024)
     var done=0L
     var last=0L
     while(true){
      if(Thread.currentThread().isInterrupted)
       throw java.io.InterruptedIOException("Google Drive 다운로드 취소")
      val n=input.read(buffer)
      if(n<0)break
      if(n==0)continue
      done+=n
      require(done<=20L*1024*1024*1024){"Google Drive 도서 크기가 너무 큽니다."}
      output.write(buffer,0,n)
      val now=System.currentTimeMillis()
      if(now-last>=350L){
       onProgress("Google Drive 파일 저장 중",done,expectedSize)
       last=now
      }
     }
     output.fd.sync()
     onProgress("Google Drive 파일 저장 중",done,expectedSize)
    }
   }
   require(stage.length()>0L){"rclone 다운로드 결과가 비어 있습니다."}
   if(expectedSize>0)require(stage.length()==expectedSize){
    "Google Drive 파일 수신 크기가 목록 정보와 다릅니다."
   }
   if(!stage.renameTo(target)) {
    stage.copyTo(target,overwrite=true)
    require(stage.length()==target.length()){ "rclone 다운로드 파일 크기가 다릅니다." }
   }
  } finally {
   running.set(false);monitor?.interrupt()
   stage.delete()
  }
 }
 /** Avoids a *third* copy during ZIP Range failure recovery in the reader.
  * The plugin stages its native rclone download, then streams via its FD. */
 fun streamTo(root:String,remote:String,output:java.io.OutputStream) {
  val response=call("download",Bundle().apply{
   putString("fs",root);putString("remote",remote)
  })
  ParcelFileDescriptor.AutoCloseInputStream(fd(response)).use{input->
   val buf=ByteArray(128*1024)
   var count=0L
   while(true){
    if(Thread.currentThread().isInterrupted)
     throw java.io.InterruptedIOException("Google Drive 원본 다운로드 중단")
    val n=input.read(buf)
    if(n<0)break
    if(n==0)continue
    count+=n
    require(count<=20L*1024*1024*1024){"도서 원본이 너무 큽니다."}
    output.write(buf,0,n)
   }
   require(count>0){"Google Drive 다운로드가 비어 있습니다."}
  }
 }
 fun upload(root:String,remote:String,source:File,options:JSONObject) {
  require(source.isFile&&source.length() in 1L..(40L*1024*1024)) {"업로드 파일 크기가 올바르지 않습니다."}
  val response=call("uploadBegin",Bundle().apply {
   putString("fs",root);putString("remote",remote)
   putString("options",options.toString())
  })
  val job=response.getString("job")?:error("rclone 업로드 작업 ID가 없습니다.")
  try {
   var offset=0L
   source.inputStream().use { input ->
    val chunk=ByteArray(128*1024)
    while(true) {
     val size=input.read(chunk)
     if(size<0)break
     if(size==0)continue
     val bytes=if(size==chunk.size)chunk else chunk.copyOf(size)
     val status=call("uploadChunk",Bundle().apply {
      putString("job",job);putLong("offset",offset);putByteArray("data",bytes)
     })
     offset+=size
     check(status.getLong("size",-1L)==offset){"rclone 전송 데이터 길이 오류"}
    }
   }
   check(offset==source.length()){"rclone 업로드 원본 파일이 변경되었습니다."}
   call("uploadCommit",Bundle().apply{putString("job",job)})
  } catch(e:Exception) {
   runCatching{call("uploadAbort",Bundle().apply{putString("job",job)})}
   throw e
  }
 }
}
