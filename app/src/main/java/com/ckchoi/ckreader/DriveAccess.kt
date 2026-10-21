package com.ckchoi.ckreader

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.OutputStream

/** Routes saved connections without sharing credentials or transport state. */
internal class DriveAccess(private val context:Context,private val repository:LibraryRepository) {
 private val google by lazy{GoogleDriveAccess(context)}
 private val rclone by lazy{RcloneBridge(context,repository)}
 companion object {
  fun google(source:LibrarySource):Boolean=runCatching{JSONObject(source.extra)
   .optBoolean(GoogleDriveSignIn.AUTH_KEY)}.getOrDefault(false)
 }
 fun list(source:LibrarySource,folder:String,foldersOnlyWithIds:Boolean=false):List<RcloneBridge.Item> =
  if(google(source))google.list(source,folder,foldersOnlyWithIds) else rclone.list(source,folder,foldersOnlyWithIds)
 fun archiveSize(source:LibrarySource,path:String):Long =
  if(google(source))google.archiveSize(source,path) else rclone.archiveSize(source,path)
 fun archiveRange(source:LibrarySource,path:String,offset:Long,length:Int):ByteArray =
  if(google(source))google.archiveRange(source,path,offset,length) else rclone.archiveRange(source,path,offset,length)
 fun copyTo(source:LibrarySource,path:String,target:File,expectedSize:Long=-1,onProgress:(String,Long,Long)->Unit={_,_,_->}) {
  if(google(source))google.copyTo(source,path,target,expectedSize,onProgress)
  else rclone.copyTo(source,path,target,expectedSize,onProgress)
 }
 fun streamTo(source:LibrarySource,path:String,out:OutputStream){
  if(google(source))google.streamTo(source,path,out) else rclone.streamTo(source,path,out)
 }
 fun smallFile(source:LibrarySource,path:String,maxBytes:Long=1_048_576L):ByteArray =
  if(google(source))google.smallFile(source,path,maxBytes) else rclone.smallFile(source,path,maxBytes)
 fun releaseFolderPicker(source:LibrarySource){if(!google(source))rclone.releaseFolderPicker(source)}
 fun deleteFile(source:LibrarySource,path:String){
  check(!google(source)){"Google Drive 직접 연결에서는 도서 삭제를 지원하지 않습니다."}
  rclone.deleteFile(source,path)
 }
 fun canWriteFolder(source:LibrarySource,folder:String,moveChildren:Boolean=false):Boolean =
  if(google(source))google.canWriteFolder(source,folder,moveChildren)
  else rclone.canWriteFolder(source,folder,moveChildren)
 fun createYamlOnly(source:LibrarySource,folder:String,bytes:ByteArray):Boolean{
  return if(google(source))google.createYamlOnly(source,folder,bytes)
   else rclone.createYamlOnly(source,folder,bytes)
 }
 fun makeDirectory(source:LibrarySource,path:String){
  if(google(source))google.makeDirectory(source,path) else rclone.makeDirectory(source,path)
 }
 fun moveOnly(source:LibrarySource,from:String,to:String){
  if(google(source))google.moveOnly(source,from,to) else rclone.moveOnly(source,from,to)
 }
}
