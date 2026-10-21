package com.ckchoi.ckreader

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.Binder
import android.os.Bundle
import android.os.Process
import com.ckchoi.elibrary.common.ArchiveRangeSource
import java.io.File
import java.security.MessageDigest

/** Private, authenticated page-by-page image provider; no account secrets in intents. */
class ReaderStreamProvider:ContentProvider() {
 companion object {
  const val AUTHORITY="com.ckchoi.ckreader.stream"
  fun bookUri(sourceId:String,bookId:String):Uri =
   Uri.Builder().scheme("content").authority(AUTHORITY).appendPath("book")
    .appendPath(sourceId).appendPath(bookId).build()
  fun archiveUri(sourceId:String,path:String,knownSize:Long=-1):Uri =
   Uri.Builder().scheme("content").authority(AUTHORITY).appendPath("archive")
    .appendPath(sourceId).appendPath(path).apply {
     if(knownSize in 22..10_000_000_000L)
      appendQueryParameter("size",knownSize.toString())
    }.build()
  fun pageUri(sourceId:String,bookId:String,index:Int):Uri =
   Uri.Builder().scheme("content").authority(AUTHORITY).appendPath("page")
    .appendPath(sourceId).appendPath(bookId).appendPath(index.toString()).build()
 }
 override fun onCreate()=true
 override fun query(uri:Uri,projection:Array<String>?,selection:String?,selectionArgs:Array<String>?,sortOrder:String?):Cursor?=null
 override fun getType(uri:Uri):String?=when(uri.pathSegments.firstOrNull()) {
  "page"->"image/jpeg"
  "archive"->"application/zip"
  else->"application/octet-stream"
 }
 override fun insert(uri:Uri,values:ContentValues?):Uri?=null
 override fun delete(uri:Uri,selection:String?,selectionArgs:Array<String>?):Int=0
 override fun update(uri:Uri,values:ContentValues?,selection:String?,selectionArgs:Array<String>?):Int=0

 private fun hash(value:String)=MessageDigest.getInstance("SHA-256")
  .digest(value.toByteArray()).joinToString(""){"%02x".format(it)}
 private fun cacheFile(sourceId:String,bookId:String,page:Int):File {
  val root=File(context!!.cacheDir,"bookoasis-pages")
  val source=LibraryRepository(context!!).sources().firstOrNull{
   it.id==sourceId&&it.type=="bookoasis"}?:error("북오아시스 저장소를 찾을 수 없습니다.")
  val identity=hash(sourceId+"|"+source.location+"|"+source.user+"|"+source.secret+"|"+source.extra)
  val folder=File(root,identity.take(24)+"-"+bookId).apply {mkdirs()}
  return File(folder,"page-"+page+".img")
 }
 private val pageLocks=Array(32){Any()}
 private fun loadPage(sourceId:String,bookId:String,page:Int):File {
  val target=cacheFile(sourceId,bookId,page)
  val lock=pageLocks[(target.absolutePath.hashCode() and Int.MAX_VALUE)%pageLocks.size]
  synchronized(lock) {
   if(target.isFile&&target.length() in 10L..(96L*1024*1024) &&
      System.currentTimeMillis()-target.lastModified()<24L*60*60*1000) {
    target.setLastModified(System.currentTimeMillis())
    return target
   }
   val source=LibraryRepository(context!!).sources().firstOrNull {
    it.id==sourceId&&it.type=="bookoasis"
   }?:error("북오아시스 저장소를 찾을 수 없습니다.")
   val temporary=File(target.parentFile,target.name+".partial")
   try {
    BookOasisOpdsAccess.client(source).pageToFile(bookId,page,temporary)
    // Refuse an HTML/JSON error page even if the server declared image/jpeg.
    java.io.FileInputStream(temporary).use {stream->
     val magic=ByteArray(8)
     require(stream.read(magic)>=8 &&
      magic[0]!= '{'.code.toByte() && magic[0]!= '<'.code.toByte() &&
      (magic[0]==0xFF.toByte() && magic[1]==0xD8.toByte() ||
       magic[0]==0x89.toByte() && magic[1]=='P'.code.toByte() ||
       magic[0]=='R'.code.toByte() && magic[1]=='I'.code.toByte() ||
       magic[0]=='G'.code.toByte() && magic[1]=='I'.code.toByte())
     ){"북오아시스 이미지 형식이 올바르지 않습니다."}
    }
    if(!temporary.renameTo(target)) {
     // Avoid exposing a partial page to readers if atomic rename fails.
     target.delete()
     require(temporary.renameTo(target)){"도서 페이지 캐시 저장 실패"}
    }
    prune(File(context!!.cacheDir,"bookoasis-pages"),target,512L*1024*1024)
   }finally {temporary.delete()}
   return target
  }
 }
 private fun prune(root:File,protected:File,max:Long) {
  runCatching {
   val files=root.listFiles()?.flatMap{it.listFiles()?.toList().orEmpty()}
    ?.filter{it.isFile&&it.name.endsWith(".img")&&it!=protected}
    ?.sortedBy{it.lastModified()}?:return
   var length=files.sumOf{it.length()}+protected.length()
   for(file in files) {
    if(length<=max)break
    val size=file.length()
    if(file.delete())length-=size
   }
  }
 }
 private fun archiveLocation(uri:Uri,repo:LibraryRepository):Pair<LibrarySource,String> {
  val parts=uri.pathSegments
  require(parts.size==3&&parts[0]=="archive"){"지원하지 않는 압축파일 URI"}
  val selected=repo.sources().firstOrNull{it.id==parts[1]&&it.type in setOf("webdav","ftp","sftp","drive","bookoasis")}
   ?:error("저장소 정보를 찾을 수 없습니다.")
  val path=parts[2]
  require(path.isNotEmpty()&&(path.endsWith(".zip",true)||path.endsWith(".cbz",true)))
  if(selected.type=="bookoasis")
   require(Regex("^bookoasis:[0-9]+\\.(zip|cbz)$",RegexOption.IGNORE_CASE).matches(path))
    {"Book Oasis ZIP/CBZ 경로가 올바르지 않습니다."}
  else require(path.split('/').none{it.isEmpty()||it=="."||it==".."||'\\' in it})
  return selected to path
 }
 override fun call(method:String,arg:String?,extras:Bundle?):Bundle? {
  if(method!=ArchiveRangeSource.SIZE&&method!=ArchiveRangeSource.READ)return super.call(method,arg,extras)
  check(Binder.getCallingUid()==Process.myUid()) {"Private archive provider"}
  val uri=Uri.parse(requireNotNull(arg))
  require(ArchiveRangeSource.matches(uri))
  val repo=LibraryRepository(context!!)
  val (source,path)=archiveLocation(uri,repo)
  // Defensive compatibility for a reader Activity restored with an old
  // content://archive intent after upgrade: do not issue another native GDS
  // Range RPC that could wait indefinitely. The reader catches this failure
  // and uses this same provider's whole-file openFile pipe as its fallback.
  if(source.type=="drive")
   throw java.io.IOException("Google Drive/GDS ZIP·CBZ는 원격 인덱스 대신 파일 전체 다운로드를 사용합니다.")
  return when(method) {
   ArchiveRangeSource.SIZE->Bundle().apply {
    // A scanned Google Drive/GDS catalog already contains the exact file size.
    // Re-stat through native rclone can block the reader for large files.
    val known=uri.getQueryParameter("size")?.toLongOrNull()
     ?.takeIf{it in 22..10_000_000_000L&&source.type=="drive"}
    putLong("length",known?:repo.archiveSize(source,path))
   }
   else->{
    requireNotNull(extras)
    val offset=extras.getLong("offset",-1)
    val count=extras.getInt("count",-1)
    require(offset>=0&&count in 1..ArchiveRangeSource.MAX_READ&&offset<=10_000_000_000L-count)
    Bundle().apply{putByteArray("bytes",repo.archiveRange(source,path,offset,count))}
   }
  }
 }
 override fun openFile(uri:Uri,mode:String):ParcelFileDescriptor {
  require(mode=="r"){"읽기 전용 파일 공급자입니다."}
  if(uri.authority==AUTHORITY&&uri.pathSegments.firstOrNull()=="archive") {
   val repo=LibraryRepository(context!!)
   val (source,path)=archiveLocation(uri,repo)
   require(source.type=="drive" || source.type=="bookoasis"){
    "이 저장소의 ZIP 원본 스트리밍은 지원하지 않습니다."
   }
   return openPipeHelper(uri,"application/zip",null,null,
    PipeDataWriter<Unit>{output,_,_,_,_->
     try {
      ParcelFileDescriptor.AutoCloseOutputStream(output).use{stream->
       if(source.type=="drive")DriveAccess(context!!,repo).streamTo(source,path,stream)
       else BookOasisOpdsAccess.client(source)
        .streamBook(Regex("^bookoasis:([0-9]+)\\.").find(path)?.groupValues?.get(1)
         ?:error("Book Oasis 파일 경로 오류"),stream)
      }
     }catch(error:Exception) {
      android.util.Log.w("ckReader","Archive pipe failed",error)
     }
    })
  }
  val parts=uri.pathSegments
  if(uri.authority==AUTHORITY && parts.size==3 && parts[0]=="book") {
   val sourceId=parts[1]
   val id=parts[2]
   require(id.toLongOrNull()!=null){"북오아시스 도서 ID가 올바르지 않습니다."}
   val source=LibraryRepository(context!!).sources().firstOrNull{
    it.id==sourceId&&it.type=="bookoasis"
   }?:error("북오아시스 저장소를 찾을 수 없습니다.")
   return openPipeHelper(uri,"application/zip",null,null,
    PipeDataWriter<Unit>{output,_,_,_,_->
     try {
      ParcelFileDescriptor.AutoCloseOutputStream(output).use{stream->
       BookOasisOpdsAccess.client(source).streamBook(id,stream)
      }
     }catch(error:Exception) {
      android.util.Log.w("ckReader","Book pipe failed",error)
     }
    })
  }
  require(parts.size==4&&parts[0]=="page"&&uri.authority==AUTHORITY){"올바르지 않은 페이지 URI"}
  val id=parts[2]
  val page=parts[3].toIntOrNull()
  require(id.toLongOrNull()!=null&&page!=null&&page in 0..50_000){"올바르지 않은 책 또는 페이지 번호"}
  // Data is sent through a pipe; loading the whole ZIP/CBZ archive is never required.
  return openPipeHelper(uri,"image/*",null,null,
   PipeDataWriter<Unit>{output,_,_,_,_ ->
    try {
     ParcelFileDescriptor.AutoCloseOutputStream(output).use{stream->
      loadPage(parts[1],id,page).inputStream().use {image->
       image.copyTo(stream,bufferSize=64*1024)
      }
     }
    }catch(error:Exception) {
     android.util.Log.w("ckReader","Page pipe failed (page $page)",error)
    }
   })
 }
}
