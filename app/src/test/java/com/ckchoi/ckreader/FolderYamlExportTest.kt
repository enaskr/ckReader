package com.ckchoi.ckreader

import org.junit.Assert.*
import org.junit.Test

class FolderYamlExportTest {
 @Test fun everyFileKeepsItsOwnMetadataAndQuotedFilename() {
  val bytes=FolderYamlExport.encode(listOf(
   FolderYamlExport.Record("[작가] 첫째: 책.epub",mapOf("title" to "첫째: 책","author" to "작가 A","isbn" to "0012345678901","summary" to "첫 줄\n둘째 줄")),
   FolderYamlExport.Record("yes.pdf",mapOf("title" to "yes","author" to "작가 B","publishedDate" to "2026-10-17"))
  ))!!
  val doc=FolderYamlMetadata.document(bytes)!!
  assertEquals("작가 A",doc.select("[작가] 첫째: 책.epub",false)?.values?.get("author"))
  assertEquals("0012345678901",doc.select("[작가] 첫째: 책.epub",false)?.values?.get("isbn"))
  assertEquals("yes",doc.select("yes.pdf",false)?.values?.get("title"))
  assertEquals("작가 B",doc.select("yes.pdf",false)?.values?.get("author"))
  assertNull(doc.select("unlisted.epub",true))
 }
 @Test fun excludesStorageSecretsAndHiddenOrNonSiblingNames() {
  val bytes=FolderYamlExport.encode(listOf(
   FolderYamlExport.Record("book.epub",mapOf("title" to "Book","secret" to "sensitive-value","user" to "account","location" to "private-origin")),
   FolderYamlExport.Record(".hidden.epub",mapOf("title" to "Hidden")),
   FolderYamlExport.Record("other/book.epub",mapOf("title" to "Elsewhere"))
  ))!!
  val text=bytes.toString(Charsets.UTF_8)
  for(value in listOf("sensitive-value","account","private-origin","Hidden","Elsewhere"))assertFalse(text.contains(value))
 }
 @Test fun embeddedThumbnailSurvivesPortableYamlRoundTrip() {
  val jpeg=ByteArray(500){(it%127).toByte()}.apply{this[0]=255.toByte();this[1]=216.toByte();this[2]=255.toByte()}
  val bytes=FolderYamlExport.encode(listOf(FolderYamlExport.Record("book.epub",mapOf("title" to "Book"),jpeg)))!!
  val parsed=FolderYamlMetadata.read(bytes,"book.epub",false)!!
  assertArrayEquals(jpeg,parsed.embeddedCover)
  assertFalse(parsed.values.containsKey("cover"))
 }
 @Test fun noReceivedWebMetadataProducesNoManifest() {
  assertNull(FolderYamlExport.encode(emptyList()))
  assertNull(FolderYamlExport.encode(listOf(FolderYamlExport.Record("book.epub",mapOf("author" to "Author")))))
 }
}
