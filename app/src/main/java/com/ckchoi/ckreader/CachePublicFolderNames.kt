package com.ckchoi.ckreader

import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

/** Names used for the user-visible Documents/ckReader cache mirror. */
internal object CachePublicFolderNames {
 private val forbidden=Regex("[\\p{Cntrl}\\\\/:*?\"<>|]")
 private val spaces=Regex("\\s+")
 private val reserved=setOf("CON","PRN","AUX","NUL")+
  (1..9).flatMap{listOf("COM$it","LPT$it")}

 fun clean(name:String):String {
  val candidate=Normalizer.normalize(name,Normalizer.Form.NFC)
   .replace(forbidden,"").replace(spaces," ").trim().trimEnd(' ','.')
   .take(80).trimEnd(' ','.')
  if(candidate.isBlank())return "저장소"
  return if(candidate.substringBefore('.').uppercase(Locale.ROOT) in reserved)"_$candidate"
   else candidate
 }

 fun forSource(id:String,name:String,sources:List<Pair<String,String>>):String {
  val base=clean(name)
  val collision=sources.count{clean(it.second).equals(base,ignoreCase=true)}>1
  if(!collision)return base
  val suffix=MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))
   .take(4).joinToString(""){"%02x".format(it)}
  return "$base-$suffix"
 }
}
