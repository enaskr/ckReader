package com.ckchoi.ckreader

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.documentfile.provider.DocumentFile
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class LibrarySource(val id:String, val type:String, val name:String, val location:String,
                         val user:String="", val secret:String="", val extra:String="") {
 val readOnly:Boolean=type.equals("sftp",true)||type.equals("gds",true)||(type=="drive"&&runCatching {
  val options=JSONObject(extra)
  (options.optBoolean(GoogleDriveSignIn.AUTH_KEY)&&!DriveWritePolicy.googleWriteScope(extra))||
   options.optBoolean("ckreader_gds")||options.optString("type").equals("gds",true)||
   options.optString("gds_endpoint").isNotBlank()
 }.getOrDefault(false))
 fun json()=JSONObject().put("id",id).put("type",type).put("name",name).put("location",location)
  .put("user",user).put("secret",secret).put("extra",extra)
 companion object { fun from(o:JSONObject)=LibrarySource(o.getString("id"),o.getString("type"),o.getString("name"),
  o.getString("location"),o.optString("user"),o.optString("secret"),o.optString("extra")) }
}

data class LibraryBook(val id:String, val source:LibrarySource, val path:String, val filename:String,
                       val uri:Uri?=null, var title:String="", var author:String="", var publisher:String="",
                       var bookId:String="", var cover:String="", var pageCount:Int=0,
                       var summary:String="", var genre:String="", var isbn:String="",
                       var metadataSource:String="", var metadataUrl:String="", var webCover:String="",
                       var seriesName:String="",var seriesTotal:Int=0,var seriesIndex:Int=0,
                       var translator:String="",var publishedDate:String="",var price:String="",
                       var cachePartition:String="",var sizeBytes:Long=-1L,
                       var yamlPreferred:Boolean=false,var yamlRevision:Int=0) {
 val format:String get()=filename.substringAfterLast('.').lowercase()
 val shownTitle:String get()=SeriesVolumeTitle.title(filename,title).ifBlank { filename }
}

data class RecentBook(val book:LibraryBook,val folder:String,val openedAt:Long)

data class FolderCatalogInfo(val author:String="",val publisher:String="",
                             val volumeCount:Int=0,val summary:String="") {
 fun toJson():JSONObject=JSONObject().put("author",author).put("publisher",publisher)
  .put("volumeCount",volumeCount).put("summary",summary)
 companion object {
  fun fromJson(json:JSONObject)=FolderCatalogInfo(
   json.optString("author").take(180),json.optString("publisher").take(180),
   json.optInt("volumeCount").coerceAtLeast(0),
   json.optString("summary").take(400))
 }
}
data class LibraryListing(val folders:List<String>, val books:List<LibraryBook>, val scannedAt:Long=System.currentTimeMillis(), val hasMore:Boolean=false, val page:Int=1,
                          val folderCovers:Map<String,String> = emptyMap(),
                          val folderInfos:Map<String,FolderCatalogInfo> = emptyMap())

class LibraryRepository(private val context:Context) {
 private val prefs=context.getSharedPreferences("ckreader_library",Context.MODE_PRIVATE)
 init {
  // The new catalog requires no secrets. Clear retired API token settings
  // left by older releases without modifying bookstore/storage credentials.
  runCatching {
   val old=context.getSharedPreferences("ckreader_metadata_api",Context.MODE_PRIVATE)
   if(old.all.isNotEmpty())old.edit().clear().commit()
   val keystore=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
   if(keystore.containsAlias("ckreader_metadata_api_v1"))
    keystore.deleteEntry("ckreader_metadata_api_v1")
  }
 }
 private val alias="ckreader_sources"
 private val rclone by lazy { DriveAccess(context,this) }
 private val folderStorage by lazy { FolderYamlStorage(context,this) }
 private val emptyRemoteFolders=RemoteBookFolderFilter()
 private val metadata by lazy { BookMetadataStore(context) }
 private val folderThumbnails by lazy { FolderThumbnailStore(context) }
 private val bookCovers by lazy { BookCoverStore(context) }
 private val durable by lazy { PersistentLibraryCache(context) }
 private val bookOasisIndex by lazy {BookOasisMetadataDb(context)}
 fun isDocumentsCacheLinked()=durable.isLinked()
 fun cacheLocationText()=durable.locationText()
 fun linkCacheDocuments(uri:Uri)=durable.linkSelectedTree(uri)
 fun cacheFolderPrompted()=durable.hasBeenPrompted()
 fun markCacheFolderPrompted()=durable.markPrompted()
 fun clearAllPersistentCache() {
  sources().forEach{source->
   clearCache(source.id)
   durable.clearSource(source.id)
   bookCovers.clearForSource(source.id)
  }
  metadata.clearAll()
 }
 fun hasCachedBookCover(book:LibraryBook):Boolean=bookCovers.has(book)
 /** Listing entries and remote image URLs alone are not a completed cache. */
 fun hasGeneratedBookCache(book:LibraryBook):Boolean =
  bookCovers.has(book) || metadata.hasCompletedCache(book)
 internal fun invalidateListingMemory(sourceId:String) {
  listingMemory.removeSource(sourceId)
  bookCovers.forgetMemory()
 }
 fun cachedBookThumbnail(book:LibraryBook):ByteArray?=bookCovers.read(book)
 fun cachedFolderThumbnail(source:LibrarySource,path:String,hint:String=""):ByteArray? {
  // Repair old folder BLOBs from current authored book covers, without I/O to
  // the source. This also handles work done by another repository/Activity.
  promoteYamlFolderThumbnail(source,path)?.let{return it}
  cachedBookOasisFolderThumbnail(source,path,hint)?.let{return it}
  folderThumbnails.cached(source,path,hint)?.let{return it}
  return null
 }
 private fun notifyFolderCover(source:LibrarySource,path:String) {
  context.sendBroadcast(Intent(BookMetadataWorker.UPDATED).setPackage(context.packageName)
   .putExtra("source",source.id).putExtra("folderCoverPath",path))
 }
 /** Cache-only: an old series image must not hide a newer detail-cover BLOB. */
 private fun cachedBookOasisFolderThumbnail(source:LibrarySource,path:String,hint:String):ByteArray? {
  if(source.type!="bookoasis")return null
  val route=runCatching{BookOasisPaths.parse(path)}.getOrNull()?:return null
  if(route.level!=2)return null
  val book=BookOasisCoverPolicy.representative(path,cached(source,path)?.books.orEmpty())?:return null
  // Other repository instances can replace the BLOB while bookCovers still has an old LRU entry.
  val bytes=durable.readCover(book)?:return null
  val changed=folderThumbnails.retain(source,path,"",bytes)
  if(hint.isNotBlank())folderThumbnails.retain(source,path,hint,bytes)
  if(changed)notifyFolderCover(source,path)
  return bytes
 }
 /** Called after detail lookup, including cache hits, before returning to the catalog. */
 fun refreshBookOasisSeriesThumbnail(book:LibraryBook,seriesPath:String) {
  if(book.source.type!="bookoasis")return
  val books=cached(book.source,seriesPath)?.books.orEmpty()
  if(books.none{it.id==book.id})return
  if(cachedBookOasisFolderThumbnail(book.source,seriesPath,"")!=null)
   // Also discard an already displayed LRU bitmap when the DB was updated earlier.
   notifyFolderCover(book.source,seriesPath)
 }
 private fun promoteYamlFolderThumbnail(source:LibrarySource,path:String,books:List<LibraryBook>?=null):ByteArray? {
  if(!FolderYamlMetadata.supports(source.type)||!LibraryVisibility.visible(path))return null
  val siblings=(books?:cached(source,path,fresh=true)?.books.orEmpty()).filter{it.path.substringBeforeLast('/',"")==path}
  for(book in FolderCoverPolicy.yamlBooks(source.type,siblings)) {
   // SQLite is authoritative across repository instances; their book LRUs may
   // still contain the preceding image while a worker has already replaced it.
   val bytes=durable.readCover(book)?:continue
   if(folderThumbnails.retain(source,path,"",bytes))context.sendBroadcast(Intent(BookMetadataWorker.UPDATED)
    .setPackage(context.packageName).putExtra("source",source.id).putExtra("folderCoverPath",path))
   return bytes
  }
  return null
 }
 private fun promoteYamlBookCover(book:LibraryBook) {
  val folder=book.path.substringBeforeLast('/',"")
  if(!LibraryVisibility.visible(folder))return
  val siblings=cached(book.source,folder,fresh=true)?.books.orEmpty().filter{it.id!=book.id}+book
  val authored=FolderCoverPolicy.yamlBooks(book.source.type,siblings)
  if(authored.isNotEmpty()) {
   promoteYamlFolderThumbnail(book.source,folder,siblings)
   return
  }
  // Keep file/folder thumbnails in the same durable SQLite cache. The first
  // sorted book represents a folder unless its cover is explicitly authored.
  // Prefer the first book when available, but never leave the folder blank
  // merely because its first book has not acquired a cover yet.
  val first=LibraryTitleOrder.books(siblings).firstOrNull()
  if(first?.id!=book.id&&folderThumbnails.cached(book.source,folder,"")!=null)return
  val image=durable.readCover(book)?:return
  if(folderThumbnails.retain(book.source,folder,"",image))notifyFolderCover(book.source,folder)
 }
 private fun cacheBookCover(book:LibraryBook,bytes:ByteArray):Boolean {
  if(!bookCovers.put(book,bytes))return false
  promoteYamlBookCover(book)
  return true
 }
 fun applyWebMetadata(book:LibraryBook):LibraryBook=metadata.enrich(book)
 fun metadataEnabled()=metadata.enabled()
 fun setMetadataEnabled(value:Boolean)=metadata.setEnabled(value)
 fun clearWebMetadata()=metadata.clearAll()
 internal fun cacheMetadata(book:LibraryBook,result:WebBookMetadata?,failure:Boolean=false) {
  metadata.recordResult(book,result,failure)
  if(result!=null)requestYamlWriteback(book)
 }
 internal fun requestYamlWriteback(book:LibraryBook) {
  if(!FolderYamlMetadata.supports(book.source.type))return
  val folder=if(book.source.type=="local")"" else book.path.substringBeforeLast('/',"")
  cached(book.source,folder)?.let{BookMetadataWorker.enqueue(context,book.source,folder,"",it)}
 }
 internal fun exportFolderRecord(book:LibraryBook):FolderYamlExport.Record? {
  val web=metadata.exportFields(book)?:return null
  val fields=web.toMutableMap()
  if(book.yamlPreferred) {
   mapOf("title" to book.title,"author" to book.author,"publisher" to book.publisher,"summary" to book.summary,
    "genre" to book.genre,"isbn" to book.isbn,"translator" to book.translator,"series_name" to book.seriesName,
    "series_index" to book.seriesIndex.takeIf{it>0}?.toString().orEmpty(),"series_total" to book.seriesTotal.takeIf{it>0}?.toString().orEmpty(),
    "publishedDate" to book.publishedDate,"price" to book.price).filterValues{it.isNotBlank()}.let(fields::putAll)
  }
  var cover=bookCovers.read(book)
  if(cover==null&&book.yamlPreferred)runCatching{originalCover(book)}.getOrNull()?.let{if(cacheBookCover(book,it))cover=bookCovers.read(book)}
  return FolderYamlExport.Record(book.filename,fields,cover)
 }
 internal fun bookAt(source:LibrarySource,path:String):LibraryBook =
  cached(source,if(source.type=="local")"" else path.substringBeforeLast('/',""))?.books?.firstOrNull{it.path==path}
   ?:metadata.enrich(book(source,path))
 internal fun migrateMovedBook(old:LibraryBook,path:String):LibraryBook {
  val current=old.copy(id=book(old.source,path).id,path=path,filename=path.substringAfterLast('/'),uri=null,cachePartition="",yamlRevision=0)
  metadata.migrate(old,current)
  bookCovers.read(old)?.let{durable.putCover(current,it)}
  val reader=context.getSharedPreferences("crema_reader",Context.MODE_PRIVATE)
  val oldHash=sha(old.source.id+":"+old.path).take(24);val newHash=sha(old.source.id+":"+path).take(24)
  val edit=reader.edit()
  for((key,value) in reader.all.filterKeys{it.contains(oldHash)}) {
   val next=key.replace(oldHash,newHash)
   when(value){is String->edit.putString(next,value);is Int->edit.putInt(next,value);is Long->edit.putLong(next,value);is Float->edit.putFloat(next,value);is Boolean->edit.putBoolean(next,value);is Set<*>->edit.putStringSet(next,value.filterIsInstance<String>().toSet())}
  }
  check(edit.commit())
  if(prefs.contains("last_"+old.id))check(prefs.edit().putLong("last_"+current.id,prefs.getLong("last_"+old.id,0L)).commit())
  val oldHidden="hidden_"+sha(old.id)
  if(prefs.contains(oldHidden))check(prefs.edit().putBoolean("hidden_"+sha(current.id),prefs.getBoolean(oldHidden,false)).commit())
  val files=File(context.cacheDir,"library-books")
  val previous=File(files,old.id+"."+old.format);val next=File(files,current.id+"."+current.format)
  if(previous.isFile&&!next.exists())previous.renameTo(next)
  return current
 }
 internal fun refreshOrganizedFolders(source:LibrarySource,folder:String,targets:Set<String>) {
  invalidateListingMemory(source.id)
  browse(source,if(source.type=="local")"" else folder)
  if(source.type!="local")targets.filter{it!=folder}.forEach{browse(source,it)}
 }
 internal fun syncFolderMetadata(books:List<LibraryBook>,force:Boolean=false):Boolean=FolderYamlSync(context,this).sync(books,force)
 fun metadataEligible(book:LibraryBook)=metadata.eligible(book)
 fun cachedDepths(source:LibrarySource)=durable.cachedDepths(source)
 internal fun cacheUsage(source:LibrarySource)=durable.cacheUsage(source)
 internal fun cacheUsageForDepth(source:LibrarySource,snapshot:CacheUsageSnapshot,depth:String)=
  durable.cacheUsageForDepth(source,snapshot,depth)
 fun searchCached(query:String,sources:List<LibrarySource>,depth:String?=null):List<LibraryBook> =
  durable.searchCached(sources,query,depth)
 fun hasCachedCover(book:LibraryBook)=bookCovers.has(book)
 internal fun saveSelectedMetadata(book:LibraryBook,match:WebBookMetadata) {
  metadata.recordResult(book,match,selected=true)
  metadata.enrich(book)
  if(match.coverUrl.isNotBlank()) {
   metadata.remoteCover(match.coverUrl)?.let{cacheBookCover(book,it)}
  }
  requestYamlWriteback(book)
 }
 /** A YAML candidate must come from a real matching local sidecar, never
  * from fields subsequently filled by web cache or a remembered book title. */
 private fun kavitaSearchCandidate(book:LibraryBook):WebBookMetadata? {
  if(!FolderYamlMetadata.supports(book.source.type))return null
  val folder=book.path.substringBeforeLast('/',"")
  val siblings=folderStorage.list(book.source,folder)
  val count=siblings.count{!it.directory&&isBook(it.name)}
  val byName=siblings.filter{!it.directory&&FolderYamlMetadata.readableSize(it.sizeBytes)}
   .associateBy{it.name.lowercase(java.util.Locale.ROOT)}
  for(candidate in FolderYamlMetadata.priorityCandidates(byName.keys,book.filename,count)) {
   val entry=byName[candidate.filename.lowercase(java.util.Locale.ROOT)]?:continue
   val data=runCatching{FolderYamlMetadata.read(folderStorage.read(book.source,entry),
    book.filename,candidate.allowPlain)}.getOrNull()?:continue
   val values=data.values
   fun get(vararg keys:String):String=keys.firstNotNullOfOrNull {key->
    values.entries.firstOrNull{it.key.equals(key,true)}?.value?.takeIf(String::isNotBlank)
   }.orEmpty()
   if(values.isEmpty()&&data.embeddedCover==null)continue
   val title=get("title","book_title","name","제목","도서명")
    .ifBlank{book.filename.substringBeforeLast('.')}
   return WebBookMetadata(title=title.take(180),
    author=get("author","authors","저자").take(170),
    publisher=get("publisher","출판사").take(170),
    description=get("summary","description","synopsis","소개").take(800),
    genre=get("genre","category","장르").take(120),
    source="YAML · "+entry.name,
    isbn=get("isbn","isbn13").take(30),
    translator=get("translator","translators","번역자").take(180),
    seriesName=get("series_name","seriesName","series","시리즈명").take(180),
    seriesTotal=get("series_total","seriesTotal","총권수").toIntOrNull()?:0,
    seriesIndex=get("series_index","seriesIndex","시리즈순번").toIntOrNull()?:0,
    publishedDate=get("publishedDate","pubDate","출간일").take(22),
    price=get("price","salePrice","가격").take(40))
  }
  return null
 }
 internal fun saveSelectedYamlMetadata(book:LibraryBook,chosen:WebBookMetadata) {
  require(MetadataSourceBadge.isYaml(chosen.source))
  // Record the explicit choice locally. Do not write the YAML back to remote
  // storage or replace a trusted Base64/sibling cover with a web image.
  metadata.recordResult(book,chosen,selected=true)
  metadata.enrich(book)
  updateCachedBookSnapshot(book)
 }
 internal fun metadataSearchResults(book:LibraryBook):List<WebBookMetadata> {
  val yaml=runCatching{kavitaSearchCandidate(book)}.getOrNull()
  if(FolderYamlMetadata.supports(book.source.type))runCatching{refreshFolderYaml(book)}
  val query=MetadataTitleMatcher.lookupTitle(book.title,book.filename)
  val external=BookMetadataSearch(context).searchCandidates(query,book.author,book.isbn,
   book.format in setOf("cbz","zip"),exhaustive=true)
  return listOfNotNull(yaml)+external
 }
 /** File sizes are obtained from trusted storage metadata, never guessed. */
 fun resolveBookSize(book:LibraryBook):Long {
  if(book.sizeBytes>=0)return book.sizeBytes
  val value=runCatching{when(book.source.type) {
   "local"->(book.uri?.let{DocumentFile.fromSingleUri(context,it)}
     ?:localDocument(book)).length()
   "drive"->rclone.archiveSize(book.source,book.path)
   "sftp"->SftpStorage().size(book.source,book.path)
   "webdav"->{
    val head=connection(davUrl(book.source.location,book.path),"HEAD",
     book.source.user,book.source.secret)
    head.instanceFollowRedirects=false
    try {if(head.responseCode in 200..299)head.getHeaderFieldLong("Content-Length",-1L)
     else -1L}
    finally{head.disconnect()}
   }
   "ftp"->{
    val client=ftpClient(book.source)
    try {
     val remote=remotePath(book.source,book.path)
     client.mlistFile(remote)?.size
      ?:client.listFiles(remote).firstOrNull{!it.isDirectory}?.size?:-1L
    }finally{runCatching{client.logout();client.disconnect()}}
   }
   "bookoasis"->BookOasisOpdsAccess.client(book.source)
    .rangedSize(book.bookId)?:-1L
   else->-1L
  }}.getOrDefault(-1L)
  book.sizeBytes=value.takeIf{it>=0}?:-1L
  return book.sizeBytes
 }

 /** Authenticated BookOasis site catalog -> durable local folder/book snapshots.
  * Source metadata is authoritative; existing values remain available offline.
  */
 private fun refreshBookOasisMetadata(book:LibraryBook,seriesPath:String) {
  val location=book.path.takeIf{it.startsWith("bookoasis:")}?:return
  val id=book.bookId.ifBlank{location.substringAfter(':').substringBefore('.')}
  if(id.toLongOrNull()==null)return
  // Refresh the series from api/media/detail so all volume rows and the
  // persistent index carry the same authoritative server metadata.
  val series=runCatching {
   val route=BookOasisPaths.parse(seriesPath)
   if(route.level==2)browse(book.source,seriesPath) else null
  }.getOrNull()
  val from=series?.books?.firstOrNull{it.bookId==id}
  if(from!=null) {
   book.title=from.title.ifBlank{book.title}
   book.author=from.author.ifBlank{book.author}
   book.publisher=from.publisher.ifBlank{book.publisher}
   book.summary=from.summary.ifBlank{book.summary}
   book.genre=from.genre.ifBlank{book.genre}
   book.isbn=from.isbn.ifBlank{book.isbn}
   book.cover=from.cover.ifBlank{book.cover}
   book.pageCount=from.pageCount.takeIf{it>0}?:book.pageCount
   book.seriesName=from.seriesName.ifBlank{book.seriesName}
   book.seriesTotal=from.seriesTotal.takeIf{it>0}?:book.seriesTotal
   book.seriesIndex=from.seriesIndex.takeIf{it>0}?:book.seriesIndex
   book.translator=from.translator.ifBlank{book.translator}
   book.publishedDate=from.publishedDate.ifBlank{book.publishedDate}
   book.price=from.price.ifBlank{book.price}
  }
  if(from==null)runCatching{BookOasisAccess.client(book.source).bookInfo(id)}.getOrNull()?.let{info->
   book.title=info.optString("title_alias").ifBlank{info.optString("title")}.ifBlank{book.title}
   book.author=info.optString("author").ifBlank{book.author}
   book.publisher=info.optString("publisher").ifBlank{book.publisher}
   book.summary=info.optString("description").ifBlank{info.optString("summary")}.ifBlank{book.summary}
   book.genre=info.optString("genre").ifBlank{book.genre}
   book.isbn=info.optString("isbn").ifBlank{book.isbn}
   book.cover=info.optString("cover_image").ifBlank{book.cover}
   book.pageCount=info.optInt("total_pages").takeIf{it>0}?:book.pageCount
  }
  book.metadataSource="Book Oasis API"
  updateCachedBookSnapshot(book)
 }

 /** Called on a background thread, before resolving the detail cover. */
 fun prepareBookDetail(book:LibraryBook,seriesPath:String="") {
  if(FolderYamlMetadata.supports(book.source.type)) {
   runCatching{refreshFolderYaml(book)}
    .onFailure{android.util.Log.w("ckReader","Folder YAML lookup failed: "+it.javaClass.simpleName)}
  }
  // BookOasis is authoritative for its own catalog. Fetch once via the
  // authenticated site's detail API, not an unrelated bookstore search.
  if(book.source.type=="bookoasis") {
   refreshBookOasisMetadata(book,seriesPath)
   return
  }
  metadata.enrich(book)
  SeriesVolumeTitle.apply(book)
  if(book.sizeBytes<0)resolveBookSize(book)
  updateCachedBookSnapshot(book)
 }

 /**
  * Detail's image fallback: permanent cache / authored sibling cover first,
  * Aladin, National Library, Data4Library, web providers, then ebook bytes.
  *
  * The source YAML image is treated as an authored cover, never overwritten
  * by a guessed web match. External candidates require >=72 match score.
  * A "missing" result is not persisted, so offline retries remain possible.
  */
 fun detailCover(book:LibraryBook,forceOnline:Boolean=false):Boolean {
  if(book.source.type=="bookoasis") {
   if(!forceOnline&&bookCovers.read(book)!=null)return true
   val image=runCatching {
    book.cover.takeIf{it.isNotBlank()}?.let{BookOasisAccess.client(book.source).cover(it)}
   }.getOrNull()?.takeIf(::decodableImage)
   if(image!=null&&cacheBookCover(book,image))return true
   if(bookCovers.read(book)!=null)return true
   return refreshBookCover(book)
  }
  // A cover already displayed by the catalog is the canonical image for detail.
  // PersistentLibraryCache survives process death and device restarts.
  if(!forceOnline&&bookCovers.read(book)!=null)return true
  // Authored YAML artwork has higher priority than a cached web/embedded
  // thumbnail from a previous version. Recheck the referenced sibling cover
  // when detail is explicitly opened, then save it to the durable DB.
  if(FolderYamlMetadata.supports(book.source.type)&&book.yamlPreferred&&
     (book.cover.isNotBlank()||book.webCover.isNotBlank())) {
   if(book.cover==FolderYamlMetadata.EMBEDDED_COVER&&bookCovers.has(book))return true
   val authored=runCatching{originalCover(book)}.getOrNull()?.takeIf(::decodableImage)
   if(authored!=null&&cacheBookCover(book,authored))return true
  }
  if(!forceOnline&&bookCovers.read(book)!=null)return true
  if(book.cover.isNotBlank()) {
   val supplied=runCatching{originalCover(book)}.getOrNull()?.takeIf(::decodableImage)
   if(supplied!=null&&cacheBookCover(book,supplied))return true
  }
  val query=MetadataTitleMatcher.lookupTitle(book.title,book.filename)
  val normalized=MetadataTitleMatcher.normalized(query)
  if(normalized.length>=2) {
   val search=BookMetadataSearch(context)
   var loaded=false
   val match=search.search(query,book.author,book.isbn,acceptCover={candidate->
     if(Thread.currentThread().isInterrupted)throw InterruptedException("표지 검색 취소")
     val url=candidate.coverUrl
     val bytes=runCatching{metadata.remoteCover(url)}.getOrNull()
      ?.takeIf(::decodableImage)
     (bytes!=null&&cacheBookCover(book,bytes)).also{if(it)loaded=true}
   })
   if(match!=null) {
    metadata.recordResult(book,match)
    metadata.enrich(book)
    updateCachedBookSnapshot(book)
    requestYamlWriteback(book)
   }
   if(loaded)return true
  }
  // Last resort: seekable EPUB/CBZ/PDF internal first image/first page.
  return refreshBookCover(book)
 }

 fun reindexBook(book:LibraryBook,force:Boolean=false,allowEmbeddedEpub:Boolean=false):Boolean {
  if(FolderYamlMetadata.supports(book.source.type))runCatching{refreshFolderYaml(book)}
  if(book.yamlPreferred&&(book.cover.isNotBlank()||book.webCover.isNotBlank())) {
   if(book.cover==FolderYamlMetadata.EMBEDDED_COVER&&bookCovers.has(book))return true
   // In a batch, never open a book just to obtain its embedded cover
   // unless it is an EPUB in a directory without kavita.yaml.
   val mayReadOriginal=allowEmbeddedEpub&&book.format.equals("epub",true)
   if(mayReadOriginal) {
    val bytes=runCatching{originalCover(book)}.getOrNull()?.takeIf(::decodableImage)
    if(bytes!=null&&cacheBookCover(book,bytes))return true
   }
  }
  var changed=bookCovers.has(book)
  if(force || metadata.eligible(book)) {
   val query=MetadataTitleMatcher.lookupTitle(book.title,book.filename)
   val match=BookMetadataSearch(context).search(query,book.author,book.isbn,
    book.format in setOf("cbz","zip"),acceptCover={candidate->
     runCatching{metadata.remoteCover(candidate.coverUrl)?.let{cacheBookCover(book,it)}==true}
      .getOrDefault(false)
    })
   if(match!=null) {
    metadata.recordResult(book,match)
    metadata.enrich(book)
    requestYamlWriteback(book)
    changed=true
   }
  }
  // Batch cache generation may open only EPUBs in folders without kavita.yaml.
  // Other formats and manifested EPUBs use YAML/metadata/remote covers only.
  return if(bookCovers.has(book))changed else
   (allowEmbeddedEpub&&book.format.equals("epub",true)&&refreshBookCover(book))||changed
 }
 private fun overlay(book:LibraryBook) {
  if(book.source.type=="bookoasis")return
  metadata.enrich(book)
  SeriesVolumeTitle.apply(book)
 }
 fun rcloneMasterPassword():String {
  prefs.getString("rclone_master",null)?.let { return decrypt(it) }
  val random=ByteArray(32).apply { java.security.SecureRandom().nextBytes(this) }
  val master=Base64.getUrlEncoder().withoutPadding().encodeToString(random)
  check(prefs.edit().putString("rclone_master",encrypt(master)).commit()) { "rclone 암호 저장 실패" }
  return master
 }

 private fun key():SecretKey {
  val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
  (store.getKey(alias,null) as? SecretKey)?.let { return it }
  return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
   init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
  }.generateKey()
 }
 private fun encrypt(value:String):String {
  val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE,key()) }
  return Base64.getEncoder().encodeToString(cipher.iv+cipher.doFinal(value.toByteArray(Charsets.UTF_8)))
 }
 private fun decrypt(value:String):String {
  val bytes=Base64.getDecoder().decode(value)
  val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,bytes.copyOfRange(0,12))) }
  return String(cipher.doFinal(bytes.copyOfRange(12,bytes.size)),Charsets.UTF_8)
 }
 fun sources():List<LibrarySource> = try {
  val raw=prefs.getString("sources",null) ?: return emptyList()
  val array=JSONArray(decrypt(raw))
  val original=(0 until array.length()).map { LibrarySource.from(array.getJSONObject(it)) }
  val cleaned=original.map{DriveConnectionPolicy.clean(it)}
  if(cleaned!=original)save(cleaned)
  else PersistentLibraryCache.registerSources(cleaned)
  cleaned
 } catch(_:Exception) { emptyList() }
 fun save(sources:List<LibrarySource>,synchronous:Boolean=false) {
  val clean=sources.map{DriveConnectionPolicy.clean(it)}
  val array=JSONArray();clean.forEach { array.put(it.json()) }
  val edit=prefs.edit().putString("sources",encrypt(array.toString()))
  if(synchronous)check(edit.commit()){"저장소 연결 정보를 저장할 수 없습니다."} else edit.apply()
  PersistentLibraryCache.registerSources(clean)
 }
 fun add(type:String,name:String,location:String,user:String="",secret:String="",extra:String="") {
  save(sources()+LibrarySource(UUID.randomUUID().toString(),type,name,location,user,secret,extra))
 }
 fun removeSource(id:String)=removeSources(setOf(id))
 /** Persist removal before clearing the matching local and Documents cache. */
 fun removeSources(ids:Set<String>,clearDownloads:Boolean=false) {
  if(ids.isEmpty())return
  val original=sources()
  val removed=original.filter{it.id in ids}
  if(removed.isEmpty())return
  val originalNames=original.map{it.id to it.name}
  save(original.filterNot{it.id in ids},synchronous=true)
  try {
   removed.forEach { source ->
    SourceIconStore(context).clearSelection(source.id)
    if(source.type=="bookoasis") {
     BookOasisCatalogWorker.cancel(context,source.id)
     BookOasisAccess.evict(source);BookOasisOpdsAccess.evict(source)
    }
    clearCache(source.id,strict=true)
    bookCovers.clearForSource(source.id,strict=true)
    metadata.clearForSource(source.id,strict=true)
    durable.clearSource(source.id,
     CachePublicFolderNames.forSource(source.id,source.name,originalNames))
   }
   if(clearDownloads)clearDownloadedBookFiles(strict=true)
   removeRecentBooksForSources(ids)
  } catch(error:Exception) {
   save(original,synchronous=true)
   throw error
  }
 }
 fun hidden(id:String)=prefs.getBoolean("hidden_"+sha(id),false)
 fun hide(id:String) { prefs.edit().putBoolean("hidden_"+sha(id),true).apply() }
 fun lastRead(id:String)=prefs.getLong("last_"+id,0L)
 private fun recentBookRecords():List<JSONObject> = runCatching {
  val array=JSONArray(prefs.getString("recent_books_v1","[]"))
  (0 until array.length()).mapNotNull{array.optJSONObject(it)}
 }.getOrDefault(emptyList())
 private fun saveRecentBookRecords(records:List<JSONObject>) {
  val array=JSONArray()
  records.take(10).forEach(array::put)
  prefs.edit().putString("recent_books_v1",array.toString()).apply()
 }
 fun recordRecentlyOpened(book:LibraryBook,folder:String="") {
  val record=JSONObject().put("id",book.id).put("sourceId",book.source.id)
   .put("path",book.path).put("filename",book.filename)
   .put("uri",book.uri?.toString().orEmpty())
   .put("folder",folder).put("openedAt",System.currentTimeMillis())
   .put("title",book.title).put("author",book.author).put("publisher",book.publisher)
   .put("bookId",book.bookId).put("cover",book.cover).put("pageCount",book.pageCount)
   .put("summary",book.summary).put("genre",book.genre).put("isbn",book.isbn)
   .put("metadataSource",book.metadataSource).put("metadataUrl",book.metadataUrl)
   .put("webCover",book.webCover).put("seriesName",book.seriesName)
   .put("seriesTotal",book.seriesTotal).put("seriesIndex",book.seriesIndex)
   .put("translator",book.translator).put("publishedDate",book.publishedDate)
   .put("price",book.price).put("cachePartition",book.cachePartition)
   .put("sizeBytes",book.sizeBytes).put("yamlPreferred",book.yamlPreferred)
   .put("yamlRevision",book.yamlRevision)
  saveRecentBookRecords(listOf(record)+recentBookRecords().filterNot{it.optString("id")==book.id})
 }
 fun removeRecentlyOpened(id:String) {
  saveRecentBookRecords(recentBookRecords().filterNot{it.optString("id")==id})
 }
 private fun removeRecentBooksForSources(ids:Set<String>) {
  saveRecentBookRecords(recentBookRecords().filterNot{it.optString("sourceId") in ids})
 }
 fun recentlyOpened():List<RecentBook> {
  val bySource=sources().associateBy{it.id}
  return recentBookRecords().mapNotNull{record->
   val source=bySource[record.optString("sourceId")]?:return@mapNotNull null
   val id=record.optString("id")
   val path=record.optString("path")
   if(id.isBlank()||path.isBlank()||hidden(id))return@mapNotNull null
   val uri=record.optString("uri").takeIf(String::isNotBlank)?.let(Uri::parse)
   val restored=LibraryBook(id,source,path,record.optString("filename").ifBlank{path.substringAfterLast('/')},uri).apply {
    title=record.optString("title");author=record.optString("author")
    publisher=record.optString("publisher");bookId=record.optString("bookId")
    cover=record.optString("cover");pageCount=record.optInt("pageCount")
    summary=record.optString("summary");genre=record.optString("genre")
    isbn=record.optString("isbn");metadataSource=record.optString("metadataSource")
    metadataUrl=record.optString("metadataUrl");webCover=record.optString("webCover")
    seriesName=record.optString("seriesName");seriesTotal=record.optInt("seriesTotal")
    seriesIndex=record.optInt("seriesIndex");translator=record.optString("translator")
    publishedDate=record.optString("publishedDate");price=record.optString("price")
    cachePartition=record.optString("cachePartition");sizeBytes=record.optLong("sizeBytes",-1L)
    yamlPreferred=record.optBoolean("yamlPreferred");yamlRevision=record.optInt("yamlRevision")
   }
   RecentBook(restored,record.optString("folder"),
    maxOf(record.optLong("openedAt"),lastRead(id)))
  }.sortedByDescending{it.openedAt}.take(10)
 }
 private fun sha(s:String)=MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString(""){"%02x".format(it)}
 private fun book(source:LibrarySource,path:String,uri:Uri?=null):LibraryBook {
  val file=path.substringAfterLast('/')
  val identity=if(source.type=="bookoasis")source.id+":"+
   sha(source.location+"|"+source.user+"|"+source.secret+"|"+source.extra) else source.id
  return LibraryBook(sha(identity+":"+path).take(24),source,path,file,uri)
 }
 // Snapshots contain metadata only. Credentials and file contents are never written here.
 private val indexRoot=File(context.filesDir,"library-index")
 // Recently visited child and parent directories stay in RAM. The disk
 // snapshots remain the fallback across app restarts or LRU eviction.
 private val listingMemory=LibraryListingMemoryCache()
 private fun safePath(path:String):String {
  val parts=path.split('/').filter { it.isNotBlank() }
  require(parts.none { it=="." || it==".." || '\\' in it || '\u0000' in it }) { "올바르지 않은 폴더 경로" }
  return parts.joinToString("/")
 }
 private fun sourceFingerprint(source:LibrarySource)=sha(source.type+"|"+source.location+"|"+
  source.user+"|"+sha(source.secret)+"|"+sha(source.extra)+"|"+(if(source.type=="drive")runCatching {
   JSONObject(source.extra).optString("root_folder_id")
  }.getOrDefault("") else ""))
 private fun memoryListingKey(source:LibrarySource,relative:String):String=
  source.id+":"+sourceFingerprint(source)+":"+relative
 private fun snapshotFile(source:LibrarySource,relative:String):File {
  val directory=File(indexRoot,sha(source.id)).apply { mkdirs() }
  return File(directory,sha(relative)+".json")
 }
 fun clearCache(sourceId:String,strict:Boolean=false) {
  emptyRemoteFolders.clearSource(sourceId)
  bookOasisIndex.clear(sourceId)
  listingMemory.removeSource(sourceId)
  folderThumbnails.clearSource(sourceId)
  val index=File(indexRoot,sha(sourceId))
  if(index.exists()&&!index.deleteRecursively()&&strict)
   error("저장소 목록 캐시를 삭제하지 못했습니다.")
  durable.clearListings(sourceId)
 }
 /** Only complete or interrupted foreground book downloads, never metadata or covers. */
 fun clearDownloadedBookFiles(strict:Boolean=false):Pair<Int,Long> {
  val root=File(context.cacheDir,"library-books")
  var count=0;var bytes=0L
  root.listFiles().orEmpty().filter{it.isFile}.forEach{file->
   val length=file.length()
   if(file.delete()){count++;bytes+=length}
   else if(strict)error("임시 도서 파일을 삭제하지 못했습니다: ${file.name}")
  }
  return count to bytes
 }
 fun clearFolderThumbnails(sourceId:String)=folderThumbnails.clearSource(sourceId)
 private fun listingKey(source:LibrarySource,path:String,search:String):String =
  if(source.type=="bookoasis")path+"?search="+search.trim() else path
 fun cached(source:LibrarySource,path:String,search:String="",fresh:Boolean=false):LibraryListing? = runCatching {
  if(!LibraryVisibility.visible(path))return@runCatching LibraryListing(emptyList(),emptyList())
  val relative=if(source.type=="local")"" else listingKey(source,safePath(path),search)
  val memoryKey=memoryListingKey(source,relative)
  if(!fresh)listingMemory.get(memoryKey)?.let { remembered ->
   val previous=remembered.books.associate{it.id to it.title}
   remembered.books.forEach(::overlay)
   val repaired=LibraryTitleOrder.listing(remembered,source.type)
   if(repaired.books.any{previous[it.id]!=it.title})saveListing(source,relative,repaired)
   return@runCatching repaired
  }
  if(source.type=="bookoasis" && !fresh) {
   val route=runCatching{BookOasisPaths.parse(path)}.getOrNull()
   val cached=bookOasisIndex.read(source,relative){book(source,it)}
    ?:when {
     path.isBlank()&&search.isNotBlank() ->
      bookOasisIndex.offlineSearch(source,search){book(source,it)}
     route?.level==1 && route.libraryId=="home" && search.isBlank() ->
      bookOasisIndex.cachedHome(source)
     else -> null
    }
   if(cached!=null) {
    cached.books.forEach(::overlay)
    val sorted=LibraryTitleOrder.listing(
     cached.copy(books=cached.books.filterNot{hidden(it.id)}),source.type)
    listingMemory.put(memoryKey,sorted)
    return@runCatching sorted
   }
   // Old OPDS snapshots are never imported into the API-only metadata index.
   return@runCatching null
  }
  val o=durable.getListing(source,relative) ?: run {
   val file=snapshotFile(source,relative)
   if(!file.isFile || file.length()>12_000_000L)return@runCatching null
   JSONObject(file.readText()).also{durable.putListing(source,relative,it)}
  }
  if(o.optString("sourceId")!=source.id || o.optString("fingerprint")!=sourceFingerprint(source) ||
      o.optString("relative")!=relative)return@runCatching null
  // v1.6.3 BookOasis snapshots have no representative-cover index. Refresh
  // them once instead of issuing a separate detail request for each row.
  if(source.type=="bookoasis" && !o.has("folderCovers"))return@runCatching null
  val folders=o.optJSONArray("folders") ?: JSONArray()
  val books=o.optJSONArray("books") ?: JSONArray()
  val coverJson=o.optJSONObject("folderCovers") ?: JSONObject()
  val folderCovers=coverJson.keys().asSequence().mapNotNull { key ->
   coverJson.optString(key).takeIf { it.isNotBlank() }?.let { key to it }
  }.toMap()
  val infosJson=o.optJSONObject("folderInfos") ?: JSONObject()
  val folderInfos=infosJson.keys().asSequence().mapNotNull { key ->
   infosJson.optJSONObject(key)?.let { key to FolderCatalogInfo.fromJson(it) }
  }.toMap()
  var repairedCachedTitle=false
  LibraryListing(
   (0 until folders.length()).map { folders.getString(it) },
   (0 until books.length()).map { index ->
    val item=books.getJSONObject(index)
    book(source,item.getString("path")).apply {
     title=item.optString("title");author=item.optString("author")
     publisher=item.optString("publisher");bookId=item.optString("bookId")
     cover=item.optString("cover");pageCount=item.optInt("pageCount",0)
     summary=item.optString("summary");genre=item.optString("genre")
     isbn=item.optString("isbn")
     translator=item.optString("translator")
     seriesName=item.optString("seriesName")
     seriesTotal=item.optInt("seriesTotal")
     seriesIndex=item.optInt("seriesIndex")
     publishedDate=item.optString("publishedDate")
     price=item.optString("price")
     cachePartition=item.optString("cachePartition")
     sizeBytes=item.optLong("sizeBytes",-1L)
     yamlPreferred=item.optBoolean("yamlPreferred",false)
     yamlRevision=item.optInt("yamlRevision",0)
     metadataSource=item.optString("metadataSource")
     metadataUrl=item.optString("metadataUrl")
     webCover=item.optString("webCover")
    }
   }.filterNot { hidden(it.id) }.onEach{book->
    val previous=book.title
    overlay(book)
    if(book.title!=previous)repairedCachedTitle=true
   },
   o.optLong("scannedAt"),o.optBoolean("hasMore"),o.optInt("page",1),folderCovers,folderInfos
  ).let{
   val sorted=LibraryTitleOrder.listing(it,source.type)
   listingMemory.put(memoryKey,sorted)
   if(source.type=="bookoasis")
    runCatching{bookOasisIndex.save(source,relative,sorted)}
   // Migrate old cached lists once, without deleting covers or source files.
   if(repairedCachedTitle)saveListing(source,relative,sorted)
   sorted
  }
 }.getOrNull()
 private fun saveListing(source:LibrarySource,path:String,listing:LibraryListing) {
  val sorted=LibraryTitleOrder.listing(listing,source.type)
  listingMemory.put(memoryListingKey(source,path),sorted)
  if(source.type=="bookoasis")
   runCatching{bookOasisIndex.save(source,path,sorted)}
    .onFailure{android.util.Log.w("ckReader","OPDS metadata DB write failed",it)}
  runCatching {
   val data=JSONObject().put("sourceId",source.id).put("fingerprint",sourceFingerprint(source))
    .put("relative",path).put("scannedAt",sorted.scannedAt).put("hasMore",sorted.hasMore).put("page",sorted.page)
   val folders=JSONArray();sorted.folders.forEach { folders.put(it) };data.put("folders",folders)
   val folderCovers=JSONObject()
   sorted.folderCovers.forEach { (key,value) ->
    if(value.isNotBlank())folderCovers.put(key,value)
   }
   data.put("folderCovers",folderCovers)
   val folderInfos=JSONObject()
   sorted.folderInfos.forEach{(key,info)->folderInfos.put(key,info.toJson())}
   data.put("folderInfos",folderInfos)
   val books=JSONArray()
   sorted.books.forEach { b ->
    books.put(JSONObject().put("path",b.path).put("title",b.title).put("author",b.author)
     .put("publisher",b.publisher).put("bookId",b.bookId).put("cover",b.cover)
     .put("pageCount",b.pageCount).put("summary",b.summary).put("genre",b.genre)
     .put("isbn",b.isbn).put("translator",b.translator)
     .put("seriesName",b.seriesName).put("seriesTotal",b.seriesTotal)
     .put("seriesIndex",b.seriesIndex).put("publishedDate",b.publishedDate)
     .put("price",b.price).put("cachePartition",b.cachePartition)
     .put("sizeBytes",b.sizeBytes).put("yamlPreferred",b.yamlPreferred).put("yamlRevision",b.yamlRevision)
     .put("metadataSource",b.metadataSource).put("metadataUrl",b.metadataUrl)
     .put("webCover",b.webCover))
   }
   data.put("books",books)
   durable.putListing(source,path,data)
   val target=snapshotFile(source,path)
   val temp=File(target.parentFile,target.name+".tmp")
   temp.writeText(data.toString())
   if(!temp.renameTo(target)) {
    target.delete()
    if(!temp.renameTo(target))error("캐시 저장 실패")
   }
  }
 }
 fun browse(source:LibrarySource,path:String="",search:String="",requestedPage:Int=1,
  onRemoteFolderProgress:(Int,Int,Int)->Unit={_,_,_->}):LibraryListing {
  if(!LibraryVisibility.visible(path))return LibraryListing(emptyList(),emptyList())
  val relative=if(source.type=="local")"" else safePath(path)
  if(source.type=="bookoasis"){
   val result=browseBookOasisApi(source,relative,search,requestedPage)
   val key=listingKey(source,relative,search)
   val combined=if(requestedPage>1) {
    val existing=cached(source,relative,search)
    if(existing!=null) {
     val folders=(existing.folders+result.folders).distinct()
     val books=(existing.books+result.books).distinctBy{it.id}
     result.copy(folders=folders,books=books,
      folderCovers=existing.folderCovers+result.folderCovers,
      folderInfos=existing.folderInfos+result.folderInfos)
    }else result
   }else result
   saveListing(source,key,combined)
   combined.books.forEach(::overlay)
   return LibraryTitleOrder.listing(combined,source.type)
  }
  val dirs=mutableListOf<String>()
  val books=mutableListOf<LibraryBook>()
  when(source.type) {
   "local"->scanLocal(source,books)
   "webdav","ftp","sftp","drive"->scanRemote(source,relative,dirs,books,
    onRemoteFolderProgress)
   else->error("지원하지 않는 저장소 유형")
  }
  val listing=LibraryListing(
   dirs.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER),
   books.filterNot { hidden(it.id) }
  )
  // Store per-volume titles after enrichment, not shared folder titles.
  listing.books.forEach(::overlay)
  saveListing(source,relative,listing)
  return LibraryTitleOrder.listing(listing,source.type)
 }
 /** Promote BookOasis detail information to its parent series card. */
 private fun updateBookOasisParent(source:LibrarySource,path:String,
                                  meta:JSONObject,volumes:List<LibraryBook>) {
  val folder=path.substringAfterLast('/')
  if(!folder.startsWith("s~"))return
  val parent=path.substringBeforeLast('/',"")
  val listing=cached(source,parent)?:return
  if(folder !in listing.folders)return
  val previous=listing.folderInfos[folder]?:FolderCatalogInfo()
  val next=FolderCatalogInfo(
   previous.author.ifBlank{meta.optString("author").take(180)},
   previous.publisher.ifBlank{meta.optString("publisher").take(180)},
   previous.volumeCount.takeIf{it>0}?:volumes.size,
   previous.summary.ifBlank{meta.optString("summary").take(400)}
  )
  val cover=listing.folderCovers[folder].orEmpty()
   .ifBlank{volumes.firstOrNull()?.cover.orEmpty()}
  if(next!=previous||cover!=listing.folderCovers[folder].orEmpty()) {
   val covers=if(cover.isNotBlank())listing.folderCovers+(folder to cover)
    else listing.folderCovers
   saveListing(source,listingKey(source,parent,""),
    listing.copy(folderInfos=listing.folderInfos+(folder to next),folderCovers=covers))
  }
 }

 private fun opdsBook(source:LibrarySource,path:String,
                      entry:BookOasisOpdsParser.Entry,position:Int=0,
                      count:Int=0,seriesName:String=""):LibraryBook? {
  val remote=BookOasisOpdsAccess.client(source)
  val id=remote.bookId(entry)?:return null
  val format=remote.bookFormat(entry).takeIf{it in setOf("epub","pdf","txt","zip","cbz")}
   ?:return null
  val author=entry.author.ifBlank{
   Regex("저자:\\s*([^/·]+)").find(entry.summary)?.groupValues?.getOrNull(1)?.trim().orEmpty()
  }
  val title=entry.title.replace(Regex("^\\[[^]]+\\]\\s*"),"").ifBlank{entry.title}
  return book(source,"bookoasis:$id.$format").apply {
   this.title=title.take(500);this.author=author.take(180)
   publisher=entry.publisher.take(180);bookId=id;cover=entry.cover
   pageCount=entry.pages;summary=entry.summary.take(800)
   this.seriesName=seriesName.take(180)
   seriesTotal=count;seriesIndex=position
   cachePartition=path.substringBefore('/').takeIf{it.isNotBlank()}.orEmpty()
   metadataSource="Book Oasis OPDS"
  }
 }

 private fun browseBookOasisApi(source:LibrarySource,path:String,search:String,requestedPage:Int):LibraryListing {
  val api=BookOasisAccess.client(source)
  val route=BookOasisPaths.parse(path)
  if(route.level==0&&search.isBlank()) {
   val categories=api.categories()
   return LibraryListing(
    listOf(BookOasisPaths.library("home","전체 보기"))+
     categories.map{BookOasisPaths.library(it.id,it.name)},emptyList())
  }
  if(route.level<2) {
   val categories=if(route.libraryId?.toLongOrNull()!=null)
    listOf(BookOasisClient.Category(route.libraryId!!,BookOasisPaths.display(path)))
    else api.categories()
   val folders=LinkedHashSet<String>()
   val covers=LinkedHashMap<String,String>()
   val infos=LinkedHashMap<String,FolderCatalogInfo>()
   var hasMore=false
   categories.forEach {category->
    val result=api.series(category.id,search,requestedPage)
    hasMore=hasMore||result.hasMore
    result.items.forEach {series->
     val folder=BookOasisPaths.series(series.libraryId,series.name,series.representativeId)
     folders.add(folder)
     if(series.cover.isNotBlank())covers[folder]=series.cover
     infos[folder]=FolderCatalogInfo(series.author,series.publisher,
      series.volumeCount,"")
    }
   }
   return LibraryListing(folders.toList(),emptyList(),hasMore=hasMore,
    page=requestedPage,folderCovers=covers,folderInfos=infos)
  }
  val library=route.libraryId?:error("북오아시스 보관함을 찾을 수 없습니다.")
  val seriesName=route.seriesName?:error("북오아시스 시리즈를 찾을 수 없습니다.")
  val (meta,volumes)=api.volumes(library,seriesName,route.representativeId.orEmpty())
  val summary=meta.optString("description").ifBlank{meta.optString("summary")}
  val cover=meta.optString("cover_image")
  val books=volumes.mapIndexed{index,volume->
   val format=if(volume.format=="imgdir")"cbz" else volume.format
   book(source,"bookoasis:${volume.id}.$format").apply{
    title=volume.title.take(500)
    author=volume.author.ifBlank{meta.optString("author")}.take(180)
    publisher=volume.publisher.ifBlank{meta.optString("publisher")}.take(180)
    bookId=volume.id
    this.cover=volume.cover.ifBlank{cover}
    this.summary=summary.take(800)
    genre=meta.optString("genre")
    isbn=meta.optString("isbn")
    translator=meta.optString("translator")
    publishedDate=meta.optString("publish_date").ifBlank{meta.optString("published_date")}
    price=meta.optString("price")
    pageCount=volume.totalPages
    this.seriesName=seriesName.take(180)
    seriesTotal=volumes.size
    seriesIndex=index+1
    cachePartition=library
    metadataSource="Book Oasis API"
   }
  }
  updateBookOasisParent(source,path,meta,books)
  return LibraryListing(emptyList(),books.filterNot{hidden(it.id)})
 }

 private fun browseBookOasis(source:LibrarySource,path:String,search:String,requestedPage:Int):LibraryListing {
  val remote=BookOasisOpdsAccess.client(source)
  val route=BookOasisPaths.parse(path)
  if(route.level==0 && search.isBlank()) {
   val feed=remote.rootFeed()
   val dirs=mutableListOf(BookOasisPaths.library("home","전체 보기"))
   for(item in feed.entries.filter{it.navigation}) {
    val id=remote.libraryId(item) ?:when {
     item.href.substringBefore('?').endsWith("/recently-added")->"recently-added"
     item.href.substringBefore('?').endsWith("/recently-read")->"recently-read"
     item.href.substringBefore('?').endsWith("/favorite")->"favorite"
     else->null
    } ?:continue
    dirs.add(BookOasisPaths.library(id,item.title))
   }
   return LibraryListing(dirs.distinct(),emptyList())
  }
  if(route.level<2) {
   val category=route.libraryId?:"home"
   val feed=if(search.isNotBlank())remote.searchFeed(search,requestedPage)
    else remote.categoryFeed(category,requestedPage)
   val folders=ArrayList<String>()
   val covers=LinkedHashMap<String,String>()
   val infos=LinkedHashMap<String,FolderCatalogInfo>()
   val books=ArrayList<LibraryBook>()
   for(item in feed.entries) {
    if(item.navigation) {
     val lib=remote.seriesLibraryId(item) ?:category.takeIf{it.toLongOrNull()!=null}
      ?:continue
     // Upstream labels empty series as "기타" but navigates to
     // /opds/series/{library}/__empty_series__. Keep the token reversible.
     val keyName=if(item.href.substringBefore('?').endsWith("/__empty_series__"))
      "__empty_series__" else item.title
     val folder=BookOasisPaths.series(lib,keyName)
     if(folder !in infos)folders.add(folder)
     covers[folder]=item.cover
     infos[folder]=FolderCatalogInfo(item.author,item.publisher,0,item.summary.take(400))
    }else {
     opdsBook(source,path,item)?.let {books.add(it)}
    }
   }
   return LibraryListing(folders,books.filterNot{hidden(it.id)},
    hasMore=feed.next!=null,page=requestedPage,
    folderCovers=covers.filterValues{it.isNotBlank()},folderInfos=infos)
  }
  val previous=cached(source,path)?.books?.associateBy{it.bookId}.orEmpty()
  val entries=remote.allSeriesBooks(route.libraryId?:"home",route.seriesName.orEmpty())
  val books=entries.mapIndexedNotNull{index,item ->
   opdsBook(source,path,item,index+1,entries.size,
    route.seriesName.orEmpty().let{if(it=="__empty_series__")"기타" else it})?.apply{
    val old=previous[bookId]
    if(old!=null) {
     author=author.ifBlank{old.author};publisher=publisher.ifBlank{old.publisher}
     summary=summary.ifBlank{old.summary};genre=genre.ifBlank{old.genre}
     isbn=isbn.ifBlank{old.isbn};cover=cover.ifBlank{old.cover}
     pageCount=pageCount.takeIf{it>0}?:old.pageCount
     translator=translator.ifBlank{old.translator}
     publishedDate=old.publishedDate;price=old.price
    }
   }
  }
  val metadata=JSONObject()
   .put("summary",entries.firstOrNull()?.summary.orEmpty())
   .put("cover_image",entries.firstOrNull()?.cover.orEmpty())
  updateBookOasisParent(source,path,metadata,books)
  return LibraryListing(emptyList(),books.filterNot{hidden(it.id)})
 }

 /**
  * OPDS Atom feed-based incremental catalog sync. Store each coherent
  * snapshot in SQLite; never discard an older complete offline snapshot
  * until the new feed has been fetched successfully in full.
  */
 fun browseBookOasisComplete(source:LibrarySource,path:String,search:String,
  seed:LibraryListing?=null,
  onProgress:(LibraryListing)->Unit,
  cancelled:()->Boolean={false}):LibraryListing {
  require(source.type=="bookoasis")
  val relative=safePath(path)
  val route=BookOasisPaths.parse(relative)
  if(route.level>=2 || (route.level==0 && search.isBlank())) {
   // Standard Book Oasis OPDS library navigation feeds are complete XML
   // documents rather than the legacy REST page=1,2,... response. Re-fetch
   // from page one to replace old partially cached REST lists correctly.
   val result=browse(source,relative,search)
   onProgress(result)
   return result
  }
  val key=listingKey(source,relative,search)
  val previous=seed?:cached(source,relative,search)
  val incomplete=previous?.takeIf{it.hasMore&&it.page in 1..9_999}
  val complete=previous?.takeIf{!it.hasMore}
  val folders=LinkedHashSet<String>().apply{addAll(incomplete?.folders.orEmpty())}
  val books=LinkedHashMap<String,LibraryBook>().apply {
   incomplete?.books?.forEach{put(it.id,it)}
  }
  val covers=LinkedHashMap<String,String>().apply{putAll(previous?.folderCovers.orEmpty())}
  val infos=LinkedHashMap<String,FolderCatalogInfo>().apply{putAll(previous?.folderInfos.orEmpty())}
  var page=(incomplete?.page?:0)+1
  var latest:LibraryListing?=null
  var lastNotification=0L
  try {
   while(page<=10_000) {
    if(cancelled())throw java.util.concurrent.CancellationException("OPDS sync cancelled")
    val response=browseBookOasisApi(source,relative,search,page)
    if(cancelled())throw java.util.concurrent.CancellationException("OPDS sync cancelled")
    var added=0
    response.folders.forEach{if(folders.add(it))added++}
    response.books.forEach{if(books.putIfAbsent(it.id,it)==null)added++}
    covers.putAll(response.folderCovers)
    response.folderInfos.forEach{(folder,info)->
     val old=infos[folder]
     infos[folder]=FolderCatalogInfo(
      info.author.ifBlank{old?.author.orEmpty()},
      info.publisher.ifBlank{old?.publisher.orEmpty()},
      info.volumeCount.takeIf{it>0}?:old?.volumeCount?:0,
      info.summary.ifBlank{old?.summary.orEmpty()})
    }
    if(response.hasMore&&added==0)
     error("북오아시스 OPDS 페이지가 중복되었습니다 (page $page).")
    val snapshot=LibraryTitleOrder.listing(LibraryListing(
     folders.toList(),books.values.toList(),hasMore=response.hasMore,page=page,
     folderCovers=covers.toMap(),folderInfos=infos.toMap()),source.type)
    latest=snapshot
    // A stable complete offline catalog must survive a failed re-sync.
    if((complete==null || !response.hasMore) &&
     (page==1 || page%5==0 || !response.hasMore))
     saveListing(source,key,snapshot)
    val now=System.currentTimeMillis()
    if(page==1 || !response.hasMore || now-lastNotification>=350L) {
     onProgress(snapshot)
     lastNotification=now
    }
    if(!response.hasMore)return snapshot
    page++
   }
   error("북오아시스 OPDS 페이지 제한을 초과했습니다.")
  }catch(e:Exception) {
   if(e !is java.util.concurrent.CancellationException && !cancelled() && complete==null)
    latest?.let{saveListing(source,key,it)}
   throw e
  }
 }
 fun bookOasisPages(book:LibraryBook):Int =
  book.pageCount.takeIf{it>0}?:BookOasisAccess.client(book.source).pages(book.bookId)
 fun bookOasisImage(sourceId:String,id:String,index:Int):ByteArray {
  val source=sources().firstOrNull{it.id==sourceId&&it.type=="bookoasis"}
   ?:error("북오아시스 저장소가 삭제되었습니다.")
  return BookOasisAccess.client(source).imagePage(id,index)
 }
 private fun isBook(name:String)=listOf(".epub",".pdf",".txt",".md",".zip",".cbz").any {name.endsWith(it,true)}
 private fun yamlName(names:Collection<String>,bookName:String):String? {
  val stem=bookName.substringBeforeLast('.')
  for(extension in listOf("yaml","yml"))names.firstOrNull { it.equals("$stem.$extension",true) }?.let { return it }
  val yaml=names.filter { it.endsWith(".yaml",true)||it.endsWith(".yml",true) }
  return yaml.singleOrNull()
 }
 private fun applyYamlValues(book:LibraryBook,values:Map<String,String>):Boolean {
  fun get(vararg names:String):String = names.firstNotNullOfOrNull {name ->
   values.entries.firstOrNull{it.key.equals(name,true)}
    ?.value?.trim()?.takeIf(String::isNotBlank)
  }.orEmpty()
  val changed=listOf("title","book_title","name","제목","도서명","author","authors","저자",
   "publisher","출판사","isbn","isbn13","cover","cover_image","cover_url","image","표지",
   "description","summary","synopsis","소개","줄거리","genre","category","장르",
   "series","series_name","seriesName","시리즈명","series_total","seriesTotal","총권수",
   "series_index","seriesIndex","권수","시리즈순번","translator","translators","번역자",
   "publishedDate","pubDate","출간일","price","salePrice","가격","book_id","bookId","id","도서ID","도서번호",
   "pageCount","metadataUrl")
   .any{field->values.keys.any{it.equals(field,true)}}
  if(!changed)return false
  fun fill(value:String,setter:(String)->Unit) {
   if(value.isNotBlank())setter(value)
  }
  fill(get("title","book_title","name","제목","도서명").take(180)){book.title=it}
  fill(get("author","authors","저자").take(170)){book.author=it}
  fill(get("publisher","출판사").take(170)){book.publisher=it}
  fill(get("book_id","bookId","id","도서ID","도서번호").take(80)){book.bookId=it}
  fill(get("isbn","isbn13","ISBN").take(30)){book.isbn=it}
  val rawCover=get("cover","cover_image","cover_url","image","표지")
  if(rawCover.isNotBlank()) {
   if(rawCover.startsWith("https://")){
    book.webCover=rawCover.take(550)
    book.cover=""
   } else book.cover=rawCover.take(350)
  }
  fill(get("summary","description","synopsis","소개","줄거리").take(800)){book.summary=it}
  fill(get("genre","category","장르").take(120)){book.genre=it}
  fill(get("translator","translators","번역자").take(180)){book.translator=it}
  fill(get("series","seriesName","series_name","시리즈명").take(180)){book.seriesName=it}
  get("series_total","seriesTotal","총권수").toIntOrNull()?.takeIf{it>0}?.let{book.seriesTotal=it}
  get("series_index","seriesIndex","권수","시리즈순번")
   .toIntOrNull()?.takeIf{it>0}?.let{book.seriesIndex=it}
  fill(get("publishedDate","pubDate","출간일").take(22)){book.publishedDate=it}
  fill(get("price","salePrice","가격").take(40)){book.price=it}
  fill(get("metadataUrl").take(550)){book.metadataUrl=it}
  get("pageCount").toIntOrNull()?.takeIf{it>0}?.let{book.pageCount=it}
  SeriesVolumeTitle.apply(book)
  return true
 }
 private fun applyFolderYaml(book:LibraryBook,items:List<FolderYamlStorage.Entry>,
                            yamlCache:MutableMap<String,FolderYamlMetadata.CachedDocument>,
                            strictPriority:Boolean=false):Boolean {
  val count=items.count{!it.directory&&isBook(it.name)}
  val lookups=items.associateBy{it.name.lowercase(java.util.Locale.ROOT)}
  var failed=false
  val priority=if(strictPriority)FolderYamlMetadata.priorityCandidates(lookups.keys,book.filename,count)
   else FolderYamlMetadata.candidates(lookups.keys,book.filename,count)
  for(option in priority) {
   val item=lookups[option.filename.lowercase(java.util.Locale.ROOT)]
    ?.takeIf{!it.directory&&FolderYamlMetadata.readableSize(it.sizeBytes)}?:continue
   val loaded=yamlCache.getOrPut(item.path) {
    runCatching{FolderYamlMetadata.document(folderStorage.read(book.source,item))}
     .fold({FolderYamlMetadata.CachedDocument(it)},{FolderYamlMetadata.CachedDocument(null,true)})
   }
   if(loaded.failed){failed=true;continue}
   val data=loaded.document?.select(book.filename,option.allowPlain)?:continue
   val applied=applyYamlValues(book,data.values)
   val embedded=data.embeddedCover?.let{cacheBookCover(book,it)}==true
   if(embedded)book.cover=FolderYamlMetadata.EMBEDDED_COVER
   if(applied||embedded) {
    book.yamlPreferred=true
    book.yamlRevision=FolderYamlMetadata.REVISION
    val label=when(book.source.type){"drive"->"Google Drive";"webdav"->"WebDAV";"ftp"->"FTP";"sftp"->"SFTP";else->"로컬"}
    book.metadataSource=label+" · "+item.name
    return true
   }
  }
  if(failed)throw java.io.IOException("저장소 YAML 파일을 읽지 못했습니다.")
  book.yamlRevision=FolderYamlMetadata.REVISION
  book.yamlPreferred=false
  return false
 }
 private fun resolveFolderCover(book:LibraryBook,items:List<FolderYamlStorage.Entry>) {
  if(book.cover==FolderYamlMetadata.EMBEDDED_COVER)return
  val reference=book.cover.substringAfterLast('/').takeIf{it.isNotBlank()}
  val companion=if(book.yamlPreferred&&book.webCover.isNotBlank())null
   else FolderCoverPolicy.companionCover(book.filename,items.map{it.name})
  val target=(reference?.let{name->items.firstOrNull{it.name.equals(name,true)}}
    ?:companion?.let{name->items.firstOrNull{it.name.equals(name,true)}})?.takeUnless{it.directory}
  book.cover=target?.let{if(book.source.type=="drive")
   items.firstOrNull{entry->entry.path==book.path}?.id.orEmpty()+"|"+it.path else it.name}.orEmpty()
 }
 /** Revalidate authored metadata before web lookup on every filesystem source. */
 internal fun refreshFolderYaml(book:LibraryBook,
                      siblingCache:MutableMap<String,List<FolderYamlStorage.Entry>>?=null,
                      yamlCache:MutableMap<String,FolderYamlMetadata.CachedDocument>?=null,
                      strictPriority:Boolean=false,overlayCached:Boolean=true) {
  if(!FolderYamlMetadata.supports(book.source.type)||!LibraryVisibility.visible(book.path))return
  val folder=book.path.substringBeforeLast('/',"")
  val cacheKey=book.source.id+"|"+folder
  val items=siblingCache?.get(cacheKey)?:folderStorage.list(book.source,folder)
   .also{siblingCache?.put(cacheKey,it)}
  items.firstOrNull{!it.directory&&it.path==book.path}?.sizeBytes?.takeIf{it>=0}?.let{book.sizeBytes=it}
  applyFolderYaml(book,items,yamlCache?:HashMap(),strictPriority)
  resolveFolderCover(book,items)
  if(overlayCached)metadata.enrich(book)
  SeriesVolumeTitle.apply(book)
  if(overlayCached)updateCachedBookSnapshot(book)
  if(overlayCached)promoteYamlBookCover(book)
 }
 private fun updateCachedBookSnapshot(book:LibraryBook) {
  val folder=if(book.source.type=="local")""
   else book.path.substringBeforeLast('/',"")
  val listing=cached(book.source,folder)?:return
  if(listing.books.none{it.id==book.id})return
  saveListing(book.source,listingKey(book.source,folder,""),
   listing.copy(books=listing.books.map{if(it.id==book.id)book else it}))
 }

 private fun scanLocal(source:LibrarySource,out:MutableList<LibraryBook>) {
  val root=folderStorage.localDirectory(source,"")
  val stack=java.util.ArrayDeque<Pair<DocumentFile,String>>()
  val visited=hashSetOf<String>()
  val yamlCache=HashMap<String,FolderYamlMetadata.CachedDocument>()
  stack.addLast(root to "")
  while(stack.isNotEmpty()) {
   if(Thread.currentThread().isInterrupted)throw InterruptedException("탐색 중단")
   val (folder,relative)=stack.removeLast()
   if(!visited.add(folder.uri.toString()))continue
   val children=folder.listFiles().filter{LibraryVisibility.visible(it.name.orEmpty())}
   val items=children.mapNotNull{item->item.name?.let{name->FolderYamlStorage.Entry(name,
    FolderYamlStorage.child(relative,name),item.isDirectory,item.length().takeIf{it>=0}?:-1L,item.uri.toString())}}
   for(item in children)if(item.isDirectory)item.name?.let{name->stack.addLast(item to FolderYamlStorage.child(relative,name))}
   scanFolderBooks(source,items,out,yamlCache)
  }
 }
 private fun connection(url:String,method:String="GET",user:String="",pass:String=""):HttpURLConnection {
  val c=URL(url).openConnection() as HttpURLConnection
  c.requestMethod=method;c.connectTimeout=20000;c.readTimeout=30000
  if(user.isNotEmpty())c.setRequestProperty("Authorization","Basic "+Base64.getEncoder().encodeToString("$user:$pass".toByteArray()))
  return c
 }
 private fun davUrl(base:String,path:String):String=WebDavListing.url(base,path)
 private fun scanFolderBooks(source:LibrarySource,items:List<FolderYamlStorage.Entry>,
                             out:MutableList<LibraryBook>,yamlCache:MutableMap<String,FolderYamlMetadata.CachedDocument>) {
  val start=out.size
  for(item in items) {
   if(item.directory||!isBook(item.name)||!LibraryVisibility.visible(item.path))continue
   val book=book(source,item.path,if(source.type=="local")Uri.parse(item.id) else null)
   book.sizeBytes=item.sizeBytes
   // An unreadable sidecar must not hide an otherwise readable book. Its old
   // revision remains pending so the background metadata job can retry it.
   runCatching{applyFolderYaml(book,items,yamlCache)}
   resolveFolderCover(book,items)
   SeriesVolumeTitle.apply(book)
   out.add(book)
  }
  if(out.size>start)promoteYamlFolderThumbnail(source,out[start].path.substringBeforeLast('/',""),out.subList(start,out.size))
 }
 private fun scanRemote(source:LibrarySource,path:String,dirs:MutableList<String>,
  out:MutableList<LibraryBook>,onProgress:(Int,Int,Int)->Unit={_,_,_->}) {
  val items=folderStorage.list(source,path)
  // Verify descendants before exposing a folder. This preserves parents
  // containing books nested several levels down, but omits empty/metadata-only
  // subtrees from the rendered listing and its persisted cache.
  val key=source.id+"|"+sourceFingerprint(source)
  dirs.addAll(emptyRemoteFolders.filter(
   key,path,items,
   list={child->folderStorage.list(source,child)},
   isBook=::isBook,onProgress=onProgress))
  scanFolderBooks(source,items,out,HashMap())
 }
 private fun openDav(source:LibrarySource,path:String):InputStream {
  val c=connection(davUrl(source.location,path),user=source.user,pass=source.secret)
  try {
   if(c.responseCode !in 200..299)error("WebDAV HTTP ${c.responseCode}")
   return object:java.io.FilterInputStream(c.inputStream) {
    override fun close(){try{super.close()}finally{c.disconnect()}}
   }
  }catch(e:Exception){c.disconnect();throw e}
 }
 private fun ftpClient(source:LibrarySource):FTPClient=FolderYamlStorage.ftpClient(source)
 private fun limited(input:InputStream,maxBytes:Int=5_000_000):ByteArray=input.use { stream ->
  val output=java.io.ByteArrayOutputStream()
  val buffer=ByteArray(8192)
  while(true) {
   val count=stream.read(buffer)
   if(count<0)break
   if(count==0)continue
   if(output.size()+count>maxBytes)error("파일이 너무 큽니다")
   output.write(buffer,0,count)
  }
  output.toByteArray()
 }
 private fun decodableImage(bytes:ByteArray):Boolean {
  val opts=android.graphics.BitmapFactory.Options().apply{inJustDecodeBounds=true}
  android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size,opts)
  return opts.outWidth in 1..20_000 && opts.outHeight in 1..20_000
 }

 data class BookCardRefreshResult(val updated:Boolean,val sourceCode:String,
  val hasCover:Boolean,val wroteOrMoved:Boolean=false)

 /**
  * Book-card refresh has exactly five ordered sources:
  * K: kavita.yaml, Y: book-file YAML, A: Aladin, N: National Library,
  * L: Data4Library. Never silently use unrelated websites here.
  *
  * Save metadata to durable SQLite *before* considering any write/move.
  * Never move a file whose authored sidecar was found, and never overwrite an
  * existing kavita manifest. A failed online search is non-destructive.
  */
 fun refreshBookCardMetadata(book:LibraryBook):BookCardRefreshResult {
  if(book.source.type=="bookoasis") {
   prepareBookDetail(book)
   val cover=detailCover(book,forceOnline=true)
   return BookCardRefreshResult(true,"?",cover,false)
  }
  if(Thread.currentThread().isInterrupted)throw InterruptedException("도서 새로고침 취소")
  val updated=LibraryBook(book.id,book.source,book.path,book.filename,book.uri).apply{
   sizeBytes=book.sizeBytes
  }
  if(FolderYamlMetadata.supports(book.source.type))
   refreshFolderYaml(updated,strictPriority=true,overlayCached=false)
  val hasYaml=updated.yamlPreferred && MetadataSourceBadge.isYaml(updated.metadataSource)
  var authoredCover=false
  if(hasYaml){
   authoredCover=updated.cover==FolderYamlMetadata.EMBEDDED_COVER && bookCovers.has(updated)
   if(!authoredCover && updated.cover.isNotBlank()) {
    val bytes=runCatching{originalCover(updated)}.getOrNull()?.takeIf(::decodableImage)
    if(bytes!=null)authoredCover=cacheBookCover(updated,bytes)
   }
   if(!authoredCover && updated.webCover.isNotBlank()) {
    val bytes=runCatching{metadata.remoteCover(updated.webCover)}.getOrNull()
     ?.takeIf(::decodableImage)
    if(bytes!=null)authoredCover=cacheBookCover(updated,bytes)
   }
  }
  val needsOfficial=!hasYaml || updated.title.isBlank() || updated.author.isBlank() ||
   updated.publisher.isBlank() || updated.summary.isBlank() || !authoredCover
  var matched:WebBookMetadata?=null
  var matchedCover=false
  val lookupTitle=MetadataTitleMatcher.lookupTitle(
   updated.title.ifBlank{book.title},book.filename)
  val lookupAuthor=updated.author.ifBlank{book.author}
  val lookupIsbn=updated.isbn.ifBlank{book.isbn}
  if(needsOfficial){
   val search=BookMetadataSearch(context)
   matched=search.searchOfficial(lookupTitle,lookupAuthor,lookupIsbn,acceptCover={candidate->
    if(Thread.currentThread().isInterrupted)throw InterruptedException("도서정보 검색 취소")
    if(authoredCover)return@searchOfficial false
    val bytes=runCatching{metadata.remoteCover(candidate.coverUrl)}.getOrNull()
     ?.takeIf(::decodableImage)
    if(bytes!=null && cacheBookCover(updated,bytes)){
     matchedCover=true
     true
    } else false
   })
  }
  if(Thread.currentThread().isInterrupted)throw InterruptedException("도서정보 검색 취소")
  if(!hasYaml && matched==null)return BookCardRefreshResult(false,
   MetadataSourceBadge.code(book.metadataSource),bookCovers.has(book))
  // Explicit user action may replace an older automatic/manual candidate;
  // authored YAML fields remain immutable even for a new official match.
  if(matched!=null){
   metadata.recordResult(updated,matched,selected=true)
   metadata.enrich(updated)
  }
  if(hasYaml) {
   // If a lower catalog provided missing text/cover, YAML is still the
   // primary source and its user-authored values win.
   if(!MetadataSourceBadge.isYaml(updated.metadataSource))
    error("원본 YAML 출처가 유실되었습니다.")
  } else updated.metadataSource=matched!!.source
  SeriesVolumeTitle.apply(updated)
  // The row and the SQLite listing must agree before a possible file move.
  book.title=updated.title;book.author=updated.author;book.publisher=updated.publisher
  book.summary=updated.summary;book.genre=updated.genre;book.bookId=updated.bookId
  book.cover=updated.cover;book.webCover=updated.webCover
  book.isbn=updated.isbn;book.metadataSource=updated.metadataSource
  book.metadataUrl=updated.metadataUrl
  book.seriesName=updated.seriesName;book.seriesIndex=updated.seriesIndex
  book.seriesTotal=updated.seriesTotal;book.translator=updated.translator
  book.publishedDate=updated.publishedDate;book.price=updated.price
  book.pageCount=updated.pageCount;book.yamlPreferred=updated.yamlPreferred
  book.yamlRevision=updated.yamlRevision
  updateCachedBookSnapshot(book)
  // A YAML is created only after a successful official match, a re-list of
  // the actual sibling folder, and verified write/move permissions.
  val organized=!hasYaml&&matched!=null&&
   MetadataFileWritePolicy.eligible(lookupTitle,lookupAuthor,lookupIsbn,matched)&&
   syncFolderMetadata(listOf(updated),force=true)
  return BookCardRefreshResult(true,MetadataSourceBadge.code(updated.metadataSource),
   authoredCover||matchedCover||bookCovers.has(book),organized)
 }

 /**
  * Explicit book-card refresh. Read the book itself first (EPUB cover, first
  * CBZ/ZIP image or first PDF page), then fall back to existing cover sources.
  * Never modify the ebook/YAML or download an unbounded remote file.
  */
 fun refreshBookCover(book:LibraryBook):Boolean {
  if(book.source.type=="bookoasis") {
   val image=book.cover.takeIf{it.isNotBlank()}?.let{
    runCatching{BookOasisAccess.client(book.source).cover(it)}.getOrNull()
   }?.takeIf(::decodableImage)
   return image!=null&&cacheBookCover(book,image)
  }
  if(FolderYamlMetadata.supports(book.source.type)&&book.yamlPreferred) {
   if(book.cover==FolderYamlMetadata.EMBEDDED_COVER&&bookCovers.has(book))return true
   val authored=runCatching{originalCover(book)}.getOrNull()?.takeIf(::decodableImage)
   if(authored!=null)return cacheBookCover(book,authored)
  }
  val embedded=if(BookCoverPolicy.supported(book.format))runCatching {
   when(book.source.type) {
    "local"->extractLocalBookCover(book)
    "drive","ftp","webdav","sftp"->extractEmbeddedCover(book)
    else->null
   }
  }.getOrNull() else null
  val candidate=embedded?.takeIf(::decodableImage) ?:
   runCatching{originalCover(book)}.getOrNull()?.takeIf(::decodableImage)
  return candidate!=null && cacheBookCover(book,candidate)
 }

 /** For SAF, try a seekable descriptor; capped copying is only a fallback. */
 private fun extractLocalBookCover(book:LibraryBook):ByteArray? {
  val uri=book.uri?:localDocument(book).uri
  val direct=runCatching {
   if(book.format=="pdf") {
    context.contentResolver.openFileDescriptor(uri,"r")?.let {
     EmbeddedPdfCover.fromDescriptor(it)
    }
   } else {
    context.contentResolver.openFileDescriptor(uri,"r")?.use { descriptor ->
     java.io.FileInputStream(descriptor.fileDescriptor).use { input ->
      val channel=input.channel
      EmbeddedZipCover.extract(channel.size(),book.format) {offset,count ->
       channel.position(offset)
       val buffer=java.nio.ByteBuffer.allocate(count)
       while(buffer.hasRemaining()) {
        val read=channel.read(buffer)
        check(read>0){"도서 부분 읽기에 실패했습니다."}
       }
       buffer.array()
      }
     }
    }
   }
  }.getOrNull()
  if(direct!=null && decodableImage(direct))return direct

  val temp=File.createTempFile("ckreader-local-cover-","."+book.format,context.cacheDir)
  try {
   val stream=context.contentResolver.openInputStream(uri)?:return null
   stream.use {input ->
    temp.outputStream().use{output->copyBoundedPreview(input,output,24L*1024*1024)}
   }
   if(temp.length() !in 24L..24L*1024*1024)return null
   return if(book.format=="pdf")EmbeddedPdfCover.fromLocal(temp)
    else java.io.RandomAccessFile(temp,"r").use {file ->
     EmbeddedZipCover.extract(file.length(),book.format){offset,count ->
      val bytes=ByteArray(count)
      file.seek(offset);file.readFully(bytes);bytes
     }
    }
  } finally {temp.delete()}
 }

 /** A BookOasis volume can be read without first downloading its whole book. */
 private fun extractBookOasisVolumeCover(book:LibraryBook):ByteArray? {
  val id=book.bookId.takeIf{it.toLongOrNull()!=null}?:return null
  val client=BookOasisOpdsAccess.client(book.source)
  if(book.format in setOf("cbz","zip"))
   return runCatching{client.page(id,0)}.getOrNull()?.takeIf(::decodableImage)
  if(book.format !in setOf("pdf","epub"))return null
  val length=client.rangedSize(id)
  if(length!=null) {
   val ranged=runCatching {
    if(book.format=="pdf")EmbeddedPdfCover.fromRanges(context,length){offset,count ->
     client.rangedBytes(id,offset,count)
    } else EmbeddedZipCover.extract(length,"epub"){offset,count ->
     client.rangedBytes(id,offset,count)
    }
   }.getOrNull()
   if(ranged!=null && decodableImage(ranged))return ranged
  }
  val data=client.smallBook(id)?:return null
  if(data.size !in 24..24*1024*1024)return null
  if(book.format=="epub")return EmbeddedZipCover.extract(data.size.toLong(),"epub"){offset,count ->
   data.copyOfRange(offset.toInt(),offset.toInt()+count)
  }
  val temp=File.createTempFile("ckreader-bookoasis-cover-",".pdf",context.cacheDir)
  try {
   temp.writeBytes(data)
   return EmbeddedPdfCover.fromLocal(temp)
  } finally {temp.delete()}
 }

 /**
  * Parse EPUB/CBZ ZIP directory or PDF's first page by byte-range requests.
  * Original remote files are never modified.
  */
 private fun extractEmbeddedCover(book:LibraryBook):ByteArray? {
  if(book.source.type !in setOf("drive","ftp","webdav","sftp") ||
     book.format !in setOf("epub","zip","cbz","pdf"))return null
  // A cover worker must not issue a native Drive/GDS Range request while
  // a foreground download is waiting for the extension's RPC lock. Prefer a
  // valid cached ZIP or leave the cover for regular YAML/web metadata lookup.
  if(book.source.type=="drive" && book.format in setOf("zip","cbz")){
   val file=File(File(context.cacheDir,"library-books"),book.id+"."+book.format)
   if(!file.isFile || !completeZip(file))return null
   return runCatching {
    java.io.RandomAccessFile(file,"r").use {local->
     EmbeddedZipCover.extract(local.length(),book.format){offset,count->
      val bytes=ByteArray(count)
      local.seek(offset);local.readFully(bytes);bytes
     }
    }
   }.getOrNull()?.takeIf(::decodableImage)
  }
  val fast=runCatching {
   val size=archiveSize(book.source,book.path)
   if(book.format=="pdf") EmbeddedPdfCover.fromRanges(context,size) {offset,count ->
    archiveRange(book.source,book.path,offset,count)
   } else EmbeddedZipCover.extract(size,book.format) {offset,count ->
    archiveRange(book.source,book.path,offset,count)
   }
  }.getOrNull()
  if(fast!=null && decodableImage(fast))return fast

  // Servers without reliable Range support: only known, bounded small files.
  val local=runCatching{downloadSmallPreview(book,24L*1024*1024)}.getOrNull()
   ?:return null
  try {
   val result=runCatching {
    if(book.format=="pdf") EmbeddedPdfCover.fromLocal(local)
    else java.io.RandomAccessFile(local,"r").use { file ->
     EmbeddedZipCover.extract(file.length(),book.format) {offset,count ->
      val data=ByteArray(count)
      file.seek(offset);file.readFully(data)
      data
     }
    }
   }.getOrNull()
   return result?.takeIf(::decodableImage)
  } finally {local.delete()}
 }

 /** Avoid downloading arbitrary-sized remote books when seeking is unavailable. */
 private fun downloadSmallPreview(book:LibraryBook,max:Long):File? {
  val source=book.source
  val path=book.path
  val size=when(source.type) {
   "drive" -> rclone.archiveSize(source,path)
   "sftp" -> SftpStorage().size(source,path)
   "webdav" -> {
    val head=connection(davUrl(source.location,path),"HEAD",source.user,source.secret)
    try {
     if(head.responseCode !in 200..299)0L
     else head.getHeaderFieldLong("Content-Length",-1L)
    } finally {head.disconnect()}
   }
   "ftp" -> {
    val client=ftpClient(source)
    try {
     val target=remotePath(source,path)
     (client.mlistFile(target)?.size ?: client.listFiles(target).firstOrNull()?.size ?:0L)
    } finally {runCatching{client.logout();client.disconnect()}}
   }
   else ->0L
  }
  if(size !in 24L..max)return null
  val target=File.createTempFile("ckreader-thumb-","."+book.format,context.cacheDir)
  var successful=false
  try {
   when(source.type) {
    "drive" -> rclone.copyTo(source,path,target)
    "sftp" -> SftpStorage().copy(source,path,target,max)
    "webdav" -> openDav(source,path).use {input ->
     target.outputStream().use {output->copyBoundedPreview(input,output,max)}
    }
    "ftp" -> {
     val client=ftpClient(source)
     try {
      val stream=client.retrieveFileStream(remotePath(source,path))
       ?:error("FTP 파일을 읽을 수 없습니다.")
      try {stream.use {input ->
       target.outputStream().use{output->copyBoundedPreview(input,output,max)}
      }} finally {check(client.completePendingCommand()){"FTP 파일 수신이 끝나지 않았습니다."}}
     } finally {runCatching{client.logout();client.disconnect()}}
    }
   }
   if(target.length() !in 24L..max || target.length()!=size)return null
   successful=true
   return target
  } finally {if(!successful)target.delete()}
 }

 private fun copyBoundedPreview(input:InputStream,output:java.io.OutputStream,max:Long) {
  val buffer=ByteArray(32*1024)
  var count=0L
  while(true){
   if(Thread.currentThread().isInterrupted)throw InterruptedException("표지 추출 취소")
   val n=input.read(buffer)
   if(n<0)break
   if(n==0)continue
   count+=n
   require(count<=max){"원격 파일이 미리보기 최대 크기를 초과했습니다."}
   output.write(buffer,0,n)
  }
 }

 /**
  * BookOasis uses authenticated catalog covers first and falls back to the
  * first volume's internal cover/image when the server supplied no cover.
  */
 private fun bookOasisFolderCover(source:LibrarySource,path:String,coverHint:String):ByteArray? {
  val client=BookOasisAccess.client(source)
  val route=BookOasisPaths.parse(path)
  fun actualCover(reference:String):ByteArray? {
   if(reference.isBlank() || "fallback" in reference.substringBefore('?').lowercase())return null
   return runCatching{client.cover(reference)}.getOrNull()?.takeIf(::decodableImage)
  }
  if(route.level==2) {
   BookOasisCoverPolicy.representative(path,cached(source,path)?.books.orEmpty())?.let{book->
    durable.readCover(book)?.let{return it}
   }
  }
  actualCover(coverHint)?.let{return it}
  if(route.level==2) {
   val books=cached(source,path)?.books?:return null
   books.firstOrNull()?.let{book->
    actualCover(book.cover)?.let{return it}
    if(book.format in setOf("zip","cbz"))
     runCatching{client.imagePage(book.bookId,0)}.getOrNull()?.takeIf(::decodableImage)?.let{return it}
   }
  }
  // Folder cards use authenticated API covers; never scan a full library for a thumbnail.
  return null
 }

 /**
  * Folder thumbnails are resolved on background workers only. BookOasis uses
  * its catalog's representative cover; ordinary remotes must prove that the
  * folder has no child directories before using the first title-sorted book.
  */
 fun folderThumbnail(source:LibrarySource,folderPath:String,coverHint:String=""):ByteArray? {
  require(source.type in setOf("bookoasis","drive","ftp","webdav"))
  val path=safePath(folderPath)
  promoteYamlFolderThumbnail(source,path)?.let{return it}
  cachedBookOasisFolderThumbnail(source,path,coverHint)?.let{return it}
  return folderThumbnails.thumbnail(source,path,coverHint) {
   if(source.type=="bookoasis")bookOasisFolderCover(source,path,coverHint)
   else {
    val folder=cached(source,path) ?: browse(source,path)
    promoteYamlFolderThumbnail(source,path,folder.books)?.let{return@thumbnail it}
    val first=FolderCoverPolicy.firstBook(source.type,folder)
    if(first==null)null
    else {
     // Respect the dedicated cover first. If it is missing or unreadable,
     // extract the original EPUB cover, first CBZ image or PDF first page.
     val separate=runCatching {cover(first)}.getOrNull()
     if(separate!=null && decodableImage(separate))separate
     else extractEmbeddedCover(first)
    }
   }
  }
 }

 fun cover(book:LibraryBook):ByteArray? {
  bookCovers.read(book)?.let{return it}
  if(bookCovers.isMissing(book))return null
  // Load once on a background executor, then keep a 50x50 DB thumbnail
  // indefinitely instead of calling storage/web at each library visit.
  if(refreshBookCover(book))return bookCovers.read(book)
  // Do not persist an unverified negative result: offline/403/timeout
  // failures must remain retryable instead of hiding future valid covers.
  return null
 }
 private fun originalCover(book:LibraryBook):ByteArray? {
  val separate=if(book.cover.isNotBlank())
   runCatching {storedCover(book)}.getOrNull()?.takeIf(::decodableImage) else null
  return separate ?: book.webCover.takeIf { it.isNotBlank() }?.let {
   metadata.remoteCover(it)
  }
 }
 private fun storedCover(book:LibraryBook):ByteArray? {
  if(book.cover==FolderYamlMetadata.EMBEDDED_COVER)return bookCovers.read(book)
  val cover=book.cover.substringAfter('|',book.cover).substringAfterLast('/')
  if(cover.isBlank() || cover=="." || cover=="..")return null
  val parent=book.path.substringBeforeLast('/',"")
  return when(book.source.type) {
   "local"->{
    var folder=DocumentFile.fromTreeUri(context,Uri.parse(book.source.location)) ?: return null
    for(part in parent.split('/').filter { it.isNotBlank() })folder=folder.findFile(part) ?: return null
    val file=folder.findFile(cover) ?: return null
    context.contentResolver.openInputStream(file.uri)?.let(::limited)
   }
   "webdav"->limited(openDav(book.source,if(parent.isEmpty())cover else "$parent/$cover"))
   "sftp"->SftpStorage().read(book.source,if(parent.isEmpty())cover else "$parent/$cover",5_000_000)
   "ftp"->{
    val ftp=ftpClient(book.source)
    try { val path=URL(book.source.location).path.trimEnd('/')+"/"+parent.let { if(it.isBlank())"" else "$it/" }+cover
     val bytes=java.io.ByteArrayOutputStream();if(!ftp.retrieveFile(path,bytes))return null
     if(bytes.size()>5_000_000)return null;bytes.toByteArray()
    } finally { runCatching { ftp.logout();ftp.disconnect() } }
   }
   "drive"->book.cover.substringAfter('|',"").takeIf { it.isNotBlank() }?.let {
    rclone.smallFile(book.source,it,5_000_000L)
   }
   "bookoasis"->book.cover.takeIf {it.isNotBlank()}?.let {
    BookOasisAccess.client(book.source).cover(it)
   }
   else->null
  }
 }
 private fun localDocument(book:LibraryBook):DocumentFile {
  var item=DocumentFile.fromTreeUri(context,Uri.parse(book.source.location)) ?: error("로컬 저장소 접근 불가")
  for(part in book.path.split('/').filter { it.isNotBlank() }) {
   if(part=="." || part=="..")error("올바르지 않은 파일 경로")
   item=item.findFile(part) ?: error("파일을 찾을 수 없습니다: "+book.filename)
  }
  if(!item.isFile)error("파일이 아닌 경로입니다")
  return item
 }
 fun delete(book:LibraryBook) {
  check(!book.source.readOnly){"GDS는 읽기 전용 저장소입니다."}
  check(!DriveAccess.google(book.source)){
   "Google Drive 직접 연결에서는 도서 삭제를 지원하지 않습니다."}
  when(book.source.type) {
   "local"->if(!android.provider.DocumentsContract.deleteDocument(context.contentResolver,book.uri ?: localDocument(book).uri))error("로컬 파일 삭제 실패")
   "webdav"->{
    val c=connection(davUrl(book.source.location,book.path),"DELETE",book.source.user,book.source.secret)
    if(c.responseCode !in 200..299)error("WebDAV 삭제 HTTP ${c.responseCode}");c.disconnect()
   }
   "ftp"->{val ftp=ftpClient(book.source);try {
    if(!ftp.deleteFile(URL(book.source.location).path.trimEnd('/')+"/"+book.path))error("FTP 삭제 실패")
   } finally { runCatching { ftp.logout();ftp.disconnect() } }}
   "drive"->{rclone.deleteFile(book.source,book.path)}
   "bookoasis"->error("북오아시스 연결에서는 원본 도서를 삭제하지 않습니다.")
   else->error("지원하지 않는 저장소")
  }
  File(File(context.cacheDir,"library-books"),book.id+"."+book.format).delete()
  durable.removeBook(book)
  clearCache(book.source.id)
  removeRecentlyOpened(book.id)
 }
 private fun remotePath(source:LibrarySource,path:String):String {
  val parts=path.split('/')
  require(parts.isNotEmpty()&&parts.none {it.isEmpty()||it=="."||it==".."||'\\' in it||'\u0000' in it})
  val base=java.net.URL(source.location).path.trimEnd('/')
  return base+"/"+parts.joinToString("/")
 }
 private fun opdsBookId(path:String):String =
  Regex("^bookoasis:([0-9]+)\\.(?:zip|cbz)$",RegexOption.IGNORE_CASE)
   .matchEntire(path)?.groupValues?.get(1)
   ?:error("Book Oasis ZIP/CBZ 경로가 올바르지 않습니다.")

 fun archiveSize(source:LibrarySource,path:String):Long {
  val size=when(source.type) {
   "drive"->rclone.archiveSize(source,path)
   "sftp"->SftpStorage().size(source,path)
   "webdav"->{
    val endpoint=davUrl(source.location,path)
    val head=connection(endpoint,"HEAD",source.user,source.secret)
    head.instanceFollowRedirects=false
    val contentLength=try {
     if(head.responseCode !in 200..299)error("WebDAV HEAD 실패")
     head.getHeaderFieldLong("Content-Length",-1)
    }finally{head.disconnect()}
    val probe=connection(endpoint,user=source.user,pass=source.secret)
    probe.instanceFollowRedirects=false
    probe.setRequestProperty("Range","bytes=0-0")
    try {
     require(probe.responseCode==206){"WebDAV 서버가 HTTP Range를 지원하지 않습니다."}
     val contentRange=probe.getHeaderField("Content-Range").orEmpty()
     require(contentRange.startsWith("bytes 0-0/")){"WebDAV 바이트 범위를 확인할 수 없습니다."}
     val total=contentRange.substringAfter('/').toLongOrNull()?:error("파일 크기 없음")
     require(contentLength<=0||contentLength==total){"서버 파일 크기 정보 불일치"}
     total
    }finally{probe.disconnect()}
   }
   "ftp"->{
    val client=ftpClient(source)
    try {
     val name=remotePath(source,path)
     val entries=client.listFiles(name)
     val size=entries.firstOrNull{!it.isDirectory&&it.size>0}?.size?:0
     require(size>0){"FTP 서버에서 압축파일 크기를 확인할 수 없습니다."}
     require(client.hasFeature("REST STREAM")){"FTP 서버가 Range 명령을 지원하지 않습니다."}
     size
    }finally{runCatching{client.logout();client.disconnect()}}
   }
   "bookoasis"->BookOasisOpdsAccess.client(source).rangedSize(opdsBookId(path))
    ?:error("Book Oasis OPDS 서버가 ZIP/CBZ Range 요청을 지원하지 않습니다.")
   else->error("이 저장소는 원격 압축 스트리밍을 지원하지 않습니다.")
  }
  require(size in 1..10_000_000_000L){"원격 압축 파일 크기가 지원 범위를 벗어났습니다."}
  return size
 }
 fun archiveRange(source:LibrarySource,path:String,offset:Long,length:Int):ByteArray {
  require(offset>=0&&length in 1..1_048_576)
  return when(source.type) {
   "drive"->rclone.archiveRange(source,path,offset,length)
   "sftp"->SftpStorage().range(source,path,offset,length)
   "webdav"->{
    val conn=connection(davUrl(source.location,path),user=source.user,pass=source.secret)
    conn.instanceFollowRedirects=false
    conn.setRequestProperty("Range","bytes="+offset+"-"+(offset+length-1))
    try {
     require(conn.responseCode==206){"WebDAV Range 요청 실패 (HTTP "+conn.responseCode+")"}
     val response=conn.getHeaderField("Content-Range").orEmpty()
     require(response.startsWith("bytes "+offset+"-")){"요청 범위와 다른 파일이 반환됐습니다."}
     conn.inputStream.use { stream ->
      val data=java.io.ByteArrayOutputStream(length)
      val buffer=ByteArray(32768)
      while(data.size()<length){
       val n=stream.read(buffer,0,minOf(buffer.size,length-data.size()))
       if(n<0)break
       if(n>0)data.write(buffer,0,n)
      }
      data.toByteArray()
     }
    }finally{conn.disconnect()}
   }
   "ftp"->{
    val client=ftpClient(source)
    try {
     client.setRestartOffset(offset)
     val stream=client.retrieveFileStream(remotePath(source,path))
      ?:error("FTP 파일 부분 읽기를 지원하지 않습니다.")
     val data=java.io.ByteArrayOutputStream(length)
     try {stream.use{
      val buffer=ByteArray(32768)
      while(data.size()<length){
       val n=it.read(buffer,0,minOf(buffer.size,length-data.size()))
       if(n<0)break
       if(n>0)data.write(buffer,0,n)
      }
     }}finally{runCatching{client.completePendingCommand()}}
     data.toByteArray()
    }finally{runCatching{client.logout();client.disconnect()}}
   }
   "bookoasis"->BookOasisOpdsAccess.client(source)
    .rangedBytes(opdsBookId(path),offset,length)
   else->error("원격 압축파일 Range 읽기 미지원")
  }
 }
 private fun completeZip(file:File):Boolean=runCatching {
  if(!file.isFile||file.length()<22)return@runCatching false
  java.util.zip.ZipFile(file).use{archive->
   val iterator=archive.entries()
   iterator.hasMoreElements()
  }
 }.getOrDefault(false)

 /** Large ZIP/CBZ files stay on disk; remote Range and OPDS-PSE avoid full downloads. */
 private fun trackedCopy(input:InputStream,target:File,expected:Long,
  onProgress:(Long,Long)->Unit) {
  target.outputStream().use{out->
   input.use{stream->
    var done=0L
    var last=0L
    val buffer=ByteArray(128*1024)
    while(true){
     if(Thread.currentThread().isInterrupted)
      throw java.io.InterruptedIOException("다운로드를 취소했습니다.")
     val read=stream.read(buffer)
     if(read<0)break
     if(read==0)continue
     done+=read
     require(done<=20L*1024*1024*1024){"파일 크기가 20GB를 초과합니다."}
     out.write(buffer,0,read)
     val now=System.currentTimeMillis()
     if(now-last>=300L){onProgress(done,expected);last=now}
    }
    out.fd.sync()
    if(expected>0)require(done==expected){
     "서버 파일 크기가 달라 다운로드가 불완전합니다."
    }
    onProgress(done,expected)
   }
  }
 }

 /** Remote opens return a virtual seekable archive when possible.
  * All full-download modes report transferred bytes (never a fake percentage).
  */
 fun open(book:LibraryBook,onProgress:(Long,Long)->Unit={_,_->},
  onStage:(String)->Unit={}):Uri {
  if(book.source.type=="local"){
   onStage("로컬 ZIP/CBZ 파일 열기 준비 중")
   return book.uri ?:localDocument(book).uri
  }
  book.uri?.let{onStage("보관된 파일 열기");return it}
  val cache=File(context.cacheDir,"library-books").apply{mkdirs()}
  val target=File(cache,book.id+"."+book.format)
  onStage("로컬 캐시 확인 중")
  if(target.exists()&&target.length()>0) {
   if(book.format !in setOf("zip","cbz") || completeZip(target)) {
    onStage("다운로드 완료된 파일로 열기")
    DownloadedBookRetention.markUsed(target)
    DownloadedBookRetention.prune(cache,target)
    return Uri.fromFile(target)
   }
   target.delete()
  }
  var unavailableBookOasisPage=false
  if(book.source.type=="bookoasis"&&book.format in setOf("cbz","zip")&&
    bookOasisPages(book)>0) {
   onStage("북오아시스 OPDS 첫 페이지 확인 중")
   unavailableBookOasisPage=!runCatching {
    BookOasisOpdsAccess.client(book.source).pageAvailable(book.bookId,0)
   }.getOrElse {error->
    if(error is java.util.concurrent.CancellationException ||
       error is java.io.InterruptedIOException || Thread.currentThread().isInterrupted)
     throw error
    android.util.Log.w("ckReader","OPDS page probe failed; using full archive",error)
    false
   }
   if(!unavailableBookOasisPage) {
    onStage("북오아시스 OPDS 페이지 스트리밍 준비")
    return ReaderStreamProvider.bookUri(book.source.id,book.bookId)
   }
   onStage("북오아시스 페이지를 찾을 수 없어 원본 파일로 전환 중")
  }
  // Keep the established disk fallback for Drive ZIP/CBZ archives.
  // Authentication and transport are selected independently by DriveAccess.
  val useDisk=unavailableBookOasisPage || RemoteArchiveReadPolicy.useDisk(
   book.source.type,book.format,book.sizeBytes)
  if(useDisk)onStage("Google Drive/GDS ZIP·CBZ · 다운로드 후 로컬 파일 열기")
  if(!useDisk&&
   book.source.type in setOf("webdav","ftp","sftp","drive","bookoasis")&&
   book.format in setOf("zip","cbz")) {
   val streaming=runCatching {
    onStage("원격 ZIP/CBZ 파일 정보 확인 중")
    val size=if(book.source.type=="drive" && book.sizeBytes in 22..10_000_000_000L)
     book.sizeBytes else archiveSize(book.source,book.path)
    onStage("ZIP 압축파일 헤더 검사 중")
    val first=archiveRange(book.source,book.path,0,4)
    require(first.size==4&&first[0]=='P'.code.toByte()&&first[1]=='K'.code.toByte()){
     "ZIP 파일 헤더를 읽지 못했습니다."
    }
    onStage("ZIP 끝부분 인덱스 확인 중")
    val tail=archiveRange(book.source,book.path,(size-128).coerceAtLeast(0),
     minOf(128L,size).toInt())
    require(tail.isNotEmpty()){"ZIP 끝부분을 읽을 수 없습니다."}
    onStage("원격 ZIP 부분 읽기 준비 완료")
    ReaderStreamProvider.archiveUri(book.source.id,book.path,size)
   }.onFailure {error->
    android.util.Log.w("ckReader","Range reader probe failed; falling back to download "+
     book.source.type+" "+error.javaClass.simpleName)
   }.getOrNull()
   if(streaming!=null)return streaming
  }
  val temp=File(cache,book.id+".part")
  var installed=false
  try {
   if(Thread.currentThread().isInterrupted)
    throw java.io.InterruptedIOException("파일 열기가 취소되었습니다.")
   onStage("전체 ZIP/CBZ 파일 다운로드 중")
   when(book.source.type) {
    "webdav"->trackedCopy(openDav(book.source,book.path),temp,book.sizeBytes,onProgress)
    "ftp"->{
     val ftp=ftpClient(book.source)
     try{
      val path=URL(book.source.location).path.trimEnd('/')+"/"+book.path
      val stream=ftp.retrieveFileStream(path)?:error("FTP 원본 파일을 열 수 없습니다.")
      trackedCopy(stream,temp,book.sizeBytes,onProgress)
      require(ftp.completePendingCommand()){"FTP 전송 종료 처리 실패"}
     }finally{runCatching{ftp.logout();ftp.disconnect()}}
    }
    "drive"->rclone.copyTo(book.source,book.path,temp,book.sizeBytes){stage,done,total->
     onStage(stage)
     if(done>=0)onProgress(done,total)
    }
    "sftp"->SftpStorage().copy(book.source,book.path,temp,
     onProgress=onProgress)
    "bookoasis"->BookOasisOpdsAccess.client(book.source).download(book.bookId,temp,onProgress)
    else->error("지원하지 않는 저장소")
   }
   if(temp.length()==0L)error("빈 파일")
   if(book.format in setOf("zip","cbz")) {
    onStage("다운로드 완료 · ZIP 내부 페이지 목록 검사 중")
    require(completeZip(temp)){"ZIP/CBZ 다운로드가 불완전하거나 압축 파일이 손상되었습니다."}
   }
   if(!temp.renameTo(target))error("파일 저장 실패")
   installed=true
   DownloadedBookRetention.markUsed(target)
   DownloadedBookRetention.prune(cache,target)
   onStage("ZIP/CBZ 준비 완료")
   return Uri.fromFile(target)
  }finally{
   if(book.source.type!="bookoasis"||installed)temp.delete()
  }
 }
 companion object {
  fun rcloneRemotes(text:String):Map<String,JSONObject> {
   val sections=linkedMapOf<String,MutableMap<String,String>>();var current:MutableMap<String,String>?=null
   text.lineSequence().forEach { raw ->
    val line=raw.trim()
    if(line.startsWith("[")&&line.endsWith("]"))current=sections.getOrPut(line.substring(1,line.length-1)){linkedMapOf()}
    else if(!line.startsWith("#")&&!line.startsWith(";")&&'=' in line)current?.put(line.substringBefore('=').trim(),line.substringAfter('=').trim())
   }
   return sections.filterValues { it["type"] in setOf("drive","gds") }.mapValues { (_,data)->
    // Preserve rclone's Shared Drive ID and options verbatim; omitting team_drive
    // causes shared drive files to be looked up under the personal My Drive root.
    JSONObject().apply {
     for((key,value) in data)put(key,value)
    }
   }
  }
 }
}
