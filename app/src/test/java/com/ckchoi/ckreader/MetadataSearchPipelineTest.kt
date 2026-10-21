package com.ckchoi.ckreader

import org.junit.Assert.*
import org.junit.Test

class MetadataSearchPipelineTest {
 private val title="어린 왕자"
 private val isbn="9788932917245"
 private fun book(source:String,cover:String="",description:String="")=
  WebBookMetadata(title,author="생텍쥐페리",publisher=source,source=source,
   coverUrl=cover,description=description,isbn=isbn)

 @Test fun sanitizesSymbolsAndForeignScriptsButKeepsWordsAndAsciiDigits() {
  assertEquals("나 혼자만 레벨업 123 ABC",MetadataTitleMatcher.searchQuery("나 혼자만★레벨업! １２３：ＡＢＣ 日本語😀"))
  assertEquals("C 1984",MetadataTitleMatcher.searchQuery("C++ / 1984"))
  assertEquals("",MetadataTitleMatcher.searchQuery("★!? 日本語"))
  assertEquals("한글 ABC 123",MetadataTitleMatcher.searchQuery("  한글\nABC\t123  "))
 }
 @Test fun coverlessAladinContinuesAndNlIsbnFeedsData4Library() {
  val calls=ArrayList<MetadataSearchStage>()
  val result=MetadataSearchPipeline.resolve(title,"","",{stage,hint->
   calls.add(stage)
   when(stage) {
    MetadataSearchStage.ALADIN->listOf(book("알라딘").copy(isbn=""))
    MetadataSearchStage.NATIONAL_LIBRARY->listOf(book("국립중앙도서관"))
    MetadataSearchStage.DATA4LIBRARY->{
     assertEquals(isbn,hint)
     listOf(book("정보나루","https://image.aladin.co.kr/cover.jpg","책 소개"))
    }
    else->fail("Complete official metadata must stop before web search").let{emptyList()}
   }
  },{true})
  assertEquals(listOf(MetadataSearchStage.ALADIN,MetadataSearchStage.NATIONAL_LIBRARY,
   MetadataSearchStage.DATA4LIBRARY),calls)
  assertEquals("알라딘",result?.publisher)
  assertEquals("책 소개",result?.description)
  assertEquals("https://image.aladin.co.kr/cover.jpg",result?.coverUrl)
 }
 @Test fun failedCoverDownloadContinuesInRequestedOrder() {
  val calls=ArrayList<MetadataSearchStage>()
  val downloads=ArrayList<String>()
  val result=MetadataSearchPipeline.resolve(title,"","",{stage,_->
   calls.add(stage)
   listOf(book(stage.name,"https://example.invalid/"+stage.name,"소개"))
  },{candidate->downloads.add(candidate.coverUrl);candidate.coverUrl.endsWith("WEB")})
  assertEquals(MetadataSearchStage.entries,calls)
  assertEquals(4,downloads.size)
  assertEquals("ALADIN",result?.publisher)
  assertTrue(result!!.coverUrl.endsWith("WEB"))
 }
 @Test fun failedUrlsAreNotSavedAsSuccessfulCovers() {
  val result=MetadataSearchPipeline.resolve(title,"","",{_,_->
   listOf(book("알라딘","https://example.invalid/broken","소개"))
  },{false})
  assertNotNull(result)
  assertEquals("",result?.coverUrl)
 }
 @Test fun bookCardRefreshNeverQueriesWebAndHonorsOfficialOrdering(){
  val calls=mutableListOf<MetadataSearchStage>()
  val result=MetadataSearchPipeline.resolve(title,"",isbn,{stage,_->
   calls.add(stage)
   listOf(book(stage.name))
  },{false},listOf(MetadataSearchStage.ALADIN,
   MetadataSearchStage.NATIONAL_LIBRARY,MetadataSearchStage.DATA4LIBRARY))
  assertNotNull(result)
  assertEquals(listOf(MetadataSearchStage.ALADIN,
   MetadataSearchStage.NATIONAL_LIBRARY,MetadataSearchStage.DATA4LIBRARY),calls)
  assertFalse(calls.contains(MetadataSearchStage.WEB))
 }
 @Test fun completeFirstProviderSkipsAllLaterProviders() {
  val calls=ArrayList<MetadataSearchStage>()
  val result=MetadataSearchPipeline.resolve(title,"","",{stage,_->
   calls.add(stage);listOf(book("알라딘","https://example.invalid/cover","소개"))
  },{true})
  assertEquals(listOf(MetadataSearchStage.ALADIN),calls)
  assertEquals("소개",result?.description)
 }
 @Test fun incompatibleEditionsNeverSupplyAnotherBooksCover() {
  val first=book("알라딘")
  val other=book("정보나루","https://example.invalid/wrong","다른 책 소개").copy(isbn="9780000000000")
  assertEquals(first,MetadataSearchPipeline.fillMissing(first,other))
 }
 @Test fun exhaustiveSuggestionsPreserveProviderPriority() {
  val calls=ArrayList<MetadataSearchStage>()
  val results=MetadataSearchPipeline.candidates(title,"","",true){stage,_->
   calls.add(stage);listOf(book(stage.name,"https://example.invalid/cover","소개"))
  }
  assertEquals(MetadataSearchStage.entries,calls)
  assertEquals(MetadataSearchStage.entries.map{it.name},results.map{it.source})
 }
}
