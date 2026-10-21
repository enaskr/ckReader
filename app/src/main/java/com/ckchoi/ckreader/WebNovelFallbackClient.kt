package com.ckchoi.ckreader

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Native Android read-only adaptation of the source endpoints/parsers in
 * https://github.com/javara999/naverkakaoridi (source reviewed at v1.6.4).
 *
 * Restricted to public search; no login cookies, Cloudflare bypass, changing
 * upstream records, or BookOasis Python runtime. 403/429 are honored.
 */
internal object WebNovelFallbackParser {
 private fun clean(value:String,max:Int=200):String=Jsoup.parse(value).text()
  .replace(Regex("[\\u0000-\\u001f]+")," ").trim().take(max)
 private fun list(array:JSONArray?):List<JSONObject> =
  (0 until minOf(array?.length()?:0,20)).mapNotNull{array?.optJSONObject(it)}
 private fun names(array:JSONArray?):String=list(array).mapNotNull{
  it.optString("name").takeIf(String::isNotBlank)
 }.distinct().joinToString(", ").take(170)
 private fun validCover(value:String):String {
  val normalized=value.trim()
  if(!normalized.startsWith("https://")||normalized.length>550)return ""
  return normalized
 }
 fun kakaoPage(json:JSONObject):List<WebBookMetadata> =
  list(json.optJSONObject("result")?.optJSONArray("list")).mapNotNull{row->
   if(row.optBoolean("adult")||row.optBoolean("is_adult"))return@mapNotNull null
   val title=clean(row.optString("title"))
   val id=row.optString("series_id").ifBlank{row.optString("id")}
   if(title.isEmpty()||id.toLongOrNull()==null)return@mapNotNull null
   val value=row.optString("thumbnail")
   val cover=if(value.startsWith("https://"))value else
    if(value.isNotEmpty())"https://page-images.kakaoentcdn.com/download/resource?kid="+
     java.net.URLEncoder.encode(value,"UTF-8") else ""
   WebBookMetadata(title,clean(row.optString("authors"),170),"카카오페이지",
    clean(row.optString("description"),800),
    clean(row.optString("sub_category").ifBlank{row.optString("category")},100),
    validCover(cover),"카카오페이지","https://page.kakao.com/content/"+id,
    publishedDate=row.optString("start_sale_dt").take(20))
  }
 fun ridi(nextData:JSONObject):List<WebBookMetadata> {
  val grid=nextData.optJSONObject("props")?.optJSONObject("pageProps")
   ?.optJSONObject("gridData")?.optJSONObject("riGrid")?.optJSONObject("grid")
  val cells=list(grid?.optJSONArray("cells"))
  return cells.flatMap{list(it.optJSONObject("cell__SearchBookListWithTab")?.optJSONArray("books"))}
   .mapNotNull{item->
    val book=item.optJSONObject("book")?:return@mapNotNull null
    if(book.optBoolean("isAdultOnly"))return@mapNotNull null
    val id=item.optString("id").ifBlank{book.optString("id")}
    if(!id.matches(Regex("[A-Za-z0-9_-]{1,40}")))return@mapNotNull null
    val series=book.optJSONObject("series")
    val title=clean(series?.optString("title").orEmpty()
     .ifBlank{item.optJSONObject("title")?.optString("main").orEmpty()}
     .ifBlank{book.optString("title")})
    if(title.isBlank())return@mapNotNull null
    val thumbnail=series?.optJSONObject("thumbnail")?:book.optJSONObject("thumbnail")
    val image=thumbnail?.optString("xxlarge").orEmpty()
     .ifBlank{thumbnail?.optString("large").orEmpty()}
     .ifBlank{thumbnail?.optString("small").orEmpty()}
    WebBookMetadata(title,names(book.optJSONArray("authors")),
     clean(book.optJSONObject("publicationInfo")?.optString("name").orEmpty(),160)
      .ifBlank{"리디"},clean(book.optJSONObject("introduction")
       ?.optString("description").orEmpty(),800),
     names(book.optJSONArray("categories")).take(120),
     validCover(image),"리디","https://ridibooks.com/books/"+id,
     seriesName=clean(series?.optString("title").orEmpty(),160))
   }.take(20)
 }
 fun novelpia(json:JSONObject):List<WebBookMetadata> =
  list(json.optJSONObject("data")?.optJSONArray("search_result")).mapNotNull{item->
   if(item.optInt("novel_age")>=15)return@mapNotNull null
   val id=item.optString("novel_no")
   val title=clean(item.optString("novel_name"))
   if(id.toLongOrNull()==null||title.isBlank())return@mapNotNull null
   val raw=item.optString("novel_thumb_all").ifBlank{item.optString("novel_thumb")}
   val cover=if(raw.startsWith("/"))"https://novelpia.com"+raw else raw
   WebBookMetadata(title,clean(item.optString("mem_nick"),160),"노벨피아",
    coverUrl=validCover(cover),source="노벨피아",
    sourceUrl="https://novelpia.com/novel/"+id,
    publishedDate=item.optString("content_viewdate").take(20))
  }
 fun munpia(json:JSONObject):List<WebBookMetadata> =
  list(json.optJSONObject("result")?.optJSONArray("searchNovelTabDtos")).mapNotNull{item->
   if(item.optBoolean("adult"))return@mapNotNull null
   val id=item.optString("novelId")
   val title=clean(item.optString("title"))
   if(id.toLongOrNull()==null||title.isBlank())return@mapNotNull null
   val genres=listOf(item.optString("mainGenre"),item.optString("subGenre"))
    .filter{it.isNotBlank()}.joinToString(", ")
   WebBookMetadata(title,clean(item.optString("author"),160),"문피아",
    clean(item.optString("story"),800),clean(genres,100),
    validCover(item.optString("coverUrl")),"문피아",
    "https://www.munpia.com/novel/detail/"+id,
    publishedDate=item.optString("updateAt").take(20))
  }
 fun naverSeries(html:String,query:String):List<WebBookMetadata> {
  val document=Jsoup.parse(html)
  val ids=Regex("""/comic/detail\.series\?productNo=(\d{1,14})""")
   .findAll(html).map{it.groupValues[1]}.distinct().take(12).toList()
  return ids.mapNotNull{id->
   val element=document.selectFirst("a[href*=\"productNo=$id\"]")
    ?:return@mapNotNull null
   val title=clean(element.attr("title").ifBlank{element.text()})
   if(!MetadataTitleMatcher.normalized(title)
      .contains(MetadataTitleMatcher.normalized(query)))return@mapNotNull null
   WebBookMetadata(title,publisher="네이버시리즈",source="네이버시리즈",
    sourceUrl="https://series.naver.com/comic/detail.series?productNo="+id)
  }
 }
 fun naverSeriesDetail(html:String,id:String):WebBookMetadata? {
  if(id.toLongOrNull()==null)return null
  val doc=Jsoup.parse(html)
  val title=doc.selectFirst("meta[property=og:title]")?.attr("content")
   ?.let{Jsoup.parse(it).text()}.orEmpty().take(180)
  if(title.isBlank())return null
  val description=doc.selectFirst("meta[property=og:description]")?.attr("content")
   ?.let{Jsoup.parse(it).text()}.orEmpty().take(800)
  val cover=doc.selectFirst("meta[property=og:image]")?.attr("content").orEmpty()
  val author=Regex("""(?:글|그림|작가)\s*[:：]\s*([^,\n]+)""")
   .find(description)?.groupValues?.getOrNull(1).orEmpty()
  return WebBookMetadata(title,clean(author,150),"네이버시리즈",description,
   coverUrl=if(cover.startsWith("https://"))cover.take(550) else "",
   source="네이버시리즈",
   sourceUrl="https://series.naver.com/comic/detail.series?productNo="+id)
 }
}

/** All public hosts are fixed. Each upstream failure affects only that source. */
internal class WebNovelFallbackClient {
 private val http=OkHttpClient.Builder()
  .connectTimeout(3,TimeUnit.SECONDS).readTimeout(5,TimeUnit.SECONDS)
  .callTimeout(7,TimeUnit.SECONDS).followRedirects(false).build()
 private val hosts=setOf("series.naver.com","bff-page.kakao.com",
  "ridibooks.com","novelpia.com","www.munpia.com")
 private val blockedUntil=Companion.blockedUntil
 companion object {
  private val blockedUntil=ConcurrentHashMap<String,Long>()
 }
 @Volatile var contacted=false
  private set
 private fun fetch(url:HttpUrl,referer:String,post:Boolean=false,max:Int=2_500_000):String? {
  if(url.scheme!="https"||url.host !in hosts||url.port!=443)return null
  val host=url.host
  if((blockedUntil[host]?:0L)>System.currentTimeMillis())return null
  val request=Request.Builder().url(url)
   .header("Accept","application/json,text/html;q=0.9,*/*;q=0.8")
   .header("User-Agent","Mozilla/5.0 (Linux; Android 15; Mobile) AppleWebKit/537.36 Chrome/132.0.0.0 Mobile Safari/537.36")
   .header("Referer",referer)
  if(post)request.post(byteArrayOf().toRequestBody("application/x-www-form-urlencoded".toMediaType()))
  return http.newCall(request.build()).execute().use{response->
   if(response.code in setOf(403,429,503)||response.code in 300..399){
    blockedUntil[host]=System.currentTimeMillis()+30L*60*1000
    return@use null
   }
   if(!response.isSuccessful)return@use null
   val kind=response.header("Content-Type").orEmpty().lowercase()
   if(!kind.contains("json")&&!kind.contains("html")&&!kind.contains("text/plain"))
    return@use null
   contacted=true
   val stream=response.body?.byteStream()?:return@use null
   stream.use {
    val output=ByteArrayOutputStream()
    val buffer=ByteArray(8192)
    while(true) {
     if(Thread.currentThread().isInterrupted)throw InterruptedException()
     val n=it.read(buffer);if(n<0)break
     if(output.size()+n>max)return@use null
     output.write(buffer,0,n)
    }
    String(output.toByteArray(),Charsets.UTF_8)
   }
  }
 }
 private fun url(host:String,path:String,params:Map<String,String> = emptyMap()):HttpUrl{
  val b=("https://"+host+path).toHttpUrlOrNull()!!.newBuilder()
  params.forEach{(k,v)->b.addQueryParameter(k,v)}
  return b.build()
 }
 private fun json(url:HttpUrl,referer:String,post:Boolean=false):JSONObject? =
  fetch(url,referer,post)?.let{runCatching{JSONObject(it)}.getOrNull()}
 private fun series(query:String):List<WebBookMetadata> {
  val search=url("series.naver.com","/search/search.series",
   mapOf("t" to "all","fs" to "comic","q" to query))
  val page=fetch(search,"https://series.naver.com/")?:return emptyList()
  val first=WebNovelFallbackParser.naverSeries(page,query)
  return first.take(4).map{brief->
   val id=brief.sourceUrl.substringAfter("productNo=")
   val detail=fetch(url("series.naver.com","/comic/detail.series",
    mapOf("productNo" to id)),"https://series.naver.com/")
    ?.let{WebNovelFallbackParser.naverSeriesDetail(it,id)}
   detail?:brief
  }
 }
 private fun kakaoPage(query:String):List<WebBookMetadata> {
  val params=mapOf("keyword" to query,"category_uid" to "0","is_complete" to "false",
   "sort_type" to "ACCURACY","page" to "0","size" to "15")
  val url=url("bff-page.kakao.com","/api/gateway/api/v2/search/series",params)
  return json(url,"https://page.kakao.com/")?.let(WebNovelFallbackParser::kakaoPage)
   .orEmpty()
 }
 private fun ridi(query:String):List<WebBookMetadata> {
  val url=url("ridibooks.com","/search",mapOf("q" to query))
  val text=fetch(url,"https://ridibooks.com/")?:return emptyList()
  val next=Jsoup.parse(text).getElementById("__NEXT_DATA__")?.data()
   ?.takeIf{it.length<2_000_000}?:return emptyList()
  return runCatching{WebNovelFallbackParser.ridi(JSONObject(next))}
   .getOrDefault(emptyList())
 }
 private fun novelpia(query:String):List<WebBookMetadata> {
  val encoded=java.net.URLEncoder.encode(query,"UTF-8").replace("+","%20")
  return json(url("novelpia.com","/proc/novelsearch/"+encoded),
   "https://novelpia.com/",post=true)
   ?.let(WebNovelFallbackParser::novelpia).orEmpty()
 }
 private fun munpia(query:String):List<WebBookMetadata> =
  json(url("www.munpia.com","/api/v1/main/search",mapOf("query" to query)),
   "https://www.munpia.com/")?.let(WebNovelFallbackParser::munpia).orEmpty()

 /** Each server may change or block undocumented endpoints. */
 fun search(source:String,title:String):List<WebBookMetadata> {
  val query=MetadataTitleMatcher.searchQuery(title)
  if(MetadataTitleMatcher.normalized(query).length<2)return emptyList()
  return runCatching{when(source){
   "naver_series"->series(query)
   "kakaopage"->kakaoPage(query)
   "ridi"->ridi(query)
   "novelpia"->novelpia(query)
   "munpia"->munpia(query)
   else->emptyList()
  }}.getOrDefault(emptyList())
 }
}
