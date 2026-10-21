package com.ckchoi.ckreader

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import javax.xml.parsers.SAXParserFactory

/** Bounded, XXE-safe Atom / OPDS 1.x catalog parser; also runs in plain JVM tests. */
internal object BookOasisOpdsParser {
 data class Entry(
  val id:String, val title:String, val summary:String="", val author:String="",
  val publisher:String="", val href:String="", val cover:String="", val mime:String="",
  val format:String="", val pages:Int=0, val navigation:Boolean=false
 )
 data class Feed(val entries:List<Entry>,val next:String?=null)
 private data class MutableEntry(
  var id:String="", var title:String="", var summary:String="", var author:String="",
  var publisher:String="", var href:String="", var cover:String="", var mime:String="",
  var format:String="", var pages:Int=0, var navigation:Boolean=false
 ) {
  fun freeze():Entry=Entry(id,title,summary,author,publisher,href,cover,mime,format,pages,navigation)
 }

 fun parse(xml:ByteArray):Feed {
  require(xml.size in 20..32_000_000){"OPDS 응답 크기가 올바르지 않습니다."}
  val preview=String(xml,Charsets.UTF_8)
  require(!preview.contains("<!DOCTYPE",ignoreCase=true)&&
   !preview.contains("<!ENTITY",ignoreCase=true)){"DTD/ENTITY가 포함된 XML은 허용하지 않습니다."}
  val items=ArrayList<Entry>()
  var next:String?=null
  var inFeed=false
  var current:MutableEntry?=null
  val stack=ArrayList<String>()
  val text=StringBuilder()
  val handler=object:DefaultHandler() {
   override fun startElement(uri:String?,localName:String?,qName:String?,attrs:Attributes) {
    val tag=localName?.ifBlank{qName.orEmpty().substringAfter(':')}.orEmpty()
    stack.add(tag)
    text.setLength(0)
    if(tag=="feed")inFeed=true
    if(tag=="entry")current=MutableEntry()
    if(tag=="category") {
     val value=attrs.getValue("term").orEmpty()
     if(current!=null && value.isNotBlank())current?.format=value
    }
    if(tag!="link")return
    val rel=attrs.getValue("rel").orEmpty()
    val href=attrs.getValue("href").orEmpty()
    if(current==null) {
     if(rel=="next"&&href.isNotBlank())next=href
     return
    }
    val row=current?:return
    when {
     rel=="subsection" || (rel=="alternate" &&
      attrs.getValue("type").orEmpty().contains("atom+xml")) -> {
      row.href=href;row.navigation=true
     }
     rel=="http://opds-spec.org/acquisition" ||
      rel=="http://opds-spec.org/acquisition/open-book" ||
      rel.startsWith("http://opds-spec.org/acquisition/") -> {
      if(row.href.isBlank() || rel.endsWith("open-book")) {
       row.href=href;row.mime=attrs.getValue("type").orEmpty()
      }
     }
     rel=="http://opds-spec.org/image" -> row.cover=href
     rel=="http://opds-spec.org/image/thumbnail" && row.cover.isBlank() -> row.cover=href
     rel=="http://vaemendis.net/opds-pse/stream" -> {
      val count=(attrs.getValue("pse:count") ?:attrs.getValue("count")
       ?:attrs.getValue("http://vaemendis.net/opds-pse/ns","count"))
       ?.toIntOrNull()?:0
      row.pages=count.coerceIn(0,500_000)
     }
    }
   }
   override fun characters(ch:CharArray,start:Int,length:Int) {
    if(text.length<8_000)text.append(ch,start,length.coerceAtMost(8_000-text.length))
   }
   override fun endElement(uri:String?,localName:String?,qName:String?) {
    val tag=localName?.ifBlank{qName.orEmpty().substringAfter(':')}.orEmpty()
    val value=text.toString().trim()
    val row=current
    if(row!=null)when(tag) {
     "id" -> if(stack.size>=2)row.id=value.take(500)
     "title" -> row.title=value.take(500)
     "summary" -> row.summary=value.take(4000)
     "name" -> if(stack.contains("author"))row.author=value.take(250)
     "publisher" -> row.publisher=value.take(250)
     "entry" -> {
      if(row.href.isNotBlank() && row.title.isNotBlank()) {
       require(items.size<120_000){"OPDS 카탈로그 항목이 너무 많습니다."}
       items.add(row.freeze())
      }
      current=null
     }
    }
    if(stack.isNotEmpty())stack.removeAt(stack.lastIndex)
    text.setLength(0)
   }
  }
  val factory=SAXParserFactory.newInstance().apply {
   isNamespaceAware=true
   // Android parser implementations vary; the explicit DOCTYPE / ENTITY
   // rejection above is mandatory even when a hardening feature is absent.
   runCatching{setFeature("http://xml.org/sax/features/external-general-entities",false)}
   runCatching{setFeature("http://xml.org/sax/features/external-parameter-entities",false)}
   runCatching{setFeature("http://apache.org/xml/features/disallow-doctype-decl",true)}
  }
  factory.newSAXParser().parse(ByteArrayInputStream(xml),handler)
  require(inFeed){"OPDS Atom feed가 아닙니다."}
  return Feed(items,next)
 }
}
