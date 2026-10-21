package com.ckchoi.ckreader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdfparser.PDFParser
import com.tom_roush.pdfbox.rendering.PDFRenderer
import com.tom_roush.pdfbox.rendering.ImageType
import java.io.ByteArrayOutputStream
import java.io.File

/** Render only the first PDF page for folder artwork. No PDF text/image OCR. */
internal object EmbeddedPdfCover {
 private const val MAX_RENDER_SIZE=900
 private const val MAX_PDF_BYTES=1_000_000_000L

 /**
  * Reader is backed by short HTTP/WebDAV/FTP/rclone range requests.
  * Do not use a self-hosted ProxyFileDescriptor: killing the app during pread
  * can leave its reader in uninterruptible FUSE I/O and block every next launch.
  */
 fun fromRanges(context:Context,length:Long,fetch:(Long,Int)->ByteArray):ByteArray? {
  if(length !in 8..MAX_PDF_BYTES)return null
  PDFBoxResourceLoader.init(context.applicationContext)
  RangePdfRead(length,fetch).use { ranges ->
   val parser=PDFParser(ranges)
   // Do not scan arbitrary remote bytes trying to repair a malformed PDF.
   parser.isLenient=false
   parser.parse()
   parser.pdDocument.use {pdf ->
    if(pdf.numberOfPages<1)return null
    val box=pdf.getPage(0).cropBox
    if(box.width<=0||box.height<=0||!box.width.isFinite()||!box.height.isFinite())return null
    val scale=minOf(MAX_RENDER_SIZE/box.width,MAX_RENDER_SIZE/box.height,1.5f)
    val image=PDFRenderer(pdf).apply{isSubsamplingAllowed=true}.renderImage(0,scale,ImageType.RGB)
    try {
     return ByteArrayOutputStream().use{bytes->
      if(image.compress(Bitmap.CompressFormat.JPEG,83,bytes))bytes.toByteArray() else null
     }
    } finally {image.recycle()}
    }
  }
 }

 /** Open directly from a seekable Storage Access Framework file descriptor. */
 fun fromDescriptor(descriptor:ParcelFileDescriptor):ByteArray? {
  descriptor.use {pfd ->
   PdfRenderer(pfd).use {pdf ->
    if(pdf.pageCount<1)return null
    pdf.openPage(0).use {page -> return render(page)}
   }
  }
 }

 /** Local temporary file is the bounded fallback when the server has no Range support. */
 fun fromLocal(file:File):ByteArray? {
  if(!file.isFile || file.length() !in 8..MAX_PDF_BYTES)return null
  return fromDescriptor(ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY))
 }

 private fun render(page:PdfRenderer.Page):ByteArray? {
  if(page.width<=0||page.height<=0)return null
  val scale=minOf(MAX_RENDER_SIZE.toFloat()/page.width,
   MAX_RENDER_SIZE.toFloat()/page.height,1.5f)
  val width=(page.width*scale).toInt().coerceIn(1,MAX_RENDER_SIZE)
  val height=(page.height*scale).toInt().coerceIn(1,MAX_RENDER_SIZE)
  val image=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
  try {
   image.eraseColor(Color.WHITE)
   page.render(image,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
   return ByteArrayOutputStream().use {bytes ->
    if(image.compress(Bitmap.CompressFormat.JPEG,83,bytes))bytes.toByteArray()
    else null
   }
  } finally {image.recycle()}
 }
}
