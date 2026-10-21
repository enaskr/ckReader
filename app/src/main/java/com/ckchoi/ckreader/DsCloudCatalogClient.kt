package com.ckchoi.ckreader

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * User-specified Aladin catalog. Prefer authenticated HTTPS, with a
 * best-effort unauthenticated HTTP fallback only for connection failures.
 * The public cover URLs are always the authenticated HTTPS address.
 */
internal object DsCloudBookParser {
 const val BASE=AladinApiRouting.PRIMARY
 private val coverPath=Regex("^/covers/[0-9]{1,13}$")
 private fun text(value:String,max:Int):String=value
  .replace(Regex("[\\u0000-\\u001f]+")," ")
  .replace(Regex("<[^>]*>"),"").trim().take(max)
 private fun nonNull(o:JSONObject,name:String)=o.optString(name).takeUnless{it=="null"}.orEmpty()
 private fun isbnOf(data:JSONObject):String {
  val ebook=nonNull(data,"ebook_isbn")
  val paper=nonNull(data,"book_isbn")
  return ebook.ifBlank{paper}.filter{it.isDigit()||it=='X'}.take(18)
 }
 internal fun parseBook(o:JSONObject):WebBookMetadata? {
  if(o.optInt("is_adult",0)!=0||o.optBoolean("is_adult",false))return null
  val id=o.optLong("item_id",0)
  val title=text(nonNull(o,"title"),180)
  if(id !in 1..9_999_999_999L||title.isBlank())return null
  val localCover=nonNull(o,"cover_image_url")
  val cover=when {
   coverPath.matches(localCover)->BASE+localCover
   // Aladin image links are the only allowed external-image fallback.
   nonNull(o,"cover_url").startsWith("https://image.aladin.co.kr/")->
    nonNull(o,"cover_url").take(550)
   else->""
  }
  return WebBookMetadata(
   title=title,
   author=text(nonNull(o,"authors").replace(';',','),170),
   publisher=text(nonNull(o,"publisher"),160),
   description=text(nonNull(o,"description"),800),
   genre=text(nonNull(o,"category_name"),120),
   coverUrl=cover,
   source="알라딘 OpenAPI",
   sourceUrl="https://www.aladin.co.kr/shop/wproduct.aspx?ItemId="+id,
   isbn=isbnOf(o),
   seriesName=text(nonNull(o,"series_name"),160),
   publishedDate=nonNull(o,"publish_date").take(22))
 }
 internal fun parseItems(obj:JSONObject):List<WebBookMetadata> {
  val array=obj.optJSONArray("items")?:return emptyList()
  return (0 until minOf(array.length(),100)).mapNotNull{array.optJSONObject(it)?.let(::parseBook)}
 }
 internal fun combine(brief:WebBookMetadata,details:WebBookMetadata):WebBookMetadata =
  brief.copy(
   author=details.author.ifBlank{brief.author},
   publisher=details.publisher.ifBlank{brief.publisher},
   description=details.description.ifBlank{brief.description},
   genre=details.genre.ifBlank{brief.genre},
   coverUrl=details.coverUrl.ifBlank{brief.coverUrl},
   isbn=details.isbn.ifBlank{brief.isbn},
   seriesName=details.seriesName.ifBlank{brief.seriesName},
   publishedDate=details.publishedDate.ifBlank{brief.publishedDate}
  )
}
internal class DsCloudCatalogClient(context:android.content.Context?=null) {
 private val gateway=AladinApiGateway(context)
 @Volatile var contacted=false
  private set

 private fun get(path:String,parameters:Map<String,String> = emptyMap()):JSONObject? {
  val response=gateway.request(path,parameters)
  val bytes=response.bytes?:return null
  return runCatching{JSONObject(String(bytes,Charsets.UTF_8))}.getOrNull()?.also{
   contacted=true
  }
 }
 fun search(title:String,author:String,isbn:String,exhaustive:Boolean=false):List<WebBookMetadata> {
  val key=MetadataTitleMatcher.searchQuery(title)
  if(MetadataTitleMatcher.normalized(key).length<2)return emptyList()
  val found=LinkedHashMap<String,WebBookMetadata>()
  val normalizedIsbn=isbn.uppercase().filter{it.isDigit()||it=='X'}
  if(normalizedIsbn.matches(Regex("([0-9]{13}|[0-9]{9}[0-9X])"))) {
   runCatching{get("/api/isbn/"+normalizedIsbn)}
    .getOrNull()?.let(DsCloudBookParser::parseItems)?.forEach{
     found[it.sourceUrl]=it
   }
  }
  if(!found.values.any{MetadataTitleMatcher.score(key,author,normalizedIsbn,it)>=85}) {
   // Consult two bounded title pages to avoid missing books behind newer results.
   for(page in 1..2) {
    val response=runCatching{get("/api/books",mapOf("q" to key,"field" to "title",
     "page" to page.toString(),"limit" to "40","sort" to "title",
     "include_adult" to "false"))}.getOrNull()?:break
    DsCloudBookParser.parseItems(response).forEach{found[it.sourceUrl]=it}
    if(response.optBoolean("has_next")!=true)break
    if(!exhaustive&&found.values.any{
      MetadataTitleMatcher.score(key,author,normalizedIsbn,it)>=94
    })break
   }
  }
  val ranked=MetadataTitleMatcher.rank(key,author,normalizedIsbn,found.values.toList())
   .take(if(exhaustive)24 else 8)
  // The list response does not include book introduction/series metadata.
  // Enrich only the top 2-4 ranked candidates to avoid excessive requests.
  val count=if(exhaustive)minOf(4,ranked.size) else minOf(2,ranked.size)
  return ranked.mapIndexed{index,brief->
   if(index>=count)brief else {
    val id=brief.sourceUrl.substringAfter("ItemId=").toLongOrNull()
    val detail=if(id!=null)runCatching{get("/api/books/$id")}.getOrNull()
      ?.let(DsCloudBookParser::parseBook) else null
    if(detail==null)brief else DsCloudBookParser.combine(brief,detail)
   }
  }
 }
}
