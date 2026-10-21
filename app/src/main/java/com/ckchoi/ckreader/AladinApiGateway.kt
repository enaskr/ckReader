package com.ckchoi.ckreader

import android.content.Context
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Fixed hosts and routing rules, with no redirect or credential leakage. */
internal object AladinApiRouting {
 const val PRIMARY="https://aladinapi.ibmer.app"
 const val LEGACY="http://help.dscloud.me:9786"
 private val bookPath=Regex("^/api/(health|books(/[0-9]{1,13})?|isbn/[0-9X]{10,13})$")
 private val coverPath=Regex("^/covers/[0-9]{1,13}$")

 fun validPath(path:String):Boolean=bookPath.matches(path)||coverPath.matches(path)
 fun isCoverPath(path:String):Boolean=coverPath.matches(path)
 fun requiresLegacy(status:Int?,transportFailure:Boolean):Boolean=
  transportFailure||status in setOf(502,503,504)

 /** Recognize URLs in new API responses AND old cached metadata. */
 fun cachedCoverPath(raw:String):String? {
  val url=raw.toHttpUrlOrNull()?:return null
  val isPrimary=url.scheme=="https"&&url.host=="aladinapi.ibmer.app"&&url.port==443
  val isLegacy=url.scheme=="http"&&url.host=="help.dscloud.me"&&url.port==9786
  if(!isPrimary&&!isLegacy)return null
  if(url.username.isNotEmpty()||url.password.isNotEmpty()||url.query!=null||
   url.fragment!=null)return null
  return url.encodedPath.takeIf(::isCoverPath)
 }
}

internal enum class AladinApiStatus {
 HTTPS_OK, LEGACY_OK, KEY_MISSING, AUTH_FAILED,
 HTTPS_UNAVAILABLE, LEGACY_FORBIDDEN, SERVER_ERROR
}
internal data class AladinApiReply(
 val status:AladinApiStatus,
 val bytes:ByteArray?=null,
 val mediaType:String="",
 val httpStatus:Int?=null
)

/**
 * HTTPS is ALWAYS attempted first. Only connection failures / gateway 502-504
 * trigger old HTTP fallback. NEVER send the API key over plaintext HTTP.
 *
 * Explicit host/path allowlists and disabled redirects mitigate token exposure
 * and SSRF through book metadata/cover URLs.
 */
internal class AladinApiGateway(private val context:Context?) {
 private val http=OkHttpClient.Builder()
  .connectTimeout(5,TimeUnit.SECONDS).readTimeout(8,TimeUnit.SECONDS)
  .callTimeout(11,TimeUnit.SECONDS)
  .followRedirects(false).followSslRedirects(false).build()

 private data class Attempt(val code:Int?=null,val data:ByteArray?=null,
  val contentType:String="",val connectionFailed:Boolean=false)
 private fun fetch(base:String,path:String,params:Map<String,String>,
                   key:String?,limit:Int):Attempt {
  check(AladinApiRouting.validPath(path))
  check((base==AladinApiRouting.PRIMARY&&key!=null)||
   (base==AladinApiRouting.LEGACY&&key==null))
  val url=(base+path).toHttpUrlOrNull()?.newBuilder()?:return Attempt(connectionFailed=true)
  params.forEach{(k,v)->url.addQueryParameter(k,v)}
  val target=url.build()
  check((base==AladinApiRouting.PRIMARY&&target.scheme=="https"&&
   target.host=="aladinapi.ibmer.app"&&target.port==443)||
   (base==AladinApiRouting.LEGACY&&target.scheme=="http"&&
    target.host=="help.dscloud.me"&&target.port==9786))
  val request=Request.Builder().url(target)
   .header("User-Agent","ckReader/1.6.15 (Android book metadata)")
   .header("Accept",if(AladinApiRouting.isCoverPath(path))"image/*" else "application/json")
  // Do not add keys or cookies to legacy HTTP requests.
  if(key!=null)request.header("X-API-Key",key)
  return try {
   http.newCall(request.build()).execute().use{response->
    val code=response.code
    if(!response.isSuccessful)return@use Attempt(code=code)
    val media=response.header("Content-Type").orEmpty().lowercase()
    val accepts=if(AladinApiRouting.isCoverPath(path))
     media.startsWith("image/jpeg")||media.startsWith("image/png")||
      media.startsWith("image/webp")||media.startsWith("image/gif")||
      media.startsWith("application/octet-stream")
    else media.contains("application/json")
    if(!accepts)return@use Attempt(code=code,contentType=media)
    val stream=response.body?.byteStream()?:return@use Attempt(code=code,contentType=media)
    val bytes=stream.use {input->
     val output=ByteArrayOutputStream()
     val buffer=ByteArray(8192)
     while(true){
      if(Thread.currentThread().isInterrupted)throw InterruptedException("검색 취소")
      val len=input.read(buffer)
      if(len<0)break
      if(output.size()+len>limit)return Attempt(code=code,contentType=media)
      output.write(buffer,0,len)
     }
     output.toByteArray()
    }
    Attempt(code,bytes,media)
   }
  } catch(error:IOException) {
   Attempt(connectionFailed=true)
  }
 }

 fun request(path:String,params:Map<String,String> = emptyMap(),
             overrideKey:String?=null):AladinApiReply {
  if(BuildConfig.LITE)return AladinApiReply(AladinApiStatus.KEY_MISSING)
  require(AladinApiRouting.validPath(path))
  val key=overrideKey?:context?.let{AladinApiCredentials(it).read()}
  if(key==null||!AladinApiCredentials.validKey(key))
   return AladinApiReply(AladinApiStatus.KEY_MISSING)
  val limit=if(AladinApiRouting.isCoverPath(path))5_000_000 else 1_500_000
  val primary=fetch(AladinApiRouting.PRIMARY,path,params,key,limit)
  if(primary.data!=null)return AladinApiReply(AladinApiStatus.HTTPS_OK,
   primary.data,primary.contentType,primary.code)
  if(primary.code in listOf(401,403))
   return AladinApiReply(AladinApiStatus.AUTH_FAILED,httpStatus=primary.code)
  if(!AladinApiRouting.requiresLegacy(primary.code,primary.connectionFailed))
   return AladinApiReply(AladinApiStatus.SERVER_ERROR,httpStatus=primary.code)
  // A legacy host might be read-only/public; never expose the HTTPS key.
  val legacy=fetch(AladinApiRouting.LEGACY,path,params,null,limit)
  if(legacy.data!=null)return AladinApiReply(AladinApiStatus.LEGACY_OK,
   legacy.data,legacy.contentType,legacy.code)
  return AladinApiReply(
   if(legacy.code in listOf(401,403))AladinApiStatus.LEGACY_FORBIDDEN
   else AladinApiStatus.HTTPS_UNAVAILABLE,
   httpStatus=legacy.code?:primary.code)
 }

 fun cover(path:String):ByteArray?=request(path).bytes

 fun test(key:String?=null):AladinApiReply=request("/api/health",overrideKey=key)
}
