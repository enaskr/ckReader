package com.ckchoi.ckreader

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URL
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Filesystem access shared by YAML reading, exporting and library scans. */
internal class FolderYamlStorage(private val context:Context,repository:LibraryRepository) {
 data class Entry(val name:String,val path:String,val directory:Boolean,val sizeBytes:Long=-1L,val id:String="")
 private val drive=DriveAccess(context,repository)
 companion object {
  private val http=OkHttpClient.Builder().connectTimeout(20,TimeUnit.SECONDS)
   .readTimeout(30,TimeUnit.SECONDS).callTimeout(45,TimeUnit.SECONDS).build()
  fun relative(path:String):String {
   val parts=path.split('/').filter{it.isNotEmpty()}
   require(parts.none{it=="."||it==".."||'\\' in it||'\u0000' in it}) {"올바르지 않은 저장소 경로"}
   return parts.joinToString("/")
  }
  fun child(folder:String,name:String)=if(folder.isEmpty())name else "$folder/$name"
  fun hasManifest(names:Collection<String>)=names.any{it.equals("kavita.yaml",true)||it.equals("kavita.yml",true)}
  fun ftpClient(source:LibrarySource):FTPClient {
   val url=URL(source.location)
   val ftp=FTPClient().apply{controlEncoding="UTF-8";autodetectUTF8=true;connectTimeout=20_000;defaultTimeout=30_000;setDataTimeout(java.time.Duration.ofSeconds(30))}
   try {
    ftp.connect(url.host,if(url.port>0)url.port else 21)
    check(ftp.login(source.user,source.secret)){"FTP 로그인 실패"}
    ftp.enterLocalPassiveMode();check(ftp.setFileType(FTP.BINARY_FILE_TYPE))
    return ftp
   }catch(e:Exception){runCatching{ftp.disconnect()};throw e}
  }
  private fun close(ftp:FTPClient){runCatching{ftp.logout()};runCatching{ftp.disconnect()}}
  fun ftpPath(source:LibrarySource,path:String):String=Uri.decode(URL(source.location).path).trimEnd('/')+"/"+relative(path)
  fun limited(input:InputStream,max:Int=FolderYamlMetadata.MAX_BYTES):ByteArray=input.use{stream->
   val output=ByteArrayOutputStream();val buffer=ByteArray(8192)
   while(true){val n=stream.read(buffer);if(n<0)break;require(output.size()+n<=max){"메타데이터 파일이 너무 큽니다."};output.write(buffer,0,n)}
   output.toByteArray()
  }
 }
 fun localDirectory(source:LibrarySource,path:String):DocumentFile {
  var directory=DocumentFile.fromTreeUri(context,Uri.parse(source.location))?:error("폴더 접근 불가")
  for(part in relative(path).split('/').filter{it.isNotEmpty()})
   directory=directory.findFile(part)?:error("저장소 폴더를 찾을 수 없습니다.")
  check(directory.isDirectory){"폴더가 아닌 경로입니다."}
  return directory
 }
 fun localEntries(directory:DocumentFile,path:String):List<Entry> = directory.listFiles().mapNotNull{item->
  val name=item.name?:return@mapNotNull null
  Entry(name,child(path,name),item.isDirectory,item.length().takeIf{it>=0}?:-1L,item.uri.toString())
 }
 private fun request(source:LibrarySource,url:String)=Request.Builder().url(url).apply{
  if(source.user.isNotEmpty())header("Authorization",Credentials.basic(source.user,source.secret,Charsets.UTF_8))
 }
 fun list(source:LibrarySource,folder:String):List<Entry> {
  val path=relative(folder)
  require(LibraryVisibility.visible(path)){"숨긴 폴더입니다."}
  val entries=when(source.type) {
   "local"->localEntries(localDirectory(source,path),path)
   "drive"->drive.list(source,path).map{Entry(it.name,it.path,it.directory,it.sizeBytes,it.id)}
   "webdav"->{
    val endpoint=WebDavListing.url(source.location,path,true)
    val query=request(source,endpoint).header("Depth","1").method("PROPFIND",
     "<d:propfind xmlns:d='DAV:'><d:allprop/></d:propfind>".toRequestBody("application/xml".toMediaType())).build()
    http.newCall(query).execute().use{response->
     check(response.isSuccessful){"WebDAV HTTP ${response.code}"}
     val body=response.body?:error("WebDAV 응답 없음")
     body.charStream().use{WebDavListing.parse(it,response.request.url.toString())}
      .map{Entry(it.name,child(path,it.name),it.directory,it.sizeBytes)}
    }
   }
   "sftp"->SftpStorage().list(source,path).map{Entry(it.name,child(path,it.name),it.directory,it.size)}
   "ftp"->{
    val ftp=ftpClient(source)
    try{
     val files=ftp.listFiles(ftpPath(source,path))
     // Apache FTPClient can return an empty array for both a genuinely empty
     // directory and a rejected LIST request (550). Do not hide a folder when
     // its contents could not be verified.
     check(ftp.replyCode in 200..299){"FTP 폴더 목록 조회 실패 (HTTP-like reply "+ftp.replyCode+")"}
     files.map{Entry(it.name,child(path,it.name),it.isDirectory,it.size.takeIf{n->n>=0}?:-1L)}
    } finally{close(ftp)}
   }
   else->error("파일 폴더를 제공하지 않는 저장소입니다.")
  }
  return entries.filter{it.name.isNotBlank()&&'/' !in it.name&&'\\' !in it.name&&LibraryVisibility.visible(it.name)}
 }
 fun read(source:LibrarySource,item:Entry):ByteArray {
  require(!item.directory&&FolderYamlMetadata.readableSize(item.sizeBytes))
  relative(item.path)
  return when(source.type) {
   "local"->limited(context.contentResolver.openInputStream(Uri.parse(item.id))?:error("YAML 읽기 권한 없음"))
   "drive"->drive.smallFile(source,item.path,FolderYamlMetadata.MAX_BYTES.toLong())
   "webdav"->http.newCall(request(source,WebDavListing.url(source.location,item.path)).build()).execute().use{response->
    check(response.isSuccessful){"WebDAV HTTP ${response.code}"}
    limited(response.body?.byteStream()?:error("YAML 응답 없음"))
   }
   "sftp"->SftpStorage().read(source,item.path,FolderYamlMetadata.MAX_BYTES)
   "ftp"->{
    val ftp=ftpClient(source)
    try{
     val input=ftp.retrieveFileStream(ftpPath(source,item.path))?:error("FTP YAML 읽기 실패")
     val bytes=try{limited(input)}finally{check(ftp.completePendingCommand()){"FTP YAML 수신 실패"}}
     bytes
    }finally{close(ftp)}
   }
   else->error("YAML 파일을 읽을 수 없는 저장소입니다.")
  }
 }
 /** Existing manifests are never opened for writing. Permission denial is a normal failure. */
 fun canOrganize(source:LibrarySource,folder:String,moveChildren:Boolean):Boolean =
  !source.readOnly&&(source.type!="drive"||drive.canWriteFolder(source,folder,moveChildren))
 fun createOnly(source:LibrarySource,folder:String,bytes:ByteArray):Boolean {
  if(source.readOnly)return false
  val path=relative(folder)
  require(bytes.size in 1..FolderYamlMetadata.MAX_BYTES&&LibraryVisibility.visible(path))
  if(hasManifest(list(source,path).map{it.name}))return false
  return when(source.type) {
   "local"->{
    val dir=localDirectory(source,path)
    if(!dir.canWrite())return false
    val draft=dir.createFile("application/yaml",".ckreader-kavita-${UUID.randomUUID()}.yaml")?:return false
    var complete=false
    try{
     (context.contentResolver.openOutputStream(draft.uri,"wt")?:error("YAML 쓰기 권한 없음")).use{it.write(bytes)}
     if(hasManifest(dir.listFiles().mapNotNull{it.name}))return false
     if(!draft.renameTo("kavita.yaml")||draft.name!="kavita.yaml")return false
     complete=true;true
    }finally{if(!complete)draft.delete()}
   }
   "drive"->drive.createYamlOnly(source,path,bytes)
   "webdav"->{
    val put=request(source,WebDavListing.url(source.location,child(path,"kavita.yaml")))
     .header("If-None-Match","*").put(bytes.toRequestBody("application/yaml; charset=utf-8".toMediaType())).build()
    http.newBuilder().followRedirects(false).followSslRedirects(false).build().newCall(put).execute().use{it.isSuccessful}
   }
   "ftp"->{
    val ftp=ftpClient(source)
    val remote=ftpPath(source,path).trimEnd('/')
    val temporary="$remote/.ckreader-kavita-${UUID.randomUUID()}.tmp"
    try{
     if(hasManifest(ftp.listFiles(remote).map{it.name}))return false
     if(!ftp.storeFile(temporary,bytes.inputStream()))return false
     if(hasManifest(ftp.listFiles(remote).map{it.name}))return false
     ftp.rename(temporary,"$remote/kavita.yaml")
    }finally{runCatching{ftp.deleteFile(temporary)};close(ftp)}
   }
   else->false
  }
 }
 fun makeDirectory(source:LibrarySource,folder:String):Boolean {
  if(source.readOnly)return false
  val path=relative(folder);require(path.isNotEmpty()&&LibraryVisibility.visible(path))
  val parent=path.substringBeforeLast('/',"");val name=path.substringAfterLast('/')
  list(source,parent).firstOrNull{it.name.equals(name,true)}?.let{return it.directory&&it.name==name}
  return when(source.type) {
   "local"->{val dir=localDirectory(source,parent);dir.canWrite()&&dir.createDirectory(name)!=null}
   "drive"->{drive.makeDirectory(source,path);true}
   "webdav"->http.newCall(request(source,WebDavListing.url(source.location,path,true))
    .method("MKCOL",ByteArray(0).toRequestBody()).build()).execute().use{it.isSuccessful}
   "ftp"->{val ftp=ftpClient(source);try{ftp.makeDirectory(ftpPath(source,path))}finally{close(ftp)}}
   else->false
  }
 }
 /** Native same-storage move; never replace a destination, including after a timeout. */
 fun moveOnly(source:LibrarySource,from:String,to:String):Boolean {
  if(source.readOnly)return false
  val old=relative(from);val target=relative(to)
  require(old!=target&&LibraryVisibility.visible(old)&&LibraryVisibility.visible(target))
  val oldParent=old.substringBeforeLast('/',"");val newParent=target.substringBeforeLast('/',"")
  val oldItem=list(source,oldParent).firstOrNull{it.path==old&&!it.directory}?:return false
  if(list(source,newParent).any{it.name.equals(target.substringAfterLast('/'),true)})return false
  runCatching{when(source.type) {
   "local"->{
    val original=localDirectory(source,oldParent);val destination=localDirectory(source,newParent)
    check(original.canWrite()&&destination.canWrite())
    check(old.substringAfterLast('/')==target.substringAfterLast('/'))
    check(DocumentsContract.moveDocument(context.contentResolver,Uri.parse(oldItem.id),original.uri,destination.uri)!=null)
   }
   "drive"->drive.moveOnly(source,old,target)
   "webdav"->http.newBuilder().followRedirects(false).build().newCall(request(source,WebDavListing.url(source.location,old))
    .header("Destination",WebDavListing.url(source.location,target)).header("Overwrite","F").method("MOVE",null).build())
    .execute().use{check(it.isSuccessful){"WebDAV MOVE HTTP ${it.code}"}}
   "ftp"->{val ftp=ftpClient(source);try{check(ftp.rename(ftpPath(source,old),ftpPath(source,target)))}finally{close(ftp)}}
   else->error("파일 이동을 지원하지 않습니다.")
  }}
  val after=list(source,newParent).firstOrNull{it.path==target&&!it.directory}
  return after!=null&&(oldItem.sizeBytes<0||after.sizeBytes==oldItem.sizeBytes)&&list(source,oldParent).none{it.path==old}
 }
}
