package com.ckchoi.ckreader

import org.junit.Assert.*
import org.junit.Test

class LibraryRefreshPolicyTest {
 @Test fun currentFolderAndDescendantsAreTheSelectionRoot() {
  assertEquals("책/일반/다/달러구트 꿈 백화점 [이미예]",
   LibraryRefreshPolicy.depth("drive","책/일반/다/달러구트 꿈 백화점 [이미예]"))
  assertEquals("책/일반/사/삼국지 [요시카와 에이지]",
   LibraryRefreshPolicy.depth("drive","책/일반/사/삼국지 [요시카와 에이지]"))
 }
 @Test fun currentRootMapsToWholeSource() {
  assertEquals("__root__",LibraryRefreshPolicy.depth("drive",""))
  assertEquals("__root__",LibraryRefreshPolicy.depth("webdav",""))
  assertEquals("__root__",LibraryRefreshPolicy.depth("local",""))
  assertEquals("__root__",LibraryRefreshPolicy.depth("local","서브폴더"))
 }
 @Test fun confirmationIsExplicit() {
  assertEquals("진행중인 캐시 생성 작업을 중단하고, 현재 폴더의 캐시생성 작업을 진행하시겠습니까?",
   LibraryRefreshPolicy.MESSAGE)
 }
}
