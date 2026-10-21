package com.ckchoi.ckreader

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WebNovelFallbackParserTest {
 @Test fun parsesKakaoPageAndIgnoresAdult() {
  val fixture=JSONObject("""{"result":{"list":[
   {"series_id":1234,"title":"하늘의 별","authors":"작가",
    "thumbnail":"original-thumbnail-id","sub_category":"판타지"},
   {"series_id":4321,"title":"연령제한","adult":true}
  ]}}""")
  val data=WebNovelFallbackParser.kakaoPage(fixture)
  assertEquals(1,data.size)
  assertEquals("하늘의 별",data.first().title)
  assertEquals("카카오페이지",data.first().source)
  assertTrue(data.first().coverUrl.startsWith("https://page-images.kakaoentcdn.com/"))
 }
 @Test fun parsesRidiNextDataWithSeriesBook() {
  val fixture=JSONObject("""{"props":{"pageProps":{"gridData":{"riGrid":{"grid":{"cells":[
   {"cell__SearchBookListWithTab":{"books":[{
     "id":"123456789","book":{
       "id":"123456789","series":{"title":"우리 소설",
        "thumbnail":{"large":"https://ridibooks.com/cover.jpg"}},
       "authors":[{"name":"글쓴이"}],
       "publicationInfo":{"name":"출판그룹"}
     }}]}}
   ]}}}}}}""")
  val items=WebNovelFallbackParser.ridi(fixture)
  assertEquals(1,items.size)
  assertEquals("우리 소설",items[0].title)
  assertEquals("글쓴이",items[0].author)
  assertEquals("출판그룹",items[0].publisher)
 }
 @Test fun parsesMunpiaAndNovelpiaNoRestrictedItems() {
  val munpia=WebNovelFallbackParser.munpia(JSONObject("""{"result":{
   "searchNovelTabDtos":[{"novelId":123,"title":"최신 무협",
    "author":"작가A","coverUrl":"https://www.munpia.com/cover.jpg",
    "mainGenre":"무협","subGenre":"퓨전"},{"novelId":124,"title":"제한",
    "adult":true}]}}"""))
  assertEquals(1,munpia.size)
  assertEquals("무협, 퓨전",munpia[0].genre)
  val novel=WebNovelFallbackParser.novelpia(JSONObject("""{"data":{"search_result":[
   {"novel_no":987,"novel_name":"판타지 이야기","mem_nick":"작가B",
    "novel_age":"0","novel_thumb_all":"/cover.jpg"},
   {"novel_no":654,"novel_name":"연령 제한","novel_age":19}
  ]}}"""))
  assertEquals(1,novel.size)
  assertEquals("https://novelpia.com/cover.jpg",novel[0].coverUrl)
 }
 @Test fun parsesNaverSeriesOgDetail() {
  val doc="""<html><head><meta property="og:title" content="하나뿐인 이야기">
   <meta property="og:image" content="https://comic.naver.net/a.jpg">
   <meta property="og:description" content="글: 작가C, 회차 소개"></head></html>"""
  val book=WebNovelFallbackParser.naverSeriesDetail(doc,"1111")!!
  assertEquals("하나뿐인 이야기",book.title)
  assertEquals("작가C",book.author)
  assertEquals("네이버시리즈",book.source)
 }
}
