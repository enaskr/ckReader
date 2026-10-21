package com.ckchoi.yesss24

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream

class DownloadPublisher(private val context: Context) {
 companion object {
  fun alreadyDownloaded(context:Context,bookId:String):Boolean {
   if(!bookId.matches(Regex("[0-9]+")))return false
   val suffixes=listOf("epub","pdf","zip").map{"_${bookId}.$it"}
   if(Build.VERSION.SDK_INT>=29){
    val path=com.ckchoi.elibrary.common.GlobalConfig.relativePath(context,"yes24")+"/"
    return runCatching{
     context.contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,arrayOf(MediaStore.Downloads.DISPLAY_NAME),"${MediaStore.Downloads.RELATIVE_PATH}=?",arrayOf(path),null)?.use{cursor->
      val index=cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
      var found=false
      while(cursor.moveToNext()){if(suffixes.any{cursor.getString(index).endsWith(it,true)}){found=true;break}}
      found
     }?:false
    }.getOrDefault(false)
   }
   val folder=com.ckchoi.elibrary.common.GlobalConfig.downloadDir(context,"yes24")
   return folder.listFiles()?.any{file->file.isFile&&suffixes.any{file.name.endsWith(it,true)}}?:false
  }
 }
 @Volatile var lastUri: String = ""
 @Volatile var lastName: String = ""
 @Volatile var bookTitle: String = ""
 @Volatile var author: String = ""
 fun status(message: String) { val id=JobService.currentCatalogId;if(id.isNotBlank()&&com.ckchoi.elibrary.common.DownloadCenterStore.isCancelled(context,"yes24",id))throw InterruptedException("취소되었습니다.");JobService.status = message; if(id.isNotBlank()) com.ckchoi.elibrary.common.DownloadCenterStore.update(context,"yes24",id,"running",message) }
 fun publish(path: String): String {
  val source = File(path).canonicalFile
  val allowedRoots = listOf(
   File(context.filesDir,"downloads/export").canonicalFile,
   File(context.cacheDir,"yes24-native").canonicalFile
  )
  val insideAllowedRoot = allowedRoots.any { root -> source.path.startsWith(root.path + File.separator) }
  require(insideAllowedRoot && source.isFile && source.length()>0) { "다운로드 결과 파일을 확인하지 못했습니다." }
  val mime = when(source.extension.lowercase()) { "pdf" -> "application/pdf"; "epub" -> "application/epub+zip"; else -> "application/zip" }
  if(Build.VERSION.SDK_INT>=29) {
   val resolver=context.contentResolver
   val values=ContentValues().apply {
    put(MediaStore.Downloads.DISPLAY_NAME,source.name)
    put(MediaStore.Downloads.MIME_TYPE,mime)
    put(MediaStore.Downloads.RELATIVE_PATH,com.ckchoi.elibrary.common.GlobalConfig.relativePath(context,"yes24") + "/")
    put(MediaStore.Downloads.IS_PENDING,1)
   }
   val uri=resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values) ?: error("다운로드 폴더를 열지 못했습니다.")
   try {
    val output=resolver.openOutputStream(uri,"w") ?: error("저장 파일을 열지 못했습니다.")
    val count=output.use { out -> source.inputStream().use { input -> input.copyTo(out) } }
    check(count==source.length()) { "파일 저장이 완료되지 않았습니다." }
    check(resolver.update(uri,ContentValues().apply { put(MediaStore.Downloads.IS_PENDING,0) },null,null)==1)
    lastUri=uri.toString();lastName=source.name;return lastUri
   } catch(e:Exception) { resolver.delete(uri,null,null);throw e }
  }
  @Suppress("DEPRECATION")
  val directory=com.ckchoi.elibrary.common.GlobalConfig.downloadDir(context,"yes24")
  check(directory.isDirectory || directory.mkdirs())
  var target=File(directory,source.name);var index=1
  while(!target.createNewFile()) { target=File(directory,source.nameWithoutExtension+" ("+(index++)+")."+source.extension) }
  val temporary=File.createTempFile("yesss24-",".pending",directory)
  try {
   val count=FileOutputStream(temporary).use { output -> val count=source.inputStream().use { it.copyTo(output) };output.fd.sync();count }
   check(count==source.length());check(temporary.renameTo(target))
   android.media.MediaScannerConnection.scanFile(context,arrayOf(target.absolutePath),arrayOf(mime),null)
   lastUri=target.toURI().toString();lastName=target.name;return lastUri
  } catch(e:Exception) { temporary.delete();target.delete();throw e }
 }
}
