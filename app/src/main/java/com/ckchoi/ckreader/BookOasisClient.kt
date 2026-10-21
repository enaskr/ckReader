package com.ckchoi.ckreader

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Independent Android client for documented BookOasis APIs. */
internal class BookOasisClient(private val source: LibrarySource) {
 data class Category(val id:String,val name:String)
 data class Series(val name:String,val author:String,val publisher:String,val libraryId:String,
                   val representativeId:String="",val cover:String="",
                   val volumeCount:Int=0)
 data class Volume(val id:String,val title:String,val format:String,val author:String,
                   val publisher:String,val cover:String,val totalPages:Int)
 data class SeriesPage(val items:List<Series>,val hasMore:Boolean)

 private val base:HttpUrl=run {
  val parsed=(source.location.trim().trimEnd('/')
   .replace(Regex("/(?:opds|opds-adult|app-opds|app-opds-adult)$",RegexOption.IGNORE_CASE),"")
   +"/").toHttpUrlOrNull()
   ?:error("북오아시스 서버 주소가 올바르지 않습니다.")
  require(parsed.username.isEmpty()&&parsed.password.isEmpty()&&parsed.query==null&&parsed.fragment==null){
   "서버 주소에 사용자 정보나 쿼리를 넣지 마세요."
  }
  parsed
 }
 private val lock=Any()
 private val coverCache=object:android.util.LruCache<String,ByteArray>(8*1024*1024) {
  override fun sizeOf(key:String,value:ByteArray)=value.size
 }
 private val cookieLock=Any()
 private val cookies=ArrayList<Cookie>()
 @Volatile private var loggedIn=false
 private val http=OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS)
  .readTimeout(40,TimeUnit.SECONDS).callTimeout(90,TimeUnit.SECONDS)
  .followRedirects(false).followSslRedirects(false)
  .cookieJar(object:CookieJar {
   override fun saveFromResponse(url:HttpUrl,received:List<Cookie>) {
    synchronized(cookieLock) {
     cookies.removeAll{stored->stored.expiresAt<System.currentTimeMillis()||
       received.any{it.name==stored.name&&it.domain==stored.domain&&it.path==stored.path}}
     cookies.addAll(received)
    }
   }
   override fun loadForRequest(url:HttpUrl):List<Cookie> = synchronized(cookieLock) {
    cookies.filter{it.expiresAt>System.currentTimeMillis()&&it.matches(url)}
   }
  }).build()
 val dbType:String get()=runCatching{JSONObject(source.extra).optString("db_type","general")}
  .getOrDefault("general").let{if(it=="adult")"adult" else "general"}

 private fun url(path:String,query:Map<String,String> = emptyMap()):HttpUrl {
  require(!path.startsWith("/")&&!path.startsWith("http")&&!path.contains("..")&&'\\' !in path)
  val builder=base.newBuilder().addPathSegments(path)
  query.forEach{(key,value)->builder.addQueryParameter(key,value)}
  return builder.build()
 }
 private fun bytes(input:java.io.InputStream,limit:Long):ByteArray=input.use {
  val out=java.io.ByteArrayOutputStream()
  val buffer=ByteArray(65536);var total=0L
  while(true){val n=it.read(buffer);if(n<0)break;if(n==0)continue
   total+=n;require(total<=limit){"북오아시스 응답 크기 제한 초과"};out.write(buffer,0,n)}
  out.toByteArray()
 }
 private fun execute(request:Request,limit:Long=4_000_000L):Pair<Int,ByteArray> =
  http.newCall(request).execute().use{res->
   res.code to (res.body?.byteStream()?.let{bytes(it,limit)}?:byteArrayOf())
  }

 private fun login()=synchronized(lock) {
  if(loggedIn)return@synchronized
  require(source.user.isNotBlank()&&source.secret.isNotBlank()){"북오아시스 계정과 비밀번호가 필요합니다."}
  val body=JSONObject().put("username",source.user).put("password",source.secret)
   .put("remember_me",false).toString()
  val request=Request.Builder().url(url("login")).header("Accept","application/json")
   .post(body.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
  val result=execute(request)
  if(result.first !in 200..299)error("북오아시스 로그인 실패 (HTTP "+result.first+")")
  val response=runCatching {JSONObject(String(result.second,Charsets.UTF_8))}
   .getOrElse {error("로그인 서버가 올바른 JSON을 반환하지 않았습니다.")}
  require(response.optBoolean("success",false)){"북오아시스 로그인 정보를 확인하세요."}
  loggedIn=true
 }
 private fun getUrl(target:HttpUrl,maxBytes:Long=4_000_000L):ByteArray {
  login()
  val req=Request.Builder().url(target).header("Accept","*/*").get().build()
  var result=execute(req,maxBytes)
  if(result.first==401){
   synchronized(lock){loggedIn=false;synchronized(cookieLock){cookies.clear()}}
   login()
   result=execute(req,maxBytes)
  }
  when(result.first){
   401->error("북오아시스 로그인 세션이 만료됐습니다.")
   403->error("접근 권한이 없습니다. 계정 권한과 초기 비밀번호 변경 여부를 확인하세요.")
   404->error("북오아시스에 요청한 항목이 없습니다.")
  }
  if(result.first !in 200..299)error("북오아시스 요청 실패 (HTTP "+result.first+")")
  return result.second
 }
 private fun get(path:String,query:Map<String,String> = emptyMap(),maxBytes:Long=4_000_000L):ByteArray =
  getUrl(url(path,query),maxBytes)
 private fun json(path:String,params:Map<String,String> = emptyMap()):JSONObject {
  val response=JSONObject(String(get(path,params),Charsets.UTF_8))
  if(!response.optBoolean("success",false))
   error(response.optString("error","북오아시스 API 오류").take(150))
  return response
 }
 fun verify() {json("api/media/libraries",mapOf("type" to dbType))}
 fun categories():List<Category> {
  val array=json("api/media/libraries",mapOf("type" to dbType))
   .optJSONArray("libraries")?:JSONArray()
  return (0 until array.length()).mapNotNull{
   val row=array.optJSONObject(it)?:return@mapNotNull null
   val id=row.optString("id");val name=row.optString("name")
   if(id.isBlank()||id=="home"||name.isBlank())null else Category(id,name)
  }
 }
 fun series(libraryId:String,search:String,page:Int,limit:Int=40):SeriesPage {
  require(page in 1..10000&&limit in 1..100)
  val params=linkedMapOf("type" to dbType,"library_id" to libraryId,
   "page" to page.toString(),"limit" to limit.toString(),"sort" to "title_asc")
  if(search.isNotBlank())params["search"]=search.take(200)
  val response=json("api/media/list",params)
  val array=response.optJSONArray("series")?:JSONArray()
  return SeriesPage((0 until array.length()).mapNotNull{
   val row=array.optJSONObject(it)?:return@mapNotNull null
   val name=row.optString("series_name")
   if(name.isBlank())null else Series(name,row.optString("author"),
    row.optString("publisher"),row.optString("library_id",libraryId),
    row.optString("representative_book_id").takeIf{it.toLongOrNull()!=null}.orEmpty(),
    row.optString("cover_image"),
    row.optInt("has_books_count").takeIf{it>0}
     ?:row.optInt("book_count").takeIf{it>0}
     ?:row.optInt("volume_count").coerceAtLeast(0))
  },response.optBoolean("has_more"))
 }
 fun volumes(libraryId:String,seriesName:String,representativeId:String=""):Pair<JSONObject,List<Volume>> {
  val params=linkedMapOf("type" to dbType,"library_id" to libraryId,"series" to seriesName)
  val response=json("api/media/detail",params)
  val meta=response.optJSONObject("meta")?:JSONObject()
  val array=response.optJSONArray("books")?:JSONArray()
  return meta to (0 until array.length()).mapNotNull{
   val row=array.optJSONObject(it)?:return@mapNotNull null
   val id=row.optString("id")
   val format=row.optString("file_format").lowercase()
   if(id.toLongOrNull()==null||format !in setOf("zip","cbz","imgdir","pdf","epub","txt"))
    return@mapNotNull null
   Volume(id,row.optString("title_alias").ifBlank{row.optString("title","도서 $id")},
    format,meta.optString("author"),meta.optString("publisher"),
    row.optString("cover_image").ifBlank{meta.optString("cover_image")},
    row.optInt("total_pages",0))
  }
 }
 fun pages(id:String):Int {
  require(id.toLongOrNull()!=null)
  val count=json("api/media/books/$id/info",mapOf("type" to dbType)).optInt("total_pages",0)
  if(count>0)return count
  val detail=json("api/media/books/$id/reader-info",mapOf("type" to dbType))
  return detail.optJSONObject("book")?.optInt("total_pages",0)?:0
 }
 fun bookInfo(id:String):JSONObject {
  require(id.toLongOrNull()!=null)
  val response=json("api/media/books/$id/info",mapOf("type" to dbType))
  return response.optJSONObject("book")?:response
 }
 fun imagePage(id:String,index:Int):ByteArray {
  require(id.toLongOrNull()!=null&&index in 0..50_000)
  return get("api/media/stream",mapOf("type" to dbType,"book_id" to id,
   "page" to index.toString()),20L*1024*1024)
 }
 fun cover(path:String):ByteArray {
  val value=path.trim()
  require(value.isNotEmpty() && !value.startsWith("//") && !value.contains("..") && '\\' !in value) {
   "잘못된 표지 이미지 경로"
  }
  // Bare cover_image values such as "2/book_hash.webp?t=..." belong under
  // covers/, not at the server root. Preserve complete same-origin/API URLs.
  val target=BookOasisCoverUrl.resolve(base,value)
  require(target!=null) {
   "다른 호스트의 이미지는 요청하지 않습니다."
  }
  synchronized(coverCache) {coverCache.get(target.toString())?.let{return it}}
  val image=getUrl(target,5L*1024*1024)
  synchronized(coverCache) {if(image.isNotEmpty())coverCache.put(target.toString(),image)}
  return image
 }

 /**
  * Authenticated, seekable PDF/EPUB covers. The BookOasis download route is
  * backed by Flask send_file and may support HTTP 206. A HTTP 200 response
  * is closed without copying the whole book.
  */
 private fun <T> bookRange(id:String,range:String,read:(okhttp3.Response)->T):T {
  require(id.toLongOrNull()!=null)
  login()
  val request=Request.Builder().url(url("api/media/books/"+id+"/download",
   mapOf("type" to dbType))).header("Range",range)
   .header("Accept","application/octet-stream").build()
  var response=http.newCall(request).execute()
  if(response.code==401) {
   response.close()
   synchronized(lock){loggedIn=false;synchronized(cookieLock){cookies.clear()}}
   login()
   response=http.newCall(request).execute()
  }
  response.use{return read(it)}
 }

 fun rangedBookSize(id:String):Long?=runCatching {
  bookRange(id,"bytes=0-0") {response ->
   if(response.code!=206)return@bookRange null
   val content=response.header("Content-Range").orEmpty()
   if(!content.startsWith("bytes 0-0/"))return@bookRange null
   content.substringAfterLast('/').toLongOrNull()?.takeIf{it in 22..1_000_000_000L}
  }
 }.getOrNull()

 fun rangedBookBytes(id:String,offset:Long,count:Int):ByteArray {
  require(offset>=0L&&count in 1..1_048_576)
  return bookRange(id,"bytes="+offset+"-"+(offset+count-1)) {response ->
   require(response.code==206){"북오아시스가 부분 파일 읽기를 지원하지 않습니다."}
   require(response.header("Content-Range").orEmpty().startsWith("bytes "+offset+"-")) {
    "북오아시스에서 잘못된 바이트 범위를 응답했습니다."
   }
   val stream=response.body?.byteStream()?:error("빈 응답")
   val received=bytes(stream,count.toLong()+1)
   require(received.size==count){"원격 파일 범위의 길이가 다릅니다."}
   received
  }
 }

 /** Capped fallback for small files when HTTP range is not available. */
 fun smallCoverBook(id:String,format:String,limit:Long=24L*1024*1024):ByteArray? {
  if(id.toLongOrNull()==null || format !in setOf("pdf","epub"))return null
  return try {
   get("api/media/books/"+id+"/download",mapOf("type" to dbType),limit)
  } catch(e:Exception) {
   if(format!="pdf" || e.message?.contains("HTTP 403")!=true)throw e
   get("api/media/pdf",mapOf("db_type" to dbType,"book_id" to id),limit)
  }
 }

 fun openReadableFile(id:String,format:String,target:File) {
  try {
   download(id,target)
  }catch(downloadError:Exception) {
   // BookOasis permits reading PDF/TXT in its browser even when the account
   // cannot use the explicit download endpoint; use the authorized view API.
   if(format !in setOf("pdf","txt") ||
    downloadError.message?.contains("HTTP 403")!=true)throw downloadError
   val endpoint=if(format=="pdf")"api/media/pdf" else "api/media/txt"
   val query=mapOf("db_type" to dbType,"book_id" to id)
   login()
   val request=Request.Builder().url(url(endpoint,query)).get().build()
   http.newCall(request).execute().use{res->
    if(!res.isSuccessful)error("북오아시스 읽기 API 접근 실패 (HTTP "+res.code+")")
    require(!res.header("Content-Type").orEmpty().contains("json",true)){"서버가 파일 대신 오류를 반환했습니다."}
    target.outputStream().use{output->
     val input=res.body?.byteStream()?:error("빈 도서 응답")
     input.use{stream->
      val block=ByteArray(64*1024);var total=0L
      while(true){val n=stream.read(block);if(n<0)break;if(n==0)continue
       total+=n;require(total<=1_000_000_000L){"파일이 너무 큽니다."};output.write(block,0,n)}
     }
    }
   }
   require(target.length()>0){"읽기 API 응답에 내용이 없습니다."}
  }
 }
 fun download(id:String,target:File) {
  require(id.toLongOrNull()!=null)
  login()
  val req=Request.Builder().url(url("api/media/books/$id/download",
    mapOf("type" to dbType))).header("Accept","application/octet-stream").build()
  http.newCall(req).execute().use{res->
   if(!res.isSuccessful)error("도서 다운로드 권한 또는 서버 상태를 확인하세요 (HTTP "+res.code+")")
   if("json" in res.header("Content-Type").orEmpty().lowercase())
    error("서버가 도서 파일 대신 오류 메시지를 반환했습니다.")
   target.parentFile?.mkdirs()
   val input=res.body?.byteStream()?:error("빈 응답")
   input.use{stream->target.outputStream().use{out->
    val buffer=ByteArray(65536);var size=0L
    while(true){val n=stream.read(buffer);if(n<0)break;if(n==0)continue
     size+=n;require(size<=1_000_000_000L){"파일이 너무 큽니다."};out.write(buffer,0,n)}
   }}
  }
  require(target.length()>0){"도서 파일이 비어 있습니다."}
 }
}

internal object BookOasisAccess {
 private val clients=ConcurrentHashMap<String,BookOasisClient>()
 private fun key(source:LibrarySource):String =
  MessageDigest.getInstance("SHA-256").digest(
   (source.id+"|"+source.location+"|"+source.user+"|"+source.secret+"|"+source.extra).toByteArray())
   .joinToString(""){"%02x".format(it)}
 fun client(source:LibrarySource):BookOasisClient =
  clients.computeIfAbsent(key(source)){BookOasisClient(source)}
 fun evict(source:LibrarySource){clients.remove(key(source))}
}
