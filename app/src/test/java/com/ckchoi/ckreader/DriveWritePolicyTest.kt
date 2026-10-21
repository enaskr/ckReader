package com.ckchoi.ckreader

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DriveWritePolicyTest {
 private fun folder(vararg permissions:String)=JSONObject().put("mimeType","application/vnd.google-apps.folder")
  .put("capabilities",JSONObject().apply{permissions.forEach{put(it,true)}})
 @Test fun deniedOrMissingCapabilitiesUseLocalDbOnly() {
  assertFalse(DriveWritePolicy.folderWritable(JSONObject()))
  assertFalse(DriveWritePolicy.folderWritable(folder()))
  assertFalse(DriveWritePolicy.folderWritable(folder("canEdit")))
  assertFalse(DriveWritePolicy.folderWritable(folder("canAddChildren").put("trashed",true)))
 }
 @Test fun folderCreationAndBookMovementRequireTheirOwnPermissions() {
  assertTrue(DriveWritePolicy.folderWritable(folder("canAddChildren")))
  assertFalse(DriveWritePolicy.folderWritable(folder("canAddChildren"),true))
  assertTrue(DriveWritePolicy.folderWritable(folder("canAddChildren","canRemoveChildren"),true))
  assertTrue(DriveWritePolicy.folderWritable(folder("canAddChildren","canMoveChildrenWithinDrive")
   .put("driveId","shared-drive"),true))
  assertFalse(DriveWritePolicy.folderWritable(folder("canAddChildren","canRemoveChildren")
   .put("driveId","shared-drive"),true))
  assertFalse(DriveWritePolicy.fileMovable(JSONObject().put("capabilities",JSONObject().put("canEdit",true))))
  assertTrue(DriveWritePolicy.fileMovable(JSONObject().put("capabilities",JSONObject().put("canMoveItemWithinDrive",true))))
 }
 @Test fun readOnlyOauthScopesCannotAuthorizeWrites() {
  for(scope in listOf("drive.readonly","https://www.googleapis.com/auth/drive.readonly","drive.readonly, drive.metadata.readonly"))
   assertTrue(DriveWritePolicy.readOnlyScope(JSONObject().put("scope",scope).toString()))
  for(scope in listOf("","drive","drive.file","drive.readonly,drive"))
   assertFalse(DriveWritePolicy.readOnlyScope(JSONObject().put("scope",scope).toString()))
  assertFalse(DriveWritePolicy.googleWriteScope("{}"))
  assertFalse(DriveWritePolicy.googleWriteScope("{\"scope\":\"drive.file\"}"))
  assertTrue(DriveWritePolicy.googleWriteScope("{\"scope\":\"drive\"}"))
 }
}
