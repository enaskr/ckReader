package com.ckchoi.ckreader

import org.junit.Assert.*
import org.junit.Test

class BookOasisOpdsParserTest {
 @Test fun navigationAndCover() {
  val xml="""<?xml version="1.0" encoding="UTF-8"?>
   <feed xmlns="http://www.w3.org/2005/Atom">
    <title>BookOasis</title>
    <entry><title>소설</title><id>urn:library:12</id>
     <link rel="subsection" href="https://books.example/opds/library/12"
      type="application/atom+xml;profile=opds-catalog;kind=navigation"/>
     <link rel="http://opds-spec.org/image" href="https://books.example/covers/12/cover.webp"/>
    </entry>
   </feed>""".trimIndent()
  val parsed=BookOasisOpdsParser.parse(xml.toByteArray())
  assertEquals(1,parsed.entries.size)
  val entry=parsed.entries.single()
  assertTrue(entry.navigation)
  assertEquals("소설",entry.title)
  assertEquals("https://books.example/opds/library/12",entry.href)
  assertEquals("https://books.example/covers/12/cover.webp",entry.cover)
 }

 @Test fun acquisitionSummaryAndPageStream() {
  val xml="""<feed xmlns="http://www.w3.org/2005/Atom"
   xmlns:pse="http://vaemendis.net/opds-pse/ns">
    <entry><id>urn:general:book:198</id><title>테스트 01권</title>
     <summary>형식: 만화 · 저자: 저자이름</summary>
     <link rel="http://opds-spec.org/acquisition/open-book"
      href="https://books.example/opds/download/general/198?token=should-not-persist"
      type="application/vnd.comicbook+zip"/>
     <link rel="http://vaemendis.net/opds-pse/stream"
      href="https://books.example/opds/pse/general/198/{pageNumber}"
      type="image/jpeg" pse:count="192"/>
    </entry>
   </feed>""".trimIndent()
  val entry=BookOasisOpdsParser.parse(xml.toByteArray()).entries.single()
  assertFalse(entry.navigation)
  assertEquals("application/vnd.comicbook+zip",entry.mime)
  assertEquals(192,entry.pages)
  assertTrue(entry.href.contains("198?token="))
  assertEquals("형식: 만화 · 저자: 저자이름",entry.summary)
 }

 @Test fun nextAndSearchCategory() {
  val xml="""<feed xmlns="http://www.w3.org/2005/Atom">
   <link rel="next" href="https://books.example/opds/search?q=%ED%95%9C%EA%B8%80&amp;page=2"/>
   <entry><id>urn:book:2</id><title>[PDF] 한글</title><category term="PDF" label="PDF"/>
    <link rel="http://opds-spec.org/acquisition" href="/opds/download/general/2"
     type="application/pdf"/></entry></feed>""".trimIndent()
  val feed=BookOasisOpdsParser.parse(xml.toByteArray())
  assertEquals("https://books.example/opds/search?q=%ED%95%9C%EA%B8%80&page=2",feed.next)
  assertEquals("PDF",feed.entries.single().format)
  assertEquals("application/pdf",feed.entries.single().mime)
 }

 @Test fun rejectsXmlExternalEntities() {
  val xml="""<!DOCTYPE foo [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
    <feed xmlns="http://www.w3.org/2005/Atom"><entry><title>&xxe;</title></entry></feed>"""
  assertThrows(IllegalArgumentException::class.java) {
   BookOasisOpdsParser.parse(xml.toByteArray())
  }
 }
}
