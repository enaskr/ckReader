package com.ckchoi.ckreader

import android.content.Context
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject

/**
 * Metadata-only on-device overlay. YAML/source data always wins. Reading state
 * stays independent from the original book and book-list snapshot files.
 */
internal class BookMetadataStore(private val context:Context) {
 companion object {val PIPELINE=if(BuildConfig.LITE)"yaml-web-lite-v1" else "yaml-aladin-nl-data4library-web-v3"}
 private val prefs=context.getSharedPreferences("ckreader_metadata",Context.MODE_PRIVATE)
 private val root=File(context.filesDir,"web-book-metadata")
 private val coverRoot=File(context.cacheDir,"web-book-covers")
 private val lock=Any()
 private val durable=PersistentLibraryCache(context)
 private val bookCovers by lazy {BookCoverStore(context)}

 /** Only successfully saved metadata counts. A failed/negative search is retryable. */
 fun hasCompletedCache(book:LibraryBook):Boolean =
  record(book)?.optString("status") in setOf("matched","chosen")

 fun enabled():Boolean=prefs.getBoolean("autosearch",true)
 fun setEnabled(value:Boolean) {prefs.edit().putBoolean("autosearch",value).apply()}
 private fun sha(value:String):String=MessageDigest.getInstance("SHA-256")
  .digest(value.toByteArray()).joinToString(""){"%02x".format(it)}
 private fun fingerprint(source:LibrarySource):String=sha(source.id+"|"+source.type+"|"+
  source.location+"|"+source.user+"|"+sha(source.secret)+"|"+sha(source.extra))
 private fun file(book:LibraryBook):File =
  File(File(root,sha(book.source.id)),sha(book.id)+"-v2.json")
 private fun record(book:LibraryBook):JSONObject?=synchronized(lock) {
  // SQLite persists metadata indefinitely and is authoritative over legacy JSON.
  durable.getMetadata(book)?.takeIf {
   it.optString("fingerprint")==fingerprint(book.source) &&
    it.optString("path")==book.path
  }?.let{return@synchronized it}
  val file=file(book)
  if(!file.isFile||file.length() !in 2L..20_000L)return@synchronized null
  val legacy=runCatching{JSONObject(file.readText(Charsets.UTF_8)).takeIf{
   it.optString("fingerprint")==fingerprint(book.source) &&
   it.optString("path")==book.path
  }}.getOrNull()
  if(legacy!=null)durable.putMetadata(book,legacy)
  legacy
 }
 fun enrich(book:LibraryBook):LibraryBook {
  val metadata=record(book)?.takeIf{
   it.optString("status") in setOf("matched","chosen")
  }?:return book
  val item=metadata.optJSONObject("result")?:return book
  // A manually chosen match is authoritative; automatic matches only fill
  // empty fields, preserving local YAML and source-provided metadata.
  val chosen=BookMetadataPriority.mayReplaceExisting(
   metadata.optString("status"),book.source.type,book.yamlPreferred)
  if(chosen||book.title.isBlank())book.title=item.optString("title").ifBlank{book.title}
  if(chosen||book.author.isBlank())book.author=item.optString("author").ifBlank{book.author}
  if(chosen||book.publisher.isBlank())book.publisher=item.optString("publisher").ifBlank{book.publisher}
  if(chosen||book.summary.isBlank())book.summary=item.optString("description").ifBlank{book.summary}
  if(chosen||book.genre.isBlank())book.genre=item.optString("genre").ifBlank{book.genre}
  if(chosen||book.isbn.isBlank())book.isbn=item.optString("isbn").ifBlank{book.isbn}
  if(chosen||book.webCover.isBlank())book.webCover=item.optString("coverUrl").ifBlank{book.webCover}
  if(chosen||book.metadataSource.isBlank())book.metadataSource=item.optString("source").ifBlank{book.metadataSource}
  if(chosen||book.metadataUrl.isBlank())book.metadataUrl=item.optString("sourceUrl").ifBlank{book.metadataUrl}
  if(chosen||book.translator.isBlank())book.translator=item.optString("translator").ifBlank{book.translator}
  if(chosen||book.seriesName.isBlank())book.seriesName=item.optString("seriesName").ifBlank{book.seriesName}
  if(chosen||book.seriesTotal==0)book.seriesTotal=item.optInt("seriesTotal").takeIf{it>0}?:book.seriesTotal
  if(chosen||book.seriesIndex==0)book.seriesIndex=item.optInt("seriesIndex").takeIf{it>0}?:book.seriesIndex
  if(chosen||book.publishedDate.isBlank())book.publishedDate=item.optString("publishedDate").ifBlank{book.publishedDate}
  if(chosen||book.price.isBlank())book.price=item.optString("price").ifBlank{book.price}
  SeriesVolumeTitle.apply(book)
  return book
 }
 fun eligible(book:LibraryBook):Boolean {
  if(!enabled())return false
  // A visible/existing cover makes all metadata immutable to automatic search.
  // Skip the disk DB probe when the listing already has a cover reference.
  if(!BookMetadataCoverPolicy.shouldAutoSearch(book,
     if(book.cover.isNotBlank()||book.webCover.isNotBlank())false
     else bookCovers.has(book)))return false
  val query=MetadataTitleMatcher.lookupTitle(book.title,book.filename)
  if(MetadataTitleMatcher.normalized(query).length<2)return false
  val record=record(book)
  // Preserve an explicit user's selection from any previous version.
  if(record?.optString("status")=="chosen")return false
  // Old unmatched and machine-matched entries must be refreshed by the
  // new ordered pipeline, without clearing the existing cache database.
  if(record!=null&&record.optString("pipeline")!=PIPELINE)return true
  if(!BuildConfig.LITE&&record!=null&&record.optInt("aladinKeyRevision",-1)!=AladinApiCredentials(context).revision())
   return true
  if(!BuildConfig.LITE&&record!=null&&record.optString("libraryKeyRevision")!=libraryKeyRevision())return true
  if(book.title.isNotBlank()&&book.author.isNotBlank()&&book.publisher.isNotBlank()&&
     (book.cover.isNotBlank()||book.webCover.isNotBlank())&&book.summary.isNotBlank())
   return false
  if(record==null)return true
  val now=System.currentTimeMillis()
  val last=record.optLong("attemptedAt")
  // A conclusive match or no-result is cached until the user explicitly
  // clears the DB. Only transient network errors are retried later.
  if(record.optString("status") in listOf("chosen","none"))return false
  // Text-only matches and failed cover downloads can retry after a day.
  return now-last>=24L*60*60*1000
 }
 private fun libraryKeyRevision():String=if(BuildConfig.LITE)"" else listOf(MetadataApiProvider.NATIONAL_LIBRARY,
  MetadataApiProvider.DATA4LIBRARY).joinToString(":"){AladinApiCredentials(context,it).revision().toString()}
 internal fun exportFields(book:LibraryBook):Map<String,String>? {
  val value=record(book)?.takeIf{it.optString("status") in setOf("matched","chosen")}?:return null
  val data=value.optJSONObject("result")?:return null
  if(data.optString("title").isBlank())return null
  val aliases=linkedMapOf("title" to "title","author" to "author","publisher" to "publisher",
   "summary" to "description","genre" to "genre","isbn" to "isbn","translator" to "translator",
   "series_name" to "seriesName","series_index" to "seriesIndex","series_total" to "seriesTotal",
   "publishedDate" to "publishedDate","price" to "price","metadataUrl" to "sourceUrl",
   "metadata_source" to "source","cover" to "coverUrl")
  return aliases.mapNotNull{(target,key)->data.optString(key).takeIf{it.isNotBlank()&&
   (target !in setOf("series_index","series_total")||it.toIntOrNull()?.let{number->number>0}==true)}?.let{target to it}}.toMap()
 }
 internal fun migrate(old:LibraryBook,current:LibraryBook) {
  record(old)?.let{original->
   val updated=JSONObject(original.toString()).put("path",current.path).put("fingerprint",fingerprint(current.source))
   durable.putMetadata(current,updated)
  }
 }
 fun recordResult(book:LibraryBook,data:WebBookMetadata?,
                  temporaryFailure:Boolean=false,selected:Boolean=false) {
  // Preserve a confirmed match when a later network retry times out.
  if(!selected&&record(book)?.optString("status")=="chosen")return
  if(data==null&&temporaryFailure&&record(book)?.optString("status")=="matched")return
  val value=JSONObject().put("pipeline",PIPELINE)
   .put("libraryKeyRevision",libraryKeyRevision())
   .put("aladinKeyRevision",if(BuildConfig.LITE)0 else AladinApiCredentials(context).revision()).put("path",book.path)
   .put("fingerprint",fingerprint(book.source))
   .put("status",if(data!=null&&selected)"chosen" else if(data!=null)"matched"
    else if(temporaryFailure)"error" else "none")
   .put("attemptedAt",System.currentTimeMillis())
  if(data!=null)value.put("result",JSONObject()
   .put("title",data.title.take(180)).put("author",data.author.take(200))
   .put("publisher",data.publisher.take(200))
   .put("description",data.description.take(800))
   .put("genre",data.genre.take(120)).put("isbn",data.isbn.take(30))
   .put("coverUrl",data.coverUrl.take(550))
   .put("source",data.source.take(120)).put("sourceUrl",data.sourceUrl.take(550))
   .put("seriesName",data.seriesName.take(180))
   .put("seriesTotal",data.seriesTotal).put("seriesIndex",data.seriesIndex)
   .put("translator",data.translator.take(180))
   .put("publishedDate",data.publishedDate.take(22))
   .put("price",data.price.take(40)))
  synchronized(lock) {
   durable.putMetadata(book,value)
   val output=file(book)
   output.parentFile?.mkdirs()
   val pending=File(output.parentFile,output.name+".tmp")
   pending.writeText(value.toString(),Charsets.UTF_8)
   if(!pending.renameTo(output)){
    output.delete()
    if(!pending.renameTo(output))error("도서정보 캐시 저장 실패")
   }
  }
 }
 fun clearForSource(sourceId:String,strict:Boolean=false) {
  synchronized(lock){
   val directory=File(root,sha(sourceId))
   if(directory.exists()&&!directory.deleteRecursively()&&strict)
    error("도서정보 캐시를 삭제하지 못했습니다.")
  }
 }
 fun clearAll() {
  synchronized(lock){
   durable.clearMetadata()
   root.deleteRecursively()
   coverRoot.deleteRecursively()
  }
 }
 fun remoteCover(url:String):ByteArray? {
  val image=File(coverRoot,sha(url)+".bin")
  if(image.isFile&&image.length() in 24L..5_000_000L &&
    System.currentTimeMillis()-image.lastModified()<30L*24*60*60*1000)
   return image.readBytes()
  val bytes=BookMetadataSearch(context).image(url)?:return null
  if(bytes.size !in 24..5_000_000)return null
  runCatching {
   coverRoot.mkdirs()
   val temp=File(coverRoot,image.name+".tmp")
   temp.writeBytes(bytes)
   if(!temp.renameTo(image)){
    image.delete()
    temp.renameTo(image)
   }
   val stored=coverRoot.listFiles()?.filter{it.isFile && it.extension=="bin"}
    ?.sortedBy{it.lastModified()}.orEmpty()
   var current=stored.sumOf{it.length()}
   for(file in stored){
    if(current<=120L*1024*1024)break
    val size=file.length()
    if(file.delete())current-=size
   }
  }
  return bytes
 }
}
