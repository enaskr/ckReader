package com.ckchoi.ckreader

import java.io.File

internal data class CacheUsageSnapshot(val partitions:Map<String,Long>) {
 val totalBytes:Long get()=partitions.values.sum()
 fun bytes(filename:String):Long=partitions[filename]?:0L
}

internal object CacheUsage {
 private const val MB=1_000_000L
 /** Whole MB, rounded up, including the requested minimum display of 1 MB. */
 fun format(bytes:Long):String {
  val positive=bytes.coerceAtLeast(0L)
  val megabytes=positive/MB+if(positive%MB==0L)0 else 1
  return megabytes.coerceAtLeast(1L).toString()+" MB"
 }
 private fun databaseName(name:String):String? = when {
  name.endsWith(".db")->name
  name.endsWith(".db-wal")->name.removeSuffix("-wal")
  name.endsWith(".db-shm")->name.removeSuffix("-shm")
  name.endsWith(".db-journal")->name.removeSuffix("-journal")
  else->null
 }
 fun partitions(files:List<Pair<String,Long>>):Map<String,Long> {
  val sizes=LinkedHashMap<String,Long>()
  files.forEach{(name,bytes)->databaseName(name)?.let{key->
   sizes[key]=(sizes[key]?:0L)+bytes.coerceAtLeast(0L)
  }}
  return sizes
 }
 fun local(directory:File):Map<String,Long> = partitions(directory.listFiles()
  ?.filter{it.isFile}?.map{it.name to it.length()}.orEmpty())
 /** Public Documents mirrors are fallback copies, not a second logical cache. */
 fun snapshot(local:Map<String,Long>,mirrored:Map<String,Long>):CacheUsageSnapshot =
  CacheUsageSnapshot(mirrored+local)
}
