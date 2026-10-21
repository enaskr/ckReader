package com.ckchoi.yesss24

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class RidiLicenseTest {
 private val bytes = "vendor license fixture".toByteArray()
 private val expires = Instant.parse("2027-10-13T00:00:00Z")
 private fun info(data: ByteArray = bytes) = RidiLicense.Info(
  MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) },
  "_20271013", expires)
 private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { output ->
  ZipOutputStream(output).use { zip -> entries.forEach { (name, data) ->
   zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry()
  } }
 }.toByteArray()
 private fun rejected(action: () -> Unit) {
  try { action(); fail("Expected the invalid license to be rejected") }
  catch(_: IllegalArgumentException) { }
 }

 @Test fun acceptsOnlyThePublishedFileHash() {
  assertArrayEquals(bytes, RidiLicense.extract(zip(RidiLicense.FILE_NAME to bytes), info()))
  rejected { RidiLicense.extract(zip(RidiLicense.FILE_NAME to "changed".toByteArray()), info()) }
 }
 @Test fun refusesPathsOutsideTheLicenseFile() {
  for(name in listOf("../${RidiLicense.FILE_NAME}", "nested/${RidiLicense.FILE_NAME}", "/${RidiLicense.FILE_NAME}"))
   rejected { RidiLicense.extract(zip(name to bytes), info()) }
 }
 @Test fun refusesExtraEntriesAndEmptyArchives() {
  rejected { RidiLicense.extract(zip(), info()) }
  rejected { RidiLicense.extract(zip(RidiLicense.FILE_NAME to bytes, "other" to bytes), info()) }
 }
 @Test fun refusesZipExpansionBeyondTheLimit() {
  val huge = ByteArray(RidiLicense.MAX_LICENSE + 1)
  rejected { RidiLicense.extract(zip(RidiLicense.FILE_NAME to huge), info(huge)) }
 }
 @Test fun boundsNetworkResponseWithoutRelyingOnContentLength() {
  assertArrayEquals(bytes, RidiLicense.readBounded(ByteArrayInputStream(bytes), bytes.size))
  rejected { RidiLicense.readBounded(ByteArrayInputStream(bytes), bytes.size - 1) }
 }
 @Test fun rejectsUntrustedDownloadSuffixesAndMalformedHashes() {
  for(suffix in listOf("/../file", "_20271013?other=1", "", "_abc"))
   rejected { RidiLicense.Info(info().hash, suffix, expires) }
  rejected { RidiLicense.Info("invalid", "_20271013", expires) }
 }
 @Test fun expiresAtThePublishedInstant() {
  assertTrue(info().validAt(expires.minusSeconds(1)))
  assertFalse(info().validAt(expires))
  assertFalse(info().validAt(expires.plusSeconds(1)))
 }
 @Test fun failedValidationPreservesTheActiveLicense() {
  val dir = Files.createTempDirectory("selvy-license-test").toFile()
  try {
   val target = File(dir, RidiLicense.FILE_NAME).apply { writeText("previous license") }
   rejected { RidiLicense.replace(target, "corrupt".toByteArray(), info()) }
   assertEquals("previous license", target.readText())
   assertEquals(1, dir.listFiles()!!.size)
   RidiLicense.replace(target, bytes, info())
   assertArrayEquals(bytes, target.readBytes())
   assertEquals(1, dir.listFiles()!!.size)
  } finally { dir.deleteRecursively() }
 }
}
