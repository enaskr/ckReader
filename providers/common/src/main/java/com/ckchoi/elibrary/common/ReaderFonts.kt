package com.ckchoi.elibrary.common

import android.content.Context
import android.net.Uri
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.provider.OpenableColumns
import android.graphics.Typeface
import android.util.AtomicFile
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Signed APK fonts and user-selected local TTF/OTF files. */
object ReaderFonts {
 data class FontPack(val id:String,val label:String,val packageName:String,val fontIds:Set<String>)
 data class Family(val id:String,val label:String,val publisher:String,val style:String,
  val description:String,val asset:String,val sha256:String,val bytes:Long)
 val families:List<Family> = ReaderFontCatalog.families
 val packs:List<FontPack> = ReaderFontCatalog.packs
 private data class Pack(val id:String,val revision:Long,val version:String,val assets:AssetManager)
 private val cachedPacks=ConcurrentHashMap<String,Pack>()
 private val typefaces=ConcurrentHashMap<String,Typeface>()
 private val verified=ConcurrentHashMap<String,Long>()
 private val filesLock=Any()
 private const val USER_FONTS="reader-user-fonts"
 private const val DISABLED_FONTS="disabled_reader_fonts"
 private fun userRoot(context:Context)=File(context.filesDir,USER_FONTS)
 private fun disabled(context:Context)=context.getSharedPreferences(USER_FONTS,Context.MODE_PRIVATE)
  .getStringSet(DISABLED_FONTS,emptySet()).orEmpty()
 fun userFamilies(context:Context):List<Family> =
  userRoot(context).listFiles().orEmpty().filter{it.isFile&&
   (it.extension.equals("ttf",true)||it.extension.equals("otf",true))&&it.name.contains("--")}
   .sortedBy{it.name}.mapNotNull {file->
    val id=file.name.substringBefore("--")
    if(!id.matches(Regex("user-[a-f0-9]{20}")))return@mapNotNull null
    val label=file.name.substringAfter("--").substringBeforeLast('.').replace('_',' ')
    Family(id,label,"기기에서 추가","사용자 글꼴","다운로드 폴더에서 선택한 글꼴",
     file.name,"",file.length())
   }
 fun allFamilies(context:Context)=families+userFamilies(context)
 fun family(context:Context,index:Int):Family?=allFamilies(context).getOrNull(index-3)
 fun index(context:Context,id:String):Int=allFamilies(context).indexOfFirst{it.id==id}
  .takeIf{it>=0}?.plus(3)?:-1
 fun importFont(context:Context,uri:Uri):Family {
  val name=context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)
   ?.use{if(it.moveToFirst())it.getString(0) else null}
   ?.substringAfterLast('/')?.substringAfterLast('\\')?.take(100)
   ?:error("글꼴 파일 이름을 확인할 수 없습니다.")
  val extension=name.substringAfterLast('.',"").lowercase()
  require(extension=="ttf"||extension=="otf"){"TTF 또는 OTF 파일을 선택하세요."}
  val label=name.substringBeforeLast('.').replace(Regex("[^\\p{L}\\p{N} _.-]"),"")
   .trim().take(60).ifBlank{"사용자 글꼴"}
  val root=userRoot(context).apply{check(isDirectory||mkdirs())}
  val temporary=File.createTempFile("import-",".part",root)
  try {
   val hash=MessageDigest.getInstance("SHA-256")
   var bytes=0L
   context.contentResolver.openInputStream(uri)?.use{input->
    temporary.outputStream().use{output->
     val buffer=ByteArray(65536)
     while(true){val count=input.read(buffer);if(count<0)break
      bytes+=count;require(bytes<=50L*1024*1024){"글꼴 파일이 너무 큽니다."}
      hash.update(buffer,0,count);output.write(buffer,0,count)
     }
    }
   }?:error("글꼴 파일을 열 수 없습니다.")
   require(bytes>=100L){"빈 글꼴 파일입니다."}
   Typeface.createFromFile(temporary)
   val id="user-"+hash.digest().joinToString(""){"%02x".format(it)}.take(20)
   val existing=userFamilies(context).firstOrNull{it.id==id}
   if(existing!=null)return existing
   val target=File(root,"$id--${label.replace(' ','_')}.$extension")
   check(temporary.renameTo(target)){"글꼴을 저장할 수 없습니다."}
   return userFamilies(context).first{it.id==id}
  }finally{temporary.delete()}
 }
 fun deleteFont(context:Context,id:String):Boolean {
  if(id.startsWith("user-")) {
   val entry=userFamilies(context).firstOrNull{it.id==id}?:return false
   typefaces.keys.removeIf{it.endsWith(":$id")}
   return File(userRoot(context),entry.asset).delete()
  }
  if(families.none{it.id==id})return false
  val next=disabled(context).toMutableSet().apply{add(id)}
  context.getSharedPreferences(USER_FONTS,Context.MODE_PRIVATE).edit()
   .putStringSet(DISABLED_FONTS,next).apply()
  typefaces.keys.removeIf{it.endsWith(":$id")}
  return true
 }
 fun restoreFont(context:Context,id:String):Boolean {
  if(id !in disabled(context))return false
  val next=disabled(context).toMutableSet().apply{remove(id)}
  context.getSharedPreferences(USER_FONTS,Context.MODE_PRIVATE).edit()
   .putStringSet(DISABLED_FONTS,next).apply()
  return installed(context,id)
 }

 fun family(index:Int):Family?=families.getOrNull(index-3)
 fun index(id:String):Int=families.indexOfFirst{it.id==id}.takeIf{it>=0}?.plus(3)?:-1
 private fun pack(context:Context,definition:FontPack):Pack?=runCatching {
  val manager=context.packageManager
  val info=manager.getPackageInfo(definition.packageName,PackageManager.GET_META_DATA)
  if(manager.checkSignatures(context.packageName,definition.packageName)!=PackageManager.SIGNATURE_MATCH)return@runCatching null
  if(info.applicationInfo?.metaData?.getInt("com.ckchoi.ckreader.fonts.SCHEMA")!=1)return@runCatching null
  if(info.applicationInfo?.metaData?.getString("com.ckchoi.ckreader.fonts.PACK")!=definition.id)return@runCatching null
  cachedPacks[definition.id]?.takeIf{it.revision==info.lastUpdateTime}?:Pack(definition.id,info.lastUpdateTime,
   info.versionName.orEmpty(),manager.getResourcesForApplication(definition.packageName).assets).also{cachedPacks[definition.id]=it}
 }.getOrNull()
 fun packVersion(context:Context,id:String):String?=packs.firstOrNull{it.id==id}?.let{pack(context,it)?.version}
 fun revision(context:Context):Long {
  val packsRevision=packs.fold(0L){value,definition->value*31+(pack(context,definition)?.revision?:0L)}
  return userFamilies(context).fold(packsRevision*31+disabled(context).hashCode()){value,family->
   value*31+File(userRoot(context),family.asset).lastModified()
  }
 }
 private fun source(context:Context,id:String):Pack?=packs.asSequence().filter{id in it.fontIds}.mapNotNull{pack(context,it)}.firstOrNull()
 fun installed(context:Context,id:String)=if(id.startsWith("user-"))userFamilies(context).any{it.id==id}
  else id !in disabled(context)&&source(context,id)!=null
 fun mime(context:Context,id:String):String=if(allFamilies(context).firstOrNull{it.id==id}?.asset?.endsWith(".otf")==true)"font/otf" else "font/ttf"
 fun installedFile(context:Context,id:String):File? {
  if(id.startsWith("user-"))return userFamilies(context).firstOrNull{it.id==id}
   ?.let{File(userRoot(context),it.asset)}
  if(id in disabled(context))return null
  val family=families.firstOrNull{it.id==id}?:return null
  val source=source(context,id)?:return null // Never use a stale cache after its APK is removed.
  return runCatching {synchronized(filesLock){
   val root=File(context.filesDir,"font-pack-cache").apply{check(isDirectory||mkdirs())}
   val file=File(root,"${source.id}-${source.revision}-${family.asset}")
   if(file.isFile&&file.length()==family.bytes) {
    if(verified[file.path]==file.lastModified()||digest(file)==family.sha256) {
     verified[file.path]=file.lastModified();return@synchronized file
    }
   }
   val atomic=AtomicFile(file)
   val output=atomic.startWrite()
   try {
    val hash=MessageDigest.getInstance("SHA-256");var count=0L
    source.assets.open("fonts/${family.asset}").use{input->
     val buffer=ByteArray(65536)
     while(true){val read=input.read(buffer);if(read<0)break;if(read==0)continue
      count+=read;check(count<=family.bytes){"글꼴 크기 불일치"};hash.update(buffer,0,read);output.write(buffer,0,read)
     }
    }
    check(count==family.bytes&&hash.digest().joinToString(""){"%02x".format(it)}==family.sha256){"글꼴 무결성 확인 실패"}
    atomic.finishWrite(output)
   }catch(error:Throwable){atomic.failWrite(output);throw error}
   verified[file.path]=file.lastModified();file
  }}.getOrElse{android.util.Log.w("ckReaderFonts","Font pack asset unavailable: $id",it);null}
 }
 private fun digest(file:File):String {
  val hash=MessageDigest.getInstance("SHA-256")
  file.inputStream().use{input->val buffer=ByteArray(65536);while(true){val read=input.read(buffer);if(read<0)break;hash.update(buffer,0,read)}}
  return hash.digest().joinToString(""){"%02x".format(it)}
 }
 fun font(context:Context,id:String):Typeface? {
  if(id.startsWith("user-")) {
   val file=installedFile(context,id)?:return null
   val key="user:${file.lastModified()}:$id"
   return typefaces[key]?:runCatching{Typeface.createFromFile(file)}.getOrNull()
    ?.also{typefaces[key]=it}
  }
  if(id in disabled(context))return null
  val source=source(context,id)?:return null
  val key="${source.id}:${source.revision}:$id"
  typefaces[key]?.let{return it}
  val file=installedFile(context,id)?:return null
  return runCatching{Typeface.createFromFile(file)}.getOrNull()?.also{typefaces[key]=it}
 }
}
