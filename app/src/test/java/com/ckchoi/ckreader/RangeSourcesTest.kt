package com.ckchoi.ckreader

import com.ckchoi.elibrary.common.RangeReadChannel
import org.junit.Assert.*
import org.junit.Test
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RangeSourcesTest {
 @Test fun pdfSourceSeeksPeeksRewindsAndStopsAtEof() {
  val data=ByteArray(4000){(it%251).toByte()}
  RangePdfRead(data.size.toLong()){offset,count->data.copyOfRange(offset.toInt(),offset.toInt()+count)}.use {input->
   assertEquals(0,input.peek());assertEquals(0,input.position)
   input.seek(250);assertEquals(250,input.read());input.rewind(1)
   assertArrayEquals(data.copyOfRange(250,1250),input.readFully(1000))
   val dst=ByteArray(10){-1};assertEquals(5,input.read(dst,2,5))
   assertEquals((-1).toByte(),dst[0]);assertEquals(data[1250],dst[2])
   input.seek(3999);assertEquals(1,input.available());assertEquals(1,input.read(ByteArray(10)))
   assertTrue(input.isEOF);assertEquals(-1,input.read());assertEquals(0,input.read(ByteArray(0)))
   assertTrue(runCatching{input.readFully(1)}.exceptionOrNull() is EOFException)
  }
 }
 @Test fun archiveChannelReadsCrossBlockAndCachesWithoutWholeFileDownload() {
  var requests=0
  val data=ByteArray(4096){(it%241).toByte()}
  RangeReadChannel(data.size.toLong(),{offset,count->requests++;data.copyOfRange(offset.toInt(),offset.toInt()+count)},1024,2).use {channel->
   channel.position(900)
   val dst=ByteBuffer.allocate(300)
   assertEquals(300,channel.read(dst));assertArrayEquals(data.copyOfRange(900,1200),dst.array())
   channel.position(950);assertEquals(20,channel.read(ByteBuffer.allocate(20)))
   assertEquals(2,requests)
   channel.position(4090);assertEquals(6,channel.read(ByteBuffer.allocate(10)))
   assertEquals(-1,channel.read(ByteBuffer.allocate(1)))
   assertEquals(0,channel.read(ByteBuffer.allocate(0)))
  }
 }
 @Test fun archiveCloseNeverWaitsForBlockedRemoteRead() {
  val entered=CountDownLatch(1);val release=CountDownLatch(1)
  val pool=Executors.newFixedThreadPool(2)
  val channel=RangeReadChannel(4096,{_,n->entered.countDown();check(release.await(5,TimeUnit.SECONDS));ByteArray(n)},1024)
  try {
   val reading=pool.submit<Throwable?>{runCatching{channel.read(ByteBuffer.allocate(1))}.exceptionOrNull()}
   assertTrue(entered.await(2,TimeUnit.SECONDS))
   pool.submit{channel.close()}.get(1,TimeUnit.SECONDS)
   assertFalse(channel.isOpen)
   release.countDown()
   assertTrue(reading.get(2,TimeUnit.SECONDS) is ClosedChannelException)
  } finally {release.countDown();pool.shutdownNow()}
 }
 @Test fun archiveChannelRejectsShortRemoteResponse() {
  RangeReadChannel(4096,{_,n->ByteArray(n-1)},1024).use {channel->
   assertTrue(runCatching{channel.read(ByteBuffer.allocate(1))}.isFailure)
  }
 }
}
