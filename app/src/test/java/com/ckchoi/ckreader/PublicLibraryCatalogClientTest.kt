package com.ckchoi.ckreader

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.Assume.assumeFalse

class PublicLibraryCatalogClientTest {
 @Before fun requireFullEdition(){assumeFalse(BuildConfig.LITE)}
 @Test fun parsesOfficialNationalLibraryFieldsAndHtml() {
  val books=PublicLibraryBookParser.nationalLibrary(JSONObject("""{"total":1,"result":[{
   "title_info":"<span>어린 왕자</span>","author_info":"생텍쥐페리",
   "pub_info":"열린책들","pub_year_info":"2015","isbn":"9788932917245",
   "detail_link":"/NL/contents/search.do?id=123","kdc_name_1s":"문학"}]}"""))
  assertEquals(1,books.size)
  assertEquals("어린 왕자",books[0].title)
  assertEquals("생텍쥐페리",books[0].author)
  assertEquals("열린책들",books[0].publisher)
  assertEquals("https://www.nl.go.kr/NL/contents/search.do?id=123",books[0].sourceUrl)
  assertEquals("",books[0].coverUrl)
 }
 @Test fun parsesData4LibrarySearchAndDetailAndUpgradesImageHttps() {
  val search=PublicLibraryBookParser.data4Library(JSONObject("""{"response":{"docs":[{"doc":{
   "bookname":"어린 왕자","authors":"생텍쥐페리","publisher":"열린책들",
   "isbn13":"9788932917245","bookImageURL":"http://image.aladin.co.kr/cover.jpg",
   "bookDtUrl":"https://data4library.kr/bookV?seq=1","publication_year":"2015"}}]}}"""))
  assertEquals("https://image.aladin.co.kr/cover.jpg",search.single().coverUrl)
  val detail=PublicLibraryBookParser.data4Library(JSONObject("""{"response":{"detail":[{"book":{
   "bookname":"어린 왕자","isbn13":"9788932917245","description":"책 &amp; 소개"}}]}}"""))
  assertEquals("책 & 소개",detail.single().description)
 }
 @Test fun sendsOnlyCleanSearchTermsAndEachProvidersOwnKeyOverHttps() {
  val urls=ArrayList<okhttp3.HttpUrl>()
  val accepts=ArrayList<String?>()
  val http=OkHttpClient.Builder().addInterceptor{chain->
   val url=chain.request().url
   urls.add(url)
   accepts.add(chain.request().header("Accept"))
   val response=if(url.host=="www.nl.go.kr")"""{"total":0,"result":[]}"""
    else """{"response":{"numFound":0,"docs":[]}}"""
   Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
    .message("OK").body(response.toResponseBody()).build()
  }.build()
  val client=PublicLibraryCatalogClient(http=http,keyReader={"test-key-"+it.name})
  client.search(MetadataApiProvider.NATIONAL_LIBRARY,"어린★왕자 １２３")
  client.search(MetadataApiProvider.DATA4LIBRARY,"어린★왕자 １２３")
  assertTrue(client.contacted)
  assertEquals("어린 왕자 123",urls[0].queryParameter("kwd"))
  assertEquals("어린 왕자 123",urls[1].queryParameter("keyword"))
  assertEquals("test-key-NATIONAL_LIBRARY",urls[0].queryParameter("key"))
  assertNull(urls[0].queryParameter("authKey"))
  assertEquals("test-key-DATA4LIBRARY",urls[1].queryParameter("authKey"))
  assertTrue(urls.all{it.scheme=="https"})
  assertEquals("application/json",accepts[0])
  assertNull(accepts[1])
 }
 @Test fun missingKeysSkipNetworkAndApiErrorsDoNotCountAsSuccess() {
  val http=OkHttpClient.Builder().addInterceptor{chain->
   Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
    .message("OK").body("""{"response":{"error":"invalid key"}}""".toResponseBody()).build()
  }.build()
  val missing=PublicLibraryCatalogClient(http=http,keyReader={null})
  assertTrue(missing.search(MetadataApiProvider.DATA4LIBRARY,"어린 왕자").isEmpty())
  assertFalse(missing.contacted)
  val invalid=PublicLibraryCatalogClient(http=http,keyReader={"invalid-test-key"})
  assertTrue(invalid.search(MetadataApiProvider.DATA4LIBRARY,"어린 왕자").isEmpty())
  assertFalse(invalid.contacted)
 }
 @Test fun nationalLibraryKnownIsbnUsesDocumentedDetailSearchWithoutTitleKeyword() {
  val urls=ArrayList<okhttp3.HttpUrl>()
  val http=OkHttpClient.Builder().addInterceptor{chain->
   urls.add(chain.request().url)
   Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
    .message("OK").body("""{"total":1,"result":[{"title_info":"어린 왕자",
      "isbn":"978-89-329-1724-5 (03860)"}]}""".toResponseBody()).build()
  }.build()
  val books=PublicLibraryCatalogClient(http=http,keyReader={"test-key"})
   .search(MetadataApiProvider.NATIONAL_LIBRARY,"어린 왕자",isbn="9788932917245")
  assertEquals("9788932917245",books.single().isbn)
  assertEquals(1,urls.size)
  assertEquals("9788932917245",urls.single().queryParameter("isbnCode"))
  assertEquals("true",urls.single().queryParameter("detailSearch"))
  assertNull(urls.single().queryParameter("kwd"))
 }
 @Test fun data4LibrarySearchEnrichesTheSameIsbnUsingBookDetails() {
  val paths=ArrayList<String>()
  val http=OkHttpClient.Builder().addInterceptor{chain->
   val url=chain.request().url
   paths.add(url.encodedPath)
   val body=if(url.encodedPath.endsWith("srchBooks"))
    """{"response":{"docs":[{"doc":{"bookname":"어린 왕자","isbn13":"9788932917245",
      "publisher":"원래 출판사"}}]}}"""
   else {
    assertEquals("9788932917245",url.queryParameter("isbn13"))
    """{"response":{"detail":[{"book":{"bookname":"어린 왕자","isbn13":"9788932917245",
      "description":"상세 책 소개","publisher":"다른 출판사","bookImageURL":"http://image.aladin.co.kr/cover.jpg"}}]}}"""
   }
   Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
    .message("OK").body(body.toResponseBody()).build()
  }.build()
  val books=PublicLibraryCatalogClient(http=http,keyReader={"test-key"})
   .search(MetadataApiProvider.DATA4LIBRARY,"어린 왕자")
  assertEquals(listOf("/api/srchBooks","/api/srchDtlList"),paths)
  assertEquals("상세 책 소개",books.single().description)
  assertEquals("원래 출판사",books.single().publisher)
  assertEquals("https://image.aladin.co.kr/cover.jpg",books.single().coverUrl)
 }
}
