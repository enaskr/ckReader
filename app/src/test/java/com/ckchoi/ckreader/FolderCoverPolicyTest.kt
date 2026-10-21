package com.ckchoi.ckreader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderCoverPolicyTest {
 private val source=LibrarySource("source-test","drive","Test","")
 private fun book(name:String,title:String=""):LibraryBook=
  LibraryBook(name,source,name,name).apply{this.title=title}

 @Test fun leafFolderUsesFirstBookInDisplayedTitleOrder() {
  val listing=LibraryListing(emptyList(),listOf(
   book("z.epub","다람쥐"),book("a.epub","나무"),book("k.epub","가을")
  ))
  for(storage in listOf("drive","ftp","webdav")) {
   assertEquals("가을",FolderCoverPolicy.firstBook(storage,listing)?.title)
  }
 }

 @Test fun intermediateFolderNeverUsesBookThumbnail() {
  val listing=LibraryListing(listOf("nested"),listOf(book("first.epub","첫 권")))
  for(storage in listOf("drive","ftp","webdav")) {
   assertNull(FolderCoverPolicy.firstBook(storage,listing))
  }
 }

 @Test fun emptyAndLocalFoldersKeepDefaultIcon() {
  assertNull(FolderCoverPolicy.firstBook("drive",LibraryListing(emptyList(),emptyList())))
  assertNull(FolderCoverPolicy.firstBook("local",
   LibraryListing(emptyList(),listOf(book("book.epub")))))
  assertNull(FolderCoverPolicy.firstBook("bookoasis",
   LibraryListing(emptyList(),listOf(book("book.epub")))))
 }

 @Test fun usesExistingCoverFileWithMatchingStemOrStandardName() {
  val files=listOf("book1.epub","book1.PNG","other.yaml","cover.jpg","folder.png")
  assertEquals("book1.PNG",FolderCoverPolicy.companionCover("book1.epub",files))
  assertEquals("cover.jpg",FolderCoverPolicy.companionCover("mystery.epub",files))
  assertNull(FolderCoverPolicy.companionCover("mystery.epub",listOf("other.png")))
 }

 @Test fun oldNegativeThumbnailDoesNotHideBookOasisCachedCover() {
  assertTrue(FolderCoverPolicy.retryWhenNegativeCached("bookoasis"))
  for(type in listOf("drive","ftp","webdav"))
   assertFalse(FolderCoverPolicy.retryWhenNegativeCached(type))
 }
 @Test fun kavitaRepresentativeSkipsUnlistedFilesAndKeepsDisplayedTitleOrder() {
  val plain=book("0.epub","가장 먼저인 미등록 파일")
  val later=book("b.epub","나중 권").apply{yamlPreferred=true;metadataSource="WebDAV · kavita.yaml"}
  val first=book("a.epub","가장 앞 권").apply{yamlPreferred=true;metadataSource="FTP · KAVITA.YML"}
  val sidecar=book("c.epub","다른 YAML").apply{yamlPreferred=true;metadataSource="Google Drive · c.yaml"}
  for(type in listOf("local","drive","webdav","ftp"))
   assertEquals(listOf(first,later),FolderCoverPolicy.yamlBooks(type,listOf(plain,later,sidecar,first)))
  assertTrue(FolderCoverPolicy.yamlBooks("bookoasis",listOf(first)).isEmpty())
 }

 @Test fun seriesFolderCatalogRetainsAuthorPublisherAfterCacheRoundTrip() {
  val path=BookOasisPaths.series("10","하루 30분 요리", "1095")
  val data=FolderCatalogInfo("홍신애","푸른숲",3,"도서 설명")
  val listing=LibraryListing(listOf(path),emptyList(),
   folderInfos=mapOf(path to data))
  assertEquals(data,listing.folderInfos[path])
  assertEquals(data,FolderCatalogInfo.fromJson(data.toJson()))
 }

 @Test fun bookOasisRepresentativesKeepSeparateCoversAndSortedRoutes() {
  val a=BookOasisPaths.series("7","같은 이름","1001")
  val b=BookOasisPaths.series("7","같은 이름","2002")
  val covers=mapOf(a to "/covers/first.jpg?t=1",b to "/covers/second.jpg?t=2")
  val listing=LibraryListing(listOf(b,a),emptyList(),folderCovers=covers)
  val sorted=LibraryTitleOrder.listing(listing,"bookoasis")
  assertEquals(2,sorted.folders.size)
  assertEquals(covers,sorted.folderCovers)
  assertTrue(sorted.folderCovers[a]!!.startsWith("/covers/"))
 }
}
