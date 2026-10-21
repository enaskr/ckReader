package com.ckchoi.ckreader

import org.json.JSONObject

/** A positive Drive capability is required; unknown permissions mean local DB only. */
internal object DriveWritePolicy {
 fun googleWriteScope(extra:String):Boolean=runCatching {
  JSONObject(extra).optString("scope").split(Regex("[,\\s]+"))
   .any{it.substringAfterLast('/')=="drive"}
 }.getOrDefault(false)
 fun readOnlyScope(extra:String):Boolean=runCatching {
  val scopes=JSONObject(extra).optString("scope").split(Regex("[,\\s]+"))
   .filter{it.isNotBlank()}.map{it.substringAfterLast('/')}
  scopes.isNotEmpty()&&scopes.all{it in setOf("drive.readonly","drive.metadata.readonly")}
 }.getOrDefault(false)
 fun folderWritable(file:JSONObject,moveChildren:Boolean=false):Boolean {
  if(file.optBoolean("trashed")||file.optString("mimeType")!="application/vnd.google-apps.folder")return false
  val capabilities=file.optJSONObject("capabilities")?:return false
  return capabilities.optBoolean("canAddChildren")&&(!moveChildren||
   capabilities.optBoolean(if(file.optString("driveId").isNotBlank())
    "canMoveChildrenWithinDrive" else "canRemoveChildren"))
 }
 fun fileMovable(file:JSONObject):Boolean=!file.optBoolean("trashed")&&
  file.optJSONObject("capabilities")?.optBoolean("canMoveItemWithinDrive")==true
}
