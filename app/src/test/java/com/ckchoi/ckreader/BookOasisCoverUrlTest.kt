package com.ckchoi.ckreader

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BookOasisCoverUrlTest {
 private val base="https://books.example/".toHttpUrl()
 @Test fun catalogFilenameUsesTheCoverRouteAndKeepsItsTimestamp() {
  val reference="2/book_7affa84999f2faff9214a76fcd4628e9.webp?t=1788152622"
  assertEquals("https://books.example/covers/$reference",BookOasisCoverUrl.resolve(base,reference).toString())
 }
 @Test fun alreadyPrefixedAndAbsoluteReferencesDoNotAddCoversTwice() {
  for(reference in listOf("covers/2/book.webp?t=1","/covers/2/book.webp?t=1","https://books.example/covers/2/book.webp?t=1"))
   assertEquals("https://books.example/covers/2/book.webp?t=1",BookOasisCoverUrl.resolve(base,reference).toString())
 }
 @Test fun oldFilenameAndLeadingSlashCatalogReferencesAreSupported() {
  assertEquals("https://books.example/covers/cover.jpg",BookOasisCoverUrl.resolve(base,"cover.jpg").toString())
  assertEquals("https://books.example/covers/2/book.webp",BookOasisCoverUrl.resolve(base,"/2/book.webp").toString())
 }
 @Test fun apiAndStaticImageEndpointsStayIntact() {
  for(reference in listOf("/api/media/cover/12?type=general","api/media/cover/12?type=general","/static/cover.jpg"))
   assertEquals(base.resolve(reference),BookOasisCoverUrl.resolve(base,reference))
 }
 @Test fun mountedServerPathAndEncodedFilenamesArePreserved() {
  val mounted="https://books.example/bookoasis/".toHttpUrl()
  assertEquals("https://books.example/bookoasis/covers/2/%ED%95%9C%EA%B8%80%20A+B.webp?t=1",
   BookOasisCoverUrl.resolve(mounted,"2/%ED%95%9C%EA%B8%80%20A+B.webp?t=1").toString())
 }
 @Test fun externalAndInvalidReferencesAreRejected() {
  for(reference in listOf("", "//other.example/image.jpg", "https://other.example/image.jpg", "http://books.example/image.jpg",
   "https://books.example:444/image.jpg", "../private.jpg", "2\\image.jpg", "file:cover.jpg"))
   assertNull(reference,BookOasisCoverUrl.resolve(base,reference))
 }
}
