package com.ckchoi.yesss24

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class ReaderContentSupportTest {
 @Test fun naturalSortOrdersNumbersInPaths() {
  assertTrue(ReaderContentSupport.naturalCompare("chapter2/page2.jpg","chapter2/page10.jpg")<0)
  assertTrue(ReaderContentSupport.naturalCompare("chapter2/page10.jpg","chapter10/page1.jpg")<0)
  assertTrue(ReaderContentSupport.naturalCompare("Page009.PNG","page10.png")<0)
 }

 @Test fun textDecodersHandleKoreanAndBom() {
  val content="첫 문장입니다.\nSecond line."
  assertEquals(content,ReaderContentSupport.readText(ByteArrayInputStream(content.toByteArray(StandardCharsets.UTF_8))))
  assertEquals(content,ReaderContentSupport.readText(ByteArrayInputStream(content.toByteArray(Charset.forName("MS949")))))
  val utf16=byteArrayOf(0xff.toByte(),0xfe.toByte())+content.toByteArray(StandardCharsets.UTF_16LE)
  assertEquals(content,ReaderContentSupport.readText(ByteArrayInputStream(utf16)))
 }

 @Test fun textPagesKeepCharactersAndOrder() {
  val text="abc ".repeat(600)+"📖"+"끝".repeat(300)
  val pages=ReaderContentSupport.textPages(text,400)
  assertTrue(pages.size>2)
  assertFalse(pages.any { it.lastOrNull()?.isHighSurrogate()==true })
  assertEquals(text.replace(" ",""),pages.joinToString("").replace(" ",""))
 }

 @Test fun seekableZipIndexesPagesWithoutExtractingArchive() {
  val path=File.createTempFile("comic-seek-", ".cbz")
  try {
   ZipOutputStream(path.outputStream()).use {zip->
    for(name in listOf("images/20.jpg","images/3.jpg","images/1.png","../unsafe.jpg")) {
     zip.putNextEntry(ZipEntry(name))
     zip.write(ByteArray(100){42})
     zip.closeEntry()
    }
   }
   java.nio.channels.FileChannel.open(path.toPath()).use {channel->
    org.apache.commons.compress.archivers.zip.ZipFile(channel).use{archive->
     assertEquals(listOf("images/1.png","images/3.jpg","images/20.jpg"),
      ReaderContentSupport.pages(archive))
    }
   }
  }finally{path.delete()}
 }
 /** A sparse archive with its central directory beyond 2 GiB. No 2+GB RAM or I/O. */
 @Test fun largeSparseCbzSupportsCentralDirectoryBeyondIntMax() {
  val file=File.createTempFile("comic-over-2gb-",".cbz")
  try {
   java.io.RandomAccessFile(file,"rw").use{out->
    fun u16(value:Int) {out.write(value and 255);out.write((value ushr 8) and 255)}
    fun u32(value:Long) {for(i in 0..3)out.write(((value ushr (8*i)) and 255).toInt())}
    fun local(name:String,bytes:Long,crc:Long):Long {
     val position=out.filePointer
     val path=name.toByteArray(Charsets.US_ASCII)
     u32(0x04034b50);u16(20);u16(0);u16(0);u16(0);u16(0)
     u32(crc);u32(bytes);u32(bytes);u16(path.size);u16(0);out.write(path)
     return position
    }
    val padding=2_300_000_000L
    val header0=local("padding.bin",padding,0)
    out.seek(out.filePointer+padding)
    val pageBytes=byteArrayOf(1,2,3,4,5,6,7,8,9,10)
    val crc=java.util.zip.CRC32().apply{update(pageBytes)}.value
    val header1=local("pages/0001.jpg",pageBytes.size.toLong(),crc)
    out.write(pageBytes)
    // A formerly skipped >32 MiB per-page image, represented sparsely.
    val highResSize=48L*1024*1024
    val header2=local("pages/0002.png",highResSize,0)
    out.seek(out.filePointer+highResSize)
    val central=out.filePointer
    fun centralEntry(name:String,bytes:Long,crc32:Long,offset:Long) {
     val path=name.toByteArray(Charsets.US_ASCII)
     u32(0x02014b50);u16(20);u16(20);u16(0);u16(0);u16(0);u16(0)
     u32(crc32);u32(bytes);u32(bytes);u16(path.size);u16(0);u16(0)
     u16(0);u16(0);u32(0);u32(offset);out.write(path)
    }
    centralEntry("padding.bin",padding,0,header0)
    centralEntry("pages/0001.jpg",pageBytes.size.toLong(),crc,header1)
    centralEntry("pages/0002.png",highResSize,0,header2)
    val dirSize=out.filePointer-central
    u32(0x06054b50);u16(0);u16(0);u16(3);u16(3)
    u32(dirSize);u32(central);u16(0)
   }
   assertTrue(file.length()>Int.MAX_VALUE.toLong())
   ZipFile(file).use{archive->
    assertEquals(listOf("pages/0001.jpg","pages/0002.png"),ReaderContentSupport.pages(archive))
    assertEquals(10,archive.getInputStream(archive.getEntry("pages/0001.jpg")).use{it.readBytes().size})
   }
   java.nio.channels.FileChannel.open(file.toPath()).use{channel->
    org.apache.commons.compress.archivers.zip.ZipFile(channel).use{archive->
     assertEquals(listOf("pages/0001.jpg","pages/0002.png"),ReaderContentSupport.pages(archive))
     assertEquals(10,archive.getInputStream(archive.getEntry("pages/0001.jpg")).use{it.readBytes().size})
    }
   }
  }finally {file.delete()}
 }

 /**
  * The reported 89.2 MB GDS CBZ must be opened from the local file's ZIP
  * central directory. A sparse non-image payload makes the actual file about
  * 89.2 MB without writing/allocating all those bytes during the test.
  */
 @Test fun reported89MbGdsCbzOpensFromLocalZipWithoutRemoteRangeCalls() {
  val file=File.createTempFile("gds-89mb-kageki-",".cbz")
  try {
   java.io.RandomAccessFile(file,"rw").use {out->
    fun u16(n:Int) {out.write(n and 255);out.write((n ushr 8) and 255)}
    fun u32(n:Long) {for(i in 0..3)out.write(((n ushr (8*i)) and 255).toInt())}
    fun local(name:String,size:Long,crc:Long):Long {
     val start=out.filePointer
     val filename=name.toByteArray(Charsets.UTF_8)
     u32(0x04034b50);u16(20);u16(0x800);u16(0);u16(0);u16(0)
     u32(crc);u32(size);u32(size);u16(filename.size);u16(0)
     out.write(filename)
     return start
    }
    fun central(name:String,size:Long,crc:Long,offset:Long) {
     val filename=name.toByteArray(Charsets.UTF_8)
     u32(0x02014b50);u16(20);u16(20);u16(0x800);u16(0);u16(0);u16(0)
     u32(crc);u32(size);u32(size);u16(filename.size);u16(0);u16(0)
     u16(0);u16(0);u32(0);u32(offset);out.write(filename)
    }
    val ignoredSize=89_200_000L
    val ignoredOffset=local("padding.bin",ignoredSize,0)
    out.seek(out.filePointer+ignoredSize)
    val page=byteArrayOf(0xff.toByte(),0xd8.toByte(),1,2,3,4,5,6,0xff.toByte(),0xd9.toByte())
    val crc=java.util.zip.CRC32().apply{update(page)}.value
    val imageOffset=local("01_표지.jpg",page.size.toLong(),crc)
    out.write(page)
    val centralOffset=out.filePointer
    central("padding.bin",ignoredSize,0,ignoredOffset)
    central("01_표지.jpg",page.size.toLong(),crc,imageOffset)
    val centralSize=out.filePointer-centralOffset
    u32(0x06054b50);u16(0);u16(0);u16(2);u16(2)
    u32(centralSize);u32(centralOffset);u16(0)
   }
   assertTrue(file.length() in 89_200_000L..89_201_000L)
   ZipFile(file).use {archive->
    assertEquals(listOf("01_표지.jpg"),ReaderContentSupport.pages(archive))
    assertEquals(10,archive.getInputStream(archive.getEntry("01_표지.jpg")).use {it.readBytes().size})
   }
  }finally{file.delete()}
 }

 @Test fun zipImagesSortedAndUnsafePathsSkipped() {
  val file=File.createTempFile("comic-test-",".cbz")
  try {
   ZipOutputStream(file.outputStream()).use { archive ->
    for(name in listOf("pages/10.png","pages/2.jpg","pages/1.webp","notes.txt",
      "../escaping.png","/absolute.jpg","__MACOSX/thumbnail.jpg")) {
     archive.putNextEntry(ZipEntry(name))
     archive.write(byteArrayOf(1,2,3))
     archive.closeEntry()
    }
   }
   ZipFile(file).use { archive ->
    val names=ReaderContentSupport.pages(archive)
    assertEquals(listOf("pages/1.webp","pages/2.jpg","pages/10.png"),names)
   }
  }finally {file.delete()}
 }
}
