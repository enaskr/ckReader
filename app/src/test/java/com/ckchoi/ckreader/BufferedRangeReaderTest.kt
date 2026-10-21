package com.ckchoi.ckreader

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BufferedRangeReaderTest {
 @Test fun clearingCacheDoesNotWaitForNetworkRead() {
  val entered=java.util.concurrent.CountDownLatch(1)
  val release=java.util.concurrent.CountDownLatch(1)
  val pool=java.util.concurrent.Executors.newFixedThreadPool(2)
  val reader=BufferedRangeReader(4096,{_,count->
   entered.countDown()
   check(release.await(5,java.util.concurrent.TimeUnit.SECONDS))
   ByteArray(count)
  },blockSize=1024)
  try {
   val reading=pool.submit<Int>{reader.read(0,1,ByteArray(1))}
   assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS))
   pool.submit{reader.clear()}.get(1,java.util.concurrent.TimeUnit.SECONDS)
   release.countDown()
   assertEquals(1,reading.get(2,java.util.concurrent.TimeUnit.SECONDS))
  } finally {release.countDown();pool.shutdownNow()}
 }
 @Test fun readsOnlyRequestedBlocksAndCachesOverlappingReads() {
  val input=ByteArray(4000){(it%251).toByte()}
  val ranges=ArrayList<Pair<Long,Int>>()
  val reader=BufferedRangeReader(input.size.toLong(),{start,n->
   ranges.add(start to n)
   input.copyOfRange(start.toInt(),start.toInt()+n)
  },blockSize=1024,cacheBlocks=2)
  val buf=ByteArray(1500)
  assertEquals(1500,reader.read(500,buf.size,buf))
  assertArrayEquals(input.copyOfRange(500,2000),buf)
  assertEquals(2,ranges.size)
  assertEquals(100,reader.read(900,100,buf))
  assertEquals(2,ranges.size)
  assertEquals(800,reader.read(3200,800,buf))
  assertEquals(3,ranges.size)
  assertEquals(0,reader.read(4000,30,buf))
 }
 @Test fun refusesPartialRemoteContent() {
  val reader=BufferedRangeReader(5000,{_,count->ByteArray(count-1)},blockSize=1024)
  val result=runCatching{reader.read(0,15,ByteArray(15))}
  assertTrue(result.isFailure)
 }
 @Test fun cacheLruEvictsOldBlocks() {
  val sizes=ArrayList<Long>()
  val reader=BufferedRangeReader(5000,{offset,n->
   sizes.add(offset);ByteArray(n){1}
  },blockSize=1024,cacheBlocks=2)
  repeat(3){reader.read(it*1024L,1,ByteArray(1))}
  assertEquals(3,sizes.size)
  reader.read(0,1,ByteArray(1))
  assertEquals(4,sizes.size)
 }
}
class BookOasisPathsTest {
 @Test fun preservesUnicodeSeriesWithSlashesAndTildes() {
  val name="홍길동 / 제2권~특별편 ∙ epub"
  val cat=BookOasisPaths.library("42","만화 / 취향")
  val series=BookOasisPaths.series("42",name)
  assertTrue('/' !in cat)
  assertTrue('/' !in series)
  assertEquals("만화 / 취향",BookOasisPaths.display(cat))
  assertEquals(name,BookOasisPaths.display(series))
  val route=BookOasisPaths.parse(cat+"/"+series)
  assertEquals("42",route.libraryId)
  assertEquals(name,route.seriesName)
  assertEquals(2,route.level)
  assertEquals(1,BookOasisPaths.parse(cat).level)
 }
 @Test fun rejectsMismatchingCategoryPath() {
  val result=runCatching{
   BookOasisPaths.parse(BookOasisPaths.library("1","First")+"/"+BookOasisPaths.series("2","Other"))
  }
  assertTrue(result.isFailure)
 }
}
