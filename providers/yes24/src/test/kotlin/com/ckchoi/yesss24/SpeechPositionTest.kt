package com.ckchoi.yesss24

import org.junit.Assert.*
import org.junit.Test

class SpeechPositionTest {
 @Test fun seeksToTheTouchedSentenceAcrossInlineTextAndParagraphs() {
  val text="첫 번째 문장입니다. 두 번째 문장입니다!\n세 번째 문장입니다."
  assertEquals(text.indexOf("두 번째"),SpeechPosition.sentenceStart(text,text.indexOf("두 번째")+4))
  assertEquals(text.indexOf("세 번째"),SpeechPosition.sentenceStart(text,text.indexOf("세 번째")+6))
 }
 @Test fun decimalsDoNotStartANewSentenceAndUtf16OffsetsArePreserved() {
  val text="시작입니다. 😀 값은 3.14입니다. 다음입니다."
  assertEquals(text.indexOf("😀"),SpeechPosition.sentenceStart(text,text.indexOf("14")))
  assertEquals(text.indexOf("다음"),SpeechPosition.sentenceStart(text,text.length-2))
 }
 @Test fun onlyAStationarySingleFingerHoldAtLeast500MsSeeks() {
  assertFalse(SpeechPosition.held(499,0f,12f))
  assertTrue(SpeechPosition.held(500,0f,12f))
  assertFalse(SpeechPosition.held(900,13f,12f))
  assertFalse(SpeechPosition.held(900,0f,12f,2))
 }
}
