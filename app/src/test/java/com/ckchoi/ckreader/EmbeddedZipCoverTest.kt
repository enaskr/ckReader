package com.ckchoi.ckreader

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random

/** Uses real ZIP bytes and fake remote ranges; no Android runtime required. */
class EmbeddedZipCoverTest {
 private fun image(name:String)=("FAKE_IMAGE_"+name+"_").repeat(7).toByteArray()
 private fun zip(vararg files:Pair<String,ByteArray>):ByteArray {
  val out=ByteArrayOutputStream()
  ZipOutputStream(out).use {writer ->
   for((name,content) in files) {
    writer.putNextEntry(ZipEntry(name))
    writer.write(content)
    writer.closeEntry()
   }
  }
  return out.toByteArray()
 }
 private fun extract(data:ByteArray,format:String="epub"):ByteArray? =
  EmbeddedZipCover.extract(data.size.toLong(),format) {offset,count ->
   data.copyOfRange(offset.toInt(),offset.toInt()+count)
  }
 @Test fun epub3DeclaredCoverBeatsFirstIllustration() {
  val container="""<?xml version="1.0"?>
   <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
    <rootfiles><rootfile full-path="OEBPS/package.opf"/></rootfiles>
   </container>""".trimIndent()
  val opf="""<?xml version="1.0"?>
   <package xmlns="http://www.idpf.org/2007/opf">
    <metadata/><manifest>
     <item id="illustration" media-type="image/jpeg" href="assets/aaa.jpg"/>
     <item id="cover" media-type="image/jpeg" href="assets/artwork.jpeg" properties="cover-image"/>
    </manifest>
   </package>""".trimIndent()
  val first=image("first")
  val cover=image("correct")
  val data=zip("META-INF/container.xml" to container.toByteArray(),
   "OEBPS/package.opf" to opf.toByteArray(),
   "OEBPS/assets/aaa.jpg" to first,
   "OEBPS/assets/artwork.jpeg" to cover)
  assertArrayEquals(cover,extract(data))
 }
 @Test fun epub2LegacyMetaCoverAndRelativePath() {
  val container="""<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
   <rootfiles><rootfile full-path="OEBPS/OPS/content.opf"/></rootfiles></container>"""
  val opf="""<package xmlns="http://www.idpf.org/2007/opf">
   <metadata><meta name="cover" content="book-cover"/></metadata>
   <manifest>
     <item id="chap" href="Images/a.jpg" media-type="image/jpeg"/>
     <item id="book-cover" href="../Images/image01.png" media-type="image/png"/>
   </manifest></package>"""
  val cover=image("legacy")
  val data=zip("META-INF/container.xml" to container.toByteArray(),
   "OEBPS/OPS/content.opf" to opf.toByteArray(),
   "OEBPS/OPS/Images/a.jpg" to image("random"),
   "OEBPS/Images/image01.png" to cover)
  assertArrayEquals(cover,extract(data))
 }
 @Test fun epub2CoverGuideXhtmlPointsToCorrectPicture() {
  val container="""<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
   <rootfiles><rootfile full-path="OPS/book.opf"/></rootfiles></container>"""
  val opf="""<package xmlns="http://www.idpf.org/2007/opf">
   <manifest>
    <item id="illustration" href="images/a.jpg" media-type="image/jpeg"/>
    <item id="wrapper" href="cover.xhtml" media-type="application/xhtml+xml"/>
    <item id="illustration2" href="images/z.jpg" media-type="image/jpeg"/>
   </manifest><guide><reference type="cover" href="cover.xhtml"/></guide></package>"""
  val html="""<html xmlns="http://www.w3.org/1999/xhtml"><body>
   <img src="images/z.jpg" alt="Cover"/></body></html>"""
  val cover=image("guide")
  val data=zip("META-INF/container.xml" to container.toByteArray(),
   "OPS/book.opf" to opf.toByteArray(),"OPS/cover.xhtml" to html.toByteArray(),
   "OPS/images/a.jpg" to image("random"),"OPS/images/z.jpg" to cover)
  assertArrayEquals(cover,extract(data))
 }
 @Test fun epub3CoverXhtmlManifestWithoutGuideFindsRealCover() {
  val container="""<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
    <rootfiles><rootfile full-path="EPUB/book.opf"/></rootfiles></container>"""
  val opf="""<package xmlns="http://www.idpf.org/2007/opf">
    <manifest>
      <item id="illustration" href="images/aaa.jpg" media-type="image/jpeg"/>
      <item id="cover_page" href="cover.xhtml" media-type="application/xhtml+xml"/>
      <item id="front" href="images/artwork.gif" media-type="image/gif"/>
    </manifest></package>"""
  val html="""<html xmlns="http://www.w3.org/1999/xhtml">
    <body><img src="images/artwork.gif" /></body></html>"""
  val expected=image("correct-epub3")
  val data=zip("META-INF/container.xml" to container.toByteArray(),
   "EPUB/book.opf" to opf.toByteArray(),"EPUB/cover.xhtml" to html.toByteArray(),
   "EPUB/images/aaa.jpg" to image("wrong-image"),
   "EPUB/images/artwork.gif" to expected)
  assertArrayEquals(expected,extract(data))
 }
 @Test fun epubWithoutManifestChoosesLikelyCover() {
  val data=zip("OEBPS/assets/img001.jpg" to image("random"),
   "OEBPS/assets/front.jpg" to image("front"))
  assertArrayEquals(image("front"),extract(data))
 }
 @Test fun cbzUsesFirstNaturalPageAndSkipsUnsafeNames() {
  val data=zip("../0.jpg" to image("unsafe"),
   "__MACOSX/000.jpg" to image("mac"),
   "chapter/page10.jpg" to image("10"),
   "chapter/page2.jpg" to image("2"),
   "chapter/page01.png" to image("1"))
  assertArrayEquals(image("1"),extract(data,"cbz"))
  assertEquals(-1,EmbeddedZipCover.naturalCompare("page2.jpg","page10.jpg").coerceIn(-1,1))
 }
 @Test fun noImagesReturnsNull() {
  val data=zip("book.txt" to "Hello World".toByteArray())
  assertNull(extract(data,"epub"))
  assertNull(extract(data,"cbz"))
 }
 @Test fun nonSequentialRangeReadingDoesNotDownloadLargeComic() {
  val imageData=image("cover")
  val filler=Random(123).nextBytes(2_000_000)
  val data=zip("001-cover.jpg" to imageData,"999.dat" to filler)
  var bytesTransferred=0L
  val extracted=EmbeddedZipCover.extract(data.size.toLong(),"cbz") {offset,count ->
   bytesTransferred+=count
   data.copyOfRange(offset.toInt(),offset.toInt()+count)
  }
  assertArrayEquals(imageData,extracted)
  assertTrue("Whole book was transferred",bytesTransferred<data.size/2)
 }
}
