package com.ckchoi.ckreader

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DriveFolderPickerPolicyTest {
 @Test fun folderPathsMoveByDepthWithoutConfusingPathAndProviderId(){
  val first=DriveFolderPickerPolicy.child("","책")
  val nested=DriveFolderPickerPolicy.child(first,"2026년")
  assertEquals("책",first)
  assertEquals("책/2026년",nested)
  assertTrue(DriveFolderPickerPolicy.validId("0AC-abc_123"))
  assertFalse(DriveFolderPickerPolicy.validId("책/2026년"))
  assertFalse(DriveFolderPickerPolicy.validId("https://drive.google.com/abc"))
  assertFalse(DriveFolderPickerPolicy.validId(""))
  for(name in listOf("..",".","a/b","a\\b","")){
   assertThrows(IllegalArgumentException::class.java) {
    DriveFolderPickerPolicy.child(nested,name)
   }
  }
 }

 @Test fun nestedFolderCanBeSelectedBeforeItsChildrenFinishLoading(){
  val reading=DriveFolderPickerPolicy.Node("GDRIVE/READING","reading_id","READING")
  assertTrue(DriveFolderPickerPolicy.canSelect(reading,false))
  assertTrue(DriveFolderPickerPolicy.canSelect(reading,true))
  assertFalse(DriveFolderPickerPolicy.canSelect(reading.copy(id=""),true))
  assertFalse(DriveFolderPickerPolicy.canSelect(
   DriveFolderPickerPolicy.Node("","","GDS 기본 폴더"),false))
 }

 @Test fun shortcutFolderUsesItsTargetIdForRootSelection(){
  assertEquals("target_id",DriveFolderPickerPolicy.rootFolderId("target_id\tshortcut_id"))
  assertEquals("regular_id",DriveFolderPickerPolicy.rootFolderId("regular_id"))
  assertNull(DriveFolderPickerPolicy.rootFolderId(""))
  assertNull(DriveFolderPickerPolicy.rootFolderId("target_id\t"))
  assertNull(DriveFolderPickerPolicy.rootFolderId("target_id\tshortcut_id\textra"))
 }

 @Test fun temporaryBrowseKeepsGdsRootButClearsOrdinaryDriveRoot(){
  for(gds in listOf(true,false)){
   val saved=JSONObject()
    .put("type","drive").put("ckreader_gds",gds)
    .put("root_folder_id","existing_root")
    .put("team_drive","team-0001")
    .put("gds_endpoint","https://gds.example.invalid")
    .put("gds_userid","userid")
    .put("gds_apikey","key")
   val browsing=DriveFolderPickerPolicy.directoryConfig(saved)
   assertEquals(if(gds)"existing_root" else "",browsing.getString("root_folder_id"))
   assertEquals("existing_root",saved.getString("root_folder_id"))
   assertEquals(saved.getString("team_drive"),browsing.getString("team_drive"))
   assertEquals(saved.getString("gds_endpoint"),browsing.getString("gds_endpoint"))
   assertEquals(saved.getString("gds_userid"),browsing.getString("gds_userid"))
  }
  val oauthGds=JSONObject().put("type","drive").put("ckreader_gds",true)
   .put("token","oauth-token").put("root_folder_id","old-oauth-root")
  assertEquals("",DriveFolderPickerPolicy.directoryConfig(oauthGds).getString("root_folder_id"))
 }
 @Test fun libraryCardDisplaysOnlyConfiguredNameAndConnectionKind(){
  fun source(name:String,config:String,type:String="drive") =
   LibrarySource("uuid",type,name,"",extra=config)
  assertEquals("Google Drive",DriveFolderPickerPolicy.storageKind(
   source("연구자료", """{"ckreader_google_auth":true,"type":"drive"}""")))
  assertEquals("RClone · GDS",DriveFolderPickerPolicy.storageKind(
   source("가족 도서관","""{"ckreader_gds":true,"type":"drive"}""")))
  assertEquals("RClone",DriveFolderPickerPolicy.storageKind(
   source("개인 자료","""{"type":"drive"}""")))
  assertEquals("FTP",DriveFolderPickerPolicy.storageKind(source("FTP 책", "{}", "ftp")))
  assertEquals("Book Oasis",DriveFolderPickerPolicy.storageKind(source("북", "{}", "bookoasis")))
 }
}
