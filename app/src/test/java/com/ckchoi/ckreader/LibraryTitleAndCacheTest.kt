package com.ckchoi.ckreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryTitleAndCacheTest {
 private val source=LibrarySource("source-1","drive","GDS","")
 private fun book(file:String,title:String="")=LibraryBook(file,source,file,file).apply {
  this.title=title
 }
 private fun listing(vararg names:String)=LibraryListing(emptyList(),names.map{book(it)})

 @Test fun koreanTitlesAreSortedAlphabetically() {
  val books=listOf(book("x.epub","다섯"),book("a.epub","가을"),
   book("m.epub","나무"))
  assertEquals(listOf("가을","나무","다섯"),LibraryTitleOrder.books(books).map{it.title})
 }

 @Test fun usesYamlOrServerTitleInsteadOfFilename() {
  val books=listOf(book("01.epub","호수"),book("99.epub","강"),
   book("55.epub","나무"))
  assertEquals(listOf("강","나무","호수"),LibraryTitleOrder.books(books).map{it.title})
 }

 @Test fun fallbackUsesFilenameWithoutExtensionAndNaturalNumbers() {
  val books=listOf(book("책 10권.pdf"),book("책 2권.epub"),book("책 1권.md"))
  assertEquals(listOf("책 1권.md","책 2권.epub","책 10권.pdf"),
   LibraryTitleOrder.books(books).map{it.filename})
 }

 @Test fun bookOasisSeriesSortByDecodedDisplayNameAndPreserveHomeFirst() {
  val home=BookOasisPaths.library("home","전체 보기")
  val z=BookOasisPaths.series("1","다람쥐", "5")
  val a=BookOasisPaths.series("1","가을", "7")
  val b=BookOasisPaths.series("1","나무", "2")
  val sorted=LibraryTitleOrder.folders(listOf(z,b,home,a),"bookoasis"=="bookoasis")
  assertEquals(listOf(home,a,b,z),sorted)
 }

 @Test fun existingCachedAndFreshListingsUseIdenticalOrder() {
  val items=listOf(book("05.pdf","다섯"),book("99.pdf","가을"),book("02.pdf","나무"))
  val unsorted=LibraryListing(listOf("폴더 10","폴더 2"),items)
  val initial=LibraryTitleOrder.listing(unsorted,"drive")
  val memory=LibraryListingMemoryCache()
  memory.put("source-1:fingerprint:root",initial)
  val recovered=LibraryTitleOrder.listing(memory.get("source-1:fingerprint:root")!!,"drive")
  assertEquals(initial.books.map{it.title},recovered.books.map{it.title})
  assertEquals(listOf("폴더 2","폴더 10"),recovered.folders)
 }

 @Test fun backNavigationReusesCurrentParentAndPreviouslyVisitedChild() {
  val memory=LibraryListingMemoryCache(maxBytes=100000,maxEntries=8)
  memory.put("source-1:settings:root",listing("parent.epub"))
  memory.put("source-1:settings:root/sub",listing("child.epub"))
  assertFalse(LibraryNavigationPolicy.needsFetch(memory.get("source-1:settings:root/sub")))
  assertFalse(LibraryNavigationPolicy.needsFetch(memory.get("source-1:settings:root")))
  assertEquals("parent.epub",memory.get("source-1:settings:root")!!.books.single().filename)
  assertTrue(LibraryNavigationPolicy.needsFetch(memory.get("source-1:settings:new")))
 }

 @Test fun memoryEvictionRetainsDiskFallbackAndInvalidatesChangedSource() {
  val memory=LibraryListingMemoryCache(maxBytes=100000,maxEntries=2)
  memory.put("source-1:v1:root",listing("a.epub"))
  memory.put("source-1:v1:sub",listing("b.epub"))
  memory.get("source-1:v1:root") // make parent MRU
  memory.put("source-1:v1:other",listing("c.epub"))
  assertNull(memory.get("source-1:v1:sub"))
  assertTrue(memory.get("source-1:v1:root")!=null)
  memory.put("source-2:v1:root",listing("external.epub"))
  memory.removeSource("source-1")
  assertNull(memory.get("source-1:v1:root"))
  assertTrue(memory.get("source-2:v1:root")!=null)
 }
}
