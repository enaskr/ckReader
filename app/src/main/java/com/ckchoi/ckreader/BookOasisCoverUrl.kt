package com.ckchoi.ckreader

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Catalog cover_image values are usually filenames relative to the server's covers directory. */
internal object BookOasisCoverUrl {
 fun resolve(base:HttpUrl,reference:String):HttpUrl? {
  val value=reference.trim()
  if(value.isEmpty()||value.startsWith("//")||value.contains("..")||'\\' in value)return null
  val target=when {
   value.startsWith("http://")||value.startsWith("https://")->value.toHttpUrlOrNull()
   value.startsWith("/api/")||value.startsWith("api/")||
    value.startsWith("/static/")||value.startsWith("static/")->base.resolve(value)
   else->{
    if(Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(value))return null
    val filename=value.trimStart('/')
    val path=if(filename.startsWith("covers/",true))filename else "covers/$filename"
    base.resolve(if(value.startsWith('/')&&filename.startsWith("covers/",true))"/$path" else path)
   }
  }
  return target?.takeIf{it.scheme==base.scheme&&it.host==base.host&&it.port==base.port}
 }
}
