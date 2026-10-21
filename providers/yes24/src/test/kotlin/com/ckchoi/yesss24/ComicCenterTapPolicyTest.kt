package com.ckchoi.yesss24

import org.junit.Assert.*
import org.junit.Test

class ComicCenterTapPolicyTest {
 @Test fun acceptsOnlyMiddleRegion() {
  assertTrue(ComicCenterTapPolicy.isCenter(500f,500f,1000,1000))
  assertTrue(ComicCenterTapPolicy.isCenter(250f,750f,1000,1000))
  assertFalse(ComicCenterTapPolicy.isCenter(199f,500f,1000,1000))
  assertFalse(ComicCenterTapPolicy.isCenter(801f,500f,1000,1000))
  assertFalse(ComicCenterTapPolicy.isCenter(500f,199f,1000,1000))
  assertFalse(ComicCenterTapPolicy.isCenter(500f,801f,1000,1000))
  assertFalse(ComicCenterTapPolicy.isCenter(500f,500f,0,1000))
  assertFalse(ComicCenterTapPolicy.isCenter(500f,500f,1000,0))
 }
 @Test fun popupContainsOnlyRequestedActionText() {
  assertEquals("전체화면 보기",ComicCenterTapPolicy.actionLabel(false))
  assertEquals("전체화면 종료",ComicCenterTapPolicy.actionLabel(true))
 }
}
