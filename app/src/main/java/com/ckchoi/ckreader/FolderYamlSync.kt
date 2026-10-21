package com.ckchoi.ckreader

import android.content.Context
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject

/** Organize known books first, then create the missing portable metadata file. */
internal class FolderYamlSync(private val context:Context,private val repo:LibraryRepository) {
 private val storage=FolderYamlStorage(context,repo)
 private val journal=File(context.filesDir,"book-organization-journal")
 companion object {
  private val locks=Array(32){Any()}
  private val attempted=java.util.concurrent.ConcurrentHashMap<String,Long>()
  private fun hash(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString(""){"%02x".format(it)}
  private fun fingerprint(source:LibrarySource)=hash(source.id+"|"+source.type+"|"+source.location+"|"+source.user+"|"+source.secret+"|"+source.extra)
  private fun key(book:LibraryBook)=fingerprint(book.source)+"|"+book.path.substringBeforeLast('/',"")
  fun needs(book:LibraryBook,cache:BookMetadataStore):Boolean {
   if(book.source.readOnly||!FolderYamlMetadata.supports(book.source.type)||!LibraryVisibility.visible(book.path))return false
   if(book.source.type=="drive"&&DriveWritePolicy.readOnlyScope(book.source.extra))return false
   if(MetadataSourceBadge.isYaml(book.metadataSource))return false
   if(android.os.SystemClock.elapsedRealtime()-(attempted[key(book)]?:-300_000L)<300_000L)return false
   return cache.exportFields(book)!=null
  }
 }
 private fun pending(source:LibrarySource,old:LibraryBook,target:String):File {
  journal.mkdirs()
  val file=File(journal,hash(fingerprint(source)+"|"+old.path+"|"+target)+".json")
  val data=JSONObject().put("fingerprint",fingerprint(source)).put("old",old.path).put("target",target).put("size",old.sizeBytes)
  val temp=File(file.parentFile,file.name+".tmp")
  java.io.FileOutputStream(temp).use{it.write(data.toString().toByteArray());it.fd.sync()}
  check(temp.renameTo(file)){"도서 이동 상태를 저장할 수 없습니다."}
  return file
 }
 private fun recover(source:LibrarySource):Set<String> {
  val changed=linkedSetOf<String>()
  for(file in journal.listFiles().orEmpty().filter{it.extension=="json"&&it.length()<16_000})runCatching {
   val data=JSONObject(file.readText())
   if(data.optString("fingerprint")!=fingerprint(source))return@runCatching
   val old=FolderYamlStorage.relative(data.getString("old"));val target=FolderYamlStorage.relative(data.getString("target"))
   require(LibraryVisibility.visible(old)&&LibraryVisibility.visible(target))
   val parent=old.substringBeforeLast('/',"");val destination=target.substringBeforeLast('/',"")
   require(target.startsWith(if(parent.isEmpty())"" else "$parent/"))
   val before=storage.list(source,parent).any{it.path==old}
   val after=storage.list(source,destination).firstOrNull{it.path==target&&!it.directory}
   if(!before&&after!=null&&(data.optLong("size",-1)<0||after.sizeBytes==data.optLong("size"))) {
    repo.migrateMovedBook(repo.bookAt(source,old),target)
    changed.add(destination)
    val contents=storage.list(source,destination)
    if(!FolderYamlStorage.hasManifest(contents.map{it.name})) {
     val books=contents.filter{!it.directory&&it.name.substringAfterLast('.').lowercase() in
      setOf("epub","pdf","txt","md","cbz","zip")}
     val records=books.mapNotNull{repo.exportFolderRecord(repo.bookAt(source,it.path))}
     if(books.isNotEmpty()&&records.size==books.size&&records.all{
       MetadataSourceBadge.isOfficial(it.values["metadata_source"].orEmpty())})
      FolderYamlExport.encode(records)?.let{storage.createOnly(source,destination,it)}
    }
    if(FolderYamlStorage.hasManifest(storage.list(source,destination).map{it.name}))
     check(file.delete())
   }else if(before)check(file.delete())
  }
  return changed
 }
 private fun info(book:LibraryBook,record:FolderYamlExport.Record)=BookOrganizationRules.Book(book.path,
  record.values["title"].orEmpty(),record.values["author"].orEmpty(),record.values["series_name"].orEmpty())
 fun sync(books:List<LibraryBook>,force:Boolean=false):Boolean {
  var changed=false
  for(siblings in books.filter{!it.source.readOnly&&FolderYamlMetadata.supports(it.source.type)&&LibraryVisibility.visible(it.path)&&
   !(it.source.type=="drive"&&DriveWritePolicy.readOnlyScope(it.source.extra))}
   .groupBy{it.source.id+"|"+it.path.substringBeforeLast('/',"")}.values) {
   val first=siblings.first();val source=first.source;val folder=first.path.substringBeforeLast('/',"")
   val lock=locks[(key(first).hashCode() and Int.MAX_VALUE)%locks.size]
   synchronized(lock) {
    if(!force&&android.os.SystemClock.elapsedRealtime()-(attempted[key(first)]?:-300_000L)<300_000L)return@synchronized
    val targets=linkedSetOf<String>()
    try {
     targets.addAll(recover(source))
     if(targets.any{target->FolderYamlStorage.hasManifest(storage.list(source,target).map{it.name})})
      changed=true
     val contents=storage.list(source,folder)
     if(FolderYamlStorage.hasManifest(contents.map{it.name}))return@synchronized
     val names=contents.map{it.name}
     val available=siblings.filter{book->contents.any{it.path==book.path&&!it.directory}}
     // A YAML created or authored for any book must never be treated as a
     // missing manifest. Only official A/N/L matches are eligible for writes.
     val records=available.filterNot{book->
      FolderYamlMetadata.hasPriorityYaml(names,book.filename) ||
       MetadataSourceBadge.isYaml(book.metadataSource)
     }.mapNotNull{book->
      repo.exportFolderRecord(book)?.takeIf{record->
       val metadata=WebBookMetadata(
        title=record.values["title"].orEmpty(),
        author=record.values["author"].orEmpty(),
        isbn=record.values["isbn"].orEmpty(),
        source=record.values["metadata_source"].orEmpty())
       MetadataSourceBadge.isOfficial(metadata.source) && (force ||
        MetadataFileWritePolicy.eligible(
         MetadataTitleMatcher.lookupTitle("",book.filename),"","",metadata))
      }?.let{book.path to it}
     }.toMap()
     if(records.isEmpty())return@synchronized
     val byPath=available.associateBy{it.path}
     val total=contents.count{!it.directory&&it.name.substringAfterLast('.').lowercase() in setOf("epub","pdf","txt","md","cbz","zip")}
     val plans=BookOrganizationRules.plan(folder,available.mapNotNull{book->
      records[book.path]?.let{info(book,it)}
     },total)
     for(plan in plans) {
      if(plan.books.any{com.ckchoi.elibrary.common.ReaderDocumentSession.contains(source.id+":"+it.path)})continue
      if(!storage.canOrganize(source,folder,moveChildren=plan.folder!=folder))continue
      if(plan.folder!=folder&&!storage.makeDirectory(source,plan.folder))continue
      val destination=storage.list(source,plan.folder)
      if((plan.folder!=folder&&destination.any{it.directory})||
       FolderYamlStorage.hasManifest(destination.map{it.name}))continue
      if(plan.folder!=folder&&plan.books.any{book->destination.any{it.name.equals(book.path.substringAfterLast('/'),true)}})continue
      // A partially completed prior group can be resumed only when every
      // existing destination book has the same verified title/series+author.
      val existing=destination.filter{!it.directory&&it.name.substringAfterLast('.').lowercase() in setOf("epub","pdf","txt","md","cbz","zip")}
      val prior=if(plan.folder==folder)emptyList() else existing.map{item->
       val book=repo.bookAt(source,item.path)
       val record=repo.exportFolderRecord(book)?:return@map null
       if(BookOrganizationRules.identity(info(book,record))!=BookOrganizationRules.identity(plan.books.first()))null else book to record
      }
      if(prior.any{it==null})continue
      // Validate the complete manifest before moving an original book. An
      // oversized or incomplete export must leave every source file in place.
      val plannedRecords=prior.filterNotNull().map{it.second}+
       plan.books.mapNotNull{records[it.path]}
      val bytes=FolderYamlExport.encode(plannedRecords)?:continue
      val newlyMoved=mutableListOf<Pair<LibraryBook,LibraryBook>>()
      var complete=true
      for(member in plan.books) {
       val old=byPath[member.path]?:continue
       val target=FolderYamlStorage.child(plan.folder,old.filename)
       if(target==old.path)continue
       val entry=pending(source,old,target)
       if(!storage.moveOnly(source,old.path,target)){complete=false;break}
       targets.add(plan.folder)
       val current=repo.migrateMovedBook(old,target)
       check(entry.delete())
       newlyMoved.add(old to current)
      }
      if(complete) {
       if(runCatching{storage.createOnly(source,plan.folder,bytes)}.getOrDefault(false)){
        targets.add(plan.folder);changed=true
       } else complete=false
      }
      if(!complete) {
       // A destination without its manifest is not a completed organization.
       // Roll back moves that this pass completed; an uncertain failed move
       // keeps its journal entry for normal recovery on the next attempt.
       for((old,current) in newlyMoved.asReversed())runCatching {
        if(storage.moveOnly(source,current.path,old.path))
         repo.migrateMovedBook(current,old.path)
       }
      }
     }
    }catch(e:Exception) {
     android.util.Log.w("ckReader","Folder organization/YAML deferred (${source.type}, ${e.javaClass.simpleName})")
    }finally {
     attempted[key(first)]=android.os.SystemClock.elapsedRealtime()
     if(targets.isNotEmpty())runCatching{repo.refreshOrganizedFolders(source,folder,targets)}
     if(targets.isNotEmpty())targets.forEach{target->repo.cached(source,if(source.type=="local")"" else target)?.books
      ?.firstOrNull{it.path.substringBeforeLast('/',"")==target}?.let{repo.requestYamlWriteback(it)}}
    }
   }
  }
  return changed
 }
}
