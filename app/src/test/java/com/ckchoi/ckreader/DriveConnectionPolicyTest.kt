package com.ckchoi.ckreader
import org.junit.Test
import org.junit.Assert.*
import org.json.JSONObject

class DriveConnectionPolicyTest {
 @Test fun existingGoogleConnectionDropsAllRcloneAndGdsCredentials(){
  val original=LibrarySource("stable-id","drive","Google","Books","old-user","old-secret",
   JSONObject().put(GoogleDriveSignIn.AUTH_KEY,true)
    .put(GoogleDriveSignIn.ACCOUNT_KEY,"example@example.invalid")
    .put("team_drive","team123").put("root_folder_id","folder123")
    .put("type","drive").put("token","old-rclone-token").put("client_secret","old-secret")
    .put("gds_endpoint","https://example.invalid").put("ckreader_gds",true).toString())
  val clean=DriveConnectionPolicy.clean(original)
  val config=JSONObject(clean.extra)
  assertEquals("stable-id",clean.id);assertEquals("Books",clean.location)
  assertEquals("",clean.user);assertEquals("",clean.secret)
  assertEquals("team123",config.getString("team_drive"))
  assertEquals("folder123",config.getString("root_folder_id"))
  assertFalse(config.has("token"));assertFalse(config.has("client_secret"))
  assertFalse(config.has("gds_endpoint"));assertFalse(config.has("type"))
  assertTrue(clean.readOnly)
  assertEquals(clean,DriveConnectionPolicy.clean(clean))
 }
 @Test fun importedRcloneOAuthAndGdsConfigRemainUnchanged(){
  val source=LibrarySource("rclone","drive","Imported","",extra=JSONObject()
   .put("type","drive").put("token","rclone-imported-token")
   .put("gds_userid","id").put("gds_apikey","key").put("ckreader_gds",true).toString())
  assertEquals(source,DriveConnectionPolicy.clean(source))
  assertFalse(DriveAccess.google(source))
  assertTrue(RcloneGdsCredentials.usable(JSONObject(source.extra)))
 }
 @Test fun rcloneGdsCanUseImportedOAuthWithoutAppGoogleLogin(){
  val config=JSONObject().put("ckreader_gds",true).put("token","imported-token")
  assertTrue(RcloneGdsCredentials.usable(config))
  val params=RcloneDriveOptions.parameters(config,false)
  assertEquals("imported-token",params.getString("token"))
  assertFalse(params.has(GoogleDriveSignIn.AUTH_KEY))
  assertFalse(params.has("gds_apikey"))
 }
}
