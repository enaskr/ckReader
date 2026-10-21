package com.ckchoi.yesss24

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import java.io.File
import java.util.LinkedHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * Extracts spoken text from a PDF page on the reader's background worker.
 * API 35+: platform PdfRenderer text API.
 * API 26-34: PdfBox-Android.
 * Image-only pages: bundled on-device Korean+Latin ML Kit recognition.
 */
internal class PdfSpeechExtractor(private val context:Context,private val source:Uri) {
 private var document:PDDocument?=null
 private var copied:File?=null
 private var ocr:TextRecognizer?=null
 private val cache=object:LinkedHashMap<Int,String>(16,.75f,true) {
  override fun removeEldestEntry(eldest:MutableMap.MutableEntry<Int,String>?):Boolean=size>16
 }

 fun extract(index:Int,renderer:PdfRenderer):String {
  cache[index]?.let{return it}
  var pageText:String?=null
  if(Build.VERSION.SDK_INT>=35) {
   pageText=runCatching {
    renderer.openPage(index).use { page ->
     page.textContents.joinToString("\n") { it.text }
    }
   }.getOrNull()
  }
  if(pageText==null || pageText.isBlank() && Build.VERSION.SDK_INT<35) {
   pageText=runCatching { pdfBoxPage(index) }.getOrDefault("")
  }
  val extracted=pageText.orEmpty()
  // A scan can contain only a printed page number or watermark text layer.
  val spoken=if(extracted.trim().length<24) {
   val recognized=runCatching {recognizeImagePage(index,renderer)}.getOrDefault("")
   if(recognized.trim().length>extracted.trim().length)recognized else extracted
  } else extracted
  val normalized=spoken.replace(Regex("[\\t ]+")," ").replace(Regex("\\n{3,}"),"\n\n").trim().take(160_000)
  cache[index]=normalized
  return normalized
 }

 private fun pdfBoxPage(index:Int):String {
  if(document==null) {
   PDFBoxResourceLoader.init(context.applicationContext)
   val file=File.createTempFile("pdf-speech-",".pdf",context.cacheDir)
   try {
    val stream=context.contentResolver.openInputStream(source) ?: error("PDF 내용을 읽을 수 없습니다.")
    stream.use { input -> file.outputStream().use { output ->
     val bytes=ByteArray(65536)
     var total=0L
     while(true) {
      val n=input.read(bytes)
      if(n<0)break
      if(n==0)continue
      total+=n
      require(total<=200L*1024L*1024L) { "PDF 텍스트 추출용 파일이 너무 큽니다." }
      output.write(bytes,0,n)
     }
    } }
    document=PDDocument.load(file)
    copied=file
   }catch(e:Exception){file.delete();throw e}
  }
  return PDFTextStripper().apply {
   startPage=index+1
   endPage=index+1
   sortByPosition=true
  }.getText(document!!)
 }

 private fun recognizeImagePage(index:Int,renderer:PdfRenderer):String {
  val bitmap=renderer.openPage(index).use { page ->
   val scale=min(2000f/page.width.coerceAtLeast(1),2700f/page.height.coerceAtLeast(1)).coerceAtMost(2.6f)
   val width=(page.width*scale).toInt().coerceIn(1,2400)
   val height=(page.height*scale).toInt().coerceIn(1,2800)
   Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888).also {
    it.eraseColor(Color.WHITE)
    page.render(it,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
   }
  }
  try {
   val recognizer=ocr ?: TextRecognition.getClient(
    KoreanTextRecognizerOptions.Builder().build()).also { ocr=it }
   val result=Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap,0)),35,TimeUnit.SECONDS)
   return result.text.orEmpty()
  } finally {
   bitmap.recycle()
  }
 }

 /**
  * Detect the line nearest a tap on the rendered PDF page.
  * The OCR coordinates are page-relative and independent of Android view zoom.
  * Never guess a character offset when the tapped line cannot be recognized.
  */
 fun extractVisible(index:Int,renderer:PdfRenderer,visible:RectF):String =
  extractFromPosition(index,renderer,visible.left,visible.top,visible)
 fun extractFromPosition(index:Int,renderer:PdfRenderer,x:Float,y:Float,visible:RectF?=null):String {
  if(x !in 0f..1f || y !in 0f..1f)return ""
  val bitmap=renderer.openPage(index).use { page ->
   val scale=min(1800f/page.width.coerceAtLeast(1),2400f/page.height.coerceAtLeast(1)).coerceAtMost(2.4f)
   val width=(page.width*scale).toInt().coerceIn(1,2000)
   val height=(page.height*scale).toInt().coerceIn(1,2600)
   Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888).also {
    it.eraseColor(Color.WHITE)
    page.render(it,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
   }
  }
  try {
   val recognizer=ocr ?: TextRecognition.getClient(
    KoreanTextRecognizerOptions.Builder().build()).also {ocr=it}
   val result=Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap,0)),35,TimeUnit.SECONDS)
   // Keep one set of ML Kit wrappers: repeated getters can create new Line
   // and Element objects, so identity-based offsets must use this snapshot.
   val paragraphs=result.textBlocks.map{block->block.lines.filter{it.text.isNotBlank()&&it.boundingBox!=null}}
   val lines=paragraphs.flatten()
   if(lines.isEmpty())return ""
   val chosen=if(visible!=null)lines.indexOfFirst{line->
    val box=line.boundingBox!!
    RectF.intersects(visible,RectF(box.left.toFloat()/bitmap.width,box.top.toFloat()/bitmap.height,
     box.right.toFloat()/bitmap.width,box.bottom.toFloat()/bitmap.height))
   }.takeIf{it>=0}?:return "" else lines.indices.minByOrNull { index ->
    val box=lines[index].boundingBox!!
    val px=x*bitmap.width
    val py=y*bitmap.height
    val dx=when {px<box.left->box.left-px;px>box.right->px-box.right;else->0f}
    val dy=when {py<box.top->box.top-py;py>box.bottom->py-box.bottom;else->0f}
    dx*dx+dy*dy*2f
   }?:return ""
   val selected=lines[chosen]
   var wordOffset=0
   if(visible==null){
    // Several sentences can share a printed line. Use the held word, rather
    // than restarting at the first sentence on that line.
    val px=x*bitmap.width
    val elements=selected.elements.filter{it.boundingBox!=null}
    val word=elements.minByOrNull {
     val box=it.boundingBox!!
     when {px<box.left->box.left-px;px>box.right->px-box.right;else->0f}
    }
    if(word!=null){
     var after=0
     for(element in elements){
      val at=selected.text.indexOf(element.text,after).takeIf{it>=0}?:continue
      if(element===word){wordOffset=at;break}
      after=at+element.text.length
     }
    }
   }
   val needle=selected.text.trim()
   // A single newline in a PDF text layer is usually a wrapped line, not a
   // paragraph boundary. Replacing it preserves UTF-16 offsets.
   val native=cache[index].orEmpty().replace(Regex("(?<!\n)\n(?!\n)")," ")
   val exact=native.indexOf(needle,ignoreCase=true)
   if(exact>=0&&native.indexOf(needle,exact+needle.length,ignoreCase=true)<0){
    val offset=exact+wordOffset
    return native.substring(if(visible==null)SpeechPosition.sentenceStart(native,offset) else exact)
   }
   var selectedOffset=0
   val recognized=buildString {
    for(block in paragraphs){
     if(isNotEmpty())append("\n")
     for(line in block){
      if(line.text.isBlank()||line.boundingBox==null)continue
      if(line===selected)selectedOffset=length+wordOffset
      append(line.text).append(' ')
     }
    }
   }
   return recognized.substring(if(visible==null)SpeechPosition.sentenceStart(recognized,selectedOffset) else selectedOffset).trim()
  }finally{bitmap.recycle()}
 }

 fun close() {
  cache.clear()
  runCatching {ocr?.close()}
  ocr=null
  runCatching {document?.close()}
  document=null
  copied?.delete()
  copied=null
 }
}
