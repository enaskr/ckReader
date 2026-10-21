package com.ckchoi.ckreader

import org.junit.Test
import org.junit.Before
import org.junit.Assume.assumeFalse
import org.junit.Assert.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer

class GoogleDriveAccessTest {
 @Before fun requireFullEdition(){assumeFalse(BuildConfig.LITE)}
 private class Reply(url:String,val body:String,val code:Int=200,val range:String=""):HttpURLConnection(URL(url)){
  override fun connect(){}
  override fun disconnect(){}
  override fun usingProxy()=false
  override fun getResponseCode()=code
  override fun getInputStream()=body.byteInputStream()
  override fun getHeaderField(name:String)=if(name=="Content-Range")range else null
 }
 private fun source(shared:String="")=LibrarySource("google","drive","Google","",extra=JSONObject()
  .put(GoogleDriveSignIn.AUTH_KEY,true).put(GoogleDriveSignIn.ACCOUNT_KEY,"example@example.invalid")
  .put("team_drive",shared).put("token","MUST_NOT_USE_RCLONE_TOKEN")
  .put("gds_apikey","MUST_NOT_USE_GDS").toString())
 private fun writableSource():LibrarySource=source().let{it.copy(extra=JSONObject(it.extra)
  .put("scope","drive").toString())}
 private fun reply(request:Request,body:String)=Response.Builder().request(request)
  .protocol(Protocol.HTTP_1_1).code(200).message("OK")
  .body(body.toResponseBody("application/json".toMediaType())).build()
 @Test fun googleListsSharedDriveAndAllPagesUsingOnlyLoginToken(){
  val requests=ArrayList<Reply>()
  val client=GoogleDriveAccess({"LOGIN_TOKEN"}){url->
   val decoded=URLDecoder.decode(url,"UTF-8")
   assertTrue(decoded.contains("'shared123' in parents"))
   assertTrue(decoded.contains("corpora=drive&driveId=shared123"))
   Reply(url,if("pageToken=" !in url)
    """{"nextPageToken":"second","files":[{"id":"folder1","name":"Books","mimeType":"application/vnd.google-apps.folder"}]}"""
   else """{"files":[{"id":"file1","name":"book.epub","mimeType":"application/epub+zip","size":"12"}]}"""
   ).also{requests.add(it)}
  }
  val result=client.list(source("shared123"),"")
  assertEquals(2,result.size);assertTrue(result[0].directory);assertEquals(12L,result[1].sizeBytes)
  assertTrue(requests.all{it.getRequestProperty("Authorization")=="Bearer LOGIN_TOKEN"})
  assertTrue(requests.none{it.url.toString().contains("MUST_NOT_USE")})
 }
 @Test fun nestedRangeResolvesProviderIdsAndUsesRangeHeader(){
  val requests=ArrayList<Reply>()
  val client=GoogleDriveAccess({"LOGIN_TOKEN"}){url->
   val decoded=URLDecoder.decode(url,"UTF-8")
   val reply=when{
    "alt=media" in url->Reply(url,"data",206,"bytes 5-8/20")
    "name = 'Books'" in decoded->Reply(url,"""{"files":[{"id":"folder1","name":"Books","mimeType":"application/vnd.google-apps.folder"}]}""")
    else->{
     assertTrue(decoded.contains("'folder1' in parents"))
     Reply(url,"""{"files":[{"id":"file1","name":"book.zip","mimeType":"application/zip","size":"20"}]}""")
    }
   }
   reply.also{requests.add(it)}
  }
  assertEquals("data",String(client.archiveRange(source(),"Books/book.zip",5,4)))
  assertEquals("bytes=5-8",requests.last().getRequestProperty("Range"))
 }
 @Test fun refusesFullBodyWhenServerIgnoresRange(){
  val client=GoogleDriveAccess({"LOGIN_TOKEN"}){url->
   if("alt=media" in url)Reply(url,"entire file")
   else Reply(url,"""{"files":[{"id":"file1","name":"book.zip","mimeType":"application/zip"}]}""")
  }
  assertThrows(IllegalArgumentException::class.java){client.archiveRange(source(),"book.zip",3,4)}
 }
 @Test fun refusesAmbiguousPathInsteadOfReadingWrongBook(){
  val client=GoogleDriveAccess({"LOGIN_TOKEN"}){url->
   Reply(url,"""{"files":[{"id":"one","name":"book.epub"},{"id":"two","name":"book.epub"}]}""")
  }
  assertThrows(IllegalArgumentException::class.java){client.archiveSize(source(),"book.epub")}
 }
 @Test fun reportsPermissionErrorWithoutLeakingCredentials(){
  val client=GoogleDriveAccess({"LOGIN_TOKEN"}){url->Reply(url,"secret",403)}
  val error=assertThrows(IllegalArgumentException::class.java){client.list(source(),"")}
  assertFalse(error.message.orEmpty().contains("secret"))
 }
 @Test fun boundedMetadataReadRejectsOversizedFileBeforeDownload(){
  var mediaCalled=false
  val client=GoogleDriveAccess({"LOGIN_TOKEN"}){url->
   when{
    "alt=media" in url->{mediaCalled=true;Reply(url,"unexpected")}
    "files/file1?" in url->Reply(url,"""{"id":"file1","size":"10000"}""")
    else->Reply(url,"""{"files":[{"id":"file1","name":"kavita.yaml","mimeType":"text/plain"}]}""")
   }
  }
  assertThrows(IllegalArgumentException::class.java){client.smallFile(source(),"kavita.yaml",128)}
  assertFalse(mediaCalled)
 }
 @Test fun createsYamlOnlyAfterFolderCapabilityAndExistingNameChecks(){
  var renamed=false
  val writes=ArrayList<Request>()
  val client=GoogleDriveAccess({"LOGIN_TOKEN"},{url->
   val decoded=URLDecoder.decode(url,"UTF-8")
   when{
    "/files/folder1?" in url->Reply(url,"""{"id":"folder1","mimeType":"application/vnd.google-apps.folder","capabilities":{"canAddChildren":true}}""")
    "name = 'Books'" in decoded->Reply(url,"""{"files":[{"id":"folder1","name":"Books","mimeType":"application/vnd.google-apps.folder"}]}""")
    "'folder1' in parents" in decoded->Reply(url,if(renamed)
     """{"files":[{"id":"created1","name":"kavita.yaml","mimeType":"application/yaml"}]}"""
     else """{"files":[]}""")
    else->error("Unexpected read: $decoded")
   }
  },OkHttpClient.Builder().addInterceptor{chain->
   val request=chain.request();writes.add(request)
   assertEquals("Bearer LOGIN_TOKEN",request.header("Authorization"))
   when(request.method){
    "POST"->{
     assertTrue(request.url.toString().contains("uploadType=multipart"))
     val buffer=Buffer();request.body!!.writeTo(buffer)
     assertTrue(buffer.readUtf8().contains("title: Test"))
     reply(request,"""{"id":"created1","name":"staged.yaml"}""")
    }
    "PATCH"->{renamed=true;reply(request,"""{"id":"created1","name":"kavita.yaml","parents":["folder1"]}""")}
    else->error("Unexpected write: ${request.method}")
   }
  }.build())
  assertTrue(client.createYamlOnly(writableSource(),"Books","title: Test".toByteArray()))
  assertEquals(listOf("POST","PATCH"),writes.map{it.method})
 }
 @Test fun existingManifestPreventsGoogleDriveUpload(){
  var wrote=false
  val client=GoogleDriveAccess({"LOGIN_TOKEN"},{url->
   val decoded=URLDecoder.decode(url,"UTF-8")
   when{
    "/files/folder1?" in url->Reply(url,"""{"id":"folder1","mimeType":"application/vnd.google-apps.folder","capabilities":{"canAddChildren":true}}""")
    "name = 'Books'" in decoded->Reply(url,"""{"files":[{"id":"folder1","name":"Books","mimeType":"application/vnd.google-apps.folder"}]}""")
    "'folder1' in parents" in decoded->Reply(url,"""{"files":[{"id":"existing","name":"kavita.yml"}]}""")
    else->error("Unexpected read: $decoded")
   }
  },OkHttpClient.Builder().addInterceptor{chain->
   wrote=true;reply(chain.request(),"{}")
  }.build())
  assertFalse(client.createYamlOnly(writableSource(),"Books","title: Test".toByteArray()))
  assertFalse(wrote)
 }
 @Test fun createsFolderAndMovesBookUsingDriveIdsAfterCapabilityChecks(){
  val writes=ArrayList<Request>()
  var sortedExists=false
  val client=GoogleDriveAccess({"LOGIN_TOKEN"},{url->
   val decoded=URLDecoder.decode(url,"UTF-8")
   when{
    "/files/folder1?" in url->Reply(url,"""{"id":"folder1","mimeType":"application/vnd.google-apps.folder","capabilities":{"canAddChildren":true,"canRemoveChildren":true}}""")
    "/files/folder2?" in url->Reply(url,"""{"id":"folder2","mimeType":"application/vnd.google-apps.folder","capabilities":{"canAddChildren":true}}""")
    "/files/file1?" in url->Reply(url,"""{"id":"file1","mimeType":"application/epub+zip","parents":["folder1"],"capabilities":{"canMoveItemWithinDrive":true}}""")
    "name = 'Books'" in decoded->Reply(url,"""{"files":[{"id":"folder1","name":"Books","mimeType":"application/vnd.google-apps.folder"}]}""")
    "name = 'Sorted'" in decoded->Reply(url,if(sortedExists)
     """{"files":[{"id":"folder2","name":"Sorted","mimeType":"application/vnd.google-apps.folder"}]}"""
     else """{"files":[]}""")
    "name = 'book.epub'" in decoded && "'folder1' in parents" in decoded->Reply(url,
     """{"files":[{"id":"file1","name":"book.epub","mimeType":"application/epub+zip"}]}""")
    "name = 'book.epub'" in decoded && "'folder2' in parents" in decoded->Reply(url,"""{"files":[]}""")
    else->error("Unexpected read: $decoded")
   }
  },OkHttpClient.Builder().addInterceptor{chain->
   val request=chain.request();writes.add(request)
   when(request.method){
    "POST"->{
     assertTrue(request.url.toString().contains("supportsAllDrives=true"))
     val buffer=Buffer();request.body!!.writeTo(buffer)
     assertTrue(buffer.readUtf8().contains("folder1"))
     sortedExists=true
     reply(request,"""{"id":"folder2","name":"Sorted","mimeType":"application/vnd.google-apps.folder"}""")
    }
    "PATCH"->{
     assertTrue(request.url.toString().contains("addParents=folder2"))
     assertTrue(request.url.toString().contains("removeParents=folder1"))
     reply(request,"""{"id":"file1","name":"book.epub","parents":["folder2"]}""")
    }
    else->error("Unexpected write: ${request.method}")
   }
  }.build())
  val source=writableSource()
  client.makeDirectory(source,"Books/Sorted")
  client.moveOnly(source,"Books/book.epub","Books/Sorted/book.epub")
  assertEquals(listOf("POST","PATCH"),writes.map{it.method})
 }
}
