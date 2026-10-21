package com.ckchoi.ckreader

import java.io.File
import java.util.Locale

/** Limits complete foreground downloads without touching originals or partial transfers. */
internal object DownloadedBookRetention {
 private val formats=setOf("epub","pdf","txt","md","zip","cbz")
 private const val MAX_BOOKS=5

 fun markUsed(file:File) {
  file.setLastModified(System.currentTimeMillis())
 }

 fun prune(directory:File,protected:File) {
  val completed=directory.listFiles().orEmpty().filter { file ->
   file.isFile && file.length()>0 && file.extension.lowercase(Locale.ROOT) in formats
  }.sortedWith(compareBy<File>{it.lastModified()}.thenBy{it.name})
  var excess=completed.size-MAX_BOOKS
  for(file in completed) {
   if(excess<=0)break
   if(file==protected)continue
   if(file.delete())excess--
  }
 }
}
