package com.ckchoi.ckreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BookOasisSeriesRoutesTest {
 @Test fun sameSeriesNameCanOccurInMultipleBookFolders() {
  val a=BookOasisPaths.series("7","동일한 시리즈", "103")
  val b=BookOasisPaths.series("7","동일한 시리즈", "204")
  assertNotEquals(a,b)
  assertEquals("동일한 시리즈",BookOasisPaths.display(a))
  assertEquals("동일한 시리즈",BookOasisPaths.display(b))
  val path=BookOasisPaths.library("7","소설")+"/"+b
  val parsed=BookOasisPaths.parse(path)
  assertEquals("7",parsed.libraryId)
  assertEquals("동일한 시리즈",parsed.seriesName)
  assertEquals("204",parsed.representativeId)
 }

 @Test fun oldSavedPathsRemainReadable() {
  val old=BookOasisPaths.series("5","이전 시리즈")
  val parsed=BookOasisPaths.parse(old)
  assertEquals("이전 시리즈",parsed.seriesName)
  assertNull(parsed.representativeId)
 }
 @Test fun homeShelfCanOpenSeriesFromAnyApiLibrary() {
  val path=BookOasisPaths.library("home","전체 보기")+"/"+
   BookOasisPaths.series("7","다른 보관함 시리즈","204")
  val parsed=BookOasisPaths.parse(path)
  assertEquals("7",parsed.libraryId)
  assertEquals("다른 보관함 시리즈",parsed.seriesName)
 }
 @Test fun emptyOpdsSeriesUsesStableRouteAndReadableLabel() {
  val encoded=BookOasisPaths.series("12","__empty_series__")
  assertEquals("기타",BookOasisPaths.display(encoded))
  assertEquals("__empty_series__",BookOasisPaths.parse(encoded).seriesName)
  assertNotEquals(encoded,BookOasisPaths.series("12","기타"))
 }
}
