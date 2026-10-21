package com.ckchoi.ckreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CachePublicFolderNamesTest {
 @Test fun keepsStorageNameExceptForbiddenFolderCharacters() {
  assertEquals("내 드라이브 AI · 책",CachePublicFolderNames.clean("내 드라이브: AI · 책?/"))
  assertEquals("Google Drive (내 드라이브)",CachePublicFolderNames.clean("Google Drive (내 드라이브)"))
  assertEquals("_CON",CachePublicFolderNames.clean("CON."))
 }
 @Test fun onlyAddsSuffixForCollidingCleanNames() {
  val sources=listOf("one" to "A/B","two" to "AB","three" to "내 드라이브")
  assertNotEquals(CachePublicFolderNames.forSource("one","A/B",sources),
   CachePublicFolderNames.forSource("two","AB",sources))
  assertEquals("내 드라이브",CachePublicFolderNames.forSource("three","내 드라이브",sources))
 }
}
