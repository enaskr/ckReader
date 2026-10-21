package com.ckchoi.yesss24

import android.text.Html
import android.util.Base64
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.zip.*
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Native YES24 crypto/export routines. No Python/Chaquopy dependency. */
object Yes24Drm {
 private fun bytes(vararg v:Int)=ByteArray(v.size){v[it].toByte()}
 private val QUERY_KEY=bytes(0xEB,0xB7,0xB0,0x27,0xEB,0x9D,0xBC,0xEC,0x9D,0xB4,0xEC,0x84,0xBC,0xEC,0x8A,0xA4)
 private val QUERY_IV=bytes(0xEC,0xB2,0xB4,0xED,0x81,0xAC,0x21,0xEB,0xB7,0xB0,0xEC,0x96,0xB4,0xEB,0x8B,0xA4)
 private val QUERY_KEY_12=bytes(0x21,0x26,0xEC,0x9D,0xB4,0xEA,0xB1,0xB8,0xEB,0xB3,0xB4,0xEB,0x8A,0x94,0x3B,0x25)
 private val QUERY_IV_12=bytes(0xEC,0x82,0xAC,0xEB,0x9E,0x8C,0xEC,0x9D,0x80,0x5F,0xEC,0x95,0x88,0xEB,0x8F,0xBC)
 private val LICENSE_IV=bytes(0xED,0x81,0xAC,0xEB,0x9D,0xBC,0xEC,0x9D,0xB4,0x3A,0xEC,0x97,0x90,0xEB,0xA6,0xAD)
 private val HMAC_KEY=bytes(0xEC,0x96,0xB8,0xEB,0x94,0x94,0xEC,0x95,0x8C,0xEC,0x97,0xA0,0x2D,0xED,0x82,0xA4,0x2D,0xEB,0x8B,0xA4,0xEC,0x8B,0x9C,0xEC,0x83,0x9D,0xEC,0x84,0xB1,0xED,0x95,0xA8)
 private val FILE_IV=bytes(0xEC,0x96,0xB8,0xEB,0x94,0x94,0xEC,0x95,0x8C,0xEC,0x97,0xA0,0xED,0x8C,0x8C,0x2B)
 private val FILE_IV2=bytes(0x75,0x5E,0x27,0x51,0x30,0x6E,0x2B,0x46,0x2A,0x76,0x3F,0x73,0x34,0x21,0x38,0x4B)
 private val DEVICE_KEY=bytes(0xEB,0x91,0xBF,0x9D,0x8D,0xEC,0xED,0xA4,0x8B,0xEB,0xEC,0x96,0x9E,0xB8,0xEC,0xEB,0xA0,0x94,0x95,0x94,0xED,0xEC,0x9C,0x95,0xA1,0x8C,0xEB,0xEC,0x97,0x8C,0xA0,0xEB)
 private val DEVICE_IV=bytes(0xED,0xEC,0x96,0xA0,0x99,0xB3,0xB8,0x94,0xB4,0x95,0xA0,0x9C,0xEB,0x88,0x98,0xA4)
 private const val TK="0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"

 fun licenseValue(text:String,tag:String,encoded:Boolean=true):String {
  if(text.isBlank())return ""
  val xml=if(encoded)String(Base64.decode(text,Base64.DEFAULT),Charsets.UTF_8) else text
  val safe=Regex.escape(tag)
  val prefix="(?:[A-Za-z_][\\w.-]*:)?"
  val pattern="(?is)<\\s*"+prefix+safe+"(?:\\s+[^>]*)?\\s*>(.*?)<\\s*/\\s*"+prefix+safe+"\\s*>"
  val m=Regex(pattern).find(xml)?:return ""
  var value=m.groupValues[1].trim()
  if(value.startsWith("<![CDATA[")&&value.endsWith("]]>")&&value.length>=12)value=value.substring(9,value.length-3)
  return value
   .replace("&lt;","<",true).replace("&gt;",">",true)
   .replace("&quot;","\"",true).replace("&apos;","'",true).replace("&amp;","&",true)
 }
 fun queryKeyIv(version:String)=if(version=="1.2") QUERY_KEY_12 to QUERY_IV_12 else QUERY_KEY to QUERY_IV
 fun randomTk():String{val r=SecureRandom();return buildString(13){repeat(13){append(TK[r.nextInt(TK.length)])}}}
 private fun aes(data:ByteArray,key:ByteArray,iv:ByteArray,decrypt:Boolean=true,padding:Boolean=true):ByteArray{
  val c=Cipher.getInstance("AES/CBC/"+if(padding)"PKCS5Padding" else "NoPadding")
  c.init(if(decrypt)Cipher.DECRYPT_MODE else Cipher.ENCRYPT_MODE,SecretKeySpec(key,"AES"),IvParameterSpec(iv));return c.doFinal(data)
 }
 fun encryptBase64(data:ByteArray,key:ByteArray,iv:ByteArray)=Base64.encodeToString(aes(data,key,iv,false,true),Base64.NO_WRAP)
 fun decodeDeviceId(encoded:String):String {
  val b=Base64.decode(encoded,Base64.DEFAULT);require(b.size>1){"기기 ID 형식이 올바르지 않습니다."}
  return String(aes(b.copyOfRange(1,b.size),DEVICE_KEY,DEVICE_IV,true,true),Charsets.UTF_8).replace("\u0000","")
 }
 private fun hmac(data:ByteArray):ByteArray{val m=Mac.getInstance("HmacMD5");m.init(SecretKeySpec(HMAC_KEY,"HmacMD5"));return m.doFinal(data)}
 private class Reader(val b:ByteArray){var p=0;fun take(n:Int):ByteArray{require(n>=0&&p+n<=b.size){"라이선스 데이터가 손상되었습니다."};return b.copyOfRange(p,p+n).also{p+=n}};fun length():Int{val x=take(4);return x[0].toInt() and 255}}
 private fun conditional(token:String,ref:String):String{val o=token.substring(2).toInt();require(o in 0..ref.length);return if(token.substring(1,2)=="f")ref.substring(o) else ref.substring(0,ref.length-o)}
 private fun authKey(ts:String,tk:String,uuid:String,device:String,order:String):ByteArray{
  val s=ts.replace("0x","_").split("_");require(s.size>=5)
  val out=StringBuilder();for(i in 1..4){val t=s[i];val ref=when(t.firstOrNull()){'a'->order;'w'->device;'y'->tk;'z'->uuid;else->error("라이선스 인증 형식이 변경되었습니다.")};out.append(conditional(t,ref))}
  return hmac(out.toString().toByteArray(Charsets.UTF_8))
 }
 fun deriveBookKey(ck:String,device:String):ByteArray{
  val r=Reader(Base64.decode(ck,Base64.DEFAULT));r.take(2);val uuid=String(r.take(32),Charsets.UTF_8);val order=String(r.take(r.length()),Charsets.UTF_8);val dummy=r.length();val tkCipher=r.take(r.length());val ts=String(r.take(r.length()),Charsets.UTF_8)
  val tk=String(aes(tkCipher,hmac((order+uuid).toByteArray(StandardCharsets.US_ASCII)),LICENSE_IV),Charsets.UTF_8).replace("\u0000","")
  val key=authKey(ts,tk,uuid,device,order);r.take(4);val keyCipher=r.take(r.length());val plain=Reader(aes(keyCipher,key,LICENSE_IV));plain.take(dummy);plain.take(1);return plain.take(16)
 }
 private fun intLE(b:ByteArray,o:Int)=ByteBuffer.wrap(b,o,4).order(ByteOrder.LITTLE_ENDIAN).int
 fun decryptFile(key:ByteArray,encrypted:ByteArray,version:Int=1):ByteArray? {
  return try {
   if(encrypted.size<8) return null
   val flag=intLE(encrypted,0);val size=intLE(encrypted,4)
   if(flag<=0||size<=0) return null
   var idx=8;var iv=FILE_IV
   if(version==2){if(encrypted.size<12)return null;idx=12+intLE(encrypted,8);iv=FILE_IV2}
   if(idx<0||idx+size>encrypted.size)return null
   aes(encrypted.copyOfRange(idx,idx+size),key,iv)
  } catch(_:Exception) { null }
 }

 fun safeName(v:String):String=v.replace(Regex("""[\\/:*?"<>|\x00-\x1f]"""),"_").replace(Regex("""\s+""")," ").trim().trim('.').ifBlank{"untitled"}.take(180)
 @Suppress("DEPRECATION")
 fun html(v:String):String=Html.fromHtml(v,Html.FROM_HTML_MODE_LEGACY).toString()

 fun exportBook(raw:File,out:File,fileType:String,key:ByteArray){
  out.parentFile?.mkdirs();val type=fileType.uppercase()
  if(type=="PDF"){val d=decryptFile(key,raw.readBytes())?:throw IOException("PDF 복호화에 실패했습니다.");out.writeBytes(d);return}
  if(type !in setOf("AUDIO","EPUB","EPUB_COMIC","COMIC"))throw IOException("지원하지 않는 도서 형식입니다. $type")
  val dir=File(out.parentFile,".work-"+System.nanoTime()).apply{mkdirs()}
  try{
   ZipInputStream(BufferedInputStream(FileInputStream(raw))).use{zin->
    var total=0L
    while(true){val e=zin.nextEntry?:break;if(e.isDirectory)continue;val name=e.name.replace('\\','/');if(name.startsWith("/")||name.split('/').contains("..")||':' in name)throw IOException("압축 파일 경로가 올바르지 않습니다.")
     val f=File(dir,name);f.parentFile?.mkdirs();val data=zin.readBytes();total+=data.size;if(total>2L*1024*1024*1024)throw IOException("도서 파일이 너무 큽니다.")
     val low=name.lowercase();val plain=if(name.startsWith("META-INF/")||low=="mimetype"||low.endsWith(".opf"))data else decryptFile(key,data)?:data
     f.writeBytes(plain)
    }
   }
   zipDirectory(dir,out,type in setOf("AUDIO","EPUB"))
  }finally{dir.deleteRecursively()}
 }
 private fun zipDirectory(root:File,out:File,epub:Boolean){
  ZipOutputStream(BufferedOutputStream(FileOutputStream(out))).use{z->
   val files=root.walkTopDown().filter{it.isFile}.toList()
   if(epub){val mime=File(root,"mimetype");if(mime.isFile){val data=mime.readBytes();val e=ZipEntry("mimetype");e.method=ZipEntry.STORED;e.size=data.size.toLong();e.compressedSize=data.size.toLong();val crc=CRC32();crc.update(data);e.crc=crc.value;z.putNextEntry(e);z.write(data);z.closeEntry()}}
   for(f in files){val name=f.relativeTo(root).invariantSeparatorsPath;if(epub&&name=="mimetype")continue;z.putNextEntry(ZipEntry(name));f.inputStream().use{it.copyTo(z)};z.closeEntry()}
  }
 }
}
