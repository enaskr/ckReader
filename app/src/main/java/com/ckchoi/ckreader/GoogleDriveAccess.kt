package com.ckchoi.ckreader

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

/** Drive v3 transport authenticated exclusively by Google Play services. */
internal class GoogleDriveAccess(
 private val authorize:(LibrarySource)->String,
 private val open:(String)->HttpURLConnection,
 private val writes:OkHttpClient
) {
 constructor(authorize:(LibrarySource)->String,open:(String)->HttpURLConnection):this(
  authorize,open,OkHttpClient.Builder().followRedirects(false).build())
 constructor(context:Context):this(
  {source->GoogleDriveSignIn.token(context,
   JSONObject(source.extra).optString(GoogleDriveSignIn.ACCOUNT_KEY),
   DriveWritePolicy.googleWriteScope(source.extra))},
  {url->URL(url).openConnection() as HttpURLConnection}
 )
 private val api="https://www.googleapis.com/drive/v3/"
 private val uploadApi="https://www.googleapis.com/upload/drive/v3/"
 private val folderMime="application/vnd.google-apps.folder"
 private fun enc(value:String)=URLEncoder.encode(value,"UTF-8")
 private fun options(source:LibrarySource)=JSONObject(source.extra)

 private fun connection(source:LibrarySource,path:String,range:String?=null):HttpURLConnection {
  check(!BuildConfig.LITE){"Google Drive 직접 연결은 Lite 버전에서 사용할 수 없습니다."}
  val auth=authorize(source)
  return open(api+path).apply {
   connectTimeout=15000;readTimeout=45000;instanceFollowRedirects=false
   setRequestProperty("Authorization","Bearer "+auth)
   range?.let{setRequestProperty("Range",it)}
  }
 }
 private fun check(conn:HttpURLConnection):Int {
  val code=conn.responseCode
  require(code in 200..299){when(code){
   401->"Google 로그인 인증이 만료되었습니다. 계정을 다시 연결하세요."
   403->"Google Drive 접근 권한이 없습니다."
   404->"Google Drive 폴더 또는 파일을 찾지 못했습니다."
   else->"Google Drive API 요청 실패 (HTTP $code)"
  }}
  return code
 }
 private fun readBounded(input:java.io.InputStream,limit:Int):ByteArray {
  val out=java.io.ByteArrayOutputStream(minOf(limit,32768))
  val buffer=ByteArray(minOf(limit,32768))
  while(out.size()<limit){
   val n=input.read(buffer,0,minOf(buffer.size,limit-out.size()))
   if(n<0)break
   if(n>0)out.write(buffer,0,n)
  }
  return out.toByteArray()
 }
 private fun json(source:LibrarySource,path:String):JSONObject {
  val conn=connection(source,path)
  try {
   check(conn)
   conn.inputStream.use { input->
    val bytes=readBounded(input,8*1024*1024+1)
    require(bytes.size<=8*1024*1024){"Google Drive 목록 응답이 너무 큽니다."}
    return JSONObject(String(bytes,Charsets.UTF_8))
   }
  } finally{conn.disconnect()}
 }
 private fun writeJson(source:LibrarySource,method:String,path:String,body:RequestBody?,
  upload:Boolean=false):JSONObject {
  check(!BuildConfig.LITE){"Google Drive 직접 연결은 Lite 버전에서 사용할 수 없습니다."}
  check(DriveWritePolicy.googleWriteScope(source.extra)){
   "Google Drive 쓰기 권한이 없습니다. 저장소를 다시 연결하세요."}
  val request=Request.Builder().url((if(upload)uploadApi else api)+path)
   .header("Authorization","Bearer "+authorize(source))
   .header("Accept","application/json").method(method,body).build()
  writes.newCall(request).execute().use{response->
   require(response.isSuccessful){when(response.code){
    401->"Google 로그인 인증이 만료되었습니다. 계정을 다시 연결하세요."
    403->"Google Drive 쓰기 권한이 없습니다. 폴더 권한을 확인하세요."
    404->"Google Drive 폴더 또는 파일을 찾지 못했습니다."
    else->"Google Drive 쓰기 실패 (HTTP ${response.code})"
   }}
   val bytes=response.body?.byteStream()?.use{readBounded(it,1_000_001)}?:byteArrayOf()
   require(bytes.size<=1_000_000){"Google Drive 쓰기 응답이 너무 큽니다."}
   return if(bytes.isEmpty())JSONObject() else JSONObject(String(bytes,Charsets.UTF_8))
  }
 }
 private fun metadata(source:LibrarySource,id:String):JSONObject=json(source,
  "files/"+enc(id)+"?supportsAllDrives=true&fields="+enc(
   "id,name,mimeType,trashed,parents,size,driveId,capabilities(canAddChildren,canRemoveChildren,canMoveChildrenWithinDrive,canMoveItemWithinDrive,canTrash)"))
 private fun folderId(source:LibrarySource,path:String):String {
  val id=resolve(source,path).getString("id")
  return if(id=="root")metadata(source,id).getString("id") else id
 }
 private fun quote(value:String)=value.replace("\\","\\\\").replace("'","\\'")
 private fun root(source:LibrarySource):String {
  val config=options(source)
  return config.optString("root_folder_id").ifBlank{config.optString("team_drive").ifBlank{"root"}}
 }
 private fun children(source:LibrarySource,parent:String,name:String?=null,foldersOnly:Boolean=false):List<JSONObject> {
  val result=ArrayList<JSONObject>()
  var page=""
  val seen=HashSet<String>()
  do {
   val query="'"+quote(parent)+"' in parents and trashed = false"+
    (name?.let{" and name = '"+quote(it)+"'"}?:"")+
    (if(foldersOnly)" and mimeType = '$folderMime'" else "")
   var path="files?q="+enc(query)+"&pageSize=1000&supportsAllDrives=true&includeItemsFromAllDrives=true"+
    "&fields="+enc("nextPageToken,files(id,name,mimeType,size,shortcutDetails(targetId,targetMimeType))")
   val shared=options(source).optString("team_drive")
   if(shared.isNotBlank())path+="&corpora=drive&driveId="+enc(shared)
   if(page.isNotBlank())path+="&pageToken="+enc(page)
   val data=json(source,path)
   val files=data.optJSONArray("files")
   if(files!=null)for(i in 0 until files.length())result.add(files.getJSONObject(i))
   page=data.optString("nextPageToken")
   require(page.isBlank()||seen.add(page)){"Google Drive 목록 페이지가 반복됩니다."}
   if(Thread.currentThread().isInterrupted)throw java.io.InterruptedIOException("Google Drive 조회 취소")
  }while(page.isNotBlank())
  return result
 }
 private fun effective(entry:JSONObject):JSONObject {
  val shortcut=entry.optJSONObject("shortcutDetails")?:return entry
  return JSONObject(entry.toString()).put("id",shortcut.getString("targetId"))
   .put("mimeType",shortcut.getString("targetMimeType"))
 }
 private fun resolve(source:LibrarySource,path:String):JSONObject {
  val segments=(source.location.trim('/')+"/"+path.trim('/')).trim('/').split('/').filter{it.isNotEmpty()}
  require(segments.none{it=="."||it==".."||'\u0000' in it}){"잘못된 Google Drive 경로"}
  var current=JSONObject().put("id",root(source)).put("mimeType",folderMime)
  for((index,name) in segments.withIndex()){
   val matches=children(source,current.getString("id"),name,index<segments.lastIndex)
   require(matches.size==1){if(matches.isEmpty())"Google Drive 경로를 찾지 못했습니다." else "같은 이름의 항목이 여러 개입니다. 고유한 이름을 사용하세요."}
   current=effective(matches.single())
  }
  return current
 }
 fun list(source:LibrarySource,folder:String,foldersOnlyWithIds:Boolean=false):List<RcloneBridge.Item> {
  val parent=resolve(source,folder).getString("id")
  return children(source,parent,foldersOnly=foldersOnlyWithIds).mapNotNull{raw->
   val name=raw.optString("name")
   if(name.isBlank()||name in setOf(".","..")||'/' in name||'\\' in name)return@mapNotNull null
   val item=effective(raw)
   RcloneBridge.Item(name,if(folder.isEmpty())name else folder.trimEnd('/')+"/"+name,
    item.optString("mimeType")==folderMime,item.getString("id"),item.optLong("size",-1))
  }
 }
 private fun file(source:LibrarySource,path:String):JSONObject {
  val item=resolve(source,path)
  require(item.optString("mimeType")!=folderMime){"폴더를 파일로 읽을 수 없습니다."}
  return json(source,"files/"+enc(item.getString("id"))+"?supportsAllDrives=true&fields=id,size")
 }
 fun archiveSize(source:LibrarySource,path:String):Long {
  val size=file(source,path).optLong("size",-1)
  require(size>=0){"Google Drive 파일 크기가 없습니다."}
  return size
 }
 fun archiveRange(source:LibrarySource,path:String,offset:Long,length:Int):ByteArray {
  require(offset>=0&&length in 1..2_097_152)
  val id=resolve(source,path).getString("id")
  val conn=connection(source,"files/"+enc(id)+"?alt=media&supportsAllDrives=true","bytes=$offset-"+(offset+length-1))
  try{
   if(conn.responseCode==416 && offset>=archiveSize(source,path))return byteArrayOf()
   require(check(conn)==206){"Google Drive 부분 읽기 응답이 올바르지 않습니다."}
   val contentRange=conn.getHeaderField("Content-Range").orEmpty()
   require(contentRange.startsWith("bytes $offset-")){"Google Drive 부분 읽기 위치가 다릅니다."}
   return conn.inputStream.use{readBounded(it,length)}
  }finally{conn.disconnect()}
 }
 private fun transfer(source:LibrarySource,path:String,out:OutputStream,maxBytes:Long,onProgress:(String,Long,Long)->Unit) {
  val info=file(source,path)
  val total=info.optLong("size",-1)
  require(total<=maxBytes){"Google Drive 파일이 너무 큽니다."}
  val conn=connection(source,"files/"+enc(info.getString("id"))+"?alt=media&supportsAllDrives=true")
  try{
   check(conn)
   var done=0L
   conn.inputStream.use{input->
    val buffer=ByteArray(128*1024)
    while(true){
     if(Thread.currentThread().isInterrupted)throw java.io.InterruptedIOException("Google Drive 전송 취소")
     val n=input.read(buffer);if(n<0)break
     done+=n;require(done<=maxBytes){"Google Drive 파일이 너무 큽니다."}
     out.write(buffer,0,n);onProgress("Google Drive 파일 수신 중",done,total)
    }
   }
   require(total<0||done==total){"Google Drive 수신 크기가 다릅니다."}
  }finally{conn.disconnect()}
 }
 fun streamTo(source:LibrarySource,path:String,out:OutputStream)=transfer(source,path,out,20L*1024*1024*1024){_,_,_->}
 fun smallFile(source:LibrarySource,path:String,maxBytes:Long):ByteArray {
  val out=java.io.ByteArrayOutputStream()
  transfer(source,path,out,maxBytes){_,_,_->}
  return out.toByteArray()
 }
 fun copyTo(source:LibrarySource,path:String,target:File,expectedSize:Long=-1,onProgress:(String,Long,Long)->Unit={_,_,_->}) {
  val parent=target.absoluteFile.parentFile!!
  require(parent.isDirectory||parent.mkdirs())
  val stage=File.createTempFile("google-drive-",".part",parent)
  try {
   stage.outputStream().use{out->transfer(source,path,out,20L*1024*1024*1024,onProgress);out.fd.sync()}
   require(stage.length()>0 && (expectedSize<=0||stage.length()==expectedSize)){"Google Drive 수신 크기가 다릅니다."}
   if(!stage.renameTo(target))stage.copyTo(target,overwrite=true)
  }finally{stage.delete()}
 }

 fun canWriteFolder(source:LibrarySource,folder:String,moveChildren:Boolean=false):Boolean {
  if(source.readOnly||!DriveWritePolicy.googleWriteScope(source.extra))return false
  return runCatching{
   val id=folderId(source,folder)
   DriveWritePolicy.folderWritable(metadata(source,id),moveChildren)
  }.getOrDefault(false)
 }

 private fun create(source:LibrarySource,name:String,parentId:String,mimeType:String,
  bytes:ByteArray?=null):JSONObject {
  val meta=JSONObject().put("name",name).put("mimeType",mimeType)
   .put("parents",JSONArray().put(parentId))
  val fields=enc("id,name,mimeType,parents,size")
  return if(bytes==null)writeJson(source,"POST",
   "files?supportsAllDrives=true&fields=$fields",
   meta.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
  else {
   val body=MultipartBody.Builder().setType("multipart/related".toMediaType())
    .addPart(meta.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
    .addPart(bytes.toRequestBody(mimeType.toMediaType())).build()
   writeJson(source,"POST","files?uploadType=multipart&supportsAllDrives=true&fields=$fields",
    body,upload=true)
  }
 }
 private fun deleteCreated(source:LibrarySource,id:String) {
  writeJson(source,"DELETE","files/"+enc(id)+"?supportsAllDrives=true",null)
 }
 fun createYamlOnly(source:LibrarySource,folder:String,bytes:ByteArray):Boolean {
  require(bytes.size in 1..FolderYamlMetadata.MAX_BYTES)
  if(!canWriteFolder(source,folder))return false
  val parent=folderId(source,folder)
  fun manifests()=children(source,parent).filter{it.optString("name").let{name->
   name.equals("kavita.yaml",true)||name.equals("kavita.yml",true)}}
  if(manifests().isNotEmpty())return false
  val staged=create(source,".ckreader-kavita-${UUID.randomUUID()}.yaml",parent,
   "application/yaml",bytes)
  val id=staged.getString("id")
  var complete=false
  try {
   if(staged.has("size"))check(staged.optLong("size")==bytes.size.toLong()){
    "Google Drive YAML 크기 확인 실패"}
   if(manifests().isNotEmpty())return false
   val renamed=writeJson(source,"PATCH","files/"+enc(id)+
    "?supportsAllDrives=true&fields="+enc("id,name,parents"),
    JSONObject().put("name","kavita.yaml").toString()
     .toRequestBody("application/json; charset=utf-8".toMediaType()))
   check(renamed.optString("id")==id&&renamed.optString("name")=="kavita.yaml"){
    "Google Drive YAML 이름 확인 실패"}
   val found=manifests()
   if(found.size!=1||found.single().optString("id")!=id)return false
   complete=true
   return true
  }finally{
   if(!complete)runCatching{deleteCreated(source,id)}
  }
 }
 fun makeDirectory(source:LibrarySource,path:String) {
  val folder=FolderYamlStorage.relative(path)
  require(folder.isNotEmpty())
  val parentPath=folder.substringBeforeLast('/',"")
  check(canWriteFolder(source,parentPath)){"Google Drive 폴더 생성 권한이 없습니다."}
  val parent=folderId(source,parentPath)
  val name=folder.substringAfterLast('/')
  val existing=children(source,parent,name)
  if(existing.isNotEmpty()){
   check(existing.size==1&&effective(existing.single()).optString("mimeType")==folderMime){
    "같은 이름의 파일이 이미 있습니다."}
   return
  }
  val created=create(source,name,parent,folderMime)
  check(created.optString("id").isNotBlank()&&created.optString("mimeType")==folderMime){
   "Google Drive 폴더 생성 결과를 확인할 수 없습니다."}
 }
 fun moveOnly(source:LibrarySource,from:String,to:String) {
  val old=FolderYamlStorage.relative(from)
  val target=FolderYamlStorage.relative(to)
  require(old!=target)
  val oldParentPath=old.substringBeforeLast('/',"")
  val targetParentPath=target.substringBeforeLast('/',"")
  require(oldParentPath!=targetParentPath){"같은 폴더 내 이름 변경은 지원하지 않습니다."}
  check(canWriteFolder(source,oldParentPath,moveChildren=true)&&
   canWriteFolder(source,targetParentPath)){
   "Google Drive 도서 이동 권한이 없습니다."}
  val oldParent=folderId(source,oldParentPath)
  val targetParent=folderId(source,targetParentPath)
  val file=resolve(source,old)
  val id=file.getString("id")
  val details=metadata(source,id)
  check(details.optString("mimeType")!=folderMime&&DriveWritePolicy.fileMovable(details)&&
   details.optJSONArray("parents")?.let{parents->parents.length()==1&&parents.optString(0)==oldParent}==true){
   "Google Drive 도서 이동 권한 또는 원본 위치를 확인할 수 없습니다."}
  check(children(source,targetParent,target.substringAfterLast('/')).isEmpty()){
   "이동할 위치에 같은 이름의 도서가 이미 있습니다."}
  val changed=writeJson(source,"PATCH","files/"+enc(id)+
   "?addParents="+enc(targetParent)+"&removeParents="+enc(oldParent)+
   "&supportsAllDrives=true&fields="+enc("id,name,parents,size"),
   JSONObject().put("name",target.substringAfterLast('/')).toString()
    .toRequestBody("application/json; charset=utf-8".toMediaType()))
  check(changed.optString("id")==id&&changed.optString("name")==target.substringAfterLast('/')&&
   changed.optJSONArray("parents")?.let{parents->parents.length()==1&&parents.optString(0)==targetParent}==true){
   "Google Drive 도서 이동 결과를 확인할 수 없습니다."}
 }
}
