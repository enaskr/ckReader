package com.ckchoi.ckreader

import android.content.Context
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit

/** Official NL holdings and Data4Library search/detail response formats. */
internal object PublicLibraryBookParser {
 private fun rows(array:JSONArray?):List<JSONObject> =
  (0 until minOf(array?.length()?:0,40)).mapNotNull{array?.optJSONObject(it)}
 private fun text(row:JSONObject,vararg fields:String):String = fields.firstNotNullOfOrNull {
  row.optString(it).takeUnless{value->value.isBlank()||value=="null"}
 }?.let{Jsoup.parse(it).text().take(800)}.orEmpty()
 private fun isbn(value:String):String=Regex("[0-9]{13}|[0-9]{9}[0-9Xx]")
  .find(value.replace("-",""))?.value?.uppercase().orEmpty()
 private fun url(value:String,base:String):String {
  val resolved=base.toHttpUrlOrNull()?.resolve(value)?:return ""
  return resolved.newBuilder().scheme("https").build().toString()
   .takeIf{value.isNotBlank()&&resolved.scheme in setOf("http","https")&&
    resolved.username.isEmpty()&&resolved.password.isEmpty()}.orEmpty().take(550)
 }
 fun nationalLibrary(json:JSONObject):List<WebBookMetadata> {
  val root=json.optJSONObject("response")?:json
  return rows(root.optJSONArray("result")?:root.optJSONArray("items")).mapNotNull { row->
   val title=text(row,"title_info","titleInfo","title")
   if(title.isBlank())return@mapNotNull null
   WebBookMetadata(title=title.take(180),
    author=text(row,"author_info","authorInfo","author").take(200),
    publisher=text(row,"pub_info","pubInfo","publisher").take(200),
    description=text(row,"description","abstract"),
    genre=text(row,"kdc_name_1s","kdcName1s").take(120),
    coverUrl=url(text(row,"image_url","imageUrl","bookImageURL","cover"),"https://www.nl.go.kr"),
    source="국립중앙도서관 OpenAPI",
    sourceUrl=url(text(row,"detail_link","detailLink"),"https://www.nl.go.kr"),
    isbn=isbn(text(row,"isbn")),publishedDate=text(row,"pub_year_info","pubYearInfo").take(22))
  }
 }
 fun data4Library(json:JSONObject):List<WebBookMetadata> {
  val root=json.optJSONObject("response")?:return emptyList()
  val entries=rows(root.optJSONArray("docs"))+rows(root.optJSONArray("detail"))
  return entries.mapNotNull { entry->
   val row=entry.optJSONObject("doc")?:entry.optJSONObject("book")?:entry
   val title=text(row,"bookname","bookName","title")
   if(title.isBlank())return@mapNotNull null
   WebBookMetadata(title=title.take(180),author=text(row,"authors","author").take(200),
    publisher=text(row,"publisher").take(200),description=text(row,"description"),
    genre=text(row,"class_nm","class_no").take(120),
    coverUrl=url(text(row,"bookImageURL"),"https://data4library.kr"),
    source="도서관 정보나루",
    sourceUrl=url(text(row,"bookDtlUrl","bookDtUrl"),"https://data4library.kr"),
    isbn=isbn(text(row,"isbn13","isbn")),publishedDate=text(row,"publication_date","publication_year").take(22))
  }
 }
}

internal class PublicLibraryCatalogClient(private val context:Context?=null,
 private val http:OkHttpClient=OkHttpClient.Builder().connectTimeout(5,TimeUnit.SECONDS)
  .readTimeout(7,TimeUnit.SECONDS).callTimeout(9,TimeUnit.SECONDS)
  .followRedirects(false).followSslRedirects(false).build(),
 private val keyReader:((MetadataApiProvider)->String?)?=null) {
 var contacted=false
  private set
 private fun get(provider:MetadataApiProvider,path:String,params:Map<String,String>):JSONObject? {
  if(BuildConfig.LITE)return null
  if(Thread.currentThread().isInterrupted)throw InterruptedException("도서정보 검색 취소")
  val key=keyReader?.invoke(provider)?:context?.let{AladinApiCredentials(it,provider).read()}
   ?:return null
  val base=when(provider) {
   MetadataApiProvider.NATIONAL_LIBRARY->"https://www.nl.go.kr"
   MetadataApiProvider.DATA4LIBRARY->"https://data4library.kr"
   else->return null
  }
  val builder=(base+path).toHttpUrlOrNull()!!.newBuilder()
  params.forEach{(name,value)->builder.addQueryParameter(name,value)}
  builder.addQueryParameter(if(provider==MetadataApiProvider.NATIONAL_LIBRARY)"key" else "authKey",key)
  // Never log request URLs: these APIs carry credentials in query parameters.
  val requestBuilder=Request.Builder().url(builder.build())
   .header("User-Agent","ckReader (Android book metadata)")
  // Data4Library returns HTTP 406 when Accept is application/json, even with format=json.
  if(provider==MetadataApiProvider.NATIONAL_LIBRARY)
   requestBuilder.header("Accept","application/json")
  val request=requestBuilder.build()
  return try {
   http.newCall(request).execute().use { response->
    if(!response.isSuccessful)return@use null
    val body=response.body?:return@use null
    val bytes=body.byteStream().use { input->
     val output=java.io.ByteArrayOutputStream()
     val buffer=ByteArray(8192)
     while(true) {
      if(Thread.currentThread().isInterrupted)throw InterruptedException("도서정보 검색 취소")
      val count=input.read(buffer)
      if(count<0)break
      if(output.size()+count>1_500_000)return null
      output.write(buffer,0,count)
     }
     output.toByteArray()
    }
    val result=JSONObject(String(bytes,Charsets.UTF_8))
    val root=result.optJSONObject("response")?:result
    if(root.has("error")||root.has("error_code")||root.has("errorCode"))return@use null
    val valid=if(provider==MetadataApiProvider.NATIONAL_LIBRARY)
     root.has("result")||root.has("items")||root.has("total")
    else root.has("docs")||root.has("detail")||root.has("numFound")
    result.takeIf{valid}?.also{contacted=true}
   }
  }catch(_:java.io.IOException){null}catch(_:org.json.JSONException){null}
 }
 fun search(provider:MetadataApiProvider,title:String,author:String="",isbn:String="",
            exhaustive:Boolean=false):List<WebBookMetadata> {
  if(BuildConfig.LITE)return emptyList()
  val query=MetadataTitleMatcher.searchQuery(title)
  if(MetadataTitleMatcher.normalized(query).length<2)return emptyList()
  val found=ArrayList<WebBookMetadata>()
  if(provider==MetadataApiProvider.NATIONAL_LIBRARY) {
   val defaults=mapOf("apiType" to "json","pageNum" to "1","pageSize" to "30","category" to "도서")
   val id=isbn.uppercase().replace("-","").takeIf{it.matches(Regex("[0-9]{13}|[0-9]{9}[0-9X]"))}
   if(id!=null)get(provider,"/NL/search/openApi/search.do",defaults+mapOf(
    "detailSearch" to "true","isbnOp" to "isbn","isbnCode" to id))
    ?.let{found.addAll(PublicLibraryBookParser.nationalLibrary(it))}
   if(found.none{MetadataTitleMatcher.score(query,author,isbn,it)>=72})
    get(provider,"/NL/search/openApi/search.do",defaults+mapOf(
    "srchTarget" to "title","kwd" to query))
    ?.let{found.addAll(PublicLibraryBookParser.nationalLibrary(it))}
  } else if(provider==MetadataApiProvider.DATA4LIBRARY) {
   val id=isbn.filter{it in '0'..'9'}.takeIf{it.length==13}
   if(id!=null)get(provider,"/api/srchDtlList",mapOf("isbn13" to id,"format" to "json"))
    ?.let{found.addAll(PublicLibraryBookParser.data4Library(it))}
   if(found.none{MetadataTitleMatcher.score(query,author,isbn,it)>=72})
    get(provider,"/api/srchBooks",mapOf("keyword" to query,
    "pageNo" to "1","pageSize" to "30","format" to "json"))
    ?.let{found.addAll(PublicLibraryBookParser.data4Library(it))}
   val ranked=MetadataTitleMatcher.rank(query,author,isbn,found)
   for(item in ranked.take(if(exhaustive)4 else 2)) {
    if(item.description.isNotBlank()&&item.coverUrl.isNotBlank())continue
    if(item.isbn==id||!item.isbn.matches(Regex("[0-9]{13}")))continue
    get(provider,"/api/srchDtlList",mapOf("isbn13" to item.isbn,"format" to "json"))
     ?.let{PublicLibraryBookParser.data4Library(it).firstOrNull()}
     ?.let{details->
      val at=found.indexOf(item)
      if(at>=0)found[at]=MetadataSearchPipeline.fillMissing(item,details)
     }
   }
  }
  return MetadataTitleMatcher.rank(query,author,isbn,found)
 }
}
