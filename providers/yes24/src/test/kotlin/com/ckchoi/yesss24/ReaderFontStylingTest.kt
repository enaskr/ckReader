package com.ckchoi.yesss24

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderFontStylingTest {
 @Test fun originalChoiceKeepsPublisherTypeface() {
  assertEquals("",ReaderFontStyling.css(0))
 }
 @Test fun serifAndSansOptionsOverrideEmbeddedStyles() {
  val serif=ReaderFontStyling.css(1)
  val sans=ReaderFontStyling.css(2)
  assertTrue(serif.contains("font-family:serif !important"))
  assertTrue(sans.contains("font-family:sans-serif !important"))
  assertTrue(serif.contains("html body *"))
  assertFalse(serif.contains("@font-face"))
 }
 @Test fun installedTrueTypeCanBeLoadedFromAppControlledOrigin() {
  val css=ReaderFontStyling.css(5,"gowun-dodum",false)
  assertTrue(css.contains("https://epub.local/__ckreader_fonts__/gowun-dodum"))
  assertTrue(css.contains("format('truetype')"))
  assertTrue(css.contains("font-family:'CKReaderUserFont', serif !important"))
 }
 @Test fun installedOpenTypeUsesCorrectMimeFontFormat() {
  val css=ReaderFontStyling.css(8,"seoul-namsan",true)
  assertTrue(css.contains("format('opentype')"))
  assertFalse(css.contains("format('truetype')"))
 }
 @Test(expected=IllegalArgumentException::class)
 fun rejectsInvalidFontIdsFromBookContent() {
  ReaderFontStyling.css(3,"../../secret",false)
 }
}
