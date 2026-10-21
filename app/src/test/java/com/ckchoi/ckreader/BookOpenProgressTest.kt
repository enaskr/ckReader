package com.ckchoi.ckreader

import org.junit.Assert.*
import org.junit.Test

class BookOpenProgressTest {
 @Test fun showsAccuratePercentageWhenTotalIsKnown() {
  val value=BookOpenProgress.label("Google Drive 파일 저장 중",
   211L*1024*1024,422L*1024*1024)
  assertTrue(value.contains("50%"))
  assertTrue(value.contains("211.0 MB / 422.0 MB"))
 }
 @Test fun neverClaimsPercentIfServerDoesNotKnowTotal() {
  val value=BookOpenProgress.label("북오아시스 ZIP 다운로드 중",
   33L*1024*1024,-1)
  assertTrue(value.contains("33.0 MB"))
  assertFalse(value.contains("%"))
 }
 @Test fun stageAndUnknownSizeDoNotFakeTransferProgress() {
  assertEquals("ZIP 파일 인덱스 분석 중",
   BookOpenProgress.label("ZIP 파일 인덱스 분석 중"))
  assertTrue(BookOpenProgress.label("파일 다운로드",0,400).contains("0%"))
 }
}
