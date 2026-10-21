package com.ckchoi.ckreader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookMetadataCoverPolicyTest {
 private val source=LibrarySource("source-1","bookoasis","Book Oasis","https://example.org")
 private fun book():LibraryBook=LibraryBook("stable-id",source,"bookoasis:1095.epub","1095.epub")

 @Test fun uncoveredBooksMayBeAutomaticallyEnriched() {
  assertTrue(BookMetadataCoverPolicy.shouldAutoSearch(book(),false))
 }
 @Test fun suppliedSourceCoverPreservesOtherMetadata() {
  val b=book().apply{cover="https://example.org/cover/1095"}
  assertFalse(BookMetadataCoverPolicy.shouldAutoSearch(b,false))
 }
 @Test fun existingWebCoverPreservesOtherMetadata() {
  val b=book().apply{webCover="https://aladinapi.ibmer.app/covers/1095"}
  assertFalse(BookMetadataCoverPolicy.shouldAutoSearch(b,false))
 }
 @Test fun sqliteBlobCoverSuppressesAutomaticLookupEvenWithoutCoverUrl() {
  assertFalse(BookMetadataCoverPolicy.shouldAutoSearch(book(),true))
 }
}
