package com.ckchoi.ckreader

import org.junit.Assert.*
import org.junit.Test

class FolderYamlMetadataTest {
 @Test fun kavitaFileEntryInheritsCommonMetaAndSearchFallbacks() {
  val yaml="""
  files:
    "[저자]도서.epub":
      page: 55
      cover: cover.png
  meta:
    Name: "도서 제목"
    Person Writers: "YAML 저자"
    Person Publisher: "YAML 출판사"
    Summary: "YAML 소개"
    Genres: "소설,역사"
    Release Date: "20191021"
  search:
    - title: "도서 제목"
      author: "YAML 저자"
      publisher: "검색 출판사"
      isbn: "9791234567890"
      score: 100
  """.trimIndent()
  val data=parse(yaml,"[저자]도서.epub")
  assertEquals("도서 제목",data?.get("title"))
  assertEquals("YAML 저자",data?.get("author"))
  assertEquals("YAML 출판사",data?.get("publisher"))
  assertEquals("YAML 소개",data?.get("summary"))
  assertEquals("9791234567890",data?.get("isbn"))
  assertEquals("cover.png",data?.get("cover"))
 }
 @Test fun actualKavitaSizeWithEmbeddedArtworkIsReadable() {
  assertTrue(FolderYamlMetadata.readableSize(744_640))
 }
 @Test fun gdsUnknownSizeStillReadsYamlAndOversizeFilesAreSkipped() {
  assertTrue(FolderYamlMetadata.readableSize(-1L))
  assertTrue(FolderYamlMetadata.readableSize(262_144L))
  assertTrue(FolderYamlMetadata.readableSize(262_145L))
  assertFalse(FolderYamlMetadata.readableSize(FolderYamlMetadata.MAX_BYTES+1L))
  assertFalse(FolderYamlMetadata.readableSize(0L))
  val values=FolderYamlMetadata.parse("title: GDS 도서\nauthors: 작가".toByteArray(),"book.epub",true)
  assertEquals("GDS 도서",values?.get("title"))
 }
 private fun parse(text:String,filename:String,allowPlain:Boolean=false):Map<String,String>? =
  FolderYamlMetadata.parse(text.toByteArray(Charsets.UTF_8),filename,allowPlain)

 @Test fun authoredPriorityIsKThenFullFilenameYamlThenStemYaml(){
  val names=listOf("metadata.yaml","book.epub.yaml","book.yaml","kavita.yml","other.yaml")
  val strict=FolderYamlMetadata.priorityCandidates(names,"book.epub").map{it.filename}
  assertEquals(listOf("kavita.yml","book.epub.yaml","book.yaml"),strict)
  assertTrue(FolderYamlMetadata.hasPriorityYaml(names,"book.epub"))
  assertFalse(FolderYamlMetadata.hasPriorityYaml(listOf("metadata.yaml"),"book.epub"))
  val full=FolderYamlMetadata.candidates(listOf("book.epub.yaml","book.yaml"),
   "book.epub",2)
  assertEquals("book.epub.yaml",full.first().filename)
 }
 @Test fun exactNameYamlTakesPrecedenceOverFolderManifest() {
  val filenames=listOf("metadata.yaml","a.yaml","b.yml","author.yaml")
  val candidates=FolderYamlMetadata.candidates(filenames,"a.epub",2)
  assertEquals("a.yaml",candidates.first().filename)
  assertTrue(candidates.first().allowPlain)
  assertEquals("metadata.yaml",candidates[1].filename)
  assertFalse(candidates[1].allowPlain)
 }
 @Test fun sharedYamlMatchesOnlyItsOwnBook() {
  val yaml="""books:
    - filename: "001.epub"
      title: "첫 번째 도서"
      author: "작가 A"
      cover: "first.jpg"
    - filename: "002.epub"
      title: "두 번째 도서"
      author: "작가 B"
      isbn: "9791234567890"
  """.trimIndent()
  val first=parse(yaml,"001.epub")
  val second=parse(yaml,"002.epub")
  assertEquals("첫 번째 도서",first?.get("title"))
  assertEquals("first.jpg",first?.get("cover"))
  assertEquals("두 번째 도서",second?.get("title"))
  assertEquals("9791234567890",second?.get("isbn"))
  assertNull(parse(yaml,"003.epub"))
 }
 @Test fun filenameKeyedYamlSelectsCorrectEntry() {
  val yaml="""books:
    romance.epub:
      title: "로맨스 소설"
      author: "작가 1"
    fantasy.epub:
      title: "판타지 소설"
      author: "작가 2"
  """.trimIndent()
  assertEquals("로맨스 소설",parse(yaml,"romance.epub")?.get("title"))
  assertEquals("작가 2",parse(yaml,"fantasy.epub")?.get("author"))
  assertNull(parse(yaml,"science.epub"))
 }
 @Test fun plainYamlAppliesOnlyWhenSingleBookOrExactFilename() {
  val plain="title: '단행본'\nauthor: '저자'\ncover: 'cover.jpg'"
  assertNull(parse(plain,"unrelated.epub",allowPlain=false))
  assertEquals("단행본",parse(plain,"unrelated.epub",allowPlain=true)?.get("title"))
  assertEquals(
   listOf("metadata.yaml"),
   FolderYamlMetadata.candidates(listOf("metadata.yaml","cover.jpg"),"single.epub",1)
    .map{it.filename})
  assertFalse(FolderYamlMetadata.candidates(listOf("metadata.yaml","cover.jpg"),"a.epub",2)
   .first().allowPlain)
 }
 @Test fun mismatchingExplicitFileIsNeverApplied() {
  val yaml="filename: other.epub\ntitle: '다른 책'"
  assertNull(parse(yaml,"target.epub",allowPlain=true))
  assertEquals("다른 책",parse(yaml,"other.epub",allowPlain=true)?.get("title"))
 }
 @Test fun nestedBookMetadataAndListAuthors() {
  val yaml="""book:
    title: "출판 정보"
    authors:
      - "저자A"
      - "저자B"
    publisher: "출판사"
    publishedDate: "2026-01-01"
  """.trimIndent()
  val value=parse(yaml,"book.epub",allowPlain=true)
  assertEquals("출판 정보",value?.get("title"))
  assertEquals("저자A, 저자B",value?.get("authors"))
 }
 @Test fun oversizedUntrustedYamlIsRejected() {
  assertNull(FolderYamlMetadata.parse(ByteArray(FolderYamlMetadata.MAX_BYTES+1){'x'.code.toByte()},
   "book.epub",true))
 }
 @Test fun embeddedPngIsDecodedInFullAndNeverBecomesAFilePath() {
  val image=ByteArray(600_000)
  byteArrayOf(137.toByte(),80,78,71,13,10,26,10).copyInto(image)
  val encoded=java.util.Base64.getEncoder().encodeToString(image)
  val yaml="files:\n  book.epub:\n    cover: "+encoded+"\nmeta:\n  Name: title\n  Person Writers: author\n"
  assertTrue(yaml.length>262144)
  val result=FolderYamlMetadata.read(yaml.toByteArray(),"book.epub",false)
  assertNotNull(result)
  assertArrayEquals(image,result?.embeddedCover)
  assertFalse(result!!.values.values.any{it.startsWith("iVBOR")})
  assertEquals("title",result.values["title"])
 }
 @Test fun kavitaWithMultipleFormatsPrefersExactFileAndRejectsAnUnlistedBook() {
  val document=FolderYamlMetadata.document("""
  files:
    same.epub:
      cover: epub.png
      page: 55
    same.pdf:
      cover: pdf.png
      page: 70
  meta:
    Name: Common title
    Person Writers: Common author
  """.trimIndent().toByteArray())!!
  assertEquals("pdf.png",document.select("same.pdf",false)?.values?.get("cover"))
  assertEquals("70",document.select("same.pdf",false)?.values?.get("pageCount"))
  assertNull(document.select("different.epub",true))
  assertNull(document.select("same.txt",true))
 }
 @Test fun commonMetaAliasesAndReleaseDateAreCanonicalized() {
  val result=parse("""
  meta:
    Name: Title
    Person Writers: Author
    Person Publisher: Publisher
    Person Translator: Translator
    Genres: Fiction
    Release Date: 20191021
    Summary: Description
  files:
    book.epub:
      page: 55
  action:
    code: BR123
  """.trimIndent(),"book.epub")!!
  assertEquals("Author",result["author"])
  assertEquals("Translator",result["translator"])
  assertEquals("Publisher",result["publisher"])
  assertEquals("2019-10-21",result["publishedDate"])
  assertEquals("Fiction",result["genre"])
  assertEquals("BR123",result["book_id"])
 }
 @Test fun brokenEmbeddedCoverAllowsYamlPosterFallback() {
  val result=FolderYamlMetadata.read("""
  files:
    book.epub:
      cover: "iVBORw0KGgo-not-valid!"
  meta:
    Name: Title
  search:
    - title: Title
      poster_url: "https://example.org/cover.jpg"
  """.trimIndent().toByteArray(),"book.epub",false)!!
  assertNull(result.embeddedCover)
  assertEquals("https://example.org/cover.jpg",result.values["cover"])
 }
 @Test fun revisionRefreshIncludesCoveredCachedDriveBooks() {
  for(type in listOf("drive","webdav","ftp","local")) {
   assertTrue(FolderYamlMetadata.needsRefresh(type,0))
   assertTrue(FolderYamlMetadata.needsRefresh(type,2))
   assertTrue(FolderYamlMetadata.needsRefresh(type,3))
   assertFalse(FolderYamlMetadata.needsRefresh(type,FolderYamlMetadata.REVISION))
  }
  assertFalse(FolderYamlMetadata.needsRefresh("bookoasis",0))
 }
 @Test fun multiVolumeManifestCanExceedTheOldEightMiBLimit() {
  val padding="x".repeat(8*1024*1024)
  val data=("files:\n  book.epub:\n    title: Large series\nunused: '"+padding+"'\n").toByteArray()
  assertTrue(FolderYamlMetadata.readableSize(data.size.toLong()))
  assertEquals("Large series",FolderYamlMetadata.parse(data,"book.epub",false)?.get("title"))
 }
 @Test fun plainKavitaCommonFieldsApplyToEverySiblingBook() {
  val names=listOf("a.epub","b.epub","kavita.yaml")
  val option=FolderYamlMetadata.candidates(names,"a.epub",2).first()
  assertTrue(option.allowPlain)
  for(file in listOf("a.epub","b.epub"))
   assertEquals("공통 저자",FolderYamlMetadata.parse("author: 공통 저자".toByteArray(),file,option.allowPlain)?.get("author"))
 }
 @Test fun commonKavitaNeverAppliesAnUnlistedFileRecord() {
  val data="files:\n  a.epub:\n    title: A\nauthor: Common Author"
  assertNull(FolderYamlMetadata.parse(data.toByteArray(),"b.epub",true))
 }
 @Test fun kavitaIsFirstAheadOfOtherYamlSidecars() {
  val candidates=FolderYamlMetadata.candidates(listOf("book.yaml","metadata.yaml","kavita.yaml"),"book.epub",2)
  assertEquals("kavita.yaml",candidates.first().filename)
 }
 @Test fun volumeFilenameSelectsItsOwnSearchEntryAheadOfHigherScoredUnrelatedTitle() {
  val yaml="""
   files:
     "[저자]성벽 1권.epub":
       cover: one.png
     "[저자]성벽 2권.epub":
       cover: two.png
   meta:
     Name: 성벽
     Person Writers: ""
   search:
     - title: 성벽의 봄
       author: 다른 작가
       publisher: 다른 출판사
       score: 95
     - title: 성벽 1권
       author: 저자
       publisher: 출판사
       description: "<b>도서소개</b> 본문"
       score: 80
     - title: 성벽 2권
       author: 저자
       publisher: 출판사
       score: 75
  """.trimIndent()
  val first=parse(yaml,"[저자]성벽 1권.epub")!!
  val second=parse(yaml,"[저자]성벽 2권.epub")!!
  assertEquals("성벽 1권",first["title"])
  assertEquals("성벽 2권",second["title"])
  assertEquals("저자",first["author"])
  assertEquals("출판사",first["publisher"])
  assertEquals("성벽",first["series_name"])
  assertEquals("1",first["series_index"])
  assertEquals("2",second["series_index"])
  assertEquals("도서소개 본문",first["summary"])
  assertEquals("two.png",second["cover"])
 }
 @Test fun conflictingYamlSearchAuthorCannotSupplyIsbn() {
  val data=parse("""
   files:
     book.epub:
       page: 3
   meta:
     Name: 같은 제목
     Person Writers: 원저자
   search:
     - title: 같은 제목
       author: 다른 저자
       isbn: "9791234567890"
  """.trimIndent(),"book.epub")!!
  assertEquals("원저자",data["author"])
  assertNull(data["isbn"])
 }
}
