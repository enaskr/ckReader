package com.ckchoi.ckreader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import android.util.LruCache
import android.widget.ImageView
import java.io.File
import java.security.MessageDigest
import java.util.UUID

internal object SourceIconFiles {
 private val uploadedId=Regex("upload_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
 fun filename(id:String):String?=id.takeIf{uploadedId.matches(it)}?.plus(".png")
}

/** Icon choices never change a storage's credentials, identity or cache fingerprint. */
internal class SourceIconStore(private val context:Context) {
 private val prefs=context.getSharedPreferences("ckreader_source_icons",Context.MODE_PRIVATE)
 private val root=File(context.filesDir,"storage-icons-v1")
 private val images=LruCache<String,Bitmap>(24)
 companion object {private val lock=Any()}
 private fun key(sourceId:String):String="source_"+MessageDigest.getInstance("SHA-256")
  .digest(sourceId.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
 private fun uploadedFile(id:String):File?=SourceIconFiles.filename(id)?.let{File(root,it)}
 fun uploaded():List<StorageIconChoice> = prefs.getStringSet("uploads",emptySet()).orEmpty()
  .sorted().mapNotNull{id->uploadedFile(id)?.takeIf{it.isFile}?.let{
   StorageIconChoice(id,prefs.getString("label_"+id,"내 이미지").orEmpty())
  }}
 fun choices():List<StorageIconChoice> = uploaded()+StorageIcons.builtIn
 fun selected(sourceId:String,type:String):StorageIconChoice? {
  val id=prefs.getString(key(sourceId),null)?:StorageIcons.defaultFor(type)?:return null
  return choices().firstOrNull{it.id==id}
 }
 fun select(sourceId:String,id:String):Boolean {
  if(choices().none{it.id==id})return false
  return prefs.edit().putString(key(sourceId),id).commit()
 }
 fun clearSelection(sourceId:String):Boolean=prefs.edit().remove(key(sourceId)).commit()
 private fun builtInBitmap(choice:StorageIconChoice):Bitmap? {
  val decoded=BitmapFactory.decodeResource(context.resources,choice.resource)?:return null
  val region=choice.region
  val source=if(region==null)decoded else Bitmap.createBitmap(decoded,region.left,region.top,
   region.right-region.left,region.bottom-region.top)
  if(source!==decoded)decoded.recycle()
  val pixels=IntArray(source.width*source.height)
  source.getPixels(pixels,0,source.width,0,0,source.width,source.height)
  val bounds=IconContentBounds.find(pixels,source.width,source.height)
  val cropped=Bitmap.createBitmap(source,bounds.left,bounds.top,bounds.right-bounds.left,bounds.bottom-bounds.top)
  if(cropped!==source)source.recycle()
  return cropped
 }
 fun image(view:ImageView,choice:StorageIconChoice):Boolean {
  val bitmap=synchronized(images){images.get(choice.id)}?:
   (if(choice.resource!=0)builtInBitmap(choice)
    else uploadedFile(choice.id)?.let{BitmapFactory.decodeFile(it.absolutePath)})
    ?.also{synchronized(images){images.put(choice.id,it)}}?:return false
  view.setImageBitmap(bitmap)
  return true
 }
 /** Copy and normalize the chosen document so its original URI is never needed again. */
 fun importImage(uri:Uri):StorageIconChoice {
  val bytes=context.contentResolver.openInputStream(uri)?.use{input->
   val out=java.io.ByteArrayOutputStream()
   val buffer=ByteArray(8192)
   while(true) {
    val n=input.read(buffer)
    if(n<0)break
    require(out.size()+n<=8_000_000){"이미지는 8 MB 이하로 선택하세요."}
    out.write(buffer,0,n)
   }
   out.toByteArray()
  }?:error("이미지 파일을 읽을 수 없습니다.")
  val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
  BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
  require(bounds.outWidth in 1..20_000&&bounds.outHeight in 1..20_000){"지원하는 이미지 파일을 선택하세요."}
  val options=BitmapFactory.Options().apply {
   inSampleSize=1
   while(maxOf(bounds.outWidth,bounds.outHeight)/inSampleSize>512)inSampleSize*=2
  }
  val decoded=BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)
   ?:error("이미지를 해독할 수 없습니다.")
  val orientation=runCatching{ExifInterface(bytes.inputStream())
   .getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL)}
   .getOrDefault(ExifInterface.ORIENTATION_NORMAL)
  val matrix=Matrix().apply {
   when(orientation) {
    ExifInterface.ORIENTATION_FLIP_HORIZONTAL->setScale(-1f,1f)
    ExifInterface.ORIENTATION_ROTATE_180->setRotate(180f)
    ExifInterface.ORIENTATION_FLIP_VERTICAL->setScale(1f,-1f)
    ExifInterface.ORIENTATION_TRANSPOSE->{setRotate(90f);postScale(-1f,1f)}
    ExifInterface.ORIENTATION_ROTATE_90->setRotate(90f)
    ExifInterface.ORIENTATION_TRANSVERSE->{setRotate(270f);postScale(-1f,1f)}
    ExifInterface.ORIENTATION_ROTATE_270->setRotate(270f)
   }
  }
  val oriented=if(matrix.isIdentity)decoded else Bitmap.createBitmap(decoded,0,0,
   decoded.width,decoded.height,matrix,true)
  val icon=Bitmap.createBitmap(192,192,Bitmap.Config.ARGB_8888)
  val scale=192f/maxOf(oriented.width,oriented.height)
  val width=oriented.width*scale;val height=oriented.height*scale
  Canvas(icon).drawBitmap(oriented,null,RectF((192-width)/2,(192-height)/2,
   (192+width)/2,(192+height)/2),Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
  val label=runCatching{context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),
   null,null,null)?.use{cursor->if(cursor.moveToFirst())cursor.getString(0) else null}}
   .getOrNull().orEmpty().substringBeforeLast('.').replace(Regex("[\\p{Cntrl}]+")," ")
   .trim().take(40).ifBlank{"내 이미지"}
  val id="upload_"+UUID.randomUUID().toString()
  val target=uploadedFile(id)!!
  val draft=File(root,target.name+".pending")
  try {
   check(root.isDirectory||root.mkdirs()){"아이콘 저장 폴더를 만들 수 없습니다."}
   draft.outputStream().use{check(icon.compress(Bitmap.CompressFormat.PNG,100,it))}
   check(draft.renameTo(target)){"아이콘 파일을 저장할 수 없습니다."}
   synchronized(lock) {
    val updated=prefs.getStringSet("uploads",emptySet()).orEmpty().toSet()+id
    if(!prefs.edit().putStringSet("uploads",updated).putString("label_"+id,label).commit()) {
     target.delete()
     error("아이콘 목록을 저장할 수 없습니다.")
    }
   }
   return StorageIconChoice(id,label)
  }finally {
   draft.delete()
   icon.recycle()
   if(oriented!==decoded)oriented.recycle()
   decoded.recycle()
  }
 }
}
