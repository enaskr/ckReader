package com.ckchoi.ckreader

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Independently implemented search client for public book and serial metadata. */
internal data class WebBookMetadata(
 val title:String,
 val author:String="",
 val publisher:String="",
 val description:String="",
 val genre:String="",
 val coverUrl:String="",
 val source:String="",
 val sourceUrl:String="",
 val isbn:String="",
 val seriesName:String="",val seriesTotal:Int=0,val seriesIndex:Int=0,
 val translator:String="",val publishedDate:String="",val price:String=""
)

internal object MetadataTitleMatcher {
 private val trailingEdition=Regex("""(?:[\s_\-·]*(?:제\s*)?\d{1,4}\s*(?:권|화|부|회|편|vol(?:ume)?\.?|episode|chapter|ch\.?|권\s*완결))\s*$""",RegexOption.IGNORE_CASE)
 private val trailingMarker=Regex("""\s*[\[(（]?(?:리디(?:북스)?|교보|예스24|yes24|알라딘|전자책|ebook|e-book|완결|합본)[\])）]?\s*$""",RegexOption.IGNORE_CASE)
 private val prefixMarker=Regex("""^\s*(?:\[(?:웹툰|웹소설|만화|소설|전자책|ebook|리디|카카오|네이버|pdf|txt)\]\s*)+""",RegexOption.IGNORE_CASE)
 fun lookupTitle(title:String,filename:String):String {
  val value=title.ifBlank {filename.substringBeforeLast('.',filename)}
   .replace('_',' ').trim()
  var candidate=prefixMarker.replace(value,"").trim()
  // Only strip known service markers. Arbitrary parentheses may be part of a real title.
  repeat(2){candidate=trailingMarker.replace(candidate,"").trim()}
  candidate=candidate.replace(Regex("""#\d{1,9}$"""),"").trim()
  repeat(2){candidate=trailingMarker.replace(candidate,"").trim()}
  candidate=trailingEdition.replace(candidate,"").trim(' ','_','-','.')
  return candidate.take(120).trim()
 }
 fun normalized(value:String):String=Normalizer.normalize(value,Normalizer.Form.NFKC)
  .lowercase(Locale.ROOT)
  .replace(Regex("""[^가-힣a-z0-9]"""),"")
 /** Preserve word boundaries while excluding punctuation, symbols and other scripts. */
 fun searchQuery(value:String):String=Normalizer.normalize(value,Normalizer.Form.NFKC)
  .replace(Regex("""[^가-힣ㄱ-ㅎㅏ-ㅣᄀ-ᇿA-Za-z0-9]+""")," ")
  .trim().take(120).trim()
 /**
  * Deterministic similarity score. ISBN exact match outranks title, and a
  * clearly conflicting author prevents automatic selection.
  */
 fun score(query:String,author:String,isbn:String,candidate:WebBookMetadata):Int {
  val left=normalized(query)
  val right=normalized(candidate.title)
  if(left.isEmpty()||right.isEmpty())return 0
  val sameIsbn=isbn.filter(Char::isDigit).takeIf{it.length in setOf(10,13)}
   ?.let{given->candidate.isbn.filter(Char::isDigit).contains(given)}==true
  if(sameIsbn)return 100
  val score=when {
   left==right->84
   left.length>=3&&right.contains(left)->68
   right.length>=3&&left.contains(right)->58
   else->{
    val n=left.length;val m=right.length
    if(n>150||m>150)return 0
    var prev=IntArray(m+1){it}
    for(i in 1..n){
     val curr=IntArray(m+1);curr[0]=i
     for(j in 1..m)curr[j]=minOf(curr[j-1]+1,prev[j]+1,
      prev[j-1]+if(left[i-1]==right[j-1])0 else 1)
     prev=curr
    }
    (70f*(1f-prev[m].toFloat()/maxOf(n,m))).toInt()
   }
  }
  val expected=normalized(author)
  val offered=normalized(candidate.author)
  val authorScore=when{
   expected.isBlank()||offered.isBlank()->0
   expected==offered->11
   expected.contains(offered)||offered.contains(expected)->8
   else->-28
  }
  val isbnMismatch=isbn.filter(Char::isDigit).takeIf{it.length in setOf(10,13)}
   ?.let{expectedIsbn->candidate.isbn.filter(Char::isDigit)
    .takeIf{it.length in setOf(10,13)}?.let{it!=expectedIsbn}}==true
  return (score+authorScore+
   (if(candidate.coverUrl.isNotBlank())2 else 0)+
   (if(candidate.publisher.isNotBlank())2 else 0)+
   (if(candidate.seriesName.isNotBlank())1 else 0)-
   (if(isbnMismatch)32 else 0)).coerceIn(0,100)
 }
 fun rank(query:String,author:String,isbn:String,items:List<WebBookMetadata>):List<WebBookMetadata> =
  items.filter{score(query,author,isbn,it)>=35}
   .distinctBy{listOf(it.source,normalized(it.title),normalized(it.author),it.isbn)}
   .sortedByDescending{score(query,author,isbn,it)}
 fun choose(query:String,author:String,knownIsbn:String,candidates:List<WebBookMetadata>):WebBookMetadata? {
  val key=normalized(query)
  if(key.length<2)return null
  if(key.length==2&&author.isBlank()&&knownIsbn.isBlank())return null
  val isbn=knownIsbn.filter(Char::isDigit).takeIf{it.length==10||it.length==13}
  val matches=candidates.filter { normalized(it.title)==key }
   .filter { item ->
    val candidateAuthor=normalized(item.author)
    val currentAuthor=normalized(author)
    currentAuthor.isEmpty()||candidateAuthor.isEmpty()||
      candidateAuthor.contains(currentAuthor)||currentAuthor.contains(candidateAuthor)
   }.filter { item ->
    isbn==null||item.isbn.isBlank()||item.isbn.filter(Char::isDigit)==isbn
   }
  if(matches.isEmpty())return null
  val distinctAuthors=matches.map{normalized(it.author)}.filter{it.isNotBlank()}.distinct()
  // Do not auto-assign ambiguous titles with conflicting author information.
  if(author.isBlank()&&isbn==null&&distinctAuthors.size>1)return null
  return matches.maxByOrNull {
   (if(it.author.isNotBlank())3 else 0)+
   (if(it.publisher.isNotBlank())2 else 0)+
   (if(it.coverUrl.isNotBlank())2 else 0)+
   (if(it.description.isNotBlank())2 else 0)+
   (if(it.isbn.isNotBlank())1 else 0)
  }
 }
}

internal class BookMetadataSearch(private val context:android.content.Context?=null) {
 private val http=OkHttpClient.Builder().connectTimeout(5,TimeUnit.SECONDS)
  .readTimeout(7,TimeUnit.SECONDS).callTimeout(9,TimeUnit.SECONDS)
  .followRedirects(false).followSslRedirects(false).build()
 private val userAgent="ckReader/1.5 (Android book metadata client)"
 @Volatile var sawSuccess=false
  private set
 private fun readBytes(response:okhttp3.Response,maximum:Int):ByteArray {
  val input=response.body?.byteStream()?:return byteArrayOf()
  input.use {
   val output=ByteArrayOutputStream()
   val buffer=ByteArray(16*1024)
   var size=0
   while(true) {
    if(Thread.currentThread().isInterrupted)throw InterruptedException("도서정보 검색 취소")
    val n=it.read(buffer)
    if(n<0)break
    size+=n
    check(size<=maximum) {"웹 메타데이터 응답이 너무 큽니다."}
    output.write(buffer,0,n)
   }
   return output.toByteArray()
  }
 }
 private fun json(host:String,path:String,params:Map<String,String>,
                  referer:String=""):JSONObject? {
  val builder=("https://"+host+path).toHttpUrlOrNull()?.newBuilder()?:return null
  params.forEach { (key,value)->builder.addQueryParameter(key,value) }
  val request=Request.Builder().url(builder.build())
   .header("User-Agent",userAgent).header("Accept","application/json")
  if(referer.isNotBlank())request.header("Referer",referer)
  return http.newCall(request.build()).execute().use {
   if(!it.isSuccessful)return@use null
   if(!it.header("Content-Type").orEmpty().contains("json",true))return@use null
   runCatching {JSONObject(String(readBytes(it,1_500_000),Charsets.UTF_8))}.getOrNull()
    ?.also{sawSuccess=true}
  }
 }
 private fun JSONArray.objects():List<JSONObject> =
  (0 until length().coerceAtMost(30)).mapNotNull{optJSONObject(it)}
 private fun JSONObject.items(name:String):List<JSONObject> =
  (optJSONArray(name)?:JSONArray()).objects()
 private fun names(items:JSONArray?):String =
  items?.objects()?.mapNotNull{it.optString("name").takeIf(String::isNotBlank)}
   ?.distinct()?.take(4)?.joinToString(", ").orEmpty().take(120)
 private fun safe(s:String,max:Int=400):String=s.replace(Regex("<[^>]*>"),"")
  .replace(Regex("[\\u0000-\\u001f]+")," ").trim().take(max)

 internal fun fromNaver(json:JSONObject,query:String):List<WebBookMetadata> {
  val rows=ArrayList<WebBookMetadata>()
  val keys=listOf("searchWebtoonResult","searchNbooksNovelResult",
   "searchNbooksComicResult","searchBestChallengeResult","searchChallengeResult")
  for(group in keys) {
   for(item in json.optJSONObject(group)?.items("searchViewList").orEmpty()) {
    if(item.optBoolean("adult")||item.optBoolean("nineteen"))continue
    val title=safe(item.optString("titleName"),150)
    val id=item.optString("titleId").ifBlank{item.optString("contentId")}
    if(id.isBlank()||MetadataTitleMatcher.normalized(title)!=MetadataTitleMatcher.normalized(query))continue
    val author=safe(item.optString("displayAuthor").ifBlank{names(item.optJSONArray("communityArtists"))},120)
    rows.add(WebBookMetadata(title,author,"",safe(item.optString("synopsis"),700),
     "",safe(item.optString("thumbnailUrl"),500),"네이버",
     if(group=="searchWebtoonResult")"https://comic.naver.com/webtoon/list?titleId="+id
     else "https://comic.naver.com/search?keyword="+java.net.URLEncoder.encode(query,"UTF-8")))
   }
  }
  return rows.take(8)
 }
 internal fun fromKakao(json:JSONObject,query:String):List<WebBookMetadata> {
  val rows=json.optJSONObject("data")?.items("content").orEmpty()
  return rows.filter { !it.optBoolean("adult") }
   .filter{MetadataTitleMatcher.normalized(it.optString("title"))==MetadataTitleMatcher.normalized(query)}
   .mapNotNull { row ->
    val id=row.optString("id")
    if(id.isEmpty())return@mapNotNull null
    val creators=row.optJSONArray("authors")?.objects().orEmpty()
     .filter{it.optString("type") in setOf("AUTHOR","ORIGINAL_STORY","ILLUSTRATOR")}
    val author=creators.mapNotNull{it.optString("name").takeIf(String::isNotBlank)}
     .distinct().take(3).joinToString(", ")
    val cover=row.optString("sharingThumbnailImage").ifBlank{
     row.optString("thumbnailImage").ifBlank{row.optString("titleImageA")}}
    WebBookMetadata(safe(row.optString("title"),150),safe(author,120),"",
     safe(row.optString("synopsis"),700),safe(row.optString("genre"),70),
     safe(cover,500),"카카오웹툰",
     "https://webtoon.kakao.com/content/"+java.net.URLEncoder.encode(row.optString("seoId"),"UTF-8")+"/"+id)
   }.take(8)
 }
 private fun kakaoDetails(id:String):JSONObject? {
  if(id.toLongOrNull()==null)return null
  return json("gateway-kw.kakao.com","/decorator/v2/decorator/contents/$id",emptyMap(),
   "https://webtoon.kakao.com/")?.optJSONObject("data")
 }
 private fun naver(query:String):List<WebBookMetadata> =
  json("comic.naver.com","/api/search/all",mapOf("keyword" to query),
    "https://comic.naver.com/")?.let{fromNaver(it,query)}.orEmpty()
 private fun kakao(query:String):List<WebBookMetadata> {
  val results=json("gateway-kw.kakao.com","/search/v2/content",
   mapOf("word" to query,"offset" to "0","limit" to "12"),
   "https://webtoon.kakao.com/")?.let{fromKakao(it,query)}.orEmpty()
  return results.map { item ->
   val id=item.sourceUrl.substringAfterLast('/')
   val details=runCatching{kakaoDetails(id)}.getOrNull()
   if(details==null)item else {
    val updated=fromKakao(JSONObject().put("data",JSONObject().put("content",JSONArray().put(details))),query)
     .firstOrNull()
    updated?.copy(author=updated.author.ifBlank{item.author},
     coverUrl=updated.coverUrl.ifBlank{item.coverUrl},
     description=updated.description.ifBlank{item.description},
     genre=updated.genre.ifBlank{item.genre})?:item
   }
  }
 }
 internal fun fromOpenLibrary(json:JSONObject,query:String):List<WebBookMetadata> {
  return json.items("docs").filter{
   MetadataTitleMatcher.normalized(it.optString("title"))==MetadataTitleMatcher.normalized(query)
  }.map { item ->
   val cover=item.optInt("cover_i",0)
   val publisher=item.optJSONArray("publisher")?.optString(0).orEmpty()
   val author=item.optJSONArray("author_name")?.optString(0).orEmpty()
   val isbn=item.optJSONArray("isbn")?.optString(0).orEmpty()
   val key=item.optString("key")
   WebBookMetadata(safe(item.optString("title"),150),safe(author,120),
    safe(publisher,120),"","",
    if(cover>0)"https://covers.openlibrary.org/b/id/$cover-M.jpg" else "",
    "Open Library",
    if(key.matches(Regex("/works/[A-Za-z0-9]+")))"https://openlibrary.org"+key else "",isbn)
  }.take(8)
 }
 internal fun fromGoogle(json:JSONObject,query:String):List<WebBookMetadata> =
  json.items("items").mapNotNull {
   val info=it.optJSONObject("volumeInfo")?:return@mapNotNull null
   val title=safe(info.optString("title"),150)
   if(MetadataTitleMatcher.normalized(title)!=MetadataTitleMatcher.normalized(query))return@mapNotNull null
   val identifiers=info.optJSONArray("industryIdentifiers")?.objects().orEmpty()
   val isbn=identifiers.firstOrNull{it.optString("type")=="ISBN_13"}?.optString("identifier").orEmpty()
   WebBookMetadata(title,safe(info.optJSONArray("authors")?.optString(0).orEmpty(),120),
    safe(info.optString("publisher"),120),safe(info.optString("description"),700),
    safe(info.optJSONArray("categories")?.optString(0).orEmpty(),70),
    info.optJSONObject("imageLinks")?.optString("thumbnail").orEmpty()
     .replace("http://","https://"),"Google Books",
    info.optString("infoLink").takeIf{it.startsWith("https://")}.orEmpty(),isbn)
  }.take(8)
 /**
  * Full candidate mode is used by per-book manual selection. Background work
  * can stop early after a high-confidence match; all responses remain
  * suggestions rather than fabricated exact identifiers.
  */
 /** First stage: authenticated Aladin OpenAPI only. */
 fun searchAladin(title:String,author:String="",isbn:String="",
                  exhaustive:Boolean=false):List<WebBookMetadata> {
  if(BuildConfig.LITE)return emptyList()
  val query=MetadataTitleMatcher.searchQuery(title)
  if(MetadataTitleMatcher.normalized(query).length<2)return emptyList()
  val primary=DsCloudCatalogClient(context)
  val result=runCatching{primary.search(query,author,isbn,exhaustive)}.getOrDefault(emptyList())
  if(primary.contacted)sawSuccess=true
  return MetadataTitleMatcher.rank(query,author,isbn,result)
 }

 /** Last online stage, adapted from naverkakaoridi public search. */
 fun searchWebFallback(title:String,author:String="",isbn:String="",
                       exhaustive:Boolean=false):List<WebBookMetadata> {
  val query=MetadataTitleMatcher.searchQuery(title)
  if(MetadataTitleMatcher.normalized(query).length<2)return emptyList()
  val fallback=WebNovelFallbackClient()
  val matches=ArrayList<WebBookMetadata>()
  for(provider in listOf("naver_webtoon","naver_series","kakao_webtoon",
   "kakaopage","ridi","novelpia","munpia")) {
   if(Thread.currentThread().isInterrupted)throw InterruptedException("도서정보 검색 취소")
   val additional=runCatching{when(provider) {
    "naver_webtoon"->naver(query)
    "kakao_webtoon"->kakao(query)
    else->fallback.search(provider,query)
   }}.getOrDefault(emptyList())
   matches.addAll(additional)
   if(fallback.contacted)sawSuccess=true
   if(!exhaustive&&matches.any{
      MetadataTitleMatcher.score(query,author,isbn,it)>=90&&it.coverUrl.isNotBlank()
    })break
  }
  return MetadataTitleMatcher.rank(query,author,isbn,matches)
   .take(if(exhaustive)40 else 20)
 }

 private fun stage(stage:MetadataSearchStage,query:String,author:String,isbn:String,
                   exhaustive:Boolean):List<WebBookMetadata> = when(stage) {
  MetadataSearchStage.ALADIN->searchAladin(query,author,isbn,exhaustive)
  MetadataSearchStage.NATIONAL_LIBRARY,MetadataSearchStage.DATA4LIBRARY->{
   if(BuildConfig.LITE)emptyList() else {
    val client=PublicLibraryCatalogClient(context)
    val provider=if(stage==MetadataSearchStage.NATIONAL_LIBRARY)
     MetadataApiProvider.NATIONAL_LIBRARY else MetadataApiProvider.DATA4LIBRARY
    val results=client.search(provider,query,author,isbn,exhaustive)
    if(client.contacted)sawSuccess=true
    results
   }
  }
  MetadataSearchStage.WEB->searchWebFallback(query,author,isbn,exhaustive)
 }
 private fun safelyFetch(stage:MetadataSearchStage,query:String,author:String,isbn:String,
                         exhaustive:Boolean):List<WebBookMetadata> = try {
  stage(stage,query,author,isbn,exhaustive)
 }catch(e:InterruptedException){throw e}catch(_:Exception){emptyList()}

 fun searchCandidates(title:String,author:String="",isbn:String="",
                      isComic:Boolean=false,exhaustive:Boolean=false):List<WebBookMetadata> {
  sawSuccess=false
  val query=MetadataTitleMatcher.searchQuery(title)
  if(MetadataTitleMatcher.normalized(query).length<2)return emptyList()
  val stages=if(BuildConfig.LITE)listOf(MetadataSearchStage.WEB) else MetadataSearchStage.entries
  return MetadataSearchPipeline.candidates(query,author,isbn,exhaustive,stages){provider,hint->
   safelyFetch(provider,query,author,hint,exhaustive)
  }
 }
 /**
  * Explicit book-card refresh: only registered official catalogs in order
  * Aladin -> National Library -> Data4Library. No unrelated web search.
  */
 fun searchOfficial(title:String,author:String="",isbn:String="",
  acceptCover:(WebBookMetadata)->Boolean={it.coverUrl.isNotBlank()}):WebBookMetadata? {
  if(BuildConfig.LITE)return null
  sawSuccess=false
  val query=MetadataTitleMatcher.searchQuery(title)
  if(MetadataTitleMatcher.normalized(query).length<2)return null
  return MetadataSearchPipeline.resolve(query,author,isbn,{provider,hint->
   safelyFetch(provider,query,author,hint,false)
  },acceptCover,listOf(MetadataSearchStage.ALADIN,
   MetadataSearchStage.NATIONAL_LIBRARY,MetadataSearchStage.DATA4LIBRARY))
 }
 fun search(title:String,author:String="",isbn:String="",isComic:Boolean=false,
            acceptCover:(WebBookMetadata)->Boolean={it.coverUrl.isNotBlank()}):WebBookMetadata? {
  sawSuccess=false
  val query=MetadataTitleMatcher.searchQuery(title)
  if(MetadataTitleMatcher.normalized(query).length<2)return null
  return MetadataSearchPipeline.resolve(query,author,isbn,{provider,hint->
   safelyFetch(provider,query,author,hint,false)
  },acceptCover,if(BuildConfig.LITE)listOf(MetadataSearchStage.WEB) else MetadataSearchStage.entries)
 }
 fun image(url:String):ByteArray? {
  if(!BuildConfig.LITE)AladinApiRouting.cachedCoverPath(url)?.let{path->
   return AladinApiGateway(context).cover(path)
  }
  val parsed=url.toHttpUrlOrNull()?:return null
  if(parsed.scheme!="https")return null
  val host=parsed.host.lowercase(Locale.ROOT)
  val allowed=setOf("image-comic.pstatic.net","covers.openlibrary.org","books.google.com",
   "books.googleusercontent.com","books.google.co.kr","shopping-phinf.pstatic.net",
   "search.pstatic.net","image.yes24.com","image.aladin.co.kr","image.aladin.co.kr",
   "search1.kakaocdn.net","search2.kakaocdn.net","t1.daumcdn.net")
  if(host !in allowed&&!host.endsWith(".kakaopagecdn.com")&&
   !host.endsWith(".googleusercontent.com")&&!host.endsWith(".pstatic.net")&&
   !host.endsWith(".kakaocdn.net")&&!host.endsWith(".aladin.co.kr")&&
   !( !BuildConfig.LITE && (host=="nl.go.kr"||host.endsWith(".nl.go.kr")||
    host=="data4library.kr"||host.endsWith(".data4library.kr")) )&&
   host!="contents.kyobobook.co.kr"&&
   host!="page-images.kakaoentcdn.com"&&host!="novelpia.com"&&
   !host.endsWith(".ridicdn.net")&&!host.endsWith(".ridibooks.com")&&
   !host.endsWith(".munpia.com")&&host!="series.naver.com")
   return null
  val request=Request.Builder().url(parsed).header("User-Agent",userAgent).build()
  return http.newCall(request).execute().use {
   if(!it.isSuccessful)return@use null
   val type=it.header("Content-Type").orEmpty().lowercase()
   if(!(type.startsWith("image/")||type.startsWith("application/octet-stream")))return@use null
   runCatching{readBytes(it,5_000_000)}.getOrNull()
  }
 }

}
