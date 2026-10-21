package com.ckchoi.ckreader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.util.Base64
import java.util.Locale

/** Safely turns bounded, untrusted YAML into structured read-only display rows. */
internal object YamlReadableDocument {
 sealed class Row(open val label:String,open val depth:Int) {
  data class Heading(override val label:String,override val depth:Int):Row(label,depth)
  data class Value(override val label:String,val text:String,override val depth:Int):Row(label,depth)
  data class Image(override val label:String,val bitmap:Bitmap,override val depth:Int):Row(label,depth)
 }
 private val commonLabels=mapOf(
  "title" to "도서명", "booktitle" to "도서명", "name" to "이름",
  "author" to "저자", "authors" to "저자", "translator" to "번역자",
  "publisher" to "출판사", "description" to "도서 소개", "summary" to "내용 소개",
  "synopsis" to "줄거리", "genre" to "장르", "genres" to "장르",
  "cover" to "표지 이미지", "coverimage" to "표지 이미지", "coverbase64" to "표지 이미지",
  "coverdata" to "표지 이미지", "poster" to "표지 이미지", "image" to "이미지",
  "series" to "시리즈", "seriesname" to "시리즈명", "seriestotal" to "시리즈 총권수",
  "seriesindex" to "시리즈 순번", "volume" to "권수", "volumes" to "권별 도서",
  "books" to "도서 목록", "files" to "파일 목록", "file" to "파일명",
  "filename" to "파일명", "isbn" to "ISBN", "isbn13" to "ISBN-13",
  "publishdate" to "출판일", "publisheddate" to "출판일", "price" to "가격",
  "language" to "언어", "tags" to "태그", "pagecount" to "쪽수",
  "format" to "파일 형식", "metadata" to "메타데이터", "bookshelves" to "책장 목록"
 )
 private fun friendly(raw:String):String {
  val key=raw.lowercase(Locale.ROOT).replace(Regex("[\\s_-]"),"")
  return commonLabels[key]?.let {if(raw.equals(it,true))it else "$it ($raw)"}?:raw
 }
 private fun potentialCover(key:String,text:String):Boolean {
  if(text.length<24)return false
  val label=key.lowercase(Locale.ROOT)
  return listOf("cover","poster","thumbnail","image","표지","그림").any{it in label} &&
   (text.startsWith("data:image/",true)||
    listOf("iVBORw0KGgo","/9j/","UklGR","R0lGOD","Qk").any{ text.startsWith(it) })
 }
 private fun bitmapFromBase64(source:String):Bitmap? {
  val encoded=if(source.startsWith("data:",true)) {
   val header=source.substringBefore(',');if(!header.contains(";base64",true))return null
   source.substringAfter(',')
  }else source
  if(encoded.length !in 24..7_100_000)return null
  return runCatching {
   val raw=Base64.getMimeDecoder().decode(encoded)
   require(raw.size in 40..5_000_000) {"Image size exceeded"}
   val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
   BitmapFactory.decodeByteArray(raw,0,raw.size,bounds)
   require(bounds.outWidth in 1..10000&&bounds.outHeight in 1..10000)
   var sample=1
   while(bounds.outWidth/sample>1200 || bounds.outHeight/sample>1200) sample*=2
   val scaled=BitmapFactory.Options().apply {
    inSampleSize=sample;inPreferredConfig=Bitmap.Config.RGB_565
   }
   BitmapFactory.decodeByteArray(raw,0,raw.size,scaled)
  }.getOrNull()
 }
 fun isYamlFile(name:String)=name.length in 5..255 &&
  '/' !in name && '\\' !in name && !name.startsWith('.') &&
  (name.endsWith(".yaml",true)||name.endsWith(".yml",true))
 fun isKavita(name:String)=name.equals("kavita.yaml",true)||name.equals("kavita.yml",true)
 fun candidateNames(names:Collection<String>):List<String> =
  names.filter(::isYamlFile).distinctBy{it.lowercase(Locale.ROOT)}.sortedWith(
   compareBy<String> {if(isKavita(it))0 else 1}.thenBy{it.lowercase(Locale.ROOT)} )
 fun parse(bytes:ByteArray):List<Row> {
  require(bytes.isNotEmpty()&&bytes.size<=FolderYamlMetadata.MAX_BYTES) {"YAML 파일 크기가 올바르지 않습니다."}
  val loader=LoaderOptions().apply {
   setMaxAliasesForCollections(12)
   setCodePointLimit(FolderYamlMetadata.MAX_BYTES)
   nestingDepthLimit=40
  }
  val doc=Yaml(SafeConstructor(loader)).load<Any?>(String(bytes,Charsets.UTF_8))
  val rows=ArrayList<Row>()
  var images=0
  fun addNode(name:String,node:Any?,depth:Int,coverContext:Boolean=false) {
   if(rows.size>=250) return
   val label=friendly(name)
   when(node) {
    is Map<*,*> -> {
     rows.add(Row.Heading(label,depth))
     val inheritCover=coverContext||listOf("cover","image","poster","thumbnail","표지").any{
      it in name.lowercase(Locale.ROOT)
     }
     for((k,v) in node) {
      if(rows.size>=248)break
      addNode(k?.toString()?:"기타",v,(depth+1).coerceAtMost(5),inheritCover)
     }
    }
    is List<*> -> {
     rows.add(Row.Heading("$label · ${node.size}개",depth))
     for((index,item) in node.withIndex()) {
      if(rows.size>=248)break
      addNode("${index+1}번 항목",item,(depth+1).coerceAtMost(5),coverContext)
     }
    }
    is String -> {
     val picture=potentialCover(if(coverContext)"cover.$name" else name,node)
     val embedded=if(images<12&&picture)bitmapFromBase64(node) else null
     if(embedded!=null) {rows.add(Row.Image(label,embedded,depth));images++}
     else rows.add(Row.Value(label,if(node.length>3000) {
      if(node.length>1000 && picture)"이미지를 표시할 수 없습니다. (Base64 형식 또는 크기를 확인하세요)"
      else node.take(3000)+"\n… (내용 일부만 표시)"
     }else node.ifEmpty{"(빈 값)"},depth))
    }
    is ByteArray -> rows.add(Row.Value(label,"바이너리 데이터 (${node.size}바이트)",depth))
    null -> rows.add(Row.Value(label,"(없음)",depth))
    else -> rows.add(Row.Value(label,node.toString().take(800),depth))
   }
  }
  addNode("YAML 도서정보",doc,0)
  if(rows.size>=248)rows.add(Row.Value("안내","메타데이터가 길어 앞부분만 표시합니다.",0))
  return rows
 }
}
