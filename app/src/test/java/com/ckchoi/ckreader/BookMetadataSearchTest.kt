package com.ckchoi.ckreader

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BookMetadataTitleTest {
 @Test fun normalizesComicsWithServiceMarkersAndVolumeSuffixes(){
  assertEquals("나 혼자만 레벨업",
   MetadataTitleMatcher.lookupTitle("","[만화] 나 혼자만 레벨업 001권 (리디).cbz"))
  assertEquals("어린 왕자",MetadataTitleMatcher.lookupTitle("","어린 왕자.epub"))
  assertEquals("1984",MetadataTitleMatcher.lookupTitle("","1984.pdf"))
  assertEquals("나혼자만레벨업",MetadataTitleMatcher.normalized("나 혼자만 레벨업"))
 }
 @Test fun exactTitleOnlyAndNoGuessedFuzzyMatch(){
  val candidates=listOf(WebBookMetadata("나 혼자만 레벨업: 라그나로크","다울"),
   WebBookMetadata("나 혼자만 레벨업","추공",coverUrl="https://example.com"))
  assertEquals("추공",MetadataTitleMatcher.choose("나 혼자만 레벨업","","",candidates)?.author)
  assertNull(MetadataTitleMatcher.choose("나 혼자만 레벨업 전설","","",candidates))
 }
 @Test fun conflictingAuthorsPreventAutomaticMetadata(){
  val list=listOf(WebBookMetadata("별빛","김철수"),WebBookMetadata("별빛","박영희"))
  assertNull(MetadataTitleMatcher.choose("별빛","","",list))
  assertEquals("김철수",MetadataTitleMatcher.choose("별빛","김철수","",list)?.author)
  assertNull(MetadataTitleMatcher.choose("별빛","이강수","",list))
 }
 @Test fun suppliedIsbnMustMatchWhenResultHasIsbn(){
  val a=WebBookMetadata("위대한 개츠비","피츠제럴드",isbn="9781234567890")
  assertNull(MetadataTitleMatcher.choose("위대한 개츠비","","9789999999999",listOf(a)))
  assertNotNull(MetadataTitleMatcher.choose("위대한 개츠비","","9781234567890",listOf(a)))
 }
}

class BookMetadataParsersTest {
 private val parser=BookMetadataSearch()
 @Test fun naverUsesExactTitleAndIgnoresAdult() {
  val sample=JSONObject("""{
    "searchWebtoonResult":{"searchViewList":[
      {"titleId":19,"titleName":"별빛",
       "displayAuthor":"이 작가","thumbnailUrl":"https://image-comic.pstatic.net/cover.jpg","synopsis":"소개"},
      {"titleId":20,"titleName":"별빛 2","displayAuthor":"다른 작가"},
      {"titleId":21,"titleName":"별빛","adult":true}
    ]}
  }""")
  val found=parser.fromNaver(sample,"별빛")
  assertEquals(1,found.size)
  assertEquals("이 작가",found[0].author)
  assertEquals("네이버",found[0].source)
  assertEquals("소개",found[0].description)
 }
 @Test fun kakaoPublicDataParsesAuthorsAndCover() {
  val sample=JSONObject("""{"data":{"content":[
  {"id":123,"title":"별빛","adult":false,"genre":"판타지",
   "authors":[{"name":"홍작가","type":"AUTHOR"},{"name":"원작자","type":"ORIGINAL_STORY"}],
   "sharingThumbnailImage":"https://kr-a.kakaopagecdn.com/c.png"},
  {"id":124,"title":"별빛의 기사","adult":false}
  ]}}""")
  val found=parser.fromKakao(sample,"별빛")
  assertEquals(1,found.size)
  assertEquals("홍작가, 원작자",found[0].author)
  assertEquals("카카오웹툰",found[0].source)
 }
 @Test fun openLibraryParsesBookAndRejectsUnrelatedTitle() {
  val sample=JSONObject("""{"docs":[
   {"title":"어린 왕자","author_name":["생텍쥐페리"],"publisher":["문학사"],"cover_i":1234,
    "isbn":["9781234567890"],"key":"/works/OL123W"},
   {"title":"어린 왕자 해설","author_name":["다른 저자"]}
   ]}""")
  val result=parser.fromOpenLibrary(sample,"어린 왕자")
  assertEquals(1,result.size)
  assertEquals("문학사",result[0].publisher)
  assertTrue(result[0].coverUrl.startsWith("https://covers.openlibrary.org/"))
  assertTrue(result[0].sourceUrl.endsWith("/works/OL123W"))
 }
 @Test fun googleBookDescriptionsAreOptional(){
  val sample=JSONObject("""{"items":[{"volumeInfo":{"title":"소설 A",
   "authors":["작가 A"],"publisher":"출판사 B",
   "imageLinks":{"thumbnail":"http://books.google.com/test"}}}]}""")
  val result=parser.fromGoogle(sample,"소설 A")
  assertEquals("작가 A",result[0].author)
  assertEquals("https://books.google.com/test",result[0].coverUrl)
 }
}
