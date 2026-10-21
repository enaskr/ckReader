package com.ckchoi.ckreader

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Separately installed Android librclone from wiserain/rclone v1.75.2-338.
 *
 * The engine uses the Google Drive backend (including Shared Drives/team_drive).
 * The app's imported connection details remain in Android Keystore-encrypted
 * preferences. Its separate native rclone configuration is also password-encrypted.
 * All calls that modify global librclone configuration are serialized.
 */
internal class RcloneBridge(private val context: Context, private val repository: LibraryRepository) {
 data class Item(val name:String, val path:String, val directory:Boolean, val id:String,
                 val sizeBytes:Long=-1L)

 companion object {
  private val lock=Any()
  private var initializedSession:String?=null
  private val configured=HashMap<String,String>()
 }

 private val extension=RclonePluginClient(context)

 private fun hash(text:String):String {
  val digest=MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
  return digest.joinToString("") { "%02x".format(it) }
 }

 private fun rpc(method:String,params:JSONObject=JSONObject()):JSONObject {
  val response=extension.rpc(method,params)
  if(response.status!=200L) {
   val output=response.output.orEmpty().lowercase()
   val message=when {
    "invalid_grant" in output || "invalid credentials" in output ->
     "Google Drive 인증 갱신에 실패했습니다. rclone에서 다시 인증한 설정을 가져오세요."
    "shared drive not found" in output ->
     "공유 드라이브를 찾지 못했습니다. team_drive ID와 접근 권한을 확인하세요."
    "permission" in output || "forbidden" in output || "403" in output ->
     "Google Drive에 접근할 권한이 없습니다."
    "not found" in output || "404" in output ->
     "선택한 폴더 또는 파일이 없습니다."
    else -> "rclone "+method+" 요청이 실패했습니다 (HTTP "+response.status+")."
   }
   error(message)
  }
  return JSONObject(response.output.orEmpty().ifBlank { "{}" })
 }

 private fun remoteName(source:LibrarySource):String="ckreader_"+hash(source.id).take(24)

 private fun pathPrefix(source:LibrarySource):String {
  val value=source.location.trim('/')
  require(value.split('/').none { it==".." || it=="." || '\u0000' in it }) {
   "Google Drive 기본 폴더 경로가 올바르지 않습니다."
  }
  return value
 }

 private fun ensureReady() {
  val session=extension.sessionId()
  if(initializedSession==session)return
  extension.initialize(repository.rcloneMasterPassword())
  configured.clear()
  initializedSession=session
 }

 private fun ensureRemote(source:LibrarySource) {
  ensureReady()
  val stored=runCatching { JSONObject(source.extra) }.getOrElse { error("rclone 연결 정보가 올바르지 않습니다.") }
  check(!stored.optBoolean(GoogleDriveSignIn.AUTH_KEY,false)) {
   "Google 로그인 저장소는 Google Drive 직접 연결을 사용해야 합니다."
  }
  val config=stored
  val isGds=config.optBoolean("ckreader_gds",false)
  val backend=config.optString("type","drive")
  require(backend=="drive" || (isGds && backend=="gds")) {
   "Google Drive 유형이 올바르지 않습니다. rclone.conf를 확인하세요."
  }
  val oauthValid=config.optString("token").isNotBlank() ||
   config.optString("service_account_file").isNotBlank()
  val gdsValid=RcloneGdsCredentials.custom(config)
  require(if(isGds)RcloneGdsCredentials.usable(config) else oauthValid) {
   if(isGds)"GDS 인증 정보가 없습니다. rclone.conf의 GDS 계정 또는 Google Drive token 설정을 확인하세요."
   else "Google Drive OAuth 토큰이 없습니다. rclone.conf를 다시 가져오세요."
  }
  val name=remoteName(source)
  val fingerprint=hash(source.type+"|"+source.location+"|"+config.toString())
  if(configured[name]==fingerprint)return

  // Only this source's deterministic remote is replaced. Do not enumerate
  // global remotes; Google login never enters the native engine.
  val parameters=RcloneDriveOptions.parameters(config,existing=false)
  rpc("config/create",JSONObject()
   .put("name",name).put("type",if(backend=="gds")"drive" else backend)
   .put("parameters",parameters)
   .put("opt",JSONObject().put("noObscure",true).put("noOutput",true).put("nonInteractive",true)))
  rpc("fscache/clear")
  configured[name]=fingerprint
 }

 private fun fsRoot(source:LibrarySource):String {
  val base=pathPrefix(source)
  return remoteName(source)+":"+(if(base.isEmpty())"" else base)
 }

 fun archiveSize(source:LibrarySource,path:String):Long = synchronized(lock) {
  ensureRemote(source)
  val size=extension.stat(fsRoot(source),path)
  require(size in 1..10_000_000_000L) {"원격 압축파일의 크기를 확인할 수 없습니다."}
  size
 }
 fun archiveRange(source:LibrarySource,path:String,offset:Long,length:Int):ByteArray = synchronized(lock) {
  ensureRemote(source)
  require(offset>=0&&length in 1..2_097_152) {"요청한 파일 범위가 올바르지 않습니다."}
  extension.readRange(fsRoot(source),path,offset,length)
 }
 fun version():String = synchronized(lock) {
  ensureReady()
  rpc("core/version").optString("version")
 }

 fun list(source:LibrarySource,folder:String,
  foldersOnlyWithIds:Boolean=false):List<Item> = synchronized(lock) {
  ensureRemote(source)
  val opt=JSONObject().put("noModTime",true).put("noMimeType",true)
   .put("showHash",false)
  if(foldersOnlyWithIds){
   // rclone RC operations/list exposes backend IDs and can filter files
   // server-side. This is the ID required for drive.root_folder_id, not Path.
   opt.put("dirsOnly",true).put("showOrigIDs",true)
  }
  val data=rpc("operations/list",JSONObject()
   .put("fs",fsRoot(source))
   .put("remote",folder)
   .put("opt",opt))
  val listing=data.optJSONArray("list") ?: JSONArray()
  (0 until listing.length()).mapNotNull { i ->
   val entry=listing.optJSONObject(i) ?: return@mapNotNull null
   val name=entry.optString("Name")
   val path=entry.optString("Path",if(folder.isEmpty())name else folder.trimEnd('/')+"/"+name)
   if(name.isBlank() || name=="." || name==".." || '/' in name || '\\' in name) {
    null
   } else Item(name,path,entry.optBoolean("IsDir"),
    entry.optString("ID").ifBlank{entry.optString("OrigID")},
    entry.optLong("Size",-1L).takeIf{it>=0}?:-1L)
  }
 }

 /**
  * Remove the short-lived rclone remote created for folder picking. The
  * active library source remote has a different stable ID and is untouched.
  * Serialized with list() so an in-flight folder RPC finishes before cleanup.
  */
 fun releaseFolderPicker(source:LibrarySource) = synchronized(lock) {
  require(source.id.startsWith("ckreader-picker-")){"임시 폴더 탐색 저장소가 아닙니다."}
  val name=remoteName(source)
  try {
   // Cleanup also covers a partially successful config/create followed by a
   // failed list, before the in-process configured map was populated.
   ensureReady()
   rpc("config/delete",JSONObject().put("name",name))
  }finally{
   configured.remove(name)
   context.getSharedPreferences("ckreader_rclone_engine",Context.MODE_PRIVATE)
    .edit().remove(name).apply()
  }
 }

 fun copyTo(source:LibrarySource,remotePath:String,target:File,
  expectedSize:Long=-1,onProgress:(String,Long,Long)->Unit={_,_,_->}) {
  onProgress("Google Drive 연결 확인 중",-1,expectedSize)
  // Configuration writes stay serialized, but gigabyte-long pipe transfers
  // must not monopolize the app's global rclone setup lock.
  val fs=synchronized(lock){ensureRemote(source);fsRoot(source)}
  extension.download(fs,remotePath,target,expectedSize,onProgress)
  require(target.isFile && target.length()>0L) { "rclone 파일 다운로드 결과가 비어 있습니다." }
 }

 /** Reader's raw-archive pipe fallback; no second app-side ZIP file. */
 fun streamTo(source:LibrarySource,remotePath:String,out:java.io.OutputStream){
  val fs=synchronized(lock){ensureRemote(source);fsRoot(source)}
  extension.streamTo(fs,remotePath,out)
 }

 fun smallFile(source:LibrarySource,remotePath:String,maxBytes:Long=1_048_576L):ByteArray {
  val file=File.createTempFile("ckreader-meta-",".tmp",context.cacheDir)
  try {
   copyTo(source,remotePath,file)
   require(file.length()<=maxBytes) { "메타데이터 파일이 너무 큽니다." }
   return file.readBytes()
  } finally { file.delete() }
 }

 fun deleteFile(source:LibrarySource,remotePath:String) = synchronized(lock) {
  check(!source.readOnly){"GDS는 읽기 전용 저장소입니다."}
  ensureRemote(source)
  rpc("operations/deletefile",JSONObject().put("fs",fsRoot(source)).put("remote",remotePath))
 }
 private fun fileDetails(source:LibrarySource,path:String):JSONObject {
  ensureRemote(source)
  // The bundled getfile command resolves existing IDs with create=false and
  // performs Files.Get only. Never probe permissions by creating a test file.
  return rpc("backend/command",JSONObject().put("fs",fsRoot(source)).put("command","getfile")
   .put("arg",JSONArray().put(FolderYamlStorage.relative(path)))
   .put("opt",JSONObject().put("real","").put("all",""))).getJSONObject("result")
 }
 fun canWriteFolder(source:LibrarySource,folder:String,moveChildren:Boolean=false):Boolean = synchronized(lock) {
  if(source.readOnly||DriveWritePolicy.readOnlyScope(source.extra))return@synchronized false
  runCatching{DriveWritePolicy.folderWritable(fileDetails(source,folder),moveChildren)}.getOrDefault(false)
 }
 fun createYamlOnly(source:LibrarySource,folder:String,bytes:ByteArray):Boolean = synchronized(lock) {
  check(!source.readOnly){"GDS는 읽기 전용 저장소입니다."}
  if(!canWriteFolder(source,folder))return@synchronized false
  ensureRemote(source)
  if(FolderYamlStorage.hasManifest(list(source,folder).map{it.name}))return@synchronized false
  val temp=File.createTempFile("ckreader-yaml-",".yaml",context.cacheDir)
  try {
   temp.writeBytes(bytes)
   val remote=FolderYamlStorage.child(FolderYamlStorage.relative(folder),"kavita.yaml")
   extension.upload(fsRoot(source),remote,temp,
    JSONObject().put("IgnoreExisting",true).put("Immutable",true))
   // A concurrent creator may have won; do not replace its object or contents.
   smallFile(source,remote,FolderYamlMetadata.MAX_BYTES.toLong()).contentEquals(bytes)
  }finally{temp.delete()}
 }
 fun makeDirectory(source:LibrarySource,path:String)=synchronized(lock) {
  check(!source.readOnly){"GDS는 읽기 전용 저장소입니다."}
  check(canWriteFolder(source,path.substringBeforeLast('/',""))){"폴더 쓰기 권한이 없어 로컬 캐시만 업데이트합니다."}
  ensureRemote(source)
  rpc("operations/mkdir",JSONObject().put("fs",fsRoot(source)).put("remote",FolderYamlStorage.relative(path)))
 }
 fun moveOnly(source:LibrarySource,from:String,to:String)=synchronized(lock) {
  check(!source.readOnly){"GDS는 읽기 전용 저장소입니다."}
  check(canWriteFolder(source,from.substringBeforeLast('/',""),moveChildren=true)&&
   canWriteFolder(source,to.substringBeforeLast('/',""))&&
   runCatching{DriveWritePolicy.fileMovable(fileDetails(source,from))}.getOrDefault(false)) {
   "도서 이동 권한이 없어 로컬 캐시만 업데이트합니다."
  }
  ensureRemote(source)
  rpc("operations/movefile",JSONObject().put("srcFs",fsRoot(source)).put("srcRemote",FolderYamlStorage.relative(from))
   .put("dstFs",fsRoot(source)).put("dstRemote",FolderYamlStorage.relative(to))
   .put("_config",JSONObject().put("IgnoreExisting",true).put("Immutable",true)))
 }
}
