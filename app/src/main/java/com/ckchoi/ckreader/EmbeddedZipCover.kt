package com.ckchoi.ckreader

import org.apache.commons.compress.archivers.zip.ZipFile as SeekZip
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.channels.NonWritableChannelException
import java.nio.channels.SeekableByteChannel
import java.util.Locale
import java.util.zip.ZipException
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/** Bounded random-access ZIP/CBZ/EPUB cover reader; never downloads the whole archive. */
internal object EmbeddedZipCover {
 private const val MAX_IMAGE = 5_000_000
 private const val MAX_OPF = 1_000_000
 private const val MAX_ENTRIES = 50_000
 private val imageExtensions=setOf("jpg","jpeg","png","webp","bmp","gif","avif")

 /** HTTP/FTP/rclone range fetches are injected for local regression tests. */
 fun extract(length:Long,format:String,fetch:(Long,Int)->ByteArray):ByteArray? {
  require(format.lowercase(Locale.ROOT) in setOf("epub","cbz","zip"))
  require(length in 22..10_000_000_000L)
  RangeChannel(length,fetch).use { channel ->
   SeekZip(channel).use { zip ->
    val entries=linkedMapOf<String,ZipArchiveEntry>()
    val iterator=zip.entries
    var seen=0
    while(iterator.hasMoreElements()) {
     if(++seen>MAX_ENTRIES)error("ZIP 파일 항목이 너무 많습니다.")
     val item=iterator.nextElement()
     val name=item.name.replace('\\','/')
     if(item.isDirectory || !safe(name))continue
     entries[name]=item
    }
    val candidates=when(format.lowercase(Locale.ROOT)) {
     "epub"->epubCandidates(zip,entries)
     else->entries.keys.filter(::isImage).sortedWith(Comparator {a,b->naturalCompare(a,b)})
    }
    for(name in candidates.distinct().take(16)) {
     val item=entries[name]?:continue
     if(item.size !in 1..MAX_IMAGE.toLong())continue
     val bytes=runCatching {zip.getInputStream(item).use {readBounded(it,MAX_IMAGE)} }.getOrNull()
     if(bytes!=null && bytes.size>=24)return bytes
    }
    return null
   }
  }
 }

 private fun safe(name:String)=name.isNotBlank()&&name.length<=2048 &&
  !name.startsWith('/')&&!name.startsWith("__MACOSX/")&&
  name.split('/').none{it.isBlank()||it=="."||it==".."||it.startsWith("._")}

 private fun isImage(name:String):Boolean =
  name.substringAfterLast('.', "").lowercase(Locale.ROOT) in imageExtensions

 private fun readBounded(stream:InputStream,max:Int):ByteArray {
  val out=ByteArrayOutputStream(minOf(max,16_384))
  val buffer=ByteArray(16_384)
  while(true) {
   val n=stream.read(buffer)
   if(n<0)break
   if(n==0)continue
   require(out.size()+n<=max){"압축 내부 파일이 너무 큽니다."}
   out.write(buffer,0,n)
  }
  return out.toByteArray()
 }

 private fun xml(bytes:ByteArray):Document {
  // Fail closed for entity declarations. Some Android XML parsers don't
  // implement the optional JAXP ACCESS_EXTERNAL_* attributes.
  val body=String(bytes,Charsets.UTF_8)
  require(!body.contains("<!DOCTYPE",true) && !body.contains("<!ENTITY",true))
  val factory=DocumentBuilderFactory.newInstance().apply {
   isNamespaceAware=true
   isExpandEntityReferences=false
   runCatching{isXIncludeAware=false}
   runCatching{setFeature(XMLConstants.FEATURE_SECURE_PROCESSING,true)}
   runCatching{setFeature("http://apache.org/xml/features/disallow-doctype-decl",true)}
   runCatching{setFeature("http://xml.org/sax/features/external-general-entities",false)}
   runCatching{setFeature("http://xml.org/sax/features/external-parameter-entities",false)}
   runCatching{setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd",false)}
   runCatching{setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD","")}
   runCatching{setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema","")}
  }
  return factory.newDocumentBuilder().apply {
   setEntityResolver { _,_ -> org.xml.sax.InputSource(java.io.StringReader("")) }
  }.parse(ByteArrayInputStream(bytes))
 }

 private fun elements(doc:Document,localName:String):List<Element> {
  val nodes=doc.getElementsByTagNameNS("*",localName)
  return (0 until nodes.length).mapNotNull {nodes.item(it) as? Element}
 }

 private fun resolve(base:String,relative:String):String? {
  val raw=relative.substringBefore('#').substringBefore('?')
  if(raw.isBlank()||raw.startsWith('/')||raw.contains('\\')||raw.length>2000)return null
  // EPUB hrefs may URL-encode spaces; URLDecoder is only used on the path.
  val decoded=runCatching {
   URLDecoder.decode(raw.replace("+","%2B"),"UTF-8")
  }.getOrNull()?:return null
  if(decoded.startsWith('/')||decoded.contains('\\'))return null
  val components=base.substringBeforeLast('/',"").split('/').filter{it.isNotEmpty()}.toMutableList()
  for(part in decoded.split('/')) {
   when(part) {
    "","."->{}
    ".."->if(components.isEmpty())return null else components.removeAt(components.lastIndex)
    else -> components.add(part)
   }
  }
  val result=components.joinToString("/")
  return result.takeIf{safe(it)}
 }

 private fun epubCandidates(zip:SeekZip,entries:Map<String,ZipArchiveEntry>):List<String> {
  val chosen=mutableListOf<String>()
  val container=entries["META-INF/container.xml"]
  if(container!=null && container.size in 1..MAX_OPF.toLong()) {
   val document=runCatching{
    xml(zip.getInputStream(container).use{readBounded(it,MAX_OPF)})
   }.getOrNull()
   val opf= document?.let{elements(it,"rootfile").firstOrNull()?.getAttribute("full-path")}
   if(!opf.isNullOrBlank() && safe(opf)) {
    val content=entries[opf]
    if(content!=null && content.size in 1..MAX_OPF.toLong()) {
     val packageDoc=runCatching{
      xml(zip.getInputStream(content).use{readBounded(it,MAX_OPF)})
     }.getOrNull()
     if(packageDoc!=null) {
      val imageItems=elements(packageDoc,"item")
       .filter{it.getAttribute("media-type").lowercase(Locale.ROOT).startsWith("image/")}
      val byId=imageItems.associateBy{it.getAttribute("id")}
      val coverId=elements(packageDoc,"meta").firstOrNull {
       it.getAttribute("name").equals("cover",true)
      }?.getAttribute("content")
      val declared=mutableListOf<Element>()
      declared+=imageItems.filter { e ->
       e.getAttribute("properties").split(Regex("\\s+")).contains("cover-image")
      }
      if(!coverId.isNullOrBlank())byId[coverId]?.let(declared::add)
      declared+=imageItems.filter {
       val href=it.getAttribute("href").lowercase(Locale.ROOT)
       "cover" in href || "front" in href
      }
      for(item in declared) {
       val path=resolve(opf,item.getAttribute("href"))?:continue
       if(isImage(path) && path in entries)chosen+=path
      }
      // EPUB2 sometimes points its cover guide at an XHTML wrapper instead
      // of an image manifest entry. Read only that bounded XHTML file, then
      // resolve its <img> or SVG <image> reference within the same ZIP.
      val guide=elements(packageDoc,"reference").firstOrNull {
       it.getAttribute("type").split(' ').contains("cover")
      }
      val wrappers=mutableListOf<String>()
      guide?.getAttribute("href")?.let{resolve(opf,it)}?.let(wrappers::add)
      wrappers+=elements(packageDoc,"item").filter {
       val href=it.getAttribute("href").lowercase(Locale.ROOT)
       val id=it.getAttribute("id").lowercase(Locale.ROOT)
       (("cover" in href)||("cover" in id)||("front" in href))&&
        (it.getAttribute("media-type").contains("xhtml",true)||
         href.endsWith(".xhtml")||href.endsWith(".html"))
      }.mapNotNull{resolve(opf,it.getAttribute("href"))}
      // EPUB3 cover.xhtml commonly has no EPUB2 <guide>, so inspect the
      // manifest's likely cover XHTML wrapper as well.
      for(wrapperPath in wrappers.distinct().take(8)) {
       val wrapper=entries[wrapperPath]?:continue
       if(wrapper.size !in 1..MAX_OPF.toLong())continue
       val html=runCatching {
        String(zip.getInputStream(wrapper).use{readBounded(it,MAX_OPF)},Charsets.UTF_8)
       }.getOrNull().orEmpty()
       val imageRef=Regex("""(?i)<(?:[\w.-]+:)?(?:img|image)\b[^>]{0,800}?\b(?:src|href|xlink:href)\s*=\s*["']([^"']{1,512})["']""")
        .find(html)?.groupValues?.getOrNull(1)
       val linked=imageRef?.let{resolve(wrapperPath,it)}
       if(linked!=null&&isImage(linked)&&linked in entries)chosen+=linked
      }
     }
    }
   }
  }
  // EPUBs without proper OPF declarations: prefer a likely cover, not an
  // arbitrary chapter illustration; then the first supported image.
  val images=entries.keys.filter(::isImage).sortedWith(Comparator {a,b->naturalCompare(a,b)})
  chosen+=images.filter {
   val base=it.substringAfterLast('/').lowercase(Locale.ROOT)
   "cover" in base || "front" in base
  }
  chosen+=images
  return chosen.distinct()
 }

 /** Numeric image filenames are ordered naturally: page2 before page10. */
 internal fun naturalCompare(a:String,b:String):Int {
  var x=0;var y=0
  while(x<a.length&&y<b.length) {
   val chA=a[x];val chB=b[y]
   if(chA in '0'..'9'&&chB in '0'..'9') {
    val startA=x;val startB=y
    while(x<a.length&&a[x] in '0'..'9')x++
    while(y<b.length&&b[y] in '0'..'9')y++
    val numA=a.substring(startA,x).trimStart('0').ifBlank{"0"}
    val numB=b.substring(startB,y).trimStart('0').ifBlank{"0"}
    val cmp=numA.length.compareTo(numB.length).takeIf{it!=0}?:numA.compareTo(numB)
    if(cmp!=0)return cmp
    val pad=(x-startA).compareTo(y-startB)
    if(pad!=0)return pad
   } else {
    val cmp=chA.lowercaseChar().compareTo(chB.lowercaseChar())
    if(cmp!=0)return cmp
    x++;y++
   }
  }
  return (a.length-x).compareTo(b.length-y)
 }

 private class RangeChannel(length:Long,fetch:(Long,Int)->ByteArray):SeekableByteChannel {
  private val blocks=BufferedRangeReader(length,fetch,blockSize=128*1024,cacheBlocks=12)
  private var open=true
  private var offset=0L
  override fun isOpen()=open
  override fun close(){open=false;blocks.clear()}
  override fun size():Long=blocks.length
  override fun position():Long=offset
  override fun position(newPosition:Long):SeekableByteChannel {
   check(open);require(newPosition>=0)
   offset=newPosition
   return this
  }
  override fun read(dst:ByteBuffer):Int {
   check(open)
   if(offset>=size())return -1
   if(!dst.hasRemaining())return 0
   val buffer=ByteArray(minOf(65536L,dst.remaining().toLong(),size()-offset).toInt())
   val received=blocks.read(offset,buffer.size,buffer)
   if(received==0)return -1
   dst.put(buffer,0,received)
   offset+=received
   return received
  }
  override fun write(src:ByteBuffer):Int=throw NonWritableChannelException()
  override fun truncate(size:Long):SeekableByteChannel=throw NonWritableChannelException()
 }
}
