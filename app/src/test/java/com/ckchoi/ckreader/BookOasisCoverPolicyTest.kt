package com.ckchoi.ckreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BookOasisCoverPolicyTest {
 private val source=LibrarySource("fixture","bookoasis","Test","")
 private fun book(id:String)=LibraryBook(id,source,"bookoasis:$id.pdf","$id.pdf",bookId=id)
 @Test fun representativeIdWinsOverVolumeSortOrder() {
  val first=book("1517");val representative=book("1516")
  val path=BookOasisPaths.library("2","15.건강 취미 여행")+"/"+BookOasisPaths.series("2","5초 복근","1516")
  assertEquals(representative,BookOasisCoverPolicy.representative(path,listOf(first,representative)))
 }
 @Test fun missingRepresentativeDoesNotUseAnotherVolumesCover() {
  assertNull(BookOasisCoverPolicy.representative(BookOasisPaths.series("2","같은 제목","1516"),listOf(book("1517"))))
 }
 @Test fun legacyRoutesUseTheFirstVolumeAndDirectSearchRoutesKeepTheRepresentative() {
  val books=listOf(book("1"),book("2"))
  assertEquals(books[0],BookOasisCoverPolicy.representative(BookOasisPaths.series("2","제목"),books))
  assertEquals(books[1],BookOasisCoverPolicy.representative(BookOasisPaths.series("2","제목","2"),books))
 }
 @Test fun categoriesAndInvalidPathsHaveNoBookRepresentative() {
  for(path in listOf("",BookOasisPaths.library("2","카테고리"),"../invalid"))
   assertNull(BookOasisCoverPolicy.representative(path,listOf(book("1"))))
 }
}
