package com.ckchoi.yesss24

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class SettingsTransfer(private val context: Context) {
 private val store = Store(context)
 private val journal = AtomicFile(File(context.filesDir,"settings-restore-rollback.json"))
 private val changedKeys = listOf("account","yes24Account","libraries","cremaOptions","filenamePattern","selectedLibrary")

 /** Roll back a restore interrupted between the two preferences commits. Contains ciphertext only. */
 fun recover() {
  if(!journal.baseFile.exists() && !File(journal.baseFile.path+".bak").exists()) return
  val old = JSONObject(String(journal.readFully(),Charsets.UTF_8))
  val vault = context.getSharedPreferences("vault",Context.MODE_PRIVATE).edit()
  if(old.isNull("vault"))vault.remove("session") else vault.putString("session",old.getString("vault"))
  check(vault.commit()) { "이전 계정정보를 복구하지 못했습니다." }
  val yes24Vault=context.getSharedPreferences("yes24Vault",Context.MODE_PRIVATE).edit()
  if(old.isNull("yes24Vault"))yes24Vault.remove("session") else yes24Vault.putString("session",old.getString("yes24Vault"))
  check(yes24Vault.commit()) { "이전 YES24 계정정보를 복구하지 못했습니다." }
  val prefs=store.prefs.edit();val values=old.getJSONObject("settings")
  for(key in changedKeys) {
   if(values.isNull(key))prefs.remove(key)
   else prefs.putString(key,values.getString(key))
  }
  check(prefs.commit()) { "이전 설정을 복구하지 못했습니다." }
  journal.delete()
 }

 fun apply(backup: JSONObject, verify: () -> Unit = {}) {
  val settings=SettingsBackupCodec.validate(backup).getJSONObject("settings")
  // Prepare every encrypted value before touching existing settings.
  val libraries=store.encode(JSONObject().put("libraries",settings.getJSONArray("libraries")))
  val options=store.encode(settings.getJSONObject("cremaOptions"))
  val account=store.encode(JSONObject());val yes24Account=store.encode(JSONObject())
  val old=JSONObject().put("vault",context.getSharedPreferences("vault",0).getString("session",null) ?: JSONObject.NULL).put("yes24Vault",context.getSharedPreferences("yes24Vault",0).getString("session",null) ?: JSONObject.NULL)
  val previous=JSONObject()
  for(key in changedKeys)previous.put(key,store.prefs.all[key] ?: JSONObject.NULL)
  old.put("settings",previous)
  try {
   RestoreTransaction.run({
    val output=journal.startWrite()
    try {output.write(old.toString().toByteArray(Charsets.UTF_8));journal.finishWrite(output)}
    catch(e:Exception){journal.failWrite(output);throw e}
   },{SecureStore(context).save(settings.getJSONObject("cremaVault").toString());SecureStore(context,"yes24.session.v1","yes24Vault").save(settings.getJSONObject("yes24Vault").toString())},{
    check(store.prefs.edit().putString("account",account).putString("yes24Account",yes24Account).putString("libraries",libraries).putString("cremaOptions",options)
     .putString("filenamePattern",settings.getString("filenamePattern"))
     .putString("selectedLibrary",settings.getString("selectedLibrary")).commit()) { "설정을 저장하지 못했습니다." }
   },{verify()},{recover()},{journal.delete()})
  } catch(e:Exception) { throw IOException("설정 복원에 실패했습니다. 이전 설정 복구 후 다시 시도해주세요.",e) }
 }

 fun read(uri: Uri): JSONObject {
  val input=context.contentResolver.openInputStream(uri) ?: throw IOException("선택한 파일을 열지 못했습니다.")
  val output=java.io.ByteArrayOutputStream()
  input.use {
   val buffer=ByteArray(8192)
   while(true){val count=it.read(buffer);if(count<0)break
    if(output.size()+count>SettingsBackupCodec.MAX_BYTES)throw IOException("설정 백업 파일은 2MB 이하여야 합니다.")
    output.write(buffer,0,count)
   }
  }
  val decoder=Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
  val text=try{decoder.decode(ByteBuffer.wrap(output.toByteArray())).toString()}catch(e:Exception){throw IOException("UTF-8 JSON 설정 파일을 선택해주세요.")}
  return SettingsBackupCodec.parse(text)
 }

 fun publish(backup: JSONObject): JSONObject {
  val data=SettingsBackupCodec.validate(backup).toString(2).toByteArray(Charsets.UTF_8)
  if(data.size>SettingsBackupCodec.MAX_BYTES)throw IOException("설정정보가 2MB를 초과하여 백업하지 못했습니다.")
  val name="Yesss24-settings-"+SimpleDateFormat("yyyyMMdd-HHmmss",Locale.US).format(Date())+"-"+UUID.randomUUID().toString().take(8)+".json"
  if(Build.VERSION.SDK_INT>=29) {
   val resolver=context.contentResolver
   val uri=resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,ContentValues().apply {
    put(MediaStore.Downloads.DISPLAY_NAME,name);put(MediaStore.Downloads.MIME_TYPE,"application/json")
    put(MediaStore.Downloads.RELATIVE_PATH,com.ckchoi.elibrary.common.GlobalConfig.relativePath(context,"yes24")+"/settings/")
    put(MediaStore.Downloads.IS_PENDING,1)
   }) ?: throw IOException("백업 폴더를 열지 못했습니다.")
   try {
    (resolver.openOutputStream(uri,"w") ?: throw IOException("백업 파일을 열지 못했습니다.")).use{it.write(data)}
    val saved=resolver.openInputStream(uri)?.use{it.readBytes()}
    check(saved!=null&&saved.contentEquals(data)){"백업 파일 저장을 확인하지 못했습니다."}
    check(resolver.update(uri,ContentValues().apply{put(MediaStore.Downloads.IS_PENDING,0)},null,null)==1)
   }catch(e:Exception){resolver.delete(uri,null,null);throw e}
  } else {
   @Suppress("DEPRECATION")
   val directory=File(com.ckchoi.elibrary.common.GlobalConfig.downloadDir(context,"yes24"),"settings")
   check(directory.isDirectory||directory.mkdirs())
   val target=File(directory,name)
   val temporary=File.createTempFile("settings-",".pending",directory)
   try {
    FileOutputStream(temporary).use{it.write(data);it.fd.sync()}
    check(temporary.readBytes().contentEquals(data))
    check(temporary.renameTo(target))
    android.media.MediaScannerConnection.scanFile(context,arrayOf(target.absolutePath),arrayOf("application/json"),null)
   }catch(e:Exception){temporary.delete();throw e}
  }
  return JSONObject().put("fileName",name).put("path",com.ckchoi.elibrary.common.GlobalConfig.relativePath(context,"yes24")+"/settings/"+name)
 }
}
