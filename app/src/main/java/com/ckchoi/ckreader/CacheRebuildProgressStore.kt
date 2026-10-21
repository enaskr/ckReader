package com.ckchoi.ckreader

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * A bounded, durable snapshot of the foreground cache-rebuild job.
 * Only file paths and outcome metadata are recorded; credentials are never stored.
 */
internal class CacheRebuildProgressStore(context:Context) {
 data class FileResult(
  val storage:String,
  val path:String,
  val file:String,
  val success:Boolean,
  val timestamp:Long,
  val skipped:Boolean=false
 )
 data class Snapshot(
  val phase:String="idle",
  val storage:String="",
  val path:String="",
  val file:String="",
  val processed:Int=0,
  val success:Int=0,
  val failure:Int=0,
  val skipped:Int=0,
  val waiting:Int=0,
  val startedAt:Long=0L,
  val updatedAt:Long=0L,
  val recent:List<FileResult> = emptyList()
 ) {
  fun withResult(result:FileResult):Snapshot=copy(
   processed=processed+if(result.skipped)0 else 1,
   success=success+if(result.skipped)0 else if(result.success)1 else 0,
   failure=failure+if(result.skipped||result.success)0 else 1,
   skipped=skipped+if(result.skipped)1 else 0,
   updatedAt=result.timestamp,
   recent=(listOf(result)+recent).take(MAX_RECENT)
  )
 }
 companion object {
  const val MAX_RECENT=50
  private const val PREFS="ckreader_cache_rebuild_progress"
  private const val KEY="snapshot"
  private val writeLock=Any()
 }
 private val preferences=context.applicationContext.getSharedPreferences(PREFS,Context.MODE_PRIVATE)

 fun read():Snapshot=decode(preferences.getString(KEY,null))

 fun update(change:(Snapshot)->Snapshot):Snapshot=synchronized(writeLock) {
  val next=change(read())
  preferences.edit().putString(KEY,encode(next)).apply()
  next
 }

 private fun decode(raw:String?):Snapshot=runCatching {
  val obj=JSONObject(raw ?: return Snapshot())
  val history=obj.optJSONArray("recent") ?: JSONArray()
  val records=(0 until minOf(history.length(),MAX_RECENT)).mapNotNull { index ->
   history.optJSONObject(index)?.let {
    FileResult(it.optString("storage"),it.optString("path"),it.optString("file"),
     it.optBoolean("success"),it.optLong("time"),it.optBoolean("skipped",false))
   }
  }
  Snapshot(
   phase=obj.optString("phase","idle"),
   storage=obj.optString("storage"),
   path=obj.optString("path"),
   file=obj.optString("file"),
   processed=obj.optInt("processed"),
   success=obj.optInt("success"),
   failure=obj.optInt("failure"),
   skipped=obj.optInt("skipped"),
   waiting=obj.optInt("waiting"),
   startedAt=obj.optLong("startedAt"),
   updatedAt=obj.optLong("updatedAt"),
   recent=records
  )
 }.getOrDefault(Snapshot())

 private fun encode(snapshot:Snapshot):String {
  val history=JSONArray()
  snapshot.recent.take(MAX_RECENT).forEach {
   history.put(JSONObject().put("storage",it.storage).put("path",it.path)
    .put("file",it.file).put("success",it.success).put("time",it.timestamp)
    .put("skipped",it.skipped))
  }
  return JSONObject().put("phase",snapshot.phase)
   .put("storage",snapshot.storage).put("path",snapshot.path)
   .put("file",snapshot.file).put("processed",snapshot.processed)
   .put("success",snapshot.success).put("failure",snapshot.failure)
   .put("skipped",snapshot.skipped).put("waiting",snapshot.waiting).put("startedAt",snapshot.startedAt)
   .put("updatedAt",snapshot.updatedAt).put("recent",history).toString()
 }
}
