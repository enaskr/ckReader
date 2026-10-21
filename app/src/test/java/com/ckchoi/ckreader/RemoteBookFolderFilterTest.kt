package com.ckchoi.ckreader

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class RemoteBookFolderFilterTest {
 private fun entry(parent:String,name:String,folder:Boolean):FolderYamlStorage.Entry =
  FolderYamlStorage.Entry(name,FolderYamlStorage.child(parent,name),folder)

 private val isBook:(String)->Boolean = { name ->
  listOf(".epub",".pdf",".txt",".md",".zip",".cbz").any{name.endsWith(it,true)}
 }
 @Test fun removesEmptyAndSidecarOnlyFoldersButKeepsNestedBooks() {
  val folders=mapOf(
   "빈폴더" to emptyList(),
   "메타데이터" to listOf(entry("메타데이터","kavita.yaml",false),entry("메타데이터","cover.jpg",false)),
   "상위" to listOf(entry("상위","하위",true)),
   "상위/하위" to listOf(entry("상위/하위","1권.CBZ",false)),
   "소설" to listOf(entry("소설","도서.epub",false)),
   "빈트리" to listOf(entry("빈트리","빈하위",true)),
   "빈트리/빈하위" to emptyList(),
  )
  val input=listOf("빈폴더","메타데이터","상위","소설","빈트리")
   .map{entry("",it,true)}
  val requested=mutableListOf<String>()
  val progress=mutableListOf<Pair<Int,Int>>()
  val filter=RemoteBookFolderFilter()
  val filtered=filter.filter("drive-root","",input,
   list={path->requested.add(path);folders[path]?:error("Unexpected: "+path)},
   isBook=isBook,onProgress={done,total,_->progress.add(done to total)})
  assertEquals(listOf("상위","소설"),filtered)
  assertEquals(listOf("빈폴더","메타데이터","상위","상위/하위","소설","빈트리","빈트리/빈하위"),requested)
  assertEquals(5 to 5,progress.last())
 }
 @Test fun nestedPathsAndHiddenDotFoldersAreHandled() {
  val parent="기존 폴더"
  val items=listOf(entry(parent,"1.책",true),entry(parent,".숨김",true),entry(parent,"빈 폴더",true))
  val files=mapOf(
   "기존 폴더/1.책" to listOf(entry("기존 폴더/1.책","책.PDF",false)),
   "기존 폴더/빈 폴더" to emptyList())
  val results=RemoteBookFolderFilter().filter("webdav",parent,items,
   list={path->files[path]?:error("Unexpected: "+path)},isBook=isBook)
  assertEquals(listOf("1.책"),results)
 }
 @Test fun cachesVerifiedSubtreesForNavigationAndExpiresEmptyFolderResults() {
  var time=10_000L
  var calls=0
  var books=false
  val contents: (String)->List<FolderYamlStorage.Entry> = {path->
   calls++
   if(books)listOf(entry(path,"새책.zip",false)) else emptyList()
  }
  val filter=RemoteBookFolderFilter(clock={time})
  val items=listOf(entry("","A",true))
  assertTrue(filter.filter("source","",items,contents,isBook).isEmpty())
  assertTrue(filter.filter("source","",items,contents,isBook).isEmpty())
  assertEquals(1,calls)
  time+=26_000L;books=true
  assertEquals(listOf("A"),filter.filter("source","",items,contents,isBook))
  assertEquals(2,calls)
  filter.clearSource("source")
  assertEquals(listOf("A"),filter.filter("source","",items,contents,isBook))
  assertEquals(3,calls)
 }
 @Test fun errorsAndTraversalLimitsDoNotHidePotentialBooks() {
  val entries=listOf(entry("","권한없음",true),entry("","너무깊음",true))
  val contents=mapOf(
   "너무깊음" to listOf(entry("너무깊음","깊은곳",true)),
   "너무깊음/깊은곳" to emptyList())
  val filter=RemoteBookFolderFilter(maxDepth=1)
  assertEquals(listOf("권한없음","너무깊음"),filter.filter("source","",entries,
   list={path->contents[path]?:throw IOException("Access denied")},isBook=isBook))
 }
 @Test fun separateSourceKeysCannotReuseOldDirectoryVerdicts() {
  val items=listOf(entry("","A",true))
  var call=0
  val filter=RemoteBookFolderFilter()
  val contents: (String)->List<FolderYamlStorage.Entry> = {path->
   call++
   if(call==1)emptyList() else listOf(entry(path,"book.cbz",false))
  }
  assertTrue(filter.filter("drive-account-1","",items,contents,isBook).isEmpty())
  assertEquals(listOf("A"),filter.filter("drive-account-2","",items,contents,isBook))
  assertEquals(2,call)
 }
}
