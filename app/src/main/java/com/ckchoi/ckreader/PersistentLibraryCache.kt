package com.ckchoi.ckreader

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture

/**
 * Durable cache of listings, metadata and <=50x50 thumbnail BLOBs.
 *
 * Local SQLite files live in filesDir (NOT cacheDir) so Android's cache cleaner
 * cannot evict them. After the user authorizes Documents or Documents/ckReader
 * through ACTION_OPEN_DOCUMENT_TREE, each database is mirrored to the public
 * Documents/ckReader/<source>/<first-directory>.db via SAF.
 *
 * The live SQLite file is closed before export. An independent .pending file
 * is written first and an older .backup file is retained until the new
 * complete database is renamed. No passwords or rclone tokens enter the DB.
 */
internal class PersistentLibraryCache(private val context:Context) {
 private val root=File(context.filesDir,"ckreader-durable-databases-v1")
 private val preferences=context.getSharedPreferences("ckreader_durable_cache",Context.MODE_PRIVATE)

 companion object {
  private val dbLock=Any()
  private val scheduler=Executors.newSingleThreadScheduledExecutor{task->
   Thread(task,"ckreader-cache-export").apply{isDaemon=true}
  }
  private val pendingExports=ConcurrentHashMap<String,ScheduledFuture<*>>()
  @Volatile private var registeredSources:List<Pair<String,String>>?=null
  fun registerSources(sources:List<LibrarySource>) {
   registeredSources=sources.map{it.id to it.name}
  }
  internal fun firstDepth(path:String):String {
   val clean=path.substringBefore("?search=").trim('/').substringBeforeLast('/',"")
   return clean.substringBefore('/').ifBlank{"__root__"}
  }
  internal fun topFolder(path:String):String =
   path.substringBefore("?search=").trim('/').substringBefore('/').ifBlank{"__root__"}
  private fun hash(value:String)=MessageDigest.getInstance("SHA-256")
   .digest(value.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
  private fun label(name:String):String=name.replace(Regex("[^A-Za-z0-9가-힣._-]"),"_")
   .trim('.','_').take(48).ifBlank{"root"}
 }

 fun isLinked():Boolean=folder()!=null
 fun locationText():String=if(isLinked())"Documents/ckReader" else "연결 필요"
 fun hasBeenPrompted():Boolean=preferences.getBoolean("asked",false)
 fun markPrompted() {preferences.edit().putBoolean("asked",true).apply()}

 /** Caller must persist read/write grants before invoking this method. */
 fun linkSelectedTree(uri:Uri):Boolean {
  val selected=DocumentFile.fromTreeUri(context,uri)?:return false
  if(!selected.isDirectory||!selected.canWrite())return false
  val root=when(selected.name) {
   "Documents"->selected.findFile("ckReader")?:selected.createDirectory("ckReader")
   "ckReader"->selected
   else->null
  }?:return false
  if(!root.isDirectory||!root.canWrite())return false
  preferences.edit().putString("tree",uri.toString())
   .putBoolean("chosenIsRoot",selected.name=="ckReader")
   .putBoolean("asked",true).apply()
  // Make previously accumulated app-internal databases visible in Documents,
  // even if they have not been modified since permission was granted.
  rootDirectoryDatabases().forEach{(dir,file)->queueExportByDir(dir,file)}
  return true
 }

 private fun rootDirectoryDatabases():List<Pair<String,File>> =
  root.listFiles()?.flatMap{dir->
   if(!dir.isDirectory)emptyList() else dir.listFiles()
    ?.filter{it.isFile&&it.extension=="db"}?.map{dir.name to it}.orEmpty()
  }.orEmpty()

 private fun folder():DocumentFile?=runCatching{
  val value=preferences.getString("tree",null)?:return@runCatching null
  val parent=DocumentFile.fromTreeUri(context,Uri.parse(value))?:return@runCatching null
  val found=if(preferences.getBoolean("chosenIsRoot",false))parent
   else parent.findFile("ckReader")?:parent.createDirectory("ckReader")
  found?.takeIf{it.isDirectory&&it.canWrite()}
 }.getOrNull()

 private fun sourceFolder(source:LibrarySource):String =
  "source-"+hash(source.id).take(12)
 private fun knownSources():List<Pair<String,String>> = registeredSources ?:
  LibraryRepository(context).sources().map{it.id to it.name}
   .also{registeredSources=it}
 private fun publicFolderName(source:LibrarySource):String =
  CachePublicFolderNames.forSource(source.id,source.name,knownSources())
 private fun publicFolderName(sourceDir:String):String {
  val source=knownSources().firstOrNull{"source-"+hash(it.first).take(12)==sourceDir}
   ?:return sourceDir
  return CachePublicFolderNames.forSource(source.first,source.second,knownSources())
 }
 private fun publicDirectory(root:DocumentFile,sourceDir:String,name:String):DocumentFile? =
  synchronized(dbLock) {
   val old=root.findFile(sourceDir)?.takeIf(DocumentFile::isDirectory)
   var current=root.findFile(name)?.takeIf(DocumentFile::isDirectory)
   if(current==null&&old!=null&&old.renameTo(name))
    current=root.findFile(name)?.takeIf(DocumentFile::isDirectory)?:old
   if(current==null)current=root.createDirectory(name)?.takeIf(DocumentFile::isDirectory)
   if(current!=null&&old!=null&&old.uri!=current.uri) {
    // A previous export may already have created the readable folder. Move
    // only missing files; never replace a newer snapshot or its backup.
    for(file in old.listFiles()) {
     val filename=file.name?:continue
     if(!file.isFile||current.findFile(filename)!=null)continue
     val moved=runCatching {DocumentsContract.moveDocument(context.contentResolver,
      file.uri,old.uri,current.uri)!=null}.getOrDefault(false)
     if(moved)continue
     runCatching {
      val draft=current.createFile("application/octet-stream",filename)?:return@runCatching
      var complete=false
      try {
       if(draft.name!=filename||file.length()>180_000_000L)return@runCatching
       context.contentResolver.openInputStream(file.uri)?.use{input->
        context.contentResolver.openOutputStream(draft.uri,"wt")?.use{output->
         input.copyTo(output,32768)
        }
       }
       complete=draft.length()==file.length()&&draft.length()>0
       if(complete)file.delete()
      }finally{if(!complete)draft.delete()}
     }
    }
    if(old.listFiles().isEmpty())old.delete()
   }
   current
  }
 private fun publicDirectory(source:LibrarySource):DocumentFile? {
  val root=folder()?:return null
  return publicDirectory(root,sourceFolder(source),publicFolderName(source))
 }
 private fun legacyPublicDirectory(source:LibrarySource):DocumentFile?=
  folder()?.findFile(sourceFolder(source))?.takeIf(DocumentFile::isDirectory)
 private fun sourcePrefix(source:LibrarySource):String {
  // A sequence is used ONLY when two registered sources share the same name.
  val sources=registeredSources ?: LibraryRepository(context).sources()
   .map{it.id to it.name}.also{registeredSources=it}
  val duplicates=sources.filter{it.second==source.name}
  val seq=if(duplicates.size>1)"-"+(duplicates.indexOfFirst{it.first==source.id}+1).coerceAtLeast(1) else ""
  return label(source.name)+seq
 }
 private fun depth(path:String,listing:Boolean):String =
  if(listing)topFolder(path) else firstDepth(path)
 private fun partition(source:LibrarySource,path:String,listing:Boolean,
                       partitionHint:String=""):String {
  val raw=if(partitionHint.isNotBlank())partitionHint else depth(path,listing)
  val first=if(partitionHint.isNotBlank())raw.substringBefore('/') else raw
  val display=if(source.type=="bookoasis"&&first!="__root__")
   BookOasisPaths.display(first) else first
  return sourcePrefix(source)+"-"+label(display)+".db"
 }
 private fun dbFile(source:LibrarySource,path:String,listing:Boolean=false,
                    partitionHint:String=""):File {
  val dir=File(root,sourceFolder(source))
  val target=File(dir,partition(source,path,listing,partitionHint))
  // Migrate v1.6.11 databases without discarding existing metadata or cover BLOBs.
  if(!target.exists()) {
   val folder=if(partitionHint.isNotBlank())partitionHint.substringBefore('/')
   else depth(path,listing)
   val old=File(dir,label(folder)+"-"+hash(folder).take(10)+".db")
   if(old.isFile) {
    dir.mkdirs()
    if(!old.renameTo(target))runCatching{old.copyTo(target,overwrite=false)}
   } else {
    // When the source was renamed or another source with the same name was
    // registered, migrate the previous prefix without resetting its cache.
    val alternative=dir.listFiles()?.firstOrNull{
     it.isFile&&it.name.endsWith("-"+label(folder)+".db")&&it!=target
    }
    if(alternative!=null){
     if(!alternative.renameTo(target))
      runCatching{alternative.copyTo(target,overwrite=false)}
    }
   }
  }
  return target
 }
 private fun remote(source:LibrarySource,local:File):DocumentFile? {
  return publicDirectory(source)
 }

 private fun ensureLocal(source:LibrarySource,file:File) {
  if(file.isFile)return
  val directories=listOfNotNull(remote(source,file),legacyPublicDirectory(source)).distinctBy{it.uri}
  if(directories.isEmpty())return
  val segment=file.name.removePrefix(sourcePrefix(source)+"-").removeSuffix(".db")
  val depth=if(segment=="root")"__root__" else segment
  val legacy=label(depth)+"-"+hash(depth).take(10)+".db"
  val existing=directories.firstNotNullOfOrNull{dir->dir.findFile(file.name)
   ?:dir.findFile(file.name+".backup")?:dir.findFile(legacy)}?:return
  if(!existing.isFile||existing.length()>180_000_000L)return
  val tmp=File(file.parentFile,file.name+".import")
  try {
   file.parentFile?.mkdirs()
   context.contentResolver.openInputStream(existing.uri)?.use{input ->
    tmp.outputStream().use{out ->
     val buf=ByteArray(32768);var count=0L
     while(true) {
      val n=input.read(buf);if(n<0)break
      count+=n;require(count<=180_000_000L)
      out.write(buf,0,n)
     }
    }
   }?:return
   if(tmp.length()<=100 || !tmp.inputStream().use {
     val head=ByteArray(16);it.read(head)==16&&String(head,Charsets.US_ASCII)=="SQLite format 3\u0000"
    })return
   tmp.renameTo(file)
  } catch(e:Exception) {Log.w("ckReaderCache","Cache import failed",e)}
  finally {tmp.delete()}
 }

 private fun open(file:File):SQLiteDatabase {
  file.parentFile?.mkdirs()
  val db=SQLiteDatabase.openOrCreateDatabase(file,null)
  db.rawQuery("PRAGMA journal_mode=DELETE",null).use{it.moveToFirst()}
  db.execSQL("CREATE TABLE IF NOT EXISTS listings ("+
   "key TEXT PRIMARY KEY, fingerprint TEXT NOT NULL, payload TEXT NOT NULL)")
  db.execSQL("CREATE TABLE IF NOT EXISTS metadata ("+
   "key TEXT PRIMARY KEY, fingerprint TEXT NOT NULL, payload TEXT NOT NULL)")
  db.execSQL("CREATE TABLE IF NOT EXISTS covers ("+
   "key TEXT PRIMARY KEY, fingerprint TEXT NOT NULL, image BLOB, missing INTEGER NOT NULL DEFAULT 0)")
  db.execSQL("CREATE TABLE IF NOT EXISTS folder_covers ("+
   "key TEXT PRIMARY KEY, fingerprint TEXT NOT NULL, image BLOB NOT NULL)")
  return db
 }
 private fun fingerprint(source:LibrarySource):String =
  hash(source.id+"|"+source.type+"|"+source.location+"|"+source.user+"|"+
   hash(source.secret)+"|"+hash(source.extra))
 private fun coverKey(book:LibraryBook):String =
  hash(book.id+"|"+book.path)
 private fun legacyCoverKeys(book:LibraryBook):List<String> =
  listOf(hash(book.id+"|"+book.path+"|"),
   hash(book.id+"|"+book.path+"|"+book.webCover)).distinct()
 private fun bookKey(book:LibraryBook):String=hash(book.id+"|"+book.path)

 private fun <T> using(source:LibrarySource,path:String,listing:Boolean=false,
                       mutation:Boolean=false,partitionHint:String="",
                       block:(SQLiteDatabase)->T):T =
  synchronized(dbLock) {
   val local=dbFile(source,path,listing,partitionHint)
   ensureLocal(source,local)
   val db=open(local)
   try {
    val result=block(db)
    if(mutation)queueExport(source,local)
    result
   }finally{db.close()}
  }

 private fun findText(db:SQLiteDatabase,table:String,key:String,fingerprint:String):String? {
  db.rawQuery("SELECT payload FROM $table WHERE key=? AND fingerprint=?",
   arrayOf(key,fingerprint)).use {
   return if(it.moveToFirst())it.getString(0) else null
  }
 }
 fun getListing(source:LibrarySource,relative:String):JSONObject?=runCatching {
  using(source,relative,listing=true){db->
   findText(db,"listings",relative,fingerprint(source))?.let(::JSONObject)
  }
 }.getOrNull()
 fun putListing(source:LibrarySource,relative:String,payload:JSONObject) {
  runCatching {using(source,relative,listing=true,mutation=true){db->
   db.execSQL("INSERT OR REPLACE INTO listings(key,fingerprint,payload) VALUES(?,?,?)",
    arrayOf(relative,fingerprint(source),payload.toString()))
  }}.onFailure{Log.w("ckReaderCache","Listing write failed",it)}
 }

 fun getMetadata(book:LibraryBook):JSONObject?=runCatching {
  val primary=using(book.source,book.path,partitionHint=book.cachePartition){db->
   findText(db,"metadata",bookKey(book),fingerprint(book.source))?.let(::JSONObject)
  }
  if(primary!=null||book.cachePartition.isBlank())primary else
   using(book.source,book.path){db->
    findText(db,"metadata",bookKey(book),fingerprint(book.source))?.let(::JSONObject)
   }?.also{putMetadata(book,it)}
 }.getOrNull()
 fun putMetadata(book:LibraryBook,payload:JSONObject) {
  runCatching{using(book.source,book.path,mutation=true,partitionHint=book.cachePartition){db->
   db.execSQL("INSERT OR REPLACE INTO metadata(key,fingerprint,payload) VALUES(?,?,?)",
    arrayOf(bookKey(book),fingerprint(book.source),payload.toString()))
  }}.onFailure{Log.w("ckReaderCache","Metadata write failed",it)}
 }
 /** Stable key prevents a cover vanishing when webCover changes after scrolling. */
 fun readCover(book:LibraryBook):ByteArray?=runCatching {
  var migrated=false
  val found=using(book.source,book.path,partitionHint=book.cachePartition){db->
   val fingerprint=fingerprint(book.source)
   val keys=listOf(coverKey(book))+legacyCoverKeys(book)
   var found:ByteArray?=null
   var foundKey=""
   for(key in keys) {
    db.rawQuery("SELECT image FROM covers WHERE key=? AND fingerprint=?",
     arrayOf(key,fingerprint)).use {
     if(it.moveToFirst()&&!it.isNull(0)){
      found=it.getBlob(0);foundKey=key
     }
    }
    if(found!=null)break
   }
   if(found!=null && foundKey!=coverKey(book)){
    db.execSQL("INSERT OR REPLACE INTO covers(key,fingerprint,image,missing) VALUES(?,?,?,0)",
     arrayOf(coverKey(book),fingerprint,found))
    migrated=true
   }
   found
  }
  if(migrated)queueExport(book.source,dbFile(book.source,book.path,
   partitionHint=book.cachePartition))
  if(found==null && book.cachePartition.isNotBlank()) {
   readCover(book.copy(cachePartition=""))?.also{putCover(book,it)}
  } else found
 }.getOrNull()
 fun hasCover(book:LibraryBook):Boolean=readCover(book)!=null
 fun isMissing(book:LibraryBook):Boolean=runCatching {
  using(book.source,book.path,partitionHint=book.cachePartition){db->
   db.rawQuery("SELECT missing FROM covers WHERE key=? AND fingerprint=?",
    arrayOf(coverKey(book),fingerprint(book.source))).use {
    it.moveToFirst()&&it.getInt(0)==1
   }
  }
 }.getOrDefault(false)
 fun putCover(book:LibraryBook,image:ByteArray?) {
  runCatching{using(book.source,book.path,mutation=true,partitionHint=book.cachePartition){db->
   db.execSQL("INSERT OR REPLACE INTO covers(key,fingerprint,image,missing) VALUES(?,?,?,?)",
    arrayOf(coverKey(book),fingerprint(book.source),image,
     if(image==null)1 else 0))
  }}.onFailure{Log.w("ckReaderCache","Cover write failed",it)}
 }
 fun removeBook(book:LibraryBook) {
  runCatching{using(book.source,book.path,mutation=true,partitionHint=book.cachePartition){db->
   db.execSQL("DELETE FROM metadata WHERE key=?",arrayOf(bookKey(book)))
   db.execSQL("DELETE FROM covers WHERE key=?",arrayOf(coverKey(book)))
  }}.onFailure{Log.w("ckReaderCache","Book cache removal failed",it)}
 }
 fun getFolderCover(source:LibrarySource,path:String,hint:String):ByteArray?=runCatching{
  using(source,path,listing=true){db->
   db.rawQuery("SELECT image FROM folder_covers WHERE key=? AND fingerprint=?",
    arrayOf(hash(path+"|"+hint),fingerprint(source))).use{
     if(it.moveToFirst())it.getBlob(0) else null
    }
  }
 }.getOrNull()
 fun putFolderCover(source:LibrarySource,path:String,hint:String,image:ByteArray) {
  runCatching{using(source,path,listing=true,mutation=true){db->
   db.execSQL("INSERT OR REPLACE INTO folder_covers(key,fingerprint,image) VALUES(?,?,?)",
    arrayOf(hash(path+"|"+hint),fingerprint(source),image))
  }}.onFailure{Log.w("ckReaderCache","Folder cover write failed",it)}
 }
 /**
  * Search cached SQLite only; no remote list or network calls are permitted.
  * Stored snapshot rows are reconstructed with the current source credentials
  * without persisting credentials into public Documents.
  */
 fun cachedDepths(source:LibrarySource):List<String> {
  val folder=File(root,sourceFolder(source))
  // Resolve the root DB first so an old public DB may be restored.
  dbFile(source,"",true).also{ensureLocal(source,it)}
  val prefix=sourcePrefix(source)+"-"
  val local=folder.listFiles()?.filter{it.isFile&&it.name.startsWith(prefix)&&it.name.endsWith(".db")}
   ?.map{it.name.removePrefix(prefix).removeSuffix(".db")}.orEmpty()
  val remote=runCatching {
   listOfNotNull(remote(source,File(folder,"_")),legacyPublicDirectory(source))
    .distinctBy{it.uri}.flatMap{it.listFiles().toList()}
    .filter{it.isFile&&it.name?.startsWith(prefix)==true&&
     it.name?.endsWith(".db")==true}
     .mapNotNull{it.name?.removePrefix(prefix)?.removeSuffix(".db")}
  }.getOrDefault(emptyList())
  return (local+remote).distinct().sorted()
 }
 /** Snapshot existing files only. Measuring usage never creates/imports a DB. */
 fun cacheUsage(source:LibrarySource):CacheUsageSnapshot = synchronized(dbLock) {
  val local=CacheUsage.local(File(root,sourceFolder(source)))
  val mirrored=runCatching {
   val uri=preferences.getString("tree",null)?:return@runCatching emptyMap<String,Long>()
   val selected=DocumentFile.fromTreeUri(context,Uri.parse(uri))
   val publicRoot=if(preferences.getBoolean("chosenIsRoot",false))selected
    else selected?.findFile("ckReader")
    val entries=listOfNotNull(publicRoot?.findFile(publicFolderName(source)),
     publicRoot?.findFile(sourceFolder(source))).distinctBy{it.uri}
     .flatMap{it.listFiles().toList()}.filter{it.isFile}
     .mapNotNull{file->file.name?.let{it to file.length()}}
   CacheUsage.partitions(entries)
  }.getOrDefault(emptyMap())
  CacheUsage.snapshot(local,mirrored)
 }
 fun cacheUsageForDepth(source:LibrarySource,snapshot:CacheUsageSnapshot,depth:String):Long {
  if(depth=="__root__"||depth=="root")return snapshot.totalBytes
  return snapshot.bytes(partition(source,depth,true))
 }
 fun searchCached(sources:List<LibrarySource>,query:String,firstDepth:String?=null,
                  max:Int=500):List<LibraryBook> {
  val key=MetadataTitleMatcher.normalized(query)
  if(key.isBlank())return emptyList()
  val found=LinkedHashMap<String,LibraryBook>()
  val metadata=BookMetadataStore(context)
  for(source in sources) {
   val folder=File(root,sourceFolder(source))
   for(depth in cachedDepths(source)) {
    if(firstDepth!=null&&depth!=firstDepth)continue
    // Import cached public DBs lazily, never connect to a remote bookshelf.
    val file=File(folder,sourcePrefix(source)+"-"+depth+".db")
    if(!file.isFile) {
     runCatching{ensureLocal(source,file)}
    }
    if(!file.isFile)continue
    val payloads=runCatching{ synchronized(dbLock) {
     open(file).use{db->
      val result=ArrayList<String>()
      db.rawQuery("SELECT payload FROM listings",null).use{cursor->
       while(cursor.moveToNext()&&result.size<2000)result.add(cursor.getString(0))
      }
      result
     }
    }}.getOrDefault(emptyList())
    for(text in payloads) {
     val listing=runCatching{JSONObject(text)}.getOrNull()?:continue
     val rows=listing.optJSONArray("books")?:continue
     for(i in 0 until rows.length()) {
      val row=rows.optJSONObject(i)?:continue
      val path=row.optString("path").takeIf{it.isNotBlank()}?:continue
      if(!LibraryVisibility.visible(path))continue
      val filename=path.substringAfterLast('/')
      val id=bookIdFor(source,path)
      val book=LibraryBook(id,source,path,filename).apply {
       title=row.optString("title");author=row.optString("author")
       publisher=row.optString("publisher");bookId=row.optString("bookId")
       cover=row.optString("cover");pageCount=row.optInt("pageCount")
       summary=row.optString("summary");genre=row.optString("genre")
       isbn=row.optString("isbn")
       translator=row.optString("translator");seriesName=row.optString("seriesName")
       seriesTotal=row.optInt("seriesTotal");seriesIndex=row.optInt("seriesIndex")
       publishedDate=row.optString("publishedDate");price=row.optString("price")
       sizeBytes=row.optLong("sizeBytes",-1L)
       yamlPreferred=row.optBoolean("yamlPreferred",false)
       yamlRevision=row.optInt("yamlRevision",0)
       metadataSource=row.optString("metadataSource")
       metadataUrl=row.optString("metadataUrl")
       webCover=row.optString("webCover")
      }
      metadata.enrich(book)
      val fields=listOf(book.title,book.author,book.translator,book.seriesName,
       book.publisher,book.filename,book.genre,book.isbn)
      if(fields.any{MetadataTitleMatcher.normalized(it).contains(key)}) {
       found[source.id+"|"+path]=book
       if(found.size>=max)return LibraryTitleOrder.books(found.values.toList())
      }
     }
    }
   }
  }
  return LibraryTitleOrder.books(found.values.toList())
 }
 private fun bookIdFor(source:LibrarySource,path:String):String {
  val identity=if(source.type=="bookoasis")
   source.id+":"+hash(source.location+"|"+source.user+"|"+
    source.secret+"|"+source.extra)
  else source.id
  return hash(identity+":"+path).take(24)
 }
 fun clearDepth(source:LibrarySource,depth:String) {
  val target=dbFile(source,if(depth=="__root__"||depth=="root")"" else depth,true)
  synchronized(dbLock){
   pendingExports.remove(target.absolutePath)?.cancel(false)
   target.delete()
    listOfNotNull(remote(source,target),legacyPublicDirectory(source))
     .distinctBy{it.uri}.forEach{dir->
      dir.findFile(target.name)?.delete()
      dir.findFile(target.name+".backup")?.delete()
     }
  }
 }
 fun clearFolderCovers(sourceId:String) {wipeTables(sourceId,listOf("folder_covers"))}
 fun clearListings(sourceId:String) {wipeTables(sourceId,listOf("listings"))}
 fun clearMetadata() {
  synchronized(dbLock){root.listFiles()?.forEach{dir->
   dir.listFiles()?.filter{it.extension=="db"}?.forEach{f->
    runCatching{open(f).use{db->
     db.execSQL("DELETE FROM metadata")
     // An embedded-book thumbnail is not a web metadata record.
     // Preserve cached artwork until the user chooses full cache deletion.
    };queueExportByDir(dir.name,f)}
   }
  }}
 }
 /** Never clear the physical file unless the user explicitly requests deletion. */
 fun clearSource(sourceId:String,publicNameOverride:String?=null) {
  synchronized(dbLock) {
    val sourceDir="source-"+hash(sourceId).take(12)
   pendingExports.keys.filter{it.contains("/"+sourceDir+"/")}.forEach{
    pendingExports.remove(it)?.cancel(false)
   }
   root.listFiles()?.filter{it.name.endsWith("-"+hash(sourceId).take(12))}
    ?.forEach{check(it.deleteRecursively()){"내부 도서 캐시를 삭제하지 못했습니다."}}
    val publicName=publicNameOverride ?: knownSources().firstOrNull{it.first==sourceId}?.let{
     CachePublicFolderNames.forSource(it.first,it.second,knownSources())}
    folder()?.listFiles()?.filter{it.name==sourceDir||it.name==publicName}
    ?.forEach{check(it.delete()){"Documents 도서 캐시를 삭제하지 못했습니다."}}
  }
 }
 private fun wipeTables(sourceId:String,tables:List<String>) {
  synchronized(dbLock) {
   root.listFiles()?.filter{it.name.endsWith("-"+hash(sourceId).take(12))}
    ?.forEach{dir->dir.listFiles()?.filter{it.extension=="db"}?.forEach{file->
     runCatching {open(file).use{db->
      tables.forEach{db.execSQL("DELETE FROM $it")}
     };queueExportByDir(dir.name,file)}
    }}
  }
 }

 private fun queueExport(source:LibrarySource,file:File) =
  queueExportByDir(sourceFolder(source),file)
 private fun queueExportByDir(sourceDir:String,file:File) {
  val uri=preferences.getString("tree",null)?:return
  if(uri.isBlank()||!file.isFile)return
  // Each task is serialized with every DB reader/writer to copy only a
  // transactionally closed SQLite file; rapid repeated updates coalesce.
  pendingExports.remove(file.absolutePath)?.cancel(false)
  val job=scheduler.schedule({runCatching {
   synchronized(dbLock) {
    val root=folder()?:return@synchronized
     val dir=publicDirectory(root,sourceDir,publicFolderName(sourceDir))
     ?:return@synchronized
    val draft=dir.findFile(file.name+".pending")?:
     dir.createFile("application/octet-stream",file.name+".pending")
     ?:return@synchronized
    context.contentResolver.openOutputStream(draft.uri,"wt")?.use{out->
     file.inputStream().use{it.copyTo(out,32768)}
    }?:return@synchronized
    if(draft.length()!=file.length())return@synchronized
    val current=dir.findFile(file.name)
    val backup=dir.findFile(file.name+".backup")
    backup?.delete()
    if(current!=null && !current.renameTo(file.name+".backup"))
     return@synchronized
    if(!draft.renameTo(file.name)){
     // Preserve previous complete snapshot if provider refused rename.
     dir.findFile(file.name+".backup")?.renameTo(file.name)
    }else dir.findFile(file.name+".backup")?.delete()
   }
  }.onFailure{Log.w("ckReaderCache","Export to Documents failed",it)}},
   800,TimeUnit.MILLISECONDS)
  pendingExports[file.absolutePath]=job
 }
}
