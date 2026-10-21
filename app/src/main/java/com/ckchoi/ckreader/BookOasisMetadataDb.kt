package com.ckchoi.ckreader

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Local, account-isolated OPDS metadata index. No credentials, signed URLs,
 * original books or cover binaries are stored here. This DB survives cacheDir
 * cleanup and serves catalog/details without network requests.
 */
internal class BookOasisMetadataDb(context:Context) {
 private val root=File(context.filesDir,"bookoasis-api-index-v2")
 companion object {
  private val lock=Any()
  private fun digest(value:String):String=MessageDigest.getInstance("SHA-256")
   .digest(value.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
 }
 private fun path(source:LibrarySource):File =
  File(root,"opds-"+digest(source.id).take(24)+".db")
 private fun identity(source:LibrarySource)=digest(source.id+"|"+source.location+"|"+
  source.user+"|"+digest(source.secret)+"|"+digest(source.extra))

 private fun <T> use(source:LibrarySource,block:(SQLiteDatabase)->T):T=synchronized(lock) {
  val file=path(source);file.parentFile?.mkdirs()
  SQLiteDatabase.openOrCreateDatabase(file,null).use{db->
   db.execSQL("CREATE TABLE IF NOT EXISTS identity (hash TEXT NOT NULL)")
   db.execSQL("CREATE TABLE IF NOT EXISTS feeds ("+
    "cache_key TEXT PRIMARY KEY, has_more INTEGER NOT NULL, page INTEGER NOT NULL,"+
    "scanned_at INTEGER NOT NULL)")
   db.execSQL("CREATE TABLE IF NOT EXISTS entries ("+
    "cache_key TEXT NOT NULL, ordinal INTEGER NOT NULL, is_book INTEGER NOT NULL,"+
    "segment TEXT NOT NULL, title TEXT NOT NULL, author TEXT NOT NULL,"+
    "publisher TEXT NOT NULL, cover TEXT NOT NULL, description TEXT NOT NULL,"+
    "genre TEXT NOT NULL, isbn TEXT NOT NULL, translator TEXT NOT NULL,"+
    "pages INTEGER NOT NULL, volume_count INTEGER NOT NULL, volume_index INTEGER NOT NULL,"+
     "book_id TEXT NOT NULL, partition TEXT NOT NULL, metadata_source TEXT NOT NULL,"+
    "extra TEXT NOT NULL DEFAULT '{}', PRIMARY KEY (cache_key,ordinal))")
   db.execSQL("CREATE INDEX IF NOT EXISTS idx_opds_entries_title ON entries(title COLLATE NOCASE)")
   db.execSQL("CREATE INDEX IF NOT EXISTS idx_opds_entries_book ON entries(book_id)")
   val fp=identity(source)
   val previous=db.rawQuery("SELECT hash FROM identity LIMIT 1",null).use{
    if(it.moveToFirst())it.getString(0) else null
   }
   if(fp!=previous) {
    db.beginTransaction()
    try {
     db.execSQL("DELETE FROM feeds")
     db.execSQL("DELETE FROM entries")
     db.execSQL("DELETE FROM identity")
     db.execSQL("INSERT INTO identity(hash) VALUES(?)",arrayOf(fp))
     db.setTransactionSuccessful()
    }finally{db.endTransaction()}
   }
   block(db)
  }
 }
 private fun values(key:String,index:Int,isBook:Boolean,segment:String,title:String,
                    author:String,publisher:String,cover:String,description:String,
                    genre:String,isbn:String,translator:String,pages:Int,
                    count:Int,volumeIndex:Int,bookId:String,partition:String,metadata:String,
                    extra:String="{}"):ContentValues =
  ContentValues(19).apply {
   put("cache_key",key);put("ordinal",index);put("is_book",if(isBook)1 else 0)
   put("segment",segment);put("title",title);put("author",author)
   put("publisher",publisher);put("cover",cover);put("description",description)
   put("genre",genre);put("isbn",isbn);put("translator",translator)
   put("pages",pages);put("volume_count",count);put("volume_index",volumeIndex)
   put("book_id",bookId);put("partition",partition);put("metadata_source",metadata)
   put("extra",extra)
  }
 fun save(source:LibrarySource,key:String,listing:LibraryListing) {
  use(source){db->
   db.beginTransaction()
   try {
    db.delete("entries","cache_key=?",arrayOf(key))
    var ordinal=0
    listing.folders.forEach {folder->
     val info=listing.folderInfos[folder]?:FolderCatalogInfo()
     db.insertOrThrow("entries",null,values(key,ordinal++,false,folder,
      BookOasisPaths.display(folder),info.author,info.publisher,
      listing.folderCovers[folder].orEmpty(),info.summary,"","","",0,info.volumeCount,0,"","",""))
    }
    listing.books.forEach {book->
     db.insertOrThrow("entries",null,values(key,ordinal++,true,book.path,
      book.title,book.author,book.publisher,book.cover,book.summary,book.genre,
      book.isbn,book.translator,book.pageCount,book.seriesTotal,
      book.seriesIndex,book.bookId,book.cachePartition,book.metadataSource,
      JSONObject().put("seriesName",book.seriesName).put("publishedDate",book.publishedDate)
       .put("price",book.price).put("sizeBytes",book.sizeBytes)
       .put("metadataUrl",book.metadataUrl).put("webCover",book.webCover)
       .put("yamlRevision",book.yamlRevision).put("yamlPreferred",book.yamlPreferred).toString()))
    }
    val state=ContentValues(4).apply{
     put("cache_key",key);put("has_more",if(listing.hasMore)1 else 0)
     put("page",listing.page);put("scanned_at",listing.scannedAt)
    }
    db.insertWithOnConflict("feeds",null,state,SQLiteDatabase.CONFLICT_REPLACE)
    db.setTransactionSuccessful()
   }finally{db.endTransaction()}
  }
 }
 fun read(source:LibrarySource,key:String,book:(String)->LibraryBook):LibraryListing? =
  use(source){db->
   val state=db.rawQuery("SELECT has_more,page,scanned_at FROM feeds WHERE cache_key=?",
    arrayOf(key)).use{if(it.moveToFirst()) Triple(it.getInt(0)!=0,it.getInt(1),it.getLong(2)) else null}
    ?:return@use null
   val folders=ArrayList<String>()
   val books=ArrayList<LibraryBook>()
   val covers=LinkedHashMap<String,String>()
   val infos=LinkedHashMap<String,FolderCatalogInfo>()
   db.rawQuery("SELECT is_book,segment,title,author,publisher,cover,description,genre,"+
     "isbn,translator,pages,volume_count,volume_index,book_id,partition,metadata_source,extra"+
    " FROM entries WHERE cache_key=? ORDER BY ordinal",arrayOf(key)).use{cursor->
    while(cursor.moveToNext()) {
     val segment=cursor.getString(1)
     val author=cursor.getString(3)
     val publisher=cursor.getString(4)
     val cover=cursor.getString(5)
     val summary=cursor.getString(6)
     if(cursor.getInt(0)==0) {
      folders.add(segment)
      if(cover.isNotBlank())covers[segment]=cover
      infos[segment]=FolderCatalogInfo(author,publisher,cursor.getInt(11),summary)
     } else {
      books.add(book(segment).apply {
       title=cursor.getString(2);this.author=author;this.publisher=publisher
       this.cover=cover;this.summary=summary;genre=cursor.getString(7)
       isbn=cursor.getString(8);translator=cursor.getString(9)
       pageCount=cursor.getInt(10);seriesTotal=cursor.getInt(11)
       seriesIndex=cursor.getInt(12);bookId=cursor.getString(13)
       cachePartition=cursor.getString(14);metadataSource=cursor.getString(15)
       applyExtra(this,cursor.getString(16))
      })
     }
    }
   }
   LibraryListing(folders,books,state.third,state.first,state.second,covers,infos)
  }

 private fun applyExtra(book:LibraryBook,raw:String) {
  val data=runCatching{JSONObject(raw)}.getOrDefault(JSONObject())
  book.seriesName=data.optString("seriesName")
  book.publishedDate=data.optString("publishedDate")
  book.price=data.optString("price")
  book.sizeBytes=data.optLong("sizeBytes",-1)
  book.metadataUrl=data.optString("metadataUrl")
  book.webCover=data.optString("webCover")
  book.yamlRevision=data.optInt("yamlRevision")
  book.yamlPreferred=data.optBoolean("yamlPreferred")
 }

 /** Instant "All books" shelf assembled from already indexed OPDS libraries. */
 fun cachedHome(source:LibrarySource):LibraryListing?=use(source){db->
  val folders=LinkedHashMap<String,FolderCatalogInfo>()
  val covers=LinkedHashMap<String,String>()
  db.rawQuery("SELECT segment,author,publisher,cover,description,volume_count"+
   " FROM entries WHERE is_book=0 AND segment LIKE 's~%'"+
   " AND cache_key LIKE 'l~%' AND cache_key NOT LIKE '%/%'"+
   " ORDER BY title COLLATE NOCASE LIMIT 120000",null).use{cursor->
   while(cursor.moveToNext()) {
    val segment=cursor.getString(0)
    folders.putIfAbsent(segment,FolderCatalogInfo(cursor.getString(1),
     cursor.getString(2),cursor.getInt(5),cursor.getString(4)))
    val cover=cursor.getString(3)
    if(cover.isNotBlank())covers[segment]=cover
   }
  }
  if(folders.isEmpty())null else LibraryListing(folders.keys.toList(),emptyList(),
   folderCovers=covers,folderInfos=folders)
 }

 /** Offline prefix/substring matching when the server-side search feed is unavailable. */
 fun offlineSearch(source:LibrarySource,query:String,book:(String)->LibraryBook):LibraryListing? {
  val needle=query.trim().take(200)
  if(needle.isBlank())return null
  return use(source){db->
   val count=db.rawQuery("SELECT COUNT(*) FROM feeds",null).use{
    if(it.moveToFirst())it.getInt(0) else 0
   }
   if(count==0)return@use null
   val folders=LinkedHashMap<String,FolderCatalogInfo>()
   val covers=LinkedHashMap<String,String>()
   val books=LinkedHashMap<String,LibraryBook>()
   val like="%"+needle.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%"
   db.rawQuery("SELECT is_book,segment,title,author,publisher,cover,description,genre,"+
     "isbn,translator,pages,volume_count,volume_index,book_id,partition,metadata_source,extra"+
    " FROM entries WHERE (title LIKE ? ESCAPE '\\' OR author LIKE ? ESCAPE '\\')"+
    " ORDER BY is_book ASC,title COLLATE NOCASE LIMIT 2000",arrayOf(like,like)).use{cursor->
    while(cursor.moveToNext()) {
     val segment=cursor.getString(1)
     val title=cursor.getString(2)
     if(cursor.getInt(0)==0 && segment.startsWith("s~")) {
      folders.putIfAbsent(segment,FolderCatalogInfo(
       cursor.getString(3),cursor.getString(4),cursor.getInt(11),cursor.getString(6)))
      val cover=cursor.getString(5)
      if(cover.isNotBlank())covers[segment]=cover
     }else if(cursor.getInt(0)==1) {
      val id=cursor.getString(13)
      if(id.isBlank())continue
      books.putIfAbsent(id,book(segment).apply {
       this.title=title;author=cursor.getString(3);publisher=cursor.getString(4)
       cover=cursor.getString(5);summary=cursor.getString(6)
       genre=cursor.getString(7);isbn=cursor.getString(8)
       translator=cursor.getString(9);pageCount=cursor.getInt(10)
       seriesTotal=cursor.getInt(11);seriesIndex=cursor.getInt(12)
       bookId=id;cachePartition=cursor.getString(14);metadataSource=cursor.getString(15)
       applyExtra(this,cursor.getString(16))
      })
     }
    }
   }
   LibraryListing(folders.keys.toList(),books.values.toList(),folderCovers=covers,folderInfos=folders)
  }
 }
 fun clear(sourceId:String) {synchronized(lock){
  pathForId(sourceId).delete()
  File(root,pathForId(sourceId).name+"-journal").delete()
  File(root,pathForId(sourceId).name+"-wal").delete()
  File(root,pathForId(sourceId).name+"-shm").delete()
 }}
 private fun pathForId(id:String)=File(root,"opds-"+digest(id).take(24)+".db")
}
