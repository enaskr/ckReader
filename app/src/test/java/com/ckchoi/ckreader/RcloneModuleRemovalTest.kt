package com.ckchoi.ckreader

import org.junit.Assert.assertEquals
import org.junit.Test

class RcloneModuleRemovalTest {
 @Test fun removesOnlyRcloneBackedDriveConnections() {
  val rclone=LibrarySource("rclone","drive","RClone","",extra="{}")
  val gds=LibrarySource("gds","drive","GDS","",extra="{\"ckreader_gds\":true}")
  val google=LibrarySource("google","drive","Google Drive","",
   extra="{\"${GoogleDriveSignIn.AUTH_KEY}\":true}")
  val webdav=LibrarySource("webdav","webdav","WebDAV","")
  assertEquals(listOf("rclone","gds"),
   RcloneModuleRemoval.affectedSources(listOf(rclone,gds,google,webdav)).map{it.id})
 }
}
