package com.ckchoi.ckreader

import android.accounts.Account
import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Google Identity Services consent and scoped Drive access.
 *
 * The Google account is persisted; access tokens stay in process memory and
 * are renewed by Play services on demand. No OAuth client secret is embedded.
 */
internal object GoogleDriveSignIn {
 const val READ_SCOPE="https://www.googleapis.com/auth/drive.readonly"
 const val WRITE_SCOPE="https://www.googleapis.com/auth/drive"
 const val EMAIL_SCOPE="https://www.googleapis.com/auth/userinfo.email"
 const val ACCOUNT_KEY="ckreader_google_account"
 const val AUTH_KEY="ckreader_google_auth"
 const val SHARED_KEY="team_drive"
 private const val TOKEN_VALIDITY_MS=38*60*1000L
 private val sessionLock=Any()
 private data class CachedToken(val token:String,val refreshed:Long,val scopes:Set<String>)
 private val tokens=HashMap<String,CachedToken>()

 data class SharedDrive(val id:String,val name:String)
 data class Session(val email:String,val token:String,val writeGranted:Boolean)
 fun request(email:String?=null,requestWrite:Boolean=true):AuthorizationRequest {
  check(!BuildConfig.LITE){"Google 로그인은 Lite 버전에서 사용할 수 없습니다."}
  val builder=AuthorizationRequest.builder()
   .setRequestedScopes((if(requestWrite)listOf(READ_SCOPE,WRITE_SCOPE,EMAIL_SCOPE)
    else listOf(READ_SCOPE,EMAIL_SCOPE)).map(::Scope))
  if(!email.isNullOrBlank())builder.setAccount(Account(email,"com.google"))
  return builder.build()
 }

 fun sessionFromAuthorization(result:AuthorizationResult):Session {
  check(!BuildConfig.LITE){"Google 로그인은 Lite 버전에서 사용할 수 없습니다."}
  check(!result.hasResolution()){"Google 로그인 동의가 필요합니다."}
  check(READ_SCOPE in result.grantedScopes||WRITE_SCOPE in result.grantedScopes){
   "Google Drive 접근 권한이 필요합니다."}
  val access=result.accessToken?.takeIf{it.isNotBlank()}
   ?:error("Google 인증 토큰이 반환되지 않았습니다.")
  val email=result.toGoogleSignInAccount()?.email
   ?.takeIf{it.contains('@')} ?: userEmail(access)
  check(email.contains('@')){"Google 계정을 확인할 수 없습니다."}
  remember(email,access,result.grantedScopes.toSet())
  return Session(email,access,WRITE_SCOPE in result.grantedScopes)
 }

 private fun userEmail(token:String):String =
  try {
   val result=getJson("https://www.googleapis.com/oauth2/v3/userinfo",token)
   result.optString("email")
  }catch(_:Exception){""}

 private fun remember(email:String,token:String,scopes:Set<String>)=synchronized(sessionLock){
  tokens[email]=CachedToken(token,System.currentTimeMillis(),scopes)
 }

 fun token(context:Context,email:String,requireWrite:Boolean=false):String {
  check(!BuildConfig.LITE){"Google 로그인은 Lite 버전에서 사용할 수 없습니다."}
  check(email.isNotBlank()&&email.contains('@')){"연결한 Google 계정이 올바르지 않습니다."}
  synchronized(sessionLock){
   tokens[email]?.takeIf{System.currentTimeMillis()-it.refreshed<TOKEN_VALIDITY_MS&&
    (if(requireWrite)WRITE_SCOPE in it.scopes else READ_SCOPE in it.scopes||WRITE_SCOPE in it.scopes)}
    ?.let{return it.token}
  }
  // Rclone bridge calls this off the UI thread. If Google requires new
  // interactive consent, fail clearly instead of silently switching accounts.
  check(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper()){
   "Google 인증 갱신은 백그라운드에서 실행해야 합니다."
  }
  val result=Tasks.await(Identity.getAuthorizationClient(context).authorize(request(email,requireWrite)),
   35,TimeUnit.SECONDS)
  check(!result.hasResolution()){
   "Google 로그인 권한이 만료되었습니다. 저장소의 Google 로그인을 다시 실행하세요."
  }
  check(if(requireWrite)WRITE_SCOPE in result.grantedScopes else
   READ_SCOPE in result.grantedScopes||WRITE_SCOPE in result.grantedScopes){
   if(requireWrite)"Google Drive 쓰기 권한이 없습니다. Google Drive 연결에서 다시 승인하세요."
   else "Google Drive 읽기 권한이 없습니다."}
  val validated=result.toGoogleSignInAccount()?.email
  check(validated.isNullOrBlank()||validated.equals(email,ignoreCase=true)){
   "다른 Google 계정이 선택되었습니다. 기존 계정으로 로그인하세요."
  }
  val access=result.accessToken?.takeIf{it.isNotBlank()}
   ?:error("Google 인증 토큰을 갱신할 수 없습니다.")
  remember(email,access,result.grantedScopes.toSet())
  return access
 }

 fun sharedDrives(token:String):List<SharedDrive> {
  check(!BuildConfig.LITE){"Google 로그인은 Lite 버전에서 사용할 수 없습니다."}
  val drives=ArrayList<SharedDrive>()
  var page=""
  val pages=HashSet<String>()
  while(drives.size<2000){
   val base="https://www.googleapis.com/drive/v3/drives?pageSize=100&fields="+
    URLEncoder.encode("nextPageToken,drives(id,name)","UTF-8")
   val url=if(page.isBlank())base else base+"&pageToken="+URLEncoder.encode(page,"UTF-8")
   val response=getJson(url,token)
   val entries=response.optJSONArray("drives")
   if(entries!=null)for(i in 0 until entries.length()){
    val d=entries.optJSONObject(i)?:continue
    val id=d.optString("id")
    if(id.matches(Regex("[A-Za-z0-9_-]{3,256}")))
     drives.add(SharedDrive(id,d.optString("name").ifBlank{"공유 드라이브"}))
   }
   page=response.optString("nextPageToken")
   if(page.isEmpty()||!pages.add(page))break
  }
  return drives.distinctBy{it.id}
 }

 private fun getJson(url:String,token:String):JSONObject {
  val conn=(URL(url).openConnection() as HttpURLConnection).apply{
   requestMethod="GET"
   connectTimeout=12_000
   readTimeout=15_000
   setRequestProperty("Authorization","Bearer "+token)
   setRequestProperty("Accept","application/json")
   instanceFollowRedirects=false
  }
  try{
   val code=conn.responseCode
   if(code==401)error("Google 로그인 인증이 만료되었습니다. 다시 로그인하세요.")
   if(code==403)error("Google Drive API 권한이 없거나 Workspace 관리자가 접근을 차단했습니다.")
   check(code in 200..299){"Google Drive 조회 실패 (HTTP $code)"}
   val text=conn.inputStream.bufferedReader().use{it.readText()}
   require(text.length<=1_000_000){"Google Drive 응답이 너무 큽니다."}
   return JSONObject(text)
  } finally{conn.disconnect()}
 }
}
