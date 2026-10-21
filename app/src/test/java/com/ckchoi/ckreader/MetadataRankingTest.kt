package com.ckchoi.ckreader

import org.junit.Assert.*
import org.junit.Test

class MetadataRankingTest {
 @Test fun exactTitleAndAuthorRankAheadOfUnrelatedBooks(){
  val precise=WebBookMetadata("클린 코드","로버트 마틴",coverUrl="https://books.google.com/cover")
  val near=WebBookMetadata("클린 코드로 배우는 Java","다른 작가")
  val far=WebBookMetadata("소프트웨어 설계","제3자")
  val ranked=MetadataTitleMatcher.rank("클린 코드","로버트 마틴","",
   listOf(far,near,precise))
  assertEquals(precise,ranked.first())
  assertTrue(MetadataTitleMatcher.score("클린 코드","로버트 마틴","",precise)>=90)
 }
 @Test fun matchingIsbnOutranksTitleSimilarity(){
  val matches=WebBookMetadata("리팩터링","마틴 파울러",isbn="9788966269723")
  assertEquals(100,MetadataTitleMatcher.score("별도 제목","","9788966269723",matches))
 }
 @Test fun differingAuthorsAvoidAutoAssignment(){
  val wrong=WebBookMetadata("어린 왕자","김철수",coverUrl="https://example.com/cover")
  assertTrue(MetadataTitleMatcher.score("어린 왕자","생텍쥐페리","",wrong)<72)
 }
 @Test fun duplicateResultsAreRemoved(){
  val item=WebBookMetadata("오래된 책","아무개",source="도서DB")
  val results=MetadataTitleMatcher.rank("오래된 책","","",listOf(item,item,item))
  assertEquals(1,results.size)
 }
}
