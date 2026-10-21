package com.ckchoi.ckreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class DownloadedBookRetentionTest {
 @Test fun keepsFiveMostRecentlyUsedCompleteBooksAndIgnoresPartialTransfers() {
  val directory=Files.createTempDirectory("ckreader-downloads-").toFile()
  try {
   val books=(1..6).map { index ->
    directory.resolve("book$index.epub").apply {
     writeText("download $index")
     assertTrue(setLastModified(index*1_000L))
    }
   }
   val partial=directory.resolve("book7.part").apply{writeText("in progress")}
   val unrelated=directory.resolve("cover.img").apply{writeText("cover")}
   DownloadedBookRetention.prune(directory,books.last())
   assertFalse(books.first().exists())
   assertEquals(5,books.count{it.exists()})
   assertTrue(partial.exists())
   assertTrue(unrelated.exists())
  } finally {directory.deleteRecursively()}
 }

 @Test fun reusedBookIsRetainedWhenNextDownloadCompletes() {
  val directory=Files.createTempDirectory("ckreader-downloads-").toFile()
  try {
   val books=(1..6).map { index ->
    directory.resolve("book$index.pdf").apply {
     writeText("download $index")
     assertTrue(setLastModified(index*1_000L))
    }
   }
   DownloadedBookRetention.markUsed(books.first())
   DownloadedBookRetention.prune(directory,books.last())
   assertTrue(books.first().exists())
   assertFalse(books[1].exists())
   assertEquals(5,books.count{it.exists()})
  } finally {directory.deleteRecursively()}
 }
}
