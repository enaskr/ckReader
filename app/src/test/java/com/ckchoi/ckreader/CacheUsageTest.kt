package com.ckchoi.ckreader

import org.junit.Assert.*
import org.junit.Test

class CacheUsageTest {
 @Test fun roundsToWholeMegabytesWithAMinimumOfOne() {
  assertEquals("1 MB",CacheUsage.format(0))
  assertEquals("1 MB",CacheUsage.format(12_345))
  assertEquals("1 MB",CacheUsage.format(999_999))
  assertEquals("1 MB",CacheUsage.format(1_000_000))
  assertEquals("2 MB",CacheUsage.format(1_000_001))
  assertEquals("125 MB",CacheUsage.format(125_000_000))
  assertFalse(CacheUsage.format(Long.MAX_VALUE).startsWith("-"))
 }
 @Test fun measuresDatabaseSidecarsButSkipsExportsAndUnrelatedFiles() {
  val files=listOf("GDS-root.db" to 20_000L,"GDS-root.db-wal" to 8_000L,
   "GDS-root.db-shm" to 2_000L,"GDS-books.db" to 40_000L,
   "GDS-books.db-journal" to 1_000L,"GDS-root.db.backup" to 200_000L,
   "GDS-root.db.pending" to 100_000L,"book.epub" to 10_000_000L)
  assertEquals(mapOf("GDS-root.db" to 30_000L,"GDS-books.db" to 41_000L),CacheUsage.partitions(files))
 }
 @Test fun mirroredPartitionsAreCountedOnceAndMirrorOnlyPartitionsRemainVisible() {
  val snapshot=CacheUsage.snapshot(mapOf("a.db" to 30_000L),
   mapOf("a.db" to 20_000L,"b.db" to 50_000L))
  assertEquals(80_000L,snapshot.totalBytes)
  assertEquals(30_000L,snapshot.bytes("a.db"))
  assertEquals(50_000L,snapshot.bytes("b.db"))
  assertEquals(0L,snapshot.bytes("new.db"))
 }
}
