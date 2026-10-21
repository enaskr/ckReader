package com.ckchoi.yesss24

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.zip.ZipFile
import org.apache.commons.compress.archivers.zip.ZipFile as SeekZip

/** Bounded decoders for untrusted documents and comic-book ZIP/CBZ archives. */
internal object ReaderContentSupport {
 private val images=setOf("jpg","jpeg","png","webp","gif","bmp","avif")
 private const val MAX_TEXT_BYTES=32L*1024L*1024L
 // Individual high-resolution manga pages can exceed 32 MiB. Never allocate
 // one full page in the Java heap: bounds and decoded bitmap use separate streams.
 private const val MAX_ZIP_IMAGE_BYTES=256L*1024L*1024L
 private const val MAX_REMOTE_IMAGE_BYTES=96L*1024L*1024L
 private const val MAX_ZIP_ENTRIES=120_000
 private const val MAX_COMIC_PAGES=50_000

 fun isImage(path:String):Boolean=path.substringAfterLast('.',"").lowercase() in images

 fun pages(archive:ZipFile):List<String> {
  val candidates=ArrayList<String>()
  val entries=archive.entries()
  var count=0
  while(entries.hasMoreElements()) {
   val entry=entries.nextElement()
   if(++count>MAX_ZIP_ENTRIES)throw IllegalArgumentException("압축 파일에 항목이 너무 많습니다.")
   val name=entry.name.replace('\\','/')
   if(entry.isDirectory||name.startsWith('/')||name.startsWith("__MACOSX/")||
      name.split('/').any { it==".." || it=="." }||name.substringAfterLast('/').startsWith("._")||
      !isImage(name))continue
   if(entry.size>MAX_ZIP_IMAGE_BYTES)continue
   candidates.add(entry.name)
   if(candidates.size>MAX_COMIC_PAGES)throw IllegalArgumentException("이미지가 너무 많습니다 (최대 50,000개).")
  }
  return candidates.sortedWith(Comparator { a,b -> naturalCompare(a.replace('\\','/'),b.replace('\\','/')) })
 }

 fun pages(archive:SeekZip):List<String> {
  val found=ArrayList<String>()
  val entries=archive.entries
  var count=0
  while(entries.hasMoreElements()) {
   val item=entries.nextElement()
   if(++count>MAX_ZIP_ENTRIES)throw IllegalArgumentException("압축에 항목이 너무 많습니다.")
   val name=item.name.replace('\\','/')
   if(item.isDirectory||name.startsWith('/')||name.startsWith("__MACOSX/")||
      name.split('/').any{it=="."||it==".."}||name.substringAfterLast('/').startsWith("._")||
      !isImage(name)||item.size>MAX_ZIP_IMAGE_BYTES)continue
   found.add(item.name)
   require(found.size<=MAX_COMIC_PAGES){"이미지 페이지가 너무 많습니다 (최대 50,000장)."}
  }
  return found.sortedWith(Comparator{a,b->naturalCompare(a.replace('\\','/'),b.replace('\\','/'))})
 }
 // Compare numerical filename segments as numbers: page2.png before page10.png.
 internal fun naturalCompare(a:String,b:String):Int {
  var i=0;var j=0
  while(i<a.length&&j<b.length){
   val x=a[i];val y=b[j]
   if(x.isDigit()&&y.isDigit()){
    val ia=i;val jb=j
    while(i<a.length&&a[i].isDigit())i++
    while(j<b.length&&b[j].isDigit())j++
    val left=a.substring(ia,i).trimStart('0').ifEmpty { "0" }
    val right=b.substring(jb,j).trimStart('0').ifEmpty { "0" }
    val byNumber=left.length.compareTo(right.length).takeIf{it!=0} ?: left.compareTo(right)
    if(byNumber!=0)return byNumber
    val byPadding=(i-ia).compareTo(j-jb)
    if(byPadding!=0)return byPadding
   } else {
    val compare=x.lowercaseChar().compareTo(y.lowercaseChar())
    if(compare!=0)return compare
    i++;j++
   }
  }
  return a.length.compareTo(b.length)
 }

 private fun boundedBytes(input:InputStream,maximum:Long):ByteArray=input.use { stream->
  val output=ByteArrayOutputStream()
  val buffer=ByteArray(64*1024)
  var readBytes=0L
  while(true){
   val n=stream.read(buffer)
   if(n<0)break
   if(n==0)continue
   readBytes+=n
   require(readBytes<=maximum) { "파일 크기 제한을 초과했습니다." }
   output.write(buffer,0,n)
  }
  output.toByteArray()
 }

 fun readText(input:InputStream):String {
  val bytes=boundedBytes(input,MAX_TEXT_BYTES)
  require(bytes.isNotEmpty()) { "텍스트 파일이 비어 있습니다." }
  val result=when {
   bytes.size>=3&&bytes[0]==0xEF.toByte()&&bytes[1]==0xBB.toByte()&&bytes[2]==0xBF.toByte()->
    String(bytes,3,bytes.size-3,StandardCharsets.UTF_8)
   bytes.size>=2&&bytes[0]==0xFF.toByte()&&bytes[1]==0xFE.toByte()->
    String(bytes,2,bytes.size-2,StandardCharsets.UTF_16LE)
   bytes.size>=2&&bytes[0]==0xFE.toByte()&&bytes[1]==0xFF.toByte()->
    String(bytes,2,bytes.size-2,StandardCharsets.UTF_16BE)
   else -> {
    val nulEven=bytes.indices.count{it%2==0&&bytes[it]==0.toByte()}
    val nulOdd=bytes.indices.count{it%2==1&&bytes[it]==0.toByte()}
    if(bytes.size>=8&&nulOdd>bytes.size/6&&nulEven<bytes.size/32) {
     String(bytes,StandardCharsets.UTF_16LE)
    }else if(bytes.size>=8&&nulEven>bytes.size/6&&nulOdd<bytes.size/32) {
     String(bytes,StandardCharsets.UTF_16BE)
    }else {
     try {
      StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
       .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
     }catch(_:CharacterCodingException) {
      String(bytes,Charset.forName("MS949"))
     }
    }
   }
  }
  return result.replace("\r\n","\n").replace('\r','\n').replace("\u0000","")
 }

 fun textPages(text:String,chunkSize:Int=2400):List<String> {
  require(chunkSize in 200..10000)
  val body=text.trim()
  if(body.isEmpty())return listOf("")
  val result=ArrayList<String>()
  var start=0
  while(start<body.length){
   var end=(start+chunkSize).coerceAtMost(body.length)
   if(end<body.length){
    val newline=body.lastIndexOf('\n',end-1)
    val space=body.lastIndexOf(' ',end-1)
    val split=maxOf(newline,space)
    if(split>start+chunkSize/2)end=split+1
    else if(end>start && Character.isHighSurrogate(body[end-1]))end--
   }
   if(end<=start)end=(start+chunkSize).coerceAtMost(body.length)
   result.add(body.substring(start,end).trim())
   start=end
   require(result.size<=15000) { "텍스트 페이지가 너무 많습니다." }
  }
  return result
 }

 private class LimitedInput(input:InputStream,private val limit:Long):
  java.io.FilterInputStream(input) {
  private var count=0L
  override fun read():Int {
   val n=super.read()
   if(n!=-1) {
    count++
    require(count<=limit){"이미지 데이터 제한을 초과했습니다."}
   }
   return n
  }
  override fun read(buffer:ByteArray,offset:Int,length:Int):Int {
   // FilterInputStream.read(byte[],...) delegates to the underlying stream.
   val n=super.read(buffer,offset,length)
   if(n>0) {
    count+=n
    require(count<=limit){"이미지 데이터 제한을 초과했습니다."}
   }
   return n
  }
 }

 /** Decode a large ZIP page in two short-lived streams, never in a huge ByteArray. */
 fun imageBitmap(archive:SeekZip,path:String,maxWidth:Int,maxHeight:Int):Bitmap {
  val entry=archive.getEntry(path)?:error("압축 파일에 이미지가 없습니다.")
  require(entry.size<=MAX_ZIP_IMAGE_BYTES){"이미지가 너무 큽니다."}
  return imageBitmap({archive.getInputStream(entry)},maxWidth,maxHeight,MAX_ZIP_IMAGE_BYTES)
 }
 fun imageBitmap(archive:ZipFile,path:String,maxWidth:Int,maxHeight:Int):Bitmap {
  val entry=archive.getEntry(path) ?: error("압축 파일에서 이미지를 찾을 수 없습니다.")
  require(entry.size<=MAX_ZIP_IMAGE_BYTES){"이미지가 너무 큽니다."}
  return imageBitmap({archive.getInputStream(entry)},maxWidth,maxHeight,MAX_ZIP_IMAGE_BYTES)
 }

 /** A provider stream is reopenable (cached on disk); image decoding is O(bitmap). */
 fun imageBitmap(open:()->InputStream,maxWidth:Int,maxHeight:Int):Bitmap =
  imageBitmap(open,maxWidth,maxHeight,MAX_REMOTE_IMAGE_BYTES)

 private fun imageBitmap(open:()->InputStream,maxWidth:Int,maxHeight:Int,limit:Long):Bitmap {
  val info=BitmapFactory.Options().apply{inJustDecodeBounds=true}
  open().use {BitmapFactory.decodeStream(LimitedInput(it,limit),null,info)}
  require(info.outWidth in 1..50_000 && info.outHeight in 1..50_000) {
   "이미지 형식을 지원하지 않습니다."
  }
  val width=maxWidth.coerceIn(400,3200)
  val height=maxHeight.coerceIn(400,3600)
  var sample=1
  while(info.outWidth/sample>width*1.6 || info.outHeight/sample>height*1.6 ||
   (info.outWidth.toLong()/sample)*(info.outHeight.toLong()/sample)>8_000_000L)
   sample*=2
  val options=BitmapFactory.Options().apply {
   inSampleSize=sample
   inPreferredConfig=Bitmap.Config.RGB_565
  }
  return open().use{
   BitmapFactory.decodeStream(LimitedInput(it,limit),null,options)
  }?:error("이미지를 해독할 수 없습니다.")
 }
}
