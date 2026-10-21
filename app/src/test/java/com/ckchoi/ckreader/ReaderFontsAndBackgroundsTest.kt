package com.ckchoi.ckreader

import com.ckchoi.elibrary.common.ReaderBackgrounds
import com.ckchoi.elibrary.common.ReaderFonts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderFontsAndBackgroundsTest {
 @Test fun backgroundSelectionsPreserveLegacyAndMeetReadabilityContrast() {
  val palettes=ReaderBackgrounds.tones
  assertEquals("테마 기본",palettes[0].label)
  assertEquals("크림",palettes[1].label)
  assertEquals("어둡게",palettes[2].label)
  assertTrue(palettes.size>=14)
  palettes.drop(1).forEachIndexed { index,tone ->
   assertTrue(tone.label+" "+ReaderBackgrounds.contrast(index+1),
    ReaderBackgrounds.contrast(index+1)>=4.5)
  }
 }
 @Test fun packFontsFollowTheThreeBuiltInChoices() {
  assertEquals(14,ReaderFonts.families.size)
  for((n,f) in ReaderFonts.families.withIndex()) {
   assertEquals(n+3,ReaderFonts.index(f.id))
   assertEquals(f,ReaderFonts.family(n+3))
  }
  assertEquals(-1,ReaderFonts.index("unknown"))
  assertEquals(null,ReaderFonts.family(2))
  assertEquals(null,ReaderFonts.family(17))
 }
 @Test fun knownFontsFromUser() {
  for(label in listOf("바른 바탕체","학교안심 바른바탕","고운돋움",
   "네이버 마루부리","서울한강체","서울남산체","경기천년바탕","IBM Plex Sans KR",
   "KoPubWorld 바탕체","KoPubWorld 돋움체","나눔손글씨 기쁨밝음","서울알림체","서강체","예스체")) {
   assertNotNull(ReaderFonts.families.find{it.label==label})
  }
 }
 @Test fun twoIndependentPacksShareExactlyTheSevenRequestedFonts() {
  assertEquals(listOf("font14","font7"),ReaderFonts.packs.map{it.id})
  val full=ReaderFonts.packs[0];val small=ReaderFonts.packs[1]
  assertEquals(14,full.fontIds.size)
  assertEquals(7,small.fontIds.size)
  assertTrue(full.fontIds.containsAll(small.fontIds))
  assertEquals(setOf("ibm-plex-sans-kr","kopub-world-batang","kopub-world-dotum",
   "seoul-alrim","seoul-hangang","seoul-namsan","yes24"),small.fontIds)
  assertEquals(ReaderFonts.families.map{it.id}.toSet(),full.fontIds)
  assertEquals(2,ReaderFonts.packs.map{it.packageName}.toSet().size)
 }
}
