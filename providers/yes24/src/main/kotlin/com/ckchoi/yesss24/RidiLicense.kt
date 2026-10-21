package com.ckchoi.yesss24

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.ZipInputStream

/** Integrity and validity checks for the vendor's normal license download. */
internal object RidiLicense {
 const val FILE_NAME = "TTS-SDN_KOR_License.dat"
 const val MAX_DOWNLOAD = 64 * 1024
 const val MAX_LICENSE = 32 * 1024

 data class Info(val hash: String, val suffix: String, val expires: Instant) {
  init {
   require(hash.matches(Regex("[0-9a-f]{64}"))) { "잘못된 Selvy 라이선스 해시입니다." }
   require(suffix.matches(Regex("_[0-9]{8}"))) { "잘못된 Selvy 라이선스 버전입니다." }
  }
  fun validAt(now: Instant) = expires.isAfter(now)
 }

 fun matches(bytes: ByteArray, info: Info): Boolean = bytes.isNotEmpty() &&
  bytes.size <= MAX_LICENSE && MessageDigest.getInstance("SHA-256").digest(bytes)
   .joinToString("") { "%02x".format(it) } == info.hash

 fun extract(zipBytes: ByteArray, info: Info): ByteArray {
  require(zipBytes.size <= MAX_DOWNLOAD) { "Selvy 라이선스 다운로드가 너무 큽니다." }
  return ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
   val entry = zip.nextEntry
   require(entry != null && !entry.isDirectory && entry.name == FILE_NAME) {
    "잘못된 Selvy 라이선스 파일입니다."
   }
   val bytes = readBounded(zip, MAX_LICENSE)
   require(matches(bytes, info)) { "Selvy 라이선스 무결성 검증에 실패했습니다." }
   require(zip.nextEntry == null) { "잘못된 Selvy 라이선스 압축 파일입니다." }
   bytes
  }
 }

 fun readBounded(input: InputStream, limit: Int): ByteArray {
  val output = ByteArrayOutputStream()
  val buffer = ByteArray(4096)
  while(true) {
   val read = input.read(buffer, 0, minOf(buffer.size, limit + 1 - output.size()))
   if(read == -1) return output.toByteArray()
   output.write(buffer, 0, read)
   require(output.size() <= limit) { "Selvy 라이선스 응답이 너무 큽니다." }
  }
 }

 /** Validate before touching the active file; a crash cannot leave a partial license. */
 fun replace(target: File, bytes: ByteArray, info: Info) {
  require(matches(bytes, info)) { "Selvy 라이선스 무결성 검증에 실패했습니다." }
  val temp = File.createTempFile("selvy-license-", ".tmp", target.parentFile!!)
  try {
   FileOutputStream(temp).use { it.write(bytes); it.fd.sync() }
   Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
  } finally { temp.delete() }
 }
}
