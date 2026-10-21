package com.ckchoi.ckreader

import org.junit.Assert.*
import org.junit.Test

class BookMetadataPriorityTest {
 @Test fun driveYamlRemainsAuthoritativeEvenOverManualSearchResult() {
  for(type in listOf("drive","webdav","ftp","local")) {
   assertFalse(BookMetadataPriority.mayReplaceExisting("chosen",type,true))
   assertFalse(BookMetadataPriority.mayReplaceExisting("matched",type,true))
  }
 }
 @Test fun manualSearchCanReplaceNonYamlMetadataOnly() {
  assertTrue(BookMetadataPriority.mayReplaceExisting("chosen","drive",false))
  assertTrue(BookMetadataPriority.mayReplaceExisting("chosen","ftp",false))
  assertFalse(BookMetadataPriority.mayReplaceExisting("matched","ftp",false))
 }
}
