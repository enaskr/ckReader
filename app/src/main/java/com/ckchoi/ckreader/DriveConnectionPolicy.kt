package com.ckchoi.ckreader

import org.json.JSONObject

/** Google login persists only account identity and the selected Drive/root. */
internal object DriveConnectionPolicy {
 fun clean(source:LibrarySource):LibrarySource {
  if(source.type!="drive")return source
  val old=runCatching{JSONObject(source.extra)}.getOrNull()?:return source
  if(!old.optBoolean(GoogleDriveSignIn.AUTH_KEY))return source
  val config=JSONObject().put(GoogleDriveSignIn.AUTH_KEY,true)
   .put(GoogleDriveSignIn.ACCOUNT_KEY,old.optString(GoogleDriveSignIn.ACCOUNT_KEY))
   .put("team_drive",old.optString("team_drive"))
   .put("root_folder_id",old.optString("root_folder_id"))
   .put("scope",if(DriveWritePolicy.googleWriteScope(source.extra))"drive" else "drive.readonly")
  return source.copy(user="",secret="",extra=config.toString())
 }
}
