package com.ckchoi.ckreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RcloneImportTest {
 @Test fun driveImportRetainsSharedDriveAndToken() {
  val file = """
   [personal]
   type = drive
   token = {"access_token":"example"}
   team_drive = shared-example
   root_folder_id = root-example
   [unrelated]
   type = ftp
   host = example.invalid
  """.trimIndent()
  val entries=LibraryRepository.rcloneRemotes(file)
  assertEquals(1,entries.size)
  assertEquals("drive",entries["personal"]?.optString("type"))
  assertEquals("shared-example",entries["personal"]?.optString("team_drive"))
  assertEquals("root-example",entries["personal"]?.optString("root_folder_id"))
  assertTrue(entries["personal"]?.optString("token")?.contains("example") == true)
  assertFalse(entries.containsKey("unrelated"))
 }

 @Test fun gdsParametersAreImportedWithoutChangingValues() {
  val file = """
   [gds_example]
   type = drive
   gds_userid = demo-user
   gds_apikey = demo-placeholder
   gds-mode = sync
   gds_endpoint = https://gds.example.invalid/api
  """.trimIndent()
  val gds=LibraryRepository.rcloneRemotes(file)["gds_example"]!!
  assertEquals("drive",gds.optString("type"))
  assertEquals("demo-user",gds.optString("gds_userid"))
  assertEquals("demo-placeholder",gds.optString("gds_apikey"))
  assertEquals("sync",gds.optString("gds-mode"))
  assertEquals("https://gds.example.invalid/api",gds.optString("gds_endpoint"))
 }

 @Test fun gdsTypeIsDiscoverable() {
  val gds=LibraryRepository.rcloneRemotes(
   "[gds_sample]\ntype=gds\ngds_userid=demo\ngds_apikey=placeholder\ngds_endpoint=https://example.invalid"
  )
  assertTrue(gds.containsKey("gds_sample"))
 }
}
