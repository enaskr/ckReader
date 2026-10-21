package com.ckchoi.yesss24

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import javax.net.ssl.HttpsURLConnection

/** Uses the same public license update endpoint as the original RIDI application. */
internal object RidiLicenseUpdate {
 private const val BASE = "https://viewer-ota.ridicdn.net/selvy_voices/license/v2/"
 private const val ASSET = "voice/selvy-ii-license"
 private fun parse(text: String): RidiLicense.Info = JSONObject(text).let {
  RidiLicense.Info(it.getString("licenseHash"), it.getString("licenseSuffix"),
   OffsetDateTime.parse(it.getString("licenseExpireDate")).toInstant())
 }
 private fun encode(info: RidiLicense.Info) = JSONObject().put("licenseHash", info.hash)
  .put("licenseSuffix", info.suffix).put("licenseExpireDate", info.expires.toString()).toString()

 fun ensureCurrent(context: Context, forceRefresh: Boolean = false) {
  val now = Instant.now()
  val license = File(RidiVoiceData.voiceDir(context), RidiLicense.FILE_NAME)
  val state = File(license.parentFile, "selvy-license.json")
  var current = runCatching {
   parse(state.readText()).takeIf { license.length() <= RidiLicense.MAX_LICENSE &&
    RidiLicense.matches(license.readBytes(), it) }
  }.getOrNull()
  fun apply(info: RidiLicense.Info, bytes: ByteArray) {
   RidiLicense.replace(license, bytes, info)
   state.writeText(encode(info))
   current = info
   Log.i("CremaAiVoice", "Selvy II license updated: ${info.suffix}, expires=${info.expires}")
  }
  val bundled = parse(context.assets.open("$ASSET.json").bufferedReader().use { it.readText() })
  if(bundled.validAt(now) && (current == null || bundled.expires.isAfter(current!!.expires))) {
   val bytes = context.assets.open("$ASSET.zip").use { it.readBytes() }
   apply(bundled, RidiLicense.extract(bytes, bundled))
  }
  val preferences = context.getSharedPreferences("selvy_license_update", Context.MODE_PRIVATE)
  val needsRefresh = current?.expires?.isBefore(now.plus(Duration.ofDays(30))) != false
  val lastAttempt = preferences.getLong("last_attempt", 0L)
  if(forceRefresh || current?.validAt(now) != true ||
   (needsRefresh && now.toEpochMilli() - lastAttempt >= Duration.ofDays(1).toMillis())) {
   preferences.edit().putLong("last_attempt", now.toEpochMilli()).apply()
   try {
    val remote = parse(download("${BASE}licenseInfo.json", 4096).toString(Charsets.UTF_8))
    check(remote.validAt(now)) { "서버의 Selvy 라이선스가 만료됐습니다." }
    if(current == null || remote.expires >= current!!.expires) {
     if(remote.hash != current?.hash || forceRefresh) {
      val zip = download("${BASE}TTS-SDN_KOR_License${remote.suffix}.dat.zip", RidiLicense.MAX_DOWNLOAD)
      apply(remote, RidiLicense.extract(zip, remote))
     }
    }
   } catch(error: Exception) {
    Log.w("CremaAiVoice", "Selvy II license refresh failed; keeping verified local license", error)
    if(current?.validAt(now) != true)
     throw IllegalStateException("Selvy II 음성 라이선스를 갱신하지 못했습니다. 인터넷 연결 후 다시 재생해주세요.", error)
   }
  }
  check(current?.validAt(now) == true) { "Selvy II 음성 라이선스가 만료됐습니다. 인터넷 연결 후 다시 재생해주세요." }
 }

 private fun download(url: String, limit: Int): ByteArray {
  val connection = URL(url).openConnection() as HttpsURLConnection
  connection.connectTimeout = 5000
  connection.readTimeout = 5000
  connection.instanceFollowRedirects = false
  try {
   check(connection.responseCode == 200) { "Selvy 라이선스 서버 응답: ${connection.responseCode}" }
   check(connection.contentLengthLong <= limit) { "Selvy 라이선스 응답이 너무 큽니다." }
   return connection.inputStream.use {
    RidiLicense.readBounded(it, limit)
   }
  } finally { connection.disconnect() }
 }
}
