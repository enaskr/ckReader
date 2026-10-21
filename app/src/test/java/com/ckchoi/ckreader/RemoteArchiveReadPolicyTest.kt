package com.ckchoi.ckreader

import org.junit.Assert.*
import org.junit.Test

class RemoteArchiveReadPolicyTest {
 @Test fun allDriveModesAndSizesUseValidatedLocalZip() {
  for(size in listOf(-1L,1L,89_200_000L,120L*1024*1024,422L*1024*1024)) {
   for(format in listOf("zip","cbz","CBZ","ZIP")) {
    assertTrue("Unexpected mode for "+format+" at "+size,RemoteArchiveReadPolicy.useDisk("drive",format,size))
   }
  }
  // GDS and normal Drive now use identical paths and cannot regress with
  // different OAuth / rclone imported configuration flags.
  for(extra in listOf(
   """{"ckreader_gds":true,"type":"drive"}""",
   """{"type":"gds","gds_endpoint":"https://example.invalid"}""",
   """{"gds_endpoint":"https://example.invalid"}""",
   """{"ckreader_google_auth":true,"type":"drive"}""",
   """{"type":"drive"}"""
  )){
   val source=LibrarySource("id","drive","Drive","",extra=extra)
   assertTrue(RemoteArchiveReadPolicy.useDisk(source.type,"cbz",89_200_000,source.readOnly))
  }
 }
 @Test fun otherFormatsAndProvidersKeepTheirOriginalBehavior(){
  for(type in listOf("bookoasis","webdav","ftp","sftp","local"))
   assertFalse(RemoteArchiveReadPolicy.useDisk(type,"zip",89_200_000))
  for(format in listOf("epub","pdf","txt","md"))
   assertFalse(RemoteArchiveReadPolicy.useDisk("drive",format,89_200_000))
 }
}
