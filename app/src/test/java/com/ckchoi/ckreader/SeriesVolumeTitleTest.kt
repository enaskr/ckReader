package com.ckchoi.ckreader

import org.junit.Assert.*
import org.junit.Test

class SeriesVolumeTitleTest {
 @Test fun dollarGutUsesFilenameSpecificVolume() {
  assertEquals("달러구트 꿈 백화점",
   SeriesVolumeTitle.title("달러구트 꿈 백화점.epub","달러구트 꿈 백화점"))
  assertEquals("달러구트 꿈 백화점 2",
   SeriesVolumeTitle.title("달러구트 꿈 백화점 2.epub","달러구트 꿈 백화점"))
 }

 @Test fun tenVolumesDoNotInheritVolumeOneSubtitle() {
  for(i in 1..10) {
   val filename="삼국지 $i.epub"
   val original="삼국지 1 - 도원결의"
   val expected=if(i==1)original else "삼국지 $i"
   assertEquals(filename,expected,SeriesVolumeTitle.title(filename,original))
  }
 }

 @Test fun correctVolumeSpecificSubtitlesRemainUnchanged() {
  assertEquals("삼국지 2 - 군웅할거",
   SeriesVolumeTitle.title("삼국지 2.epub","삼국지 2 - 군웅할거"))
  assertEquals("삼국지 10 - 오장원",
   SeriesVolumeTitle.title("삼국지 10.epub","삼국지 10 - 오장원"))
  assertEquals("삼국지 2 - 적벽대전",
   SeriesVolumeTitle.title("삼국지 2 - 적벽대전.epub","삼국지 1 - 도원결의"))
 }

 @Test fun authorPrefixAndExplicitMarkersAreRecognized() {
  assertEquals("삼국지 제2권",
   SeriesVolumeTitle.title("[요시카와]삼국지 제2권.epub","삼국지"))
  assertEquals("삼국지 12권",
   SeriesVolumeTitle.title("삼국지 12권.epub","삼국지 1 - 도원결의"))
 }

 @Test fun unrelatedMetadataAndNonVolumesAreNotOverwritten() {
  assertEquals("제목이 다른 책",
   SeriesVolumeTitle.title("삼국지 3.epub","제목이 다른 책"))
  assertEquals("서른 살 이야기",
   SeriesVolumeTitle.title("서른 살 이야기.epub","서른 살 이야기"))
  assertEquals("태양계의 비밀",
   SeriesVolumeTitle.title("태양계의 비밀 2024.epub","태양계의 비밀"))
 }

 @Test fun cacheBookIsCorrectedWithPerFileOrdinal() {
  val source=LibrarySource("test","drive","GDS","")
  val book=LibraryBook("id",source,"책/일반/사/삼국지 [작가]/삼국지 10.epub","삼국지 10.epub",
   title="삼국지 1 - 도원결의",seriesIndex=1)
  SeriesVolumeTitle.apply(book)
  assertEquals("삼국지 10",book.title)
  assertEquals(10,book.seriesIndex)
 }
 @Test fun webDavSeriesFilesWithTrailingAuthorShowEachVolume() {
  val source=LibrarySource("synology","webdav","Synology","")
  for(i in 1..4) {
   val suffix=if(i==4)" (완결)" else ""
   val filename="눈물을 마시는 새 $i$suffix [이영도].epub"
   val book=LibraryBook("id-$i",source,"책/$filename",filename,
    title="눈물을 마시는 새")
   SeriesVolumeTitle.apply(book)
   assertEquals("눈물을 마시는 새 $i$suffix",book.shownTitle)
   assertEquals(i,book.seriesIndex)
  }
 }
 @Test fun synologyServiceSuffixDoesNotHideFilenameVolume() {
  val source=LibrarySource("synology","webdav","Synology","")
  for(i in 1..3) {
   val filename="이문열 삼국지 $i [나관중]#18.epub"
   val book=LibraryBook("id-$i",source,"책/$filename",filename,
    title="이문열 삼국지 4")
   SeriesVolumeTitle.apply(book)
   assertEquals("이문열 삼국지 $i",book.shownTitle)
   assertEquals(i,book.seriesIndex)
  }
 }
}
