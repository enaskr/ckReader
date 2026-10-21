package com.ckchoi.ckreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CacheRebuildProgressStoreTest {
 @Test fun keepsOnlyMostRecent50FilesAndCountsAllProcessedBooks() {
  var state=CacheRebuildProgressStore.Snapshot(phase="running")
  for(number in 1..77) {
   state=state.withResult(CacheRebuildProgressStore.FileResult(
    "저장소","folder/sub",number.toString()+".epub",number%3!=0,number.toLong()))
  }
  assertEquals(77,state.processed)
  assertEquals(52,state.success)
  assertEquals(25,state.failure)
  assertEquals(50,state.recent.size)
  assertEquals("77.epub",state.recent.first().file)
  assertEquals("28.epub",state.recent.last().file)
  assertTrue(state.recent.first().success)
  assertFalse(state.recent[2].success)
  assertEquals(77L,state.updatedAt)
 }

 @Test fun recentFilesStayVisibleAcrossJobCountersReset() {
  val old=CacheRebuildProgressStore.Snapshot()
   .withResult(CacheRebuildProgressStore.FileResult("s1","f1","a.epub",true,1))
  val next=old.copy(phase="running",processed=0,success=0,failure=0,waiting=1)
   .withResult(CacheRebuildProgressStore.FileResult("s2","f2","b.pdf",false,2))
  assertEquals(1,next.processed)
  assertEquals(0,next.success)
  assertEquals(1,next.failure)
  assertEquals(listOf("b.pdf","a.epub"),next.recent.map{it.file})
  assertEquals(1,next.waiting)
 }
 @Test fun skippedBooksAreTrackedSeparatelyWithoutCallingThemSuccesses() {
  var state=CacheRebuildProgressStore.Snapshot(phase="running")
  state=state.withResult(CacheRebuildProgressStore.FileResult(
   "Google Drive","folder","cached.epub",true,100,skipped=true))
  assertEquals(0,state.processed)
  assertEquals(0,state.success)
  assertEquals(0,state.failure)
  assertEquals(1,state.skipped)
  assertTrue(state.recent.first().skipped)
  state=state.withResult(CacheRebuildProgressStore.FileResult(
   "Google Drive","folder","fresh.epub",true,101))
  state=state.withResult(CacheRebuildProgressStore.FileResult(
   "Google Drive","folder","broken.epub",false,102))
  assertEquals(2,state.processed)
  assertEquals(1,state.success)
  assertEquals(1,state.failure)
  assertEquals(1,state.skipped)
  assertEquals(listOf("broken.epub","fresh.epub","cached.epub"),state.recent.map{it.file})
 }
 @Test fun olderHistoryEntriesRemainNonSkippedByDefault() {
  val legacy=CacheRebuildProgressStore.FileResult("FTP","/", "old.epub",true,1)
  assertFalse(legacy.skipped)
  val state=CacheRebuildProgressStore.Snapshot().withResult(legacy)
  assertEquals(1,state.success)
  assertEquals(0,state.skipped)
 }
}
