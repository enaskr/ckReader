package com.ckchoi.yesss24

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpubPathsTest {
 @Test fun manifestReferencesDecodeKoreanAndSpaces() {
  assertEquals("OEBPS/Text/가상 현실의 음악 천재 1화.xhtml",EpubPaths.resolve("OEBPS/content.opf",
   "Text/%EA%B0%80%EC%83%81%20%ED%98%84%EC%8B%A4%EC%9D%98%20%EC%9D%8C%EC%95%85%20%EC%B2%9C%EC%9E%AC%201%ED%99%94.xhtml"))
  assertEquals("OEBPS/Text/한글 253화.xhtml",EpubPaths.resolve("OEBPS/content.opf","Text/한글 253화.xhtml"))
 }
 @Test fun containerAndTocReferencesUseTheSameResolution() {
  assertEquals("한글 폴더/content.opf",EpubPaths.resolve("","%ED%95%9C%EA%B8%80%20%ED%8F%B4%EB%8D%94/content.opf"))
  assertEquals("OEBPS/Text/1화.xhtml",EpubPaths.resolve("OEBPS/nav/toc.ncx","../Text/1%ED%99%94.xhtml#start"))
  assertEquals("chapter.xhtml",EpubPaths.resolve("toc.ncx","chapter.xhtml?mode=read#start"))
 }
 @Test fun pathCharactersAreNotFormDecodedOrDecodedTwice() {
  assertEquals("OEBPS/Text/A+B C.xhtml",EpubPaths.resolve("OEBPS/content.opf","Text/A+B%20C.xhtml"))
  assertEquals("OEBPS/Text/A+B.xhtml",EpubPaths.resolve("OEBPS/content.opf","Text/A%2BB.xhtml"))
  assertEquals("OEBPS/Text/100%20.xhtml",EpubPaths.resolve("OEBPS/content.opf","Text/100%2520.xhtml"))
  assertEquals("OEBPS/Text/a#b?c.xhtml",EpubPaths.resolve("OEBPS/content.opf","Text/a%23b%3Fc.xhtml?query#fragment"))
 }
 @Test fun invalidOrExternalReferencesDoNotEscapeTheArchive() {
  for(href in listOf("", "#only-fragment", "../../outside.xhtml", "%2e%2e/%2e%2e/outside.xhtml",
   "/absolute.xhtml", "https://example.com/book.xhtml", "//example.com/book.xhtml", "file:chapter.xhtml",
   "Text%5Cchapter.xhtml", "Text/%00.xhtml", "Text/%broken.xhtml")) {
   assertNull(href,EpubPaths.resolve("OEBPS/content.opf",href))
  }
 }
}
