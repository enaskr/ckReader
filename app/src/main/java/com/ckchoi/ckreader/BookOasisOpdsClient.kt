package com.ckchoi.ckreader

import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * BookOasis standard OPDS catalog. Basic credentials are attached to only
 * the configured server, and never appear in cached feed URLs or database rows.
 */
internal class BookOasisOpdsClient(private val source:LibrarySource) {
 private val base:HttpUrl = (source.location.trim().trimEnd('/')
  .replace(Regex("/(?:opds|opds-adult|app-opds|app-opds-adult)$",RegexOption.IGNORE_CASE),"")
  +"/").toHttpUrlOrNull()
  ?.also {
   require(it.username.isEmpty() && it.password.isEmpty() && it.query==null && it.fragment==null){
    "북오아시스 주소에 계정 또는 쿼리를 넣지 마세요."
   }
  } ?:error("북오아시스 서버 주소가 올바르지 않습니다.")
 private val http=OkHttpClient.Builder()
  .connectTimeout(15,TimeUnit.SECONDS).readTimeout(50,TimeUnit.SECONDS)
  .callTimeout(120,TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
 // Long transfers must not be terminated by the metadata request deadline.
 // Socket reads retain a timeout, but the overall transfer is unbounded in time.
 private val transferHttp=http.newBuilder()
  .callTimeout(0,TimeUnit.MILLISECONDS).readTimeout(120,TimeUnit.SECONDS).build()
 private val maxBookBytes=20L*1024*1024*1024
 private val remainingDiskReserve=32L*1024*1024
 private val countMemory=ConcurrentHashMap<String,Int>()
 private val auth:String by lazy {
  require(source.user.isNotBlank() && source.secret.isNotBlank()){"북오아시스 계정과 비밀번호가 필요합니다."}
  Credentials.basic(source.user,source.secret,Charsets.UTF_8)
 }
 val dbType:String=runCatching{JSONObject(source.extra).optString("db_type","general")}
  .getOrDefault("general").let{if(it=="adult")"adult" else "general"}
 private val root:String get()=if(dbType=="adult")"opds-adult" else "opds"

 private fun endpoint(path:String,params:Map<String,String> = emptyMap()):HttpUrl {
  require(path.isNotEmpty() && !path.startsWith("/") && !path.contains("..") && '\\' !in path)
  return base.newBuilder().addPathSegments(path).apply {
   params.forEach{(key,value)->addQueryParameter(key,value)}
  }.build()
 }
 private fun safeUrl(reference:String):HttpUrl {
  require(!reference.startsWith("//") && !reference.contains("..") && '\\' !in reference)
  val advertised=base.resolve(reference)?:error("올바르지 않은 OPDS 링크")
  require(advertised.username.isEmpty() && advertised.password.isEmpty()){
   "OPDS 링크에 계정이 포함될 수 없습니다."
  }
  if(advertised.scheme==base.scheme && advertised.host==base.host &&
     advertised.port==base.port)return advertised
  // Behind an HTTPS reverse proxy, Flask may advertise an internal host/port.
  // Do not follow that host or send credentials to it. Rebase ONLY known OPDS
  // and cover routes to the user-configured origin.
  val path=advertised.encodedPath
  require(path=="/opds" || path=="/opds-adult" ||
   path.startsWith("/opds/") || path.startsWith("/opds-adult/") ||
   path.startsWith("/covers/") || path.startsWith("/static/")){
   "외부 호스트의 임의 경로는 OPDS 리소스로 허용하지 않습니다."
  }
  return base.newBuilder().encodedPath(path)
   .encodedQuery(advertised.encodedQuery).build()
 }
 private fun request(url:HttpUrl,accept:String="application/atom+xml"):Request =
  Request.Builder().url(url).header("Authorization",auth)
   .header("Accept",accept).header("User-Agent","KOReader-compatible ckReader OPDS/1.0")
   .get().build()
 private fun <T> call(url:HttpUrl,accept:String,block:(okhttp3.Response)->T):T =
  http.newCall(request(url,accept)).execute().use { response ->
   if(response.code==401)error("북오아시스 OPDS 인증 실패: 계정/비밀번호를 확인하세요.")
   if(response.code==403)error("북오아시스 OPDS 접근 권한이 없습니다.")
   if(response.code !in 200..299)error("북오아시스 OPDS HTTP "+response.code)
   block(response)
  }
 private fun capped(input:java.io.InputStream,limit:Long):ByteArray=input.use { stream ->
  val result=java.io.ByteArrayOutputStream()
  val buffer=ByteArray(64*1024);var nbytes=0L
  while(true) {
   val n=stream.read(buffer)
   if(n<0)break
   if(n==0)continue
   nbytes+=n;require(nbytes<=limit){"OPDS 응답이 너무 큽니다."}
   result.write(buffer,0,n)
  }
  result.toByteArray()
 }
 private fun feed(url:HttpUrl):BookOasisOpdsParser.Feed {
  val result=call(url,"application/atom+xml;profile=opds-catalog, application/atom+xml"){ response ->
   require(!response.header("Content-Type").orEmpty().contains("html",true)){
    "OPDS 대신 웹페이지가 반환되었습니다."
   }
   BookOasisOpdsParser.parse(capped(response.body?.byteStream()?:error("빈 OPDS 응답"),32_000_000L))
  }
  result.entries.forEach{entry->
   val id=bookId(entry)
   if(id!=null && entry.pages>0)countMemory[id]=entry.pages
  }
  return result
 }
 fun verify():Unit {feed(endpoint(root))}
 fun rootFeed():BookOasisOpdsParser.Feed=feed(endpoint(root))

 /** Category feeds aren't paginated in BookOasis, while search/series feeds are. */
 fun categoryFeed(category:String,page:Int=1):BookOasisOpdsParser.Feed {
  require(page in 1..10_000)
  return when(category) {
   "home" -> {
    if(page!=1)return BookOasisOpdsParser.Feed(emptyList())
    val libraries=rootFeed().entries.filter{it.navigation&&libraryId(it)!=null}
    val entries=LinkedHashMap<String,BookOasisOpdsParser.Entry>()
    for(lib in libraries) {
     val id=libraryId(lib)?:continue
     for(item in categoryFeed(id).entries) {
      if(item.navigation)entries.putIfAbsent(id+"|"+item.title,item)
     }
    }
    BookOasisOpdsParser.Feed(entries.values.toList())
   }
   "recently-added","recently-read","favorite" ->
    feed(endpoint("$root/$category",mapOf("page" to page.toString(),"page_size" to "200")))
   else -> {
    require(category.toLongOrNull()!=null){"지원하지 않는 북오아시스 보관함"}
    feed(endpoint("$root/library/$category",mapOf("page" to page.toString(),"page_size" to "200")))
   }
  }
 }
 fun searchFeed(query:String,page:Int):BookOasisOpdsParser.Feed {
  require(query.isNotBlank() && page in 1..10_000)
  return feed(endpoint("$root/search",mapOf("q" to query.take(200),
   "page" to page.toString(),"page_size" to "200")))
 }
 fun seriesFeed(libraryId:String,name:String,page:Int=1):BookOasisOpdsParser.Feed {
  require(page in 1..10_000)
  val lib=libraryId.takeIf{it.toLongOrNull()!=null}?:"all"
  val url=base.newBuilder().addPathSegments("$root/series").addPathSegment(lib)
   .addPathSegment(name.ifBlank{"__empty_series__"})
   .addQueryParameter("page",page.toString()).addQueryParameter("page_size","200").build()
  return feed(url)
 }
 fun allSeriesBooks(libraryId:String,name:String):List<BookOasisOpdsParser.Entry> {
  val result=LinkedHashMap<String,BookOasisOpdsParser.Entry>()
  var page=1
  while(true){
   val response=seriesFeed(libraryId,name,page)
   for(item in response.entries.filter{!it.navigation}) {
    val id=bookId(item)?:continue
    result.putIfAbsent(id,item)
   }
   if(response.next==null)return result.values.toList()
   require(page<10_000 && response.entries.isNotEmpty()){"북오아시스 OPDS 페이지 처리 실패"}
   page++
  }
 }

 private fun urlPath(reference:String):String=runCatching{safeUrl(reference).encodedPath}.getOrDefault("")
 fun libraryId(item:BookOasisOpdsParser.Entry):String? {
  val match=Regex("/opds(?:-adult)?/library/([0-9]+)$").find(urlPath(item.href))
  return match?.groupValues?.get(1)
 }
 fun seriesLibraryId(item:BookOasisOpdsParser.Entry):String? {
  val match=Regex("/opds(?:-adult)?/series/([0-9]+|all)/").find(urlPath(item.href))
  return match?.groupValues?.get(1)?.takeUnless{it=="all"}
 }
 fun bookId(item:BookOasisOpdsParser.Entry):String? {
  val match=Regex("/opds/download/(?:general|adult)/([0-9]+)$").find(urlPath(item.href))
  return match?.groupValues?.get(1)
 }
 fun bookFormat(item:BookOasisOpdsParser.Entry):String {
  val term=item.format.lowercase().trim()
  if(term in setOf("zip","cbz","imgdir","epub","pdf","txt"))return if(term=="imgdir")"cbz" else term
  val mime=item.mime.lowercase().substringBefore(';').trim()
  return when(mime) {
   "application/epub+zip"->"epub"
   "application/pdf"->"pdf"
   "text/plain"->"txt"
   "application/vnd.comicbook+zip","application/zip","application/x-cbz"->"cbz"
   else -> when {
    item.title.startsWith("[PDF]",true)->"pdf"
    item.title.startsWith("[EPUB]",true)->"epub"
    item.title.startsWith("[TXT]",true)->"txt"
    item.pages>0 ->"cbz"
    else ->""
   }
  }
 }
 fun pageCount(id:String):Int=countMemory[id]?:0
 /** Check that PSE can serve the first page before opening a streamed reader.
  * Some catalog entries advertise a page count although their PSE image is 404. */
 fun pageAvailable(id:String,index:Int):Boolean {
  require(id.toLongOrNull()!=null && index in 0..100_000)
  val url=endpoint("opds/pse/$dbType/$id/$index")
  return transferHttp.newCall(request(url,"image/jpeg, image/png, image/webp, image/*"))
   .execute().use { response ->
    if(response.code==404 || response.code==410)return@use false
    if(response.code !in 200..299)error("OPDS 이미지 HTTP "+response.code)
    val mime=response.header("Content-Type").orEmpty().lowercase()
    if(mime.contains("html") || mime.contains("json"))return@use false
    val magic=ByteArray(8)
    val stream=response.body?.byteStream()?:return@use false
    var read=0
    while(read<magic.size) {
     val count=stream.read(magic,read,magic.size-read)
     if(count<0)break
     read+=count
    }
    read==magic.size && (
     magic[0]==0xFF.toByte() && magic[1]==0xD8.toByte() ||
     magic[0]==0x89.toByte() && magic[1]=='P'.code.toByte() ||
     magic[0]=='R'.code.toByte() && magic[1]=='I'.code.toByte() ||
     magic[0]=='G'.code.toByte() && magic[1]=='I'.code.toByte())
   }
 }
 fun cover(reference:String):ByteArray {
  val url=BookOasisCoverUrl.resolve(base,reference)
   ?:safeUrl(reference)
  return call(safeUrl(url.toString()),"image/*") {res->
   require(!res.header("Content-Type").orEmpty().contains("html",true)){"표지 대신 HTML 응답"}
   capped(res.body?.byteStream()?:error("빈 표지 응답"),5L*1024*1024)
  }
 }
 fun page(id:String,index:Int):ByteArray {
  require(id.toLongOrNull()!=null && index in 0..500_000)
  return call(endpoint("opds/pse/$dbType/$id/$index"),"image/jpeg, image/webp, image/*"){res->
   capped(res.body?.byteStream()?:error("빈 이미지 응답"),25L*1024*1024)
  }
 }
 /**
  * PSE pages are stored directly on disk. Large JPEG/PNG/WebP pages never
  * materialize as ByteArray in the provider or reader heap.
  */
 fun pageToFile(id:String,index:Int,target:File) {
  require(id.toLongOrNull()!=null && index in 0..100_000)
  val endpoint=endpoint("opds/pse/"+dbType+"/"+id+"/"+index)
  transferHttp.newCall(request(endpoint,"image/jpeg, image/png, image/webp, image/*"))
   .execute().use{res->
    if(res.code !in 200..299)error("OPDS 이미지 HTTP "+res.code)
    val mime=res.header("Content-Type").orEmpty().lowercase()
    require(!mime.contains("html") && !mime.contains("json")){"도서 이미지가 아닌 오류 응답"}
    val advertised=res.body?.contentLength()?:-1
    require(advertised<0 || advertised in 10..(96L*1024*1024)){
     "도서 페이지 이미지 크기가 제한을 초과했습니다."
    }
    var size=0L
    target.outputStream().use{out->
     (res.body?.byteStream()?:error("이미지 응답이 비어 있습니다.")).use{input->
      val buffer=ByteArray(64*1024)
      while(true) {
       if(Thread.currentThread().isInterrupted)
        throw java.io.InterruptedIOException("OPDS 이미지 다운로드 취소")
       val read=input.read(buffer)
       if(read<0)break
       if(read==0)continue
       size+=read
       require(size<=96L*1024*1024){"도서 페이지 이미지가 너무 큽니다."}
       out.write(buffer,0,read)
      }
     }
    }
    require(size>=10 && (advertised<0 || size==advertised)){"OPDS 이미지가 불완전합니다."}
   }
 }

 private fun downloadUrl(id:String):HttpUrl {
  require(id.toLongOrNull()!=null){"올바르지 않은 OPDS 도서 ID"}
  return endpoint("opds/download/$dbType/$id")
 }
 /** Stream an OPDS acquisition to an Android pipe when no page count/Range
  * is available. The reader extracts ZIP directly into its own temp file. */
 fun streamBook(id:String,output:java.io.OutputStream) {
  transferHttp.newCall(request(downloadUrl(id),"application/octet-stream, */*"))
   .execute().use{res->
    if(res.code!=200)error("북오아시스 OPDS 원본 다운로드 HTTP "+res.code)
    val mime=res.header("Content-Type").orEmpty().lowercase()
    require(!mime.contains("html")&&!mime.contains("json")){"북오아시스에서 도서 대신 오류 응답 수신"}
    val length=res.body?.contentLength()?:-1
    require(length<0 || length in 1..maxBookBytes){"북오아시스 압축파일 크기 제한 초과"}
    var done=0L
    (res.body?.byteStream()?:error("빈 OPDS 응답")).use{input->
     val buffer=ByteArray(128*1024)
     while(true) {
      if(Thread.currentThread().isInterrupted)
       throw java.io.InterruptedIOException("OPDS 도서 읽기 취소")
      val n=input.read(buffer)
      if(n<0)break
      if(n==0)continue
      done+=n
      require(done<=maxBookBytes){"도서 원본 크기 제한 초과"}
      output.write(buffer,0,n)
     }
    }
    require(done>0 && (length<0 || done==length)){"원본 ZIP/CBZ가 불완전합니다."}
  }
 }

 /**
  * Disk-streamed, resumable acquisition (no whole-book ByteArray). The .part
  * file is owned by LibraryRepository, not copied again by this client.
  *
  * Resume only with an upstream strong ETag or Last-Modified (If-Range).
  * An OPDS server ignoring Range returns 200: truncate and restart safely.
  * On a transient I/O failure the caller retains the partial until next open.
  */
 fun download(id:String,target:File,onProgress:(Long,Long)->Unit={_,_->}) {
  target.parentFile?.mkdirs()
  val validatorFile=File(target.absolutePath+".if-range")
  var previous=target.takeIf{it.isFile&&it.length()>0}?.length()?:0L
  if(previous>maxBookBytes){target.delete();validatorFile.delete();previous=0}
  val validator=validatorFile.takeIf{previous>0&&it.isFile&&it.length() in 1..200}
   ?.readText()?.trim()?.takeIf{it.isNotEmpty()}
  if(previous>0&&validator==null) {
   target.delete();previous=0
  }
  val request=request(downloadUrl(id),"application/octet-stream, */*").newBuilder().apply {
   if(previous>0) {
    header("Range","bytes="+previous+"-")
    header("If-Range",validator!!)
   }
  }.build()
  transferHttp.newCall(request).execute().use{res->
   if(res.code==401)error("Book Oasis 인증이 만료되었습니다.")
   if(res.code==403)error("Book Oasis 도서 다운로드 권한이 없습니다.")
   val resumed=previous>0&&res.code==206
   if(res.code!=200 && !resumed)error("Book Oasis 도서 다운로드 HTTP "+res.code)
   val contentType=res.header("Content-Type").orEmpty().lowercase()
   require(!contentType.contains("html") && !contentType.contains("json")){
    "도서 대신 오류 문서가 반환되었습니다."
   }
   val range=if(resumed) {
    val parts=Regex("""bytes (\d+)-(\d+)/(\d+)""")
     .matchEntire(res.header("Content-Range").orEmpty())
     ?:error("OPDS 다운로드 응답의 Content-Range가 잘못되었습니다.")
    val start=parts.groupValues[1].toLong()
    val end=parts.groupValues[2].toLong()
    val total=parts.groupValues[3].toLong()
    require(start==previous && end>=start && total>end) {
     "OPDS 다운로드 재개 위치가 서버 응답과 일치하지 않습니다."
    }
    Triple(start,end,total)
   }else null
   val append=range!=null
   if(!append)previous=0L
   val length=res.body?.contentLength()?.takeIf{it>=0}?:-1L
   val expectedSize=range?.third ?:length.takeIf{it>=0}
   require(expectedSize==null || expectedSize in 1..maxBookBytes){
    "ZIP/CBZ 파일 크기가 허용 범위를 벗어났습니다."
   }
   val remaining=expectedSize?.minus(previous)
   if(remaining!=null && remaining>0) {
    val available=target.parentFile?.usableSpace?:-1
    require(available<0 || remaining<=available-remainingDiskReserve){
     "대용량 도서를 저장할 공간이 부족합니다. 추가 필요: "+(remaining/1024/1024)+" MB"
    }
   }
   if(!append) {
    // A strong validator is required before a later 206 can be appended.
    val etag=res.header("ETag")?.trim()?.takeIf{
     it.startsWith("\"") && it.endsWith("\"") && it.length in 3..150
    }
    val lm=res.header("Last-Modified")?.trim()?.takeIf{it.length in 15..150}
    val saved=etag?:lm
    if(saved!=null)validatorFile.writeText(saved) else validatorFile.delete()
   }
   val stream=res.body?.byteStream()?:error("다운로드 응답이 비어 있습니다.")
   java.io.FileOutputStream(target,append).use{out->
    val buffer=ByteArray(128*1024)
    var done=previous
    var lastProgress=0L
    stream.use{input->
     while(true) {
      if(Thread.currentThread().isInterrupted)
       throw java.io.InterruptedIOException("도서 다운로드가 취소되었습니다.")
      val n=input.read(buffer)
      if(n<0)break
      if(n==0)continue
      done+=n
      require(done<=maxBookBytes){"도서 다운로드 크기가 20GB를 초과했습니다."}
      out.write(buffer,0,n)
      val now=System.currentTimeMillis()
      if(now-lastProgress>=400L) {
       onProgress(done,expectedSize?:-1L)
       lastProgress=now
      }
     }
    }
    out.fd.sync()
    require(done>0){"도서 다운로드 결과가 비어 있습니다."}
    if(expectedSize!=null)require(done==expectedSize) {
     "ZIP/CBZ 다운로드가 완료되지 않았습니다: "+done+" / "+expectedSize+" 바이트"
    }
    onProgress(done,expectedSize?:done)
   }
  }
  validatorFile.delete()
 }
 fun rangedSize(id:String):Long?=runCatching {
  callRange(id,"bytes=0-0"){response->
   if(response.code!=206)null else response.header("Content-Range")
    ?.substringAfterLast('/')?.toLongOrNull()?.takeIf{it in 22..10_000_000_000L}
  }
 }.getOrNull()
 private fun <T> callRange(id:String,range:String,block:(okhttp3.Response)->T):T {
  val url=downloadUrl(id)
  return http.newCall(request(url,"application/octet-stream").newBuilder()
   .header("Range",range).build()).execute().use{res->
   if(res.code==401 || res.code==403)error("OPDS 파일 읽기 권한이 없습니다.")
   if(res.code !in 200..299)error("OPDS 파일 읽기 HTTP "+res.code)
   block(res)
  }
 }
 fun rangedBytes(id:String,offset:Long,count:Int):ByteArray {
  require(offset>=0 && count in 1..1_048_576)
  return callRange(id,"bytes=$offset-${offset+count-1}") {res->
   require(res.code==206 && res.header("Content-Range").orEmpty().startsWith("bytes $offset-")){
    "OPDS 서버가 바이트 범위 읽기를 지원하지 않습니다."
   }
   val bytes=capped(res.body?.byteStream()?:error("빈 범위 응답"),count.toLong()+1)
   require(bytes.size==count){"부분 다운로드 크기가 일치하지 않습니다."}
   bytes
  }
 }
 fun smallBook(id:String,limit:Long=24L*1024*1024):ByteArray =
  call(downloadUrl(id),"application/octet-stream, */*"){res->
   capped(res.body?.byteStream()?:error("빈 도서 응답"),limit)
  }
}

internal object BookOasisOpdsAccess {
 private val clients=ConcurrentHashMap<String,BookOasisOpdsClient>()
 private fun key(source:LibrarySource):String=MessageDigest.getInstance("SHA-256")
  .digest((source.id+"|"+source.location+"|"+source.user+"|"+source.secret+"|"+source.extra)
   .toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
 fun client(source:LibrarySource):BookOasisOpdsClient=
  clients.computeIfAbsent(key(source)){BookOasisOpdsClient(source)}
 fun evict(source:LibrarySource) {clients.remove(key(source))}
}
