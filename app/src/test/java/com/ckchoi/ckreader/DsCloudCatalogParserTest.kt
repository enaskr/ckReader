package com.ckchoi.ckreader

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DsCloudCatalogParserTest {
 @Test fun mapsActualAladinCatalogResponseToBookMetadata() {
  val result=DsCloudBookParser.parseItems(JSONObject("""{
   "items":[
    {"item_id":402732161,"title":"어린 왕자 (영문판, 한글판)",
     "authors":"권성욱 지음; 앙투안 드 생텍쥐페리","publisher":"프리펍",
     "ebook_isbn":"9791173255694","book_isbn":null,
     "category_name":"소설/시/희곡 > 세계의 문학",
     "publish_date":"2013-10-24","cover_image_url":"/covers/402732161",
     "series_name":null,"is_adult":0},
    {"item_id":987,"title":"성인전용 도서","is_adult":1}
   ],"total":2,"page":1,"has_next":false
  }"""))
  assertEquals(1,result.size)
  val book=result.single()
  assertEquals("어린 왕자 (영문판, 한글판)",book.title)
  assertEquals("권성욱 지음, 앙투안 드 생텍쥐페리",book.author)
  assertEquals("프리펍",book.publisher)
  assertEquals("9791173255694",book.isbn)
  assertEquals("2013-10-24",book.publishedDate)
  assertEquals("소설/시/희곡 > 세계의 문학",book.genre)
  assertEquals("https://aladinapi.ibmer.app/covers/402732161",book.coverUrl)
  assertEquals("알라딘 OpenAPI",book.source)
  assertTrue(book.sourceUrl.endsWith("402732161"))
 }
 @Test fun detailEnrichesMissingSummaryWithoutDiscardingTitle() {
  val list=DsCloudBookParser.parseBook(JSONObject("""{
   "item_id":12345,"title":"소설","authors":"원저자",
   "cover_image_url":"/covers/12345","publisher":"기존출판사"}"""))!!
  val detail=DsCloudBookParser.parseBook(JSONObject("""{
   "item_id":12345,"title":"소설","description":"확인된 소개",
   "series_name":"시리즈 이름","publisher":"상세출판사",
   "cover_image_url":"/covers/12345"}"""))!!
  val book=DsCloudBookParser.combine(list,detail)
  assertEquals("원저자",book.author)
  assertEquals("확인된 소개",book.description)
  assertEquals("시리즈 이름",book.seriesName)
  assertEquals("상세출판사",book.publisher)
 }
 @Test fun coverPathIsNotConstructedFromArbitraryServerUrl() {
  val malicious=DsCloudBookParser.parseBook(JSONObject("""{
   "item_id":3456,"title":"테스트",
   "cover_image_url":"http://127.0.0.1/private",
   "cover_url":"http://169.254.169.254/private"}"""))!!
  assertEquals("",malicious.coverUrl)
  val valid=DsCloudBookParser.parseBook(JSONObject("""{
   "item_id":3456,"title":"테스트",
   "cover_image_url":"/covers/3456"}"""))!!
  assertEquals("https://aladinapi.ibmer.app/covers/3456",valid.coverUrl)
 }
 @Test fun booleanAdultFlagIsExcluded() {
  val book=JSONObject("""{"item_id":1234,"title":"성인 도서","is_adult":true}""")
  assertNull(DsCloudBookParser.parseBook(book))
 }
}
