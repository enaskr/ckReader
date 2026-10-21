package com.ckchoi.ckreader
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GoogleDriveSignInTest {
 @Test fun legacyMyDriveRemainsReadOnlyWithoutNewConsent(){
  val stored=LibrarySource("google","drive","Google","",extra=JSONObject()
   .put(GoogleDriveSignIn.AUTH_KEY,true)
   .put(GoogleDriveSignIn.ACCOUNT_KEY,"user@example.com")
   .put("team_drive","").toString())
  val result=JSONObject(DriveConnectionPolicy.clean(stored).extra)
  assertEquals("drive.readonly",result.getString("scope"))
  assertEquals("",result.getString("team_drive"))
  assertEquals("user@example.com",result.getString(GoogleDriveSignIn.ACCOUNT_KEY))
  assertFalse(result.has("token"));assertFalse(result.has("type"))
 }
 @Test fun writeAuthorizedDriveKeepsItsScopeWithoutRcloneToken(){
  val stored=LibrarySource("google","drive","Google","",extra=JSONObject()
   .put(GoogleDriveSignIn.AUTH_KEY,true)
   .put(GoogleDriveSignIn.ACCOUNT_KEY,"user@example.com")
   .put("scope","drive").put("token","must-remove").toString())
  val clean=DriveConnectionPolicy.clean(stored)
  assertEquals("drive",JSONObject(clean.extra).getString("scope"))
  assertFalse(clean.readOnly)
  assertFalse(JSONObject(clean.extra).has("token"))
 }
 @Test fun sharedDriveIdIsPreservedAndImportedRcloneFieldsExcluded(){
  val stored=LibrarySource("google","drive","Google","",extra=JSONObject()
   .put(GoogleDriveSignIn.AUTH_KEY,true)
   .put(GoogleDriveSignIn.ACCOUNT_KEY,"member@example.com")
   .put("team_drive","0ACxyzSharedDrive1").put("root_folder_id","folder1")
   .put("token","old-native-token").toString())
  val result=JSONObject(DriveConnectionPolicy.clean(stored).extra)
  assertEquals("0ACxyzSharedDrive1",result.getString("team_drive"))
  assertEquals("folder1",result.getString("root_folder_id"))
  assertTrue(result.getBoolean(GoogleDriveSignIn.AUTH_KEY))
  assertFalse(result.has("token"));assertFalse(result.has("gds_userid"))
 }
}
