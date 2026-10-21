package com.ckchoi.ckreader

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.nio.file.Files
import java.util.Base64
import java.util.Collections

/** Loopback OPDS contract: Basic authentication, navigation, search and acquisition. */
class BookOasisOpdsClientTest {
 @Test fun visitsFeedAndDownloadsThroughOpdsOnly() {
  val paths=Collections.synchronizedList(mutableListOf<String>())
  val server=ServerSocket(0,50,InetAddress.getByName("127.0.0.1"))
  val expected="Basic "+Base64.getEncoder().encodeToString("reader:password".toByteArray())
  val origin="http://127.0.0.1:${server.localPort}"
  val thread=Thread {
   while(!server.isClosed) {
    try {
     server.accept().use { socket ->
      socket.soTimeout=8_000
      val reader=socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
      val target=reader.readLine()?.split(' ')?.getOrNull(1).orEmpty()
      if(target.isEmpty())return@use
      val path=target.substringBefore('?')
      paths.add(path)
      val headers=mutableMapOf<String,String>()
      while(true) {
       val line=reader.readLine()?:break
       if(line.isEmpty())break
       if(':' in line)headers[line.substringBefore(':').lowercase()]=line.substringAfter(':').trim()
      }
      val authorized=headers["authorization"]==expected
      val xml=when {
       path=="/opds"->"""<feed xmlns="http://www.w3.org/2005/Atom">
        <entry><title>문학</title><id>urn:library:12</id>
         <link rel="subsection" href="$origin/opds/library/12"/></entry></feed>"""
       path=="/opds/library/12"->"""<feed xmlns="http://www.w3.org/2005/Atom">
        <entry><title>한글 시리즈</title><id>urn:series:12</id>
         <link rel="subsection" href="$origin/opds/series/12/%ED%95%9C%EA%B8%80%20%EC%8B%9C%EB%A6%AC%EC%A6%88"/></entry></feed>"""
       path.startsWith("/opds/series/12/")->"""<feed xmlns="http://www.w3.org/2005/Atom"
        xmlns:pse="http://vaemendis.net/opds-pse/ns">
        <entry><title>한글 1권</title><id>urn:general:book:198</id>
         <link rel="http://opds-spec.org/acquisition"
          href="$origin/opds/download/general/198?token=temporary" type="application/vnd.comicbook+zip"/>
         <link rel="http://vaemendis.net/opds-pse/stream"
          href="$origin/opds/pse/general/198/{pageNumber}" pse:count="17"/></entry></feed>"""
       path=="/opds/search"->"""<feed xmlns="http://www.w3.org/2005/Atom">
        <entry><title>[PDF] 검색 결과</title>
         <link rel="http://opds-spec.org/acquisition"
          href="$origin/opds/download/general/220" type="application/pdf"/></entry></feed>"""
       path=="/opds/download/general/198"->"OPDS-book-payload"
       else ->"Unknown"
      }
      val valid=path in setOf("/opds","/opds/library/12","/opds/search",
       "/opds/download/general/198","/opds/pse/general/198/1") ||
       path.startsWith("/opds/series/12/")
      val status=when {
       !authorized->401
       valid->200
       else->404
      }
      val body=if(status==200) {
       if(path=="/opds/pse/general/198/1")byteArrayOf(-1,-40,-1,-32,0,16,74,70)
       else xml.toByteArray(Charsets.UTF_8)
      }else byteArrayOf()
      val mime=when {
       path.startsWith("/opds/pse/")->"image/jpeg"
       path.startsWith("/opds/download/")->"application/octet-stream"
       else->"application/atom+xml"
      }
      val response="HTTP/1.1 $status TEST\r\nContent-Type: $mime\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
      socket.getOutputStream().use {out->
       out.write(response.toByteArray(Charsets.US_ASCII))
       out.write(body)
       out.flush()
      }
     }
    }catch(_:SocketException) {
     if(server.isClosed)break
    }catch(_:Exception) {
     if(server.isClosed)break
    }
   }
  }.apply{isDaemon=true;start()}
  try {
   val source=LibrarySource("test","bookoasis","북오아시스",
    origin,"reader","password")
   val client=BookOasisOpdsClient(source)
   client.verify()
   val root=client.rootFeed()
   assertEquals("12",client.libraryId(root.entries.single()))
   val category=client.categoryFeed("12")
   assertEquals("12",client.seriesLibraryId(category.entries.single()))
   val volumes=client.allSeriesBooks("12","한글 시리즈")
   assertEquals("198",client.bookId(volumes.single()))
   assertEquals("cbz",client.bookFormat(volumes.single()))
   assertEquals(17,client.pageCount("198"))
   assertFalse(client.pageAvailable("198",0))
   assertTrue(client.pageAvailable("198",1))
   val search=client.searchFeed("한글",1)
   assertEquals("pdf",client.bookFormat(search.entries.single()))
   val target=Files.createTempFile("ckreader-opds-test",".cbz").toFile()
   try {
    client.download("198",target)
    assertEquals("OPDS-book-payload",target.readText())
   }finally{target.delete()}
   assertTrue(paths.none{it.startsWith("/api/") || it.startsWith("/login")})
   assertTrue(paths.contains("/opds/download/general/198"))
  }finally{
   server.close()
   thread.join(1000)
  }
 }
}
