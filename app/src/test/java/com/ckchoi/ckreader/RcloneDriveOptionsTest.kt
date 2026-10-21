package com.ckchoi.ckreader

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RcloneDriveOptionsTest {
 @Test fun gdsPreservesImportedCredentialsAndOnlyReplacesRootId() {
  val imported=LibraryRepository.rcloneRemotes(
   "[remote]\ntype=drive\ngds_userid=example-user\ngds_apikey=example-secret"+
    "\ngds-mode=service\ngds_endpoint=https://gds.example.invalid\nroot_folder_id=oldroot"
  ).getValue("remote")
  val copy=JSONObject(imported.toString())
  copy.put("ckreader_gds",true).put("root_folder_id","new-folder_123")
  val options=RcloneDriveOptions.parameters(copy,existing=false)
  assertEquals("example-user",options.getString("gds_userid"))
  assertEquals("example-secret",options.getString("gds_apikey"))
  assertEquals("https://gds.example.invalid",options.getString("gds_endpoint"))
  assertEquals("service",options.getString("gds_mode"))
  assertEquals("new-folder_123",options.getString("root_folder_id"))
  assertFalse(options.has("ckreader_gds"))
  assertFalse(options.has("gds-mode"))
  assertEquals("oldroot",imported.getString("root_folder_id"))
 }

 @Test fun clearingGdsRootIdKeepsAccountParameters() {
  val config=JSONObject()
   .put("ckreader_gds",true)
   .put("gds_userid","uid").put("gds_apikey","key")
   .put("gds_endpoint","https://example.invalid")
   .put("root_folder_id","")
  val options=RcloneDriveOptions.parameters(config,existing=true)
  assertTrue(options.has("root_folder_id"))
  assertEquals("",options.getString("root_folder_id"))
  assertEquals("key",options.getString("gds_apikey"))
 }

 @Test fun normalDriveDoesNotForwardGdsCredentials() {
  val config=JSONObject().put("ckreader_gds",false).put("type","drive")
   .put("token","example-oauth").put("team_drive","team123")
   .put("gds_userid","hidden").put("gds_apikey","hidden")
  val options=RcloneDriveOptions.parameters(config,existing=true)
  assertEquals("example-oauth",options.getString("token"))
  assertEquals("team123",options.getString("team_drive"))
  assertEquals("",options.getString("gds_userid"))
  assertEquals("",options.getString("gds_apikey"))
 }
}
