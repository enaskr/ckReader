package com.ckchoi.ckreader

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.util.Locale
import java.util.Base64
import java.text.Normalizer

/** The same bounded, filename-aware Kavita parser for every filesystem source. */
internal object FolderYamlMetadata {
 const val REVISION=4
 // Multi-volume GDS manifests contain one Base64 cover per volume; real
 // 15-volume folders exceed 16 MiB. Keep a finite bound for every backend.
 const val MAX_BYTES=32*1024*1024
 const val MAX_COVER_BYTES=5_000_000
 const val EMBEDDED_COVER="ckreader:yaml-embedded-cover"
 data class Candidate(val filename:String,val allowPlain:Boolean)
 data class Result(val values:Map<String,String>,val embeddedCover:ByteArray?)
 data class CachedDocument(val document:Document?,val failed:Boolean=false)
 class Document internal constructor(private val root:Map<String,Any?>) {
  fun select(filename:String,allowPlain:Boolean):Result?=selectRecord(root,filename,allowPlain)
 }
 private val generic=listOf("kavita.yaml","kavita.yml","metadata.yaml","metadata.yml",
  "book.yaml","book.yml","bookinfo.yaml","bookinfo.yml","info.yaml","info.yml",
  "books.yaml","books.yml","index.yaml","index.yml")
 private val fileFields=listOf("filename","file","file_name","path","book_file","source_file")
 private val containers=listOf("books","files","items","entries","volumes")
 private val yamlExtension=Regex("\\.(yaml|yml)$",RegexOption.IGNORE_CASE)
 fun supports(sourceType:String)=sourceType in setOf("local","drive","webdav","ftp","sftp")
 fun needsRefresh(sourceType:String,revision:Int)=supports(sourceType)&&revision<REVISION
 fun readableSize(size:Long):Boolean=size<0||size in 1L..MAX_BYTES.toLong()
 private fun basename(file:String)=file.replace('\\','/').substringAfterLast('/').trim()
 private fun stem(file:String)=basename(file).substringBeforeLast('.')
 private fun key(text:String)=Normalizer.normalize(text,Normalizer.Form.NFC).lowercase(Locale.ROOT).trim()
 private fun fieldKey(text:String)=key(text).replace(Regex("[\\s_-]"),"")
 private fun map(raw:Any?):Map<String,Any?>? =
  (raw as? Map<*,*>)?.entries?.associate{it.key.toString() to it.value}
 private fun get(record:Map<String,Any?>,name:String)=record.entries.firstOrNull{fieldKey(it.key)==fieldKey(name)}?.value
 private fun scalar(value:Any?):String=when(value) {
  null->""
  is String->value.trim()
  is Number,is Boolean->value.toString()
  is java.util.Date->java.text.SimpleDateFormat("yyyy-MM-dd",Locale.ROOT).apply{timeZone=java.util.TimeZone.getTimeZone("UTC")}.format(value)
  is List<*>->value.map{scalar(it)}.filter{it.isNotBlank()}.take(15).joinToString(", ")
  is Map<*,*>->scalar(value.entries.firstOrNull{it.key.toString().equals("name",true)}?.value)
  else->""
 }.take(800)

 fun candidates(names:Collection<String>,bookFilename:String,bookCount:Int):List<Candidate> {
  val yaml=names.filter{yamlExtension.containsMatchIn(it)}.distinctBy(::key)
  val exact=stem(bookFilename)
  val result=ArrayList<Candidate>()
  fun add(name:String,allowPlain:Boolean) {
   yaml.firstOrNull{key(it)==key(name)}?.let{chosen->
    if(result.none{key(it.filename)==key(chosen)})result.add(Candidate(chosen,allowPlain))
   }
  }
  // Existing Kavita manifests may define common author/series data for every
  // sibling. Filename-keyed manifests still reject unlisted files below.
  for(name in listOf("kavita.yaml","kavita.yml"))add(name,true)
  // Both <file>.epub.yaml and <file>.yaml are supported, after kavita.
  for(ext in listOf("yaml","yml"))add(bookFilename+"."+ext,true)
  for(ext in listOf("yaml","yml"))add(exact+"."+ext,true)
  for(name in generic)add(name,bookCount==1)
  for(name in yaml)add(name,bookCount==1)
  return result
 }
 /** Explicit refresh has exactly the two authored stages K -> Y before APIs. */
 fun priorityCandidates(names:Collection<String>,bookFilename:String,bookCount:Int=1):List<Candidate> {
  val available=names.filter{yamlExtension.containsMatchIn(it)}
  val result=ArrayList<Candidate>()
  fun add(expected:String,allowPlain:Boolean=true) {
   available.firstOrNull{key(it)==key(expected)}?.let {found ->
    if(result.none{key(it.filename)==key(found)})result.add(Candidate(found,allowPlain))
   }
  }
  add("kavita.yaml");add("kavita.yml")
  for(ext in listOf("yaml","yml"))add(bookFilename+"."+ext)
  val stem=stem(bookFilename)
  for(ext in listOf("yaml","yml"))add(stem+"."+ext)
  return result
 }
 fun hasPriorityYaml(names:Collection<String>,bookFilename:String):Boolean =
  priorityCandidates(names,bookFilename).isNotEmpty()

 private fun matchRank(candidate:String,filename:String):Int {
  val given=basename(candidate)
  if(given.isBlank())return 0
  return when {
   key(given)==key(basename(filename))->2
   key(stem(given))==key(stem(filename))->1
   else->0
  }
 }
 private fun declaredFile(record:Map<String,Any?>):String?=fileFields
  .firstNotNullOfOrNull{name->get(record,name)?.let(::scalar)?.takeIf{it.isNotBlank()}}
 private fun recordMatches(record:Map<String,Any?>,filename:String)=
  declaredFile(record)?.let{matchRank(it,filename)>0}==true
 private fun findByFile(container:Any?,filename:String):Map<String,Any?>? {
  val asMap=map(container)
  if(asMap!=null) {
   val keyed=asMap.entries.mapNotNull{entry->map(entry.value)?.let{Triple(matchRank(entry.key,filename),entry.key,it)}}
   keyed.firstOrNull{it.first==2}?.let{return it.third}
   keyed.filter{it.first==1}.singleOrNull()?.let{return it.third}
  }
  val records=if(container is List<*>)container.mapNotNull(::map) else asMap?.values?.mapNotNull(::map).orEmpty()
  records.firstOrNull{declaredFile(it)?.let{file->matchRank(file,filename)}==2}?.let{return it}
  return records.filter{recordMatches(it,filename)}.singleOrNull()
 }
 fun document(data:ByteArray):Document? {
  if(data.isEmpty()||data.size>MAX_BYTES)return null
  val options=LoaderOptions().apply{setMaxAliasesForCollections(12);setCodePointLimit(MAX_BYTES);nestingDepthLimit=50}
  val root=runCatching{map(Yaml(SafeConstructor(options)).load<Any>(String(data,Charsets.UTF_8)))}.getOrNull()?:return null
  return Document(root)
 }
 fun read(data:ByteArray,filename:String,allowPlain:Boolean)=document(data)?.select(filename,allowPlain)
 fun parse(data:ByteArray,filename:String,allowPlain:Boolean):Map<String,String>?=read(data,filename,allowPlain)?.values

 private fun selectRecord(root:Map<String,Any?>,filename:String,allowPlain:Boolean):Result? {
  val meta=map(get(root,"meta"))
  val manifests=containers.mapNotNull{get(root,it)}
  val matched=manifests.firstNotNullOfOrNull{findByFile(it,filename)}?:findByFile(root,filename)
  // A filename manifest must not lend another file's artwork or metadata to this book.
  if(matched==null&&manifests.any{it is Map<*,*>||it is List<*>})return null
  val selected=matched?:if(allowPlain) {
   listOf("book","metadata","book_info","bookInfo","meta")
    .firstNotNullOfOrNull{map(get(root,it))}?:root
  } else return null
  if(declaredFile(selected)!=null&&!recordMatches(selected,filename))return null
  val common=meta?.let(::fields).orEmpty()
  val specific=fields(selected)
  val title=common["title"].orEmpty()
  val prefix=Regex("^\\[([^]]{1,80})]\\s*").find(stem(filename))
  val fileTitle=stem(filename).removeRange(0,prefix?.range?.last?.plus(1)?:0).trim()
  val hint=common["author"].orEmpty().ifBlank{prefix?.groupValues?.get(1).orEmpty()
   .takeUnless{it.lowercase(Locale.ROOT) in setOf("소설","웹소설","만화","epub","pdf","txt","리디","전자책")}.orEmpty()}
  val search=(get(root,"search") as? List<*>)?.take(100)?.mapNotNull(::map)
   ?.map{record->record to fields(record)}
   ?.filter{(_,values)->compatibleAuthor(hint,values["author"].orEmpty())}
   ?.map{(record,values)->Triple(record,values,when {
    sameTitle(values["title"].orEmpty(),specific["title"].orEmpty().ifBlank{fileTitle})->3
    sameTitle(values["title"].orEmpty(),title)->2
    else->0
   })}
   ?.filter{it.third>0}
   ?.sortedWith(compareByDescending<Triple<Map<String,Any?>,Map<String,String>,Int>>{it.third}
    .thenByDescending{scalar(get(it.first,"score")).toDoubleOrNull()?:100.0})?.firstOrNull()
  val converted=LinkedHashMap<String,String>()
  search?.let{converted.putAll(it.second)}
  converted.putAll(common)
  converted.putAll(specific)
  // A series-level Name must not erase the filename's specific volume title.
  if(search?.third==3&&specific["title"].isNullOrBlank()&&title.isNotBlank()) {
   val searched=search.second["title"].orEmpty()
   if(!sameTitle(searched,title)&&sameTitle(MetadataTitleMatcher.lookupTitle(searched,filename),title)) {
    converted["title"]=searched
    converted.putIfAbsent("series_name",title)
    Regex("(\\d{1,4})\\s*권\\s*$").find(searched)?.groupValues?.get(1)
     ?.toIntOrNull()?.takeIf{it>0}?.let{converted.putIfAbsent("series_index",it.toString())}
   }
  }
  map(get(root,"action"))?.let{action->
   scalar(get(action,"code")).takeIf{it.isNotBlank()}?.let{converted.putIfAbsent("book_id",it)}
  }
  val embedded=sequenceOf(selected,meta,search?.first).filterNotNull()
   .mapNotNull{embeddedCover(rawCover(it))}.firstOrNull()
  val usable=converted.keys.any{fieldKey(it) in setOf("title","booktitle","name","author","authors",
   "publisher","cover","coverimage","coverurl","isbn","isbn13","description","summary","translator",
   "series","seriesname","seriesindex","publisheddate","price","genre","pagenumber","pagecount",
   "제목","저자","출판사","표지","출간일","번역자")}
  return Result(converted,embedded).takeIf{usable||embedded!=null}
 }
 private fun sameTitle(left:String,right:String)=left.isNotBlank()&&right.isNotBlank()&&
  MetadataTitleMatcher.normalized(left)==MetadataTitleMatcher.normalized(right)
 private fun compatibleAuthor(expected:String,offered:String):Boolean {
  val a=MetadataTitleMatcher.normalized(expected);val b=MetadataTitleMatcher.normalized(offered)
  return a.isBlank()||b.isBlank()||a.contains(b)||b.contains(a)
 }
 private fun rawCover(record:Map<String,Any?>):Any?=listOf("cover","cover_image","cover_url","image","표지","poster_url")
  .firstNotNullOfOrNull{name->get(record,name)?.takeIf{it is ByteArray||(it is String&&it.isNotBlank())}}
 private fun isEmbedded(value:Any?):Boolean=value is ByteArray || (value is String&&
  (value.startsWith("data:image/",true)||listOf("iVBORw0KGgo","/9j/","R0lGOD","UklGR","Qk").any{value.startsWith(it)}))
 private fun embeddedCover(value:Any?):ByteArray? {
  if(!isEmbedded(value))return null
  val bytes=if(value is ByteArray)value else {
   val text=value as String
   if(text.length>(MAX_COVER_BYTES*4L/3+4096))return null
   val encoded=(if(text.startsWith("data:",true))text.substringAfter(',') else text).replace(Regex("\\s"),"")
   runCatching{Base64.getDecoder().decode(encoded)}.getOrNull()?:return null
  }
  if(bytes.size !in 24..MAX_COVER_BYTES)return null
  val signature=bytes.take(12).map{it.toInt() and 255}
  val image=signature.take(4)==listOf(137,80,78,71)||signature.take(3)==listOf(255,216,255)||
   signature.take(3)==listOf(71,73,70)||signature.take(2)==listOf(66,77)||
   (signature.take(4)==listOf(82,73,70,70)&&signature.drop(8).take(4)==listOf(87,69,66,80))
  return bytes.takeIf{image}
 }
 private fun fields(record:Map<String,Any?>):Map<String,String> {
  val result=record.mapValues{(_,value)->scalar(value)}.filterValues{it.isNotBlank()}.toMutableMap()
  fun set(target:String,vararg aliases:String) {
   aliases.firstNotNullOfOrNull{name->get(record,name)?.let(::scalar)?.takeIf{it.isNotBlank()}}?.let{value->
    result.keys.filter{fieldKey(it)==fieldKey(target)}.toList().forEach{result.remove(it)}
    result[target]=value
   }
  }
  set("title","title","book_title","Name","제목","도서명")
  set("author","author","authors","Person Writers","Writer","Writers","저자")
  set("publisher","publisher","Person Publisher","출판사")
  set("summary","summary","description","synopsis","소개","줄거리")
  result["summary"]?.takeIf{it.contains('<')}?.let{result["summary"]=org.jsoup.Jsoup.parse(it).text().take(800)}
  set("genre","genre","Genres","category_name","category","Tags","장르")
  set("translator","translator","translators","Person Translator","번역자")
  set("series_name","series_name","seriesName","series","시리즈명")
  set("series_index","series_index","seriesIndex","시리즈순번")
  set("series_total","series_total","seriesTotal","총권수")
  set("isbn","isbn","isbn13")
  set("pageCount","pageCount","page","pages")
  set("book_id","book_id","bookId","code")
  set("price","price","salePrice","가격")
  set("metadataUrl","Web Links","sourceUrl","link")
  set("publishedDate","publishedDate","pubDate","Release Date","출간일")
  if(result["publishedDate"].isNullOrBlank()) {
   val year=scalar(get(record,"Year"));val month=scalar(get(record,"Month"));val day=scalar(get(record,"Day"))
   if(year.isNotBlank())result["publishedDate"]=if(month.isNotBlank()&&day.isNotBlank())year+month.padStart(2,'0')+day.padStart(2,'0') else year
  }
  result["publishedDate"]?.takeIf{it.matches(Regex("\\d{8}"))}?.let{date->
   runCatching{java.time.LocalDate.parse(date,java.time.format.DateTimeFormatter.BASIC_ISO_DATE).toString()}
    .getOrNull()?.let{result["publishedDate"]=it}
  }
  val cover=rawCover(record)
  if(isEmbedded(cover)) {
   result.keys.filter{fieldKey(it) in setOf("cover","coverimage","coverurl","image","표지","posterurl")}.toList().forEach{result.remove(it)}
  }else scalar(cover).takeIf{it.isNotBlank()}?.let{result["cover"]=it}
  return result
 }
}
