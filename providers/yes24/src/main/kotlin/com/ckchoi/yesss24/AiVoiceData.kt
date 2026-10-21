package com.ckchoi.yesss24

import android.content.Context
import android.os.StatFs
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/** YES24 AI model and licence, stored in this app's private files directory. */
internal object AiVoiceData {
 private const val MODEL_BYTES=135_161_471L
 val names=arrayOf("혜진","이나","재훈","진우","지수","민재")
 val codes=intArrayOf(10,24,31,29,34,33)
 fun root(context:Context)=File(context.filesDir,"ai")
 fun voiceDir(context:Context)=File(root(context),"voices/ko_kr")
 fun ready(context:Context):Boolean {
  val dir=voiceDir(context)
  return File(root(context),"complete.flag").isFile &&
   File(dir,"model22_kor_base_pt_ondevice/decoder.pt").isFile &&
   File(dir,"TTS-SDN_KOR_License.dat").isFile
 }
 fun install(context:Context, progress:(String,Int)->Unit) {
  if(ready(context)) { progress("AI 음성 데이터가 설치되어 있습니다.",100); return }
  val space=StatFs(context.filesDir.absolutePath).availableBytes
  if(space<750_000_000L)throw IllegalStateException("AI 음성 설치에는 약 750MB의 여유 공간이 필요합니다.")
  val archive=File(context.filesDir,"ai_model.zip")
  val stage=File(context.filesDir,"ai.installing")
  if(!archive.isFile || archive.length()!=MODEL_BYTES)copyAsset(context,"voice/yes24-model.zip",archive,MODEL_BYTES){p->progress("AI 음성 데이터 복사",p*70/100)}
  else progress("복사된 데이터 확인",70)
  if(stage.exists())stage.deleteRecursively()
  stage.mkdirs()
  try {
   unzip(archive,stage){p->progress("AI 음성 데이터 설치",70+p*25/100)}
   val license=File(context.filesDir,"ai_license.zip")
   copyAsset(context,"voice/yes24-license.zip",license,0){progress("음성 라이선스 복사",95)}
   unzip(license,stage){progress("음성 라이선스 설치",96)}
   license.delete()
   val dir=File(stage,"voices/ko_kr")
   require(File(dir,"model22_kor_base_pt_ondevice/decoder.pt").isFile && File(dir,"TTS-SDN_KOR_License.dat").isFile){"음성 데이터가 올바르지 않습니다."}
   File(stage,"complete.flag").writeText("1")
   val target=root(context)
   if(target.exists())target.deleteRecursively()
   check(stage.renameTo(target)){"음성 데이터를 저장하지 못했습니다."}
   archive.delete()
   progress("AI 음성 설치 완료",100)
  } catch(e:Exception) { stage.deleteRecursively(); throw e }
 }
 private fun copyAsset(context:Context,name:String,target:File,expected:Long,progress:(Int)->Unit){
  val part=File(target.parentFile,target.name+".part")
  context.assets.open(name).buffered().use { input->FileOutputStream(part).use { output->
   val bytes=ByteArray(64*1024);var done=0L
   while(true){val n=input.read(bytes);if(n<0)break;output.write(bytes,0,n);done+=n
    if(expected>0)progress((done*100/expected).toInt().coerceIn(0,100))
   }
   check(expected<=0||done==expected){"내장 음성 데이터가 완전하지 않습니다."}
  }}
  if(target.exists())target.delete()
  check(part.renameTo(target)){"내장 음성 데이터를 저장하지 못했습니다."}
 }
 private fun unzip(archive:File,destination:File,progress:(Int)->Unit) {
  val canonical=destination.canonicalPath+File.separator
  var consumed=0L;val size=archive.length().coerceAtLeast(1)
  ZipInputStream(archive.inputStream().buffered()).use { zip->
   while(true){val entry=zip.nextEntry?:break
    val file=File(destination,entry.name)
    check(file.canonicalPath.startsWith(canonical)){"잘못된 음성 데이터 경로입니다."}
    if(entry.isDirectory)file.mkdirs() else {
     file.parentFile?.mkdirs()
     FileOutputStream(file).use{out->zip.copyTo(out,64*1024)}
    }
    consumed+=entry.compressedSize.coerceAtLeast(0)
    progress((consumed*100/size).toInt().coerceIn(0,100))
    zip.closeEntry()
   }
  }
 }
}
