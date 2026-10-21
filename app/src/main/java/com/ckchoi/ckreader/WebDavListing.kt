package com.ckchoi.ckreader

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.w3c.dom.Element
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import java.io.Reader
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/** DAV XML is parsed on Android as well as the JVM; their supported features differ. */
internal object WebDavListing {
 data class Entry(val name:String,val directory:Boolean,val sizeBytes:Long)
 private const val DAV="DAV:"
 private const val MAX_CHARS=8*1024*1024

 fun url(base:String,path:String,directory:Boolean=false):String {
  val builder=base.trim().toHttpUrl().newBuilder()
  while(builder.build().pathSegments.size>1&&builder.build().pathSegments.last().isEmpty())
   builder.removePathSegment(builder.build().pathSegments.lastIndex)
  path.split('/').filter{it.isNotEmpty()}.forEach(builder::addPathSegment)
  if(directory&&builder.build().pathSegments.last().isNotEmpty())builder.addPathSegment("")
  val url=builder.build()
  // Some DAV servers apply form decoding to paths; encode a literal plus so
  // it cannot become a space. Leave query encoding untouched.
  return url.newBuilder().encodedPath(url.encodedPath.replace("+","%2B").replace("[","%5B").replace("]","%5D")).build().toString()
 }

 fun parse(reader:Reader,endpoint:String):List<Entry> {
  val text=StringBuilder()
  val buffer=CharArray(8192)
  while(true) {
   val n=reader.read(buffer)
   if(n<0)break
   require(text.length+n<=MAX_CHARS){"WebDAV 목록 응답이 너무 큽니다."}
   text.append(buffer,0,n)
  }
  return parse(text.toString(),endpoint)
 }

 fun parse(xml:String,endpoint:String):List<Entry> {
  require(xml.length<=MAX_CHARS){"WebDAV 목록 응답이 너무 큽니다."}
  // The parser receives this exact decoded character stream, so neither an
  // encoding declaration nor unsupported factory options can bypass the ban.
  require(!xml.contains("<!DOCTYPE",true)&&!xml.contains("<!ENTITY",true)) {
   "WebDAV 목록에 허용되지 않는 XML 선언이 있습니다."
  }
  val factory=DocumentBuilderFactory.newInstance().apply {
   isNamespaceAware=true
   // Android's Harmony parser rejects all four optional features. Blocking
   // DTDs above and external entity resolution below is mandatory on every OS.
   listOf("http://apache.org/xml/features/disallow-doctype-decl" to true,
    "http://xml.org/sax/features/external-general-entities" to false,
    "http://xml.org/sax/features/external-parameter-entities" to false,
    XMLConstants.FEATURE_SECURE_PROCESSING to true).forEach{(name,value)->
     runCatching{setFeature(name,value)}
    }
   isExpandEntityReferences=false
  }
  val parser=factory.newDocumentBuilder().apply {
   setEntityResolver{_,_->throw SAXException("External entities are forbidden")}
  }
  val doc=parser.parse(InputSource(StringReader(xml)))
  require(doc.documentElement.localName=="multistatus"&&doc.documentElement.namespaceURI==DAV) {
   "WebDAV 폴더 목록이 아닌 응답입니다. 서버 주소를 확인해 주세요."
  }
  val base=endpoint.toHttpUrl()
  val parent=base.pathSegments.dropLastWhile{it.isEmpty()}
  val found=linkedMapOf<String,Entry>()
  for(node in doc.documentElement.children("response")) {
   val responseStatus=node.children("status").firstOrNull()?.textContent
   if(responseStatus!=null&&!success(responseStatus))continue
   val href=node.children("href").firstOrNull()?.textContent?.trim()?:continue
   // HttpUrl accepts legal DAV names containing Korean text and raw spaces;
   // java.net.URI.resolve silently discarded these entries on older builds.
   val child=base.resolve(href)?:continue
   val segments=child.pathSegments.dropLastWhile{it.isEmpty()}
   if(segments.size!=parent.size+1||segments.dropLast(1)!=parent)continue
   val name=segments.last()
   if(name.isBlank()||name=="."||name==".."||'/' in name||'\\' in name||'\u0000' in name)continue
   val props=node.children("propstat").filter{stat->
    success(stat.children("status").firstOrNull()?.textContent.orEmpty())
   }.flatMap{it.children("prop")}
   if(props.isEmpty())continue
   val directory=props.any{prop->prop.children("resourcetype").any{it.children("collection").isNotEmpty()}}
   val size=props.asSequence().flatMap{it.children("getcontentlength").asSequence()}
    .mapNotNull{it.textContent.trim().toLongOrNull()?.takeIf{n->n>=0}}.firstOrNull()?:-1L
   found[name]=Entry(name,directory,if(directory)-1L else size)
  }
  return found.values.toList()
 }

 private fun success(status:String)=Regex("HTTP/\\S+\\s+2\\d\\d(?:\\s.*)?",RegexOption.IGNORE_CASE).matches(status.trim())
 private fun Element.children(name:String):List<Element> = (0 until childNodes.length).mapNotNull {
  (childNodes.item(it) as? Element)?.takeIf{node->node.localName==name&&node.namespaceURI==DAV}
 }
}
