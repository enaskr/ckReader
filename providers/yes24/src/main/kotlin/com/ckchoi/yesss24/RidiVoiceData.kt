package com.ckchoi.yesss24

import android.content.Context
import android.os.StatFs
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/** Selvy model supplied inside the user-provided RIDI APK. */
internal object RidiVoiceData {
 val names=arrayOf("이나","진우","민재","유정")
 val codes=intArrayOf(24,29,33,42)
 private fun root(context:Context)=File(context.filesDir,"ridi_ai")
 fun voiceDir(context:Context)=File(root(context),"voices/ko_kr")
 private val requiredModels=mapOf(
  "model22_kor_base_pt_ondevice/decoder.pt" to 113954800L,
  "model22_kor_base_pt_ondevice/vocoder.pt" to 16659696L,
  "model22_kor_base_pt_ondevice/ref/common.dat" to 130880L,
  "model22_kor_hsy_pt_ondevice/ref/common.dat" to 198720L,
  "model22_kor_kjy_pt_ondevice/ref/common.dat" to 183360L,
  "model22_kor_lch_pt_ondevice/ref/common.dat" to 184000L,
  "model22_kor_puj_pt_ondevice/ref/common.dat" to 227840L)
 private fun complete(dir:File)=requiredModels.all{(path,size)->File(dir,path).length()==size}&&
  mapOf(24 to 145L,29 to 149L,33 to 150L,42 to 154L).all{(code,size)->File(dir,"model/model.$code").length()==size}&&
  listOf("hsy","kjy","lch","puj").all{File(dir,"model22_kor_${it}_pt_ondevice/gl.dat").length()==23L}
 fun ready(context:Context):Boolean {
  val dir=voiceDir(context)
  return File(root(context),"complete.flag").takeIf{it.isFile}?.readText()=="2" &&
   complete(dir)&&File(dir,"TTS-SDN_KOR_License.dat").isFile
 }
 fun install(context:Context) {
  if(ready(context))return
  check(StatFs(context.filesDir.absolutePath).availableBytes>220_000_000L){"Selvy 음성 설치 공간이 부족합니다."}
  val target=root(context)
  val stage=File(context.filesDir,"ridi_ai.installing")
  if(stage.exists())stage.deleteRecursively()
  check(stage.mkdirs()){ "Selvy 음성 저장소를 만들지 못했습니다." }
  try {
   val canonical=stage.canonicalPath+File.separator
   context.assets.open("ridi_voices.zip").buffered().use { input ->
    ZipInputStream(input).use { zip ->
     while(true){
      val entry=zip.nextEntry?:break
      val file=File(stage,entry.name)
      check(file.canonicalPath.startsWith(canonical)){"잘못된 Selvy 음성 데이터 경로입니다."}
      if(entry.isDirectory)file.mkdirs() else {
       file.parentFile?.mkdirs()
       FileOutputStream(file).use { output->zip.copyTo(output,64*1024) }
      }
      zip.closeEntry()
     }
    }
   }
   val dir=File(stage,"voices/ko_kr")
   check(complete(dir)&&File(dir,"TTS-SDN_KOR_License.dat").isFile){"Selvy 음성 데이터가 완전하지 않습니다."}
   File(stage,"complete.flag").writeText("2")
   if(target.exists())target.deleteRecursively()
   check(stage.renameTo(target)){"Selvy 음성 데이터를 저장하지 못했습니다."}
  }catch(error:Exception){stage.deleteRecursively();throw error}
 }
}
