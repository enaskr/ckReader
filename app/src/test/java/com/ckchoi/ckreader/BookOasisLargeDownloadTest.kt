package com.ckchoi.ckreader

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.nio.file.Files
import java.util.Collections

class BookOasisLargeDownloadTest {
 @Test fun resumesPartialAndRestartsWhenRangeIsIgnoredAndAcceptsMultiGbMetadata() {
  val socket=ServerSocket(0,20,InetAddress.getByName("127.0.0.1"))
  val requests=Collections.synchronizedMap(mutableMapOf<String,Map<String,String>>())
  val payload=ByteArray(8192){index->(index%237).toByte()}
  val thread=Thread {
   repeat(4){
    if(socket.isClosed)return@Thread
    try {
     socket.accept().use{client->
      client.soTimeout=8_000
      val lines=client.getInputStream().bufferedReader(Charsets.ISO_8859_1)
      val target=lines.readLine()?.split(' ')?.getOrNull(1).orEmpty()
      if(target.isBlank())return@use
      val headers=LinkedHashMap<String,String>()
      while(true) {
       val line=lines.readLine()?:break
       if(line.isBlank())break
       if(':' in line)headers[line.substringBefore(':').lowercase()]=line.substringAfter(':').trim()
      }
      requests[target]=headers
      val body:ByteArray
      val code:Int
      val extra:String
      when(target) {
       "/opds/download/general/101"->{
        code=206;body=payload.copyOfRange(2048,payload.size)
        extra="Content-Range: bytes 2048-8191/8192\r\n"
       }
       "/opds/download/general/102"->{
        code=200;body=payload
        extra="ETag: \"new-tag\"\r\n"
       }
       "/opds/download/general/103"->{
        code=200;body=payload.copyOf(1500)
        extra="ETag: \"large-tag\"\r\n"
       }
       else->{
        code=206;body=byteArrayOf(42)
        extra="Content-Range: bytes 0-0/2300000000\r\n"
       }
      }
      val length=if(target.endsWith("/103"))2_300_000_000L else body.size.toLong()
      val response="HTTP/1.1 "+code+" OK\r\nContent-Length: "+length+
       "\r\nContent-Type: application/octet-stream\r\n"+extra+"Connection: close\r\n\r\n"
      client.getOutputStream().use{out->
       out.write(response.toByteArray(Charsets.US_ASCII))
       out.write(body)
       out.flush()
      }
     }
    }catch(_:SocketException){if(socket.isClosed)return@Thread}
     catch(_:Exception){if(socket.isClosed)return@Thread}
   }
  }.apply{isDaemon=true;start()}
  val root=Files.createTempDirectory("ckreader-opds-large-").toFile()
  try {
   val source=LibrarySource("large-test","bookoasis","OPDS Test",
    "http://127.0.0.1:"+socket.localPort,"reader","secret")
   val client=BookOasisOpdsClient(source)
   val resumed=java.io.File(root,"resumed.part").apply{
    writeBytes(payload.copyOfRange(0,2048))
   }
   java.io.File(resumed.absolutePath+".if-range").writeText("\"correct-etag\"")
   var progress=0L
   client.download("101",resumed){size,total->progress=size;assertEquals(8192L,total)}
   assertArrayEquals(payload,resumed.readBytes())
   assertEquals(8192L,progress)
   assertEquals("bytes=2048-",requests["/opds/download/general/101"]?.get("range"))
   assertEquals("\"correct-etag\"",requests["/opds/download/general/101"]?.get("if-range"))

   val restarted=java.io.File(root,"restart.part").apply{writeBytes(ByteArray(2048){-1})}
   java.io.File(restarted.absolutePath+".if-range").writeText("\"old-etag\"")
   client.download("102",restarted)
   assertArrayEquals(payload,restarted.readBytes())
   assertFalse(java.io.File(restarted.absolutePath+".if-range").exists())

   val large=java.io.File(root,"oversize-declaration.part")
   val failure=runCatching{client.download("103",large)}.exceptionOrNull()
   assertNotNull("Truncated 2.3 GB transfer must fail (not be accepted as complete).",failure)
   assertTrue("Partial download should be preserved for another attempt",large.length()>0)
   assertEquals(1500L,large.length())
   assertTrue("Retain validator only for incomplete downloads",
    java.io.File(large.absolutePath+".if-range").exists())
   assertEquals(2_300_000_000L,client.rangedSize("104"))
   assertEquals("bytes=0-0",requests["/opds/download/general/104"]?.get("range"))
  }finally {
   socket.close()
   thread.join(1000)
   root.deleteRecursively()
  }
 }
}
