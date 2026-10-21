package com.ckchoi.ckreader

import org.junit.Assert.*
import org.junit.Test

class CatalogCoverKeyTest {
 @Test fun sameBookIdentityKeepsKeyAfterMetadataAndUrlChanges() {
  val source=LibrarySource("storage-id","bookoasis","Book Oasis","https://example.org")
  val book=LibraryBook("1095-id",source,"bookoasis:1095.epub","1095.epub")
  val key=CatalogCoverKey.book(source.id,book.id,book.path)
  book.webCover="https://aladinapi.ibmer.app/covers/1095"
  book.title="새 도서 제목"
  book.author="작가명"
  book.publisher="출판사"
  assertEquals(key,CatalogCoverKey.book(source.id,book.id,book.path))
 }
 @Test fun folderThumbnailCannotConflictWithBookThumbnail() {
  assertNotEquals(CatalogCoverKey.book("source","id","series"),
   CatalogCoverKey.folder("source","series"))
  assertNotEquals(CatalogCoverKey.folder("source-A","series"),
   CatalogCoverKey.folder("source-B","series"))
 }
}
