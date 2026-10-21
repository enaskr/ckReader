package com.ckchoi.ckreader

import java.text.Normalizer
import java.util.Locale

/** GDS book/series leaves; provider/category parents and original filenames stay intact. */
internal object BookOrganizationRules {
 data class Book(val path:String,val title:String,val author:String,val series:String="")
 data class Group(val folder:String,val books:List<Book>)
 private val volume=Regex("(?:\\s*[-_·]?\\s*)(?:제\\s*)?\\d{1,4}\\s*(?:권|화|vol\\.?)(?:\\s*완결)?$",RegexOption.IGNORE_CASE)
 fun primaryAuthor(author:String)=author.split(Regex("[,;·&]")).firstOrNull().orEmpty().trim()
 fun safeName(value:String):String {
  var name=Normalizer.normalize(value,Normalizer.Form.NFC).replace(Regex("[\\p{Cntrl}\\\\/:*?\"<>|]")," ")
   .replace(Regex("\\s+")," ").trim().trimEnd('.').trim()
  if(name.startsWith('.'))name="_"+name.trimStart('.')
  if(name.isBlank())return ""
  if(Regex("(?i)^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?$").matches(name))name="_"+name
  while(name.toByteArray(Charsets.UTF_8).size>220)name=name.substring(0,name.offsetByCodePoints(name.length,-1))
  return name.trim().trimEnd('.')
 }
 // Folder identity must retain every script and meaningful punctuation. Search
 // normalization drops characters and can otherwise merge unrelated books.
 private fun normalized(text:String)=Normalizer.normalize(text,Normalizer.Form.NFKC)
  .lowercase(Locale.ROOT).replace(Regex("\\s+")," ").trim()
 fun identity(book:Book):String {
  val title=book.series.ifBlank{volume.replace(book.title,"").trim().ifBlank{book.title}}
  val key=normalized(title)
  return key.length.toString()+":"+key+normalized(primaryAuthor(book.author))
 }
 /**
  * One book stays in the current directory. With multiple books, each book
  * receives its own safe, collision-free subfolder (title_author, or file
  * stem if title is unknown). Never merge different originals into one leaf.
  */
 fun plan(folder:String,books:List<Book>,siblingBookCount:Int):List<Group> {
  val candidates=books.filter{LibraryVisibility.visible(it.path)}
   .distinctBy{it.path}
  if(candidates.isEmpty())return emptyList()
  if(siblingBookCount==1)return candidates.map{Group(folder,listOf(it))}
  val used=HashSet<String>()
  return candidates.mapNotNull{book->
   val original=book.path.substringAfterLast('/')
   val title=safeName(book.title.ifBlank{original.substringBeforeLast('.',original)})
   val author=safeName(primaryAuthor(book.author))
   val preferred=safeName(title+if(author.isNotBlank())"_$author" else "")
    .ifBlank{safeName(original)}
   if(preferred.isBlank())return@mapNotNull null
   var selected=preferred
   var index=0
   while(!used.add(normalized(selected))){
    index++
    val suffix=safeName(original.substringBeforeLast('.',original)).take(65)
     .ifBlank{"파일"}
    selected=safeName("$preferred - $suffix"+if(index>1)" ($index)" else "")
    if(index>9999)return@mapNotNull null
   }
   Group(FolderYamlStorage.child(folder,selected),listOf(book))
  }
 }
}
