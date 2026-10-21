package com.ckchoi.ckreader

import java.util.concurrent.ConcurrentHashMap

/**
 * Hides a remote folder only after verifying it has no supported book in its
 * entire visible subtree. Folders that only contain metadata/cover sidecars
 * do not appear as empty "bookshelves".
 *
 * Connection failure or a traversal limit is *not* evidence of emptiness:
 * keep the folder visible so inaccessible books are never silently lost.
 * The work runs on the repository's existing background scan executor.
 */
internal class RemoteBookFolderFilter(
 private val maxReadsPerListing:Int=1500,
 private val maxDepth:Int=80,
 private val clock:()->Long=System::currentTimeMillis,
) {
 private data class Verdict(val containsBook:Boolean,val until:Long)
 private val verified=ConcurrentHashMap<String,Verdict>()
 private val positiveTtl=3L*60*1000
 private val negativeTtl=25L*1000

 fun clearSource(sourceId:String) {
  verified.keys.removeIf {it.startsWith(sourceId+"|")}
 }

 /** Results are relative child names, not full paths. */
 fun filter(
  key:String,
  parent:String,
  entries:List<FolderYamlStorage.Entry>,
  list:(String)->List<FolderYamlStorage.Entry>,
  isBook:(String)->Boolean,
  onProgress:(Int,Int,Int)->Unit={_,_,_->},
 ):List<String> {
  val directories=entries.filter{it.directory && LibraryVisibility.visible(it.name)}
  val sourceKey=key+"|"
  val visited=HashSet<String>()
  var reads=0
  fun cached(path:String):Boolean? {
   val item=verified[sourceKey+path]?:return null
   if(item.until<=clock()) {
    verified.remove(sourceKey+path,item)
    return null
   }
   return item.containsBook
  }
  fun store(path:String,value:Boolean) {
   if(verified.size>20_000)verified.clear()
   verified[sourceKey+path]=Verdict(value,clock()+(if(value)positiveTtl else negativeTtl))
  }
  fun containsBook(path:String,depth:Int):Boolean {
   if(Thread.currentThread().isInterrupted)
    throw InterruptedException("빈 폴더 확인 취소")
   if(!LibraryVisibility.visible(path))return false
   cached(path)?.let{return it}
   if(depth>maxDepth || reads>=maxReadsPerListing)return true
   if(!visited.add(path))return true
   reads++
   val children=try {list(path)} catch(error:Exception) {
    if(error is InterruptedException)throw error
    if(Thread.currentThread().isInterrupted)throw InterruptedException("폴더 확인 중단")
    // Permission/timeouts cannot safely imply the directory is empty.
    return true
   }
   if(children.any{!it.directory && LibraryVisibility.visible(it.name) && isBook(it.name)}){
    store(path,true)
    return true
   }
   for(child in children){
    if(!child.directory || !LibraryVisibility.visible(child.name))continue
    val descendant=FolderYamlStorage.child(path,child.name)
    if(containsBook(descendant,depth+1)){
     store(path,true)
     return true
    }
   }
   store(path,false)
   return false
  }
  val keep=ArrayList<String>(directories.size)
  directories.forEachIndexed{index,item->
   if(Thread.currentThread().isInterrupted)throw InterruptedException("폴더 검사 중단")
   if(containsBook(FolderYamlStorage.child(parent,item.name),1))keep.add(item.name)
   onProgress(index+1,directories.size,reads)
  }
  return keep
 }
}
