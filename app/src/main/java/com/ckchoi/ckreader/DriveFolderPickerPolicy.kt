package com.ckchoi.ckreader

import org.json.JSONObject

/**
 * Keeps the folder ID picker separate from the saved library root.
 * Traversal is relative to a short-lived rclone remote with no path prefix;
 * selecting a folder persists its provider ID, never its display path.
 */
internal object DriveFolderPickerPolicy {
 data class Node(val path:String,val id:String,val name:String)
 private val idPattern=Regex("[A-Za-z0-9_-]{1,256}")
 fun validId(value:String):Boolean=idPattern.matches(value)
 /** rclone represents a Drive shortcut as target ID, tab, shortcut ID. */
 fun rootFolderId(listedId:String):String? {
  val parts=listedId.split('\t')
  if(parts.size !in 1..2 || parts.any { !validId(it) })return null
  return parts[0]
 }
 fun canSelect(node:Node,childrenLoaded:Boolean):Boolean =
  if(node.id.isNotEmpty())validId(node.id) else node.path.isEmpty()&&childrenLoaded
 fun child(parent:String,name:String):String {
  require(name.isNotBlank()&&name!="."&&name!=".."&&
   '/' !in name&&'\\' !in name&&'\u0000' !in name){"잘못된 폴더 이름"}
  return if(parent.isEmpty())name else "$parent/$name"
 }
 fun directoryConfig(config:JSONObject):JSONObject=JSONObject(config.toString()).apply {
  // An imported GDS root may be the only folder its service account can list.
  // Ordinary Drive can browse above its currently selected root.
  if(!config.optBoolean("ckreader_gds") || !RcloneGdsCredentials.custom(config))
   put("root_folder_id","")
 }
 fun storageKind(source:LibrarySource):String=when(source.type){
  "drive"-> {
   val config=runCatching{JSONObject(source.extra)}.getOrDefault(JSONObject())
   when {
    config.optBoolean(GoogleDriveSignIn.AUTH_KEY)->"Google Drive"
    config.optBoolean("ckreader_gds") || config.optString("gds_endpoint").isNotBlank() ||
     config.optString("type").equals("gds",true)->"RClone · GDS"
    else->"RClone"
   }
  }
  "local"->"로컬 폴더"
  "ftp"->"FTP"
  "sftp"->"SFTP"
  "webdav"->"WebDAV"
  "bookoasis"->"Book Oasis"
  else->source.type
 }
}
