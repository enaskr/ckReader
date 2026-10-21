package com.ckchoi.yesss24

import org.junit.Assert.*
import org.junit.Test

class SpeechTextTest {
 @Test fun transformedSpeechRetainsExactSourceRanges() {
  val text="첫  문장 (생략할 말)입니다.\n다음\t\t漢字 문장입니다! 짧다."
  val parts=SpeechText.prepare(text,0,false,true,false)
  assertEquals(listOf("첫 문장 입니다.","다음 문장입니다!","짧다."),parts.map{it.text})
  assertEquals("첫  문장 (생략할 말)입니다.",text.substring(parts[0].start,parts[0].end))
  assertEquals("다음\t\t漢字 문장입니다!",text.substring(parts[1].start,parts[1].end))
  assertEquals(text.indexOf("짧다"),parts[2].start)
 }
 @Test fun repeatedSentencesAndEmojiKeepTheSelectedOccurrence() {
  val text="😀 같은 문장입니다.\n😀 같은 문장입니다.\n끝."
  val offset=text.indexOf('\n')+1
  val parts=SpeechText.prepare(text,offset,true,false,false)
  assertEquals(offset,parts[0].start)
  assertEquals("😀 같은 문장입니다.",text.substring(parts[0].start,parts[0].end))
  assertEquals("끝.",parts[1].text)
 }
 @Test fun chunksLongSentencesWithoutLosingOffsetsOrLanguage() {
  val text="앞. "+"English sentence ".repeat(50)
  val parts=SpeechText.prepare(text,3,true,false,true)
  assertTrue(parts.size>1)
  assertTrue(parts.all{it.english&&it.text.length<=350&&it.text==text.substring(it.start,it.end)})
  assertTrue(SpeechText.prepare(" \n 漢字 (생략) ",0,false,true,false).isEmpty())
 }
 @Test fun shortKoreanLinesRemainSpeakableAndKeepTheirHighlightRanges() {
  val content="네? 네. 네.\n음.....\n긴 문장이 나옵니다."
  val parts=SpeechText.prepare(content,0,true,false,false)
  assertEquals(listOf("네?","네.","네.","음.....","긴 문장이 나옵니다."),parts.map{it.text})
  assertTrue(parts.all { content.substring(it.start,it.end)==it.text })
 }

}
