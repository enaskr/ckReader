package com.ckchoi.ckreader

import com.ckchoi.elibrary.common.ReaderBackgrounds
import com.ckchoi.elibrary.common.ReaderFonts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Test

class ReaderBackgroundAndFontsTest {
 @Test fun originalReaderBackgroundIndicesRetainMeaning() {
  assertEquals("테마 기본",ReaderBackgrounds.get(0).label)
  assertEquals("크림",ReaderBackgrounds.get(1).label)
  assertEquals("어둡게",ReaderBackgrounds.get(2).label)
  assertTrue(ReaderBackgrounds.tones.size>=14)
 }

 @Test fun everyBackgroundHasReadableBookText() {
  ReaderBackgrounds.tones.forEachIndexed { index,tone ->
   if(index>0)assertTrue(tone.label+" contrast",ReaderBackgrounds.contrast(index)>=7.0)
  }
 }

 @Test fun sixRequestedFontsKeepStableViewerIndices() {
  val expected=listOf("bareun-batang","school-bareun","gowun-dodum",
   "maru-buri","seoul-hangang","seoul-namsan")
  assertEquals(expected,ReaderFonts.families.take(6).map{it.id})
  for((index,id) in expected.withIndex()) {
   assertEquals(index+3,ReaderFonts.index(id))
   assertEquals(id,ReaderFonts.family(index+3)?.id)
  }
  assertEquals(-1,ReaderFonts.index("unexpected"))
 }

 @Test fun catalogIdentifiesEveryPackAssetAndItsIntegrity() {
  assertEquals(14,ReaderFonts.families.size)
  assertEquals(14,ReaderFonts.families.map{it.id}.toSet().size)
  assertEquals(14,ReaderFonts.families.map{it.asset}.toSet().size)
  for(entry in ReaderFonts.families){
   assertTrue(entry.id.matches(Regex("[a-z0-9-]{1,50}")))
   assertEquals(entry.id+".ttf",entry.asset)
   assertTrue(entry.sha256.matches(Regex("[a-f0-9]{64}")))
   assertTrue(entry.bytes in 1_000_000L..15_000_000L)
   assertTrue(entry.description.isNotBlank()&&entry.publisher.isNotBlank()&&entry.style.isNotBlank())
  }
 }
}
