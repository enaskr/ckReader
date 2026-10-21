package com.ckchoi.ckreader

import org.junit.Assert.*
import org.junit.Test

class WebDavListingTest {
 private fun response(href:String,prop:String="",status:String="HTTP/1.1 200 OK")=
  "<d:response><d:href>$href</d:href><d:propstat><d:prop>$prop</d:prop><d:status>$status</d:status></d:propstat></d:response>"
 private fun xml(vararg rows:String)="<?xml version='1.0' encoding='UTF-8'?><d:multistatus xmlns:d='DAV:'>${rows.joinToString("")}</d:multistatus>"
 private val endpoint="https://example.org/dav/books/"

 @Test fun excludesSelfAndOtherDepthsButKeepsKoreanSpacesAndPlus() {
  val entries=WebDavListing.parse(xml(
   response("/dav/books/","<d:resourcetype><d:collection/></d:resourcetype>"),
   response("/dav/books/한글 책+1.epub","<d:getcontentlength>42</d:getcontentlength>"),
   response("/dav/books/series/","<d:resourcetype><d:collection/></d:resourcetype>"),
   response("/dav/books/series/nested.epub"),response("/unrelated/a.epub")
  ),endpoint)
  assertEquals(listOf("한글 책+1.epub","series"),entries.map{it.name})
  assertEquals(42L,entries[0].sizeBytes)
  assertTrue(entries[1].directory)
 }
 @Test fun decodesEncodedSegmentsExactlyOnce() {
  val entries=WebDavListing.parse(xml(response("%ED%95%9C%EA%B8%80%20%2B%20%252E.epub")),endpoint)
  assertEquals("한글 + %2E.epub",entries.single().name)
 }
 @Test fun supportsAbsoluteHrefsAndEffectiveRedirectedEndpoint() {
  val entries=WebDavListing.parse(xml(response("https://example.org/new/base/book.pdf")),"https://example.org/new/base/")
  assertEquals("book.pdf",entries.single().name)
 }
 @Test fun ignoresFailedPropertiesAndFailedResponses() {
  val entries=WebDavListing.parse(xml(
   response("missing.epub","<d:getcontentlength>123</d:getcontentlength>","HTTP/1.1 404 Not Found"),
   "<d:response><d:href>gone.epub</d:href><d:status>HTTP/1.1 404 Not Found</d:status></d:response>",
   "<d:response><d:href>file.epub</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 404 Not Found</d:status></d:propstat><d:propstat><d:prop><d:getcontentlength>7</d:getcontentlength></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"
  ),endpoint)
  assertEquals(1,entries.size)
  assertFalse(entries.single().directory)
  assertEquals(7L,entries.single().sizeBytes)
 }
 @Test fun rejectsEncodedPathSeparators() {
  assertTrue(WebDavListing.parse(xml(response("bad%2Ffile.epub"),response("bad%5Cfile.epub")),endpoint).isEmpty())
 }
 @Test fun worksWithDefaultDavNamespace() {
  val text="<multistatus xmlns='DAV:'><response><href>/dav/books/test.epub</href><propstat><prop/><status>HTTP/1.1 200 OK</status></propstat></response></multistatus>"
  assertEquals("test.epub",WebDavListing.parse(text,endpoint).single().name)
 }
 @Test fun rejectsDtdAndExternalEntitiesBeforeParsing() {
  for(declaration in listOf("<!DOCTYPE d:multistatus SYSTEM 'file:///private/file'>","<!ENTITY e SYSTEM 'https://example.org/private'>","<!DOCTYPE d:multistatus [<!ENTITY e 'expanded'>]>"))
   assertTrue(runCatching{WebDavListing.parse(declaration+xml(),endpoint)}.isFailure)
 }
 @Test fun rejectsHtmlLoginPages() {
  assertTrue(runCatching{WebDavListing.parse("<html><body>Login</body></html>",endpoint)}.isFailure)
 }
 @Test fun constructsEncodedUrlsWithoutLosingBaseOrQuery() {
  assertEquals("https://example.org/dav/books/%ED%95%9C%EA%B8%80%20%2B%20%231%25.epub?token=value",
   WebDavListing.url("https://example.org/dav/?token=value","books/한글 + #1%.epub"))
  assertEquals("https://example.org/dav/",WebDavListing.url("https://example.org/dav/","",true))
  assertEquals("https://example.org/dav/series/",WebDavListing.url("https://example.org/dav/","series",true))
  assertEquals("https://example.org/a.txt",WebDavListing.url("https://example.org/","a.txt"))
  assertEquals("https://example.org/Book%20%5BAuthor%5D/a.epub",WebDavListing.url("https://example.org/","Book [Author]/a.epub"))
 }
}
