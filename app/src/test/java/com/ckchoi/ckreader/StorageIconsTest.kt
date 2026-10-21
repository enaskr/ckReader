package com.ckchoi.ckreader

import org.junit.Assert.*
import org.junit.Test

class StorageIconsTest {
 @Test fun bundledChoicesHaveUniqueStableIdsAndDrawableResources() {
  val choices=StorageIcons.builtIn
  assertEquals(64,choices.size)
  assertTrue(listOf("user_blue_smilebox","user_five","user_infinicloud","user_baidu","user_terabox")
   .all{id->choices.any{it.id==id}})
  assertTrue(listOf("user_blue_folder","user_webdav_cloud","user_bookoasis","user_orange_folder")
   .all{id->choices.any{it.id==id}})
  assertEquals(choices.size,choices.map{it.id}.distinct().size)
  assertTrue(choices.all{it.resource!=0&&it.label.isNotBlank()})
  assertTrue(listOf("google_drive","webdav","ftp_folder","smb","nas","rclone")
   .all{id->choices.any{it.id==id}})
 }
 @Test fun uploadedIconNamesCannotEscapeThePrivateIconDirectory() {
  assertEquals("upload_12345678-1234-1234-1234-123456789abc.png",
   SourceIconFiles.filename("upload_12345678-1234-1234-1234-123456789abc"))
  for(value in listOf("../sources.xml","/sdcard/icon.png","upload_../icon","google_drive"))
   assertNull(SourceIconFiles.filename(value))
 }
}
