package com.ckchoi.ckreader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookCoverPolicyTest {
 @Test fun embeddedCoversAreSupportedOnlyForPageAndArchiveDocuments() {
  for(format in listOf("epub","EPUB","pdf","PDF","cbz","ZIP"))
   assertTrue(BookCoverPolicy.supported(format))
  for(format in listOf("txt","md","html","docx",""))
   assertFalse(BookCoverPolicy.supported(format))
 }

 @Test fun anExplicitRefreshDoesNotNeedAnExistingCoverReference() {
  val source=LibrarySource("account","local","Test","content://example")
  val book=LibraryBook("id",source,"folder/title.epub","title.epub")
  assertTrue(BookCoverPolicy.supported(book.format))
  assertTrue(book.cover.isBlank())
  assertTrue(book.webCover.isBlank())
 }
}
