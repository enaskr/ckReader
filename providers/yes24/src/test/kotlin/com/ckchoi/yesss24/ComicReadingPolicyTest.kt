package com.ckchoi.yesss24

import org.junit.Assert.*
import org.junit.Test

class ComicReadingPolicyTest {
 private val horizontal=ComicReadingPolicy.Direction.HORIZONTAL
 private val vertical=ComicReadingPolicy.Direction.VERTICAL

 @Test fun spreadOnUnfoldedViewOnlyAndNeverForWebtoon() {
  assertTrue(ComicReadingPolicy.spread(horizontal,true,700,700))
  assertFalse(ComicReadingPolicy.spread(horizontal,false,700,700))
  assertFalse(ComicReadingPolicy.spread(horizontal,true,390,820))
  assertFalse(ComicReadingPolicy.spread(horizontal,true,820,390))
  assertFalse(ComicReadingPolicy.spread(vertical,true,700,700))
 }

 @Test fun foldSpreadShowsPairsAndAdvancesByTwoPages() {
  assertEquals(listOf(0,1),ComicReadingPolicy.visible(0,7,true))
  assertEquals(listOf(2,3),ComicReadingPolicy.visible(3,7,true))
  assertEquals(2,ComicReadingPolicy.move(0,1,7,true))
  assertEquals(4,ComicReadingPolicy.move(2,1,7,true))
  assertEquals(6,ComicReadingPolicy.move(4,1,7,true))
  assertEquals(6,ComicReadingPolicy.move(6,1,7,true))
  assertEquals(4,ComicReadingPolicy.move(6,-1,7,true))
  assertEquals("3–4 / 7",ComicReadingPolicy.positionLabel(3,7,true))
  assertEquals("7 / 7",ComicReadingPolicy.positionLabel(6,7,true))
 }

 @Test fun verticalRetainsThreeAndAddsExactlyOnePerPageScroll() {
  assertEquals(listOf(0,1,2),ComicReadingPolicy.verticalWindow(0,30))
  assertEquals(listOf(1,2,3),ComicReadingPolicy.verticalWindow(1,30))
  assertEquals(listOf(2,3,4),ComicReadingPolicy.verticalWindow(2,30))
  assertEquals(listOf(28,29),ComicReadingPolicy.verticalWindow(28,30))
  assertEquals(listOf(29),ComicReadingPolicy.verticalWindow(29,30))
  assertTrue(ComicReadingPolicy.verticalWindow(0,0).isEmpty())
  val first=ComicReadingPolicy.verticalWindow(0,100)
  val second=ComicReadingPolicy.verticalWindow(1,100)
  assertEquals(setOf(3),second.toSet()-first.toSet())
  assertEquals(setOf(0),first.toSet()-second.toSet())
  assertTrue((0 until 100).all {ComicReadingPolicy.verticalWindow(it,100).size<=3})
 }
}
