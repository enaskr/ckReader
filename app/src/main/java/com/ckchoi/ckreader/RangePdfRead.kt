package com.ckchoi.ckreader

import com.tom_roush.pdfbox.io.RandomAccessRead
import java.io.EOFException
import java.io.IOException

/** PDFBox random access over bounded ranges; no kernel FUSE descriptor involved. */
internal class RangePdfRead(length:Long,fetch:(Long,Int)->ByteArray):RandomAccessRead {
 private val started=System.nanoTime()
 private var received=0L
 private val ranges=BufferedRangeReader(length,{offset,count->
  if(Thread.currentThread().isInterrupted)throw IOException("PDF cover cancelled")
  if(received+count>32L*1024*1024 || System.nanoTime()-started>30_000_000_000L)
   throw IOException("PDF cover range budget exceeded")
  received+=count
  fetch(offset,count)
 },blockSize=128*1024,cacheBlocks=12)
 private var offset=0L
 private var closed=false
 private fun checkOpen(){if(closed)throw IOException("PDF source closed")}
 override fun getPosition():Long {checkOpen();return offset}
 override fun length():Long {checkOpen();return ranges.length}
 override fun seek(position:Long) {checkOpen();require(position>=0);offset=position}
 override fun isClosed()=closed
 override fun close(){closed=true;ranges.clear()}
 override fun read():Int {
  val data=ByteArray(1)
  return if(read(data)==-1)-1 else data[0].toInt() and 255
 }
 override fun read(data:ByteArray)=read(data,0,data.size)
 override fun read(data:ByteArray,start:Int,count:Int):Int {
  checkOpen();require(start>=0&&count>=0&&start<=data.size-count)
  if(count==0)return 0
  if(offset>=ranges.length)return -1
  val buffer=if(start==0)data else ByteArray(minOf(count.toLong(),ranges.length-offset).toInt())
  val n=ranges.read(offset,minOf(count,buffer.size),buffer)
  if(start!=0)buffer.copyInto(data,start,0,n)
  offset+=n
  return n
 }
 override fun peek():Int {val saved=offset;return read().also{offset=saved}}
 override fun rewind(bytes:Int){require(bytes>=0&&bytes<=offset);seek(offset-bytes)}
 override fun readFully(count:Int):ByteArray {
  require(count>=0&&count<=32*1024*1024)
  val data=ByteArray(count)
  var copied=0
  while(copied<count) {
   val n=read(data,copied,count-copied)
   if(n<0)throw EOFException()
   copied+=n
  }
  return data
 }
 override fun isEOF():Boolean {checkOpen();return offset>=ranges.length}
 override fun available():Int {checkOpen();return (ranges.length-offset).coerceIn(0,Int.MAX_VALUE.toLong()).toInt()}
}
