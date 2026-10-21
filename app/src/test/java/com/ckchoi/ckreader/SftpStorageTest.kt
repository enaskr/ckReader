package com.ckchoi.ckreader

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class SftpStorageTest {
 private fun config(path:String)=LibrarySource("sftp-test","sftp","test","sftp://example.org:22$path",
  "reader","x",JSONObject().put("host_key_sha256","SHA256:"+"A".repeat(43)).toString())
 @Test fun acceptsValidPinnedFingerprints() {
  assertTrue(SftpStorage.validFingerprint("SHA256:"+"A".repeat(43)))
  assertFalse(SftpStorage.validFingerprint(""))
  assertFalse(SftpStorage.validFingerprint("invalid"))
 }
 @Test fun pathsRemainWithinBaseFolder(){
  assertEquals("/ebooks/2026/book.epub",SftpStorage.remotePath(config("/ebooks"),"2026/book.epub"))
  assertEquals("/ebooks",SftpStorage.remotePath(config("/ebooks"),""))
  assertTrue(config("/ebooks").readOnly)
  assertTrue(FolderYamlMetadata.supports("sftp"))
 }
 @Test(expected=IllegalArgumentException::class)
 fun preventsTraversal(){SftpStorage.remotePath(config("/ebooks"),"../outside")}
}
