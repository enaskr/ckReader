package com.ckchoi.ckreader

import org.json.JSONObject

/**
 * Separate ordinary Drive configuration from GDS. In GDS mode all imported
 * credentials/settings remain unchanged except the explicitly edited root ID.
 */
internal object RcloneDriveOptions {
 fun parameters(config:JSONObject,existing:Boolean):JSONObject {
  val parameters=JSONObject()
  if(config.optBoolean("ckreader_gds",false) && RcloneGdsCredentials.custom(config)) {
   for(key in config.keys()) {
    if(key!="type" && key!="ckreader_gds" && key!="gds-mode" &&
     key!="ckreader_remote_name")
     parameters.put(key,config.getString(key))
   }
   // Native wiserain/rclone names this option gds_mode.
   if(!parameters.has("gds_mode") && config.has("gds-mode"))
    parameters.put("gds_mode",config.getString("gds-mode"))
  } else {
   val normalOptions=listOf("token","client_id","client_secret","scope","team_drive",
    "root_folder_id","shared_with_me","resource_key","service_account_file")
   for(key in normalOptions)
    config.optString(key).takeIf{it.isNotBlank()}?.let{parameters.put(key,it)}
   // GDS selection with standard Drive OAuth still remains read-only at
   // LibrarySource level, but uses the regular Drive backend parameters.
   // Do not leave older GDS credentials active when changing a remote's mode.
   if(existing)for(key in listOf("gds_userid","gds_apikey","gds_endpoint","gds_mode"))
    parameters.put(key,"")
  }
  return parameters
 }
}
