package com.ckchoi.elibrary.common

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.NonWritableChannelException
import java.nio.channels.SeekableByteChannel
import java.util.LinkedHashMap

/** Seekable user-space range reader. Closing never waits for a remote request. */
class RangeReadChannel(
 private val length:Long,
 private val fetch:(Long,Int)->ByteArray,
 private val blockSize:Int=128*1024,
 private val cacheBlocks:Int=12
):SeekableByteChannel {
 init {require(length in 1..10_000_000_000L);require(blockSize in 1024..262144);require(cacheBlocks in 1..32)}
 private val chunks=object:LinkedHashMap<Long,ByteArray>(cacheBlocks,.75f,true){
  override fun removeEldestEntry(eldest:MutableMap.MutableEntry<Long,ByteArray>?)=size>cacheBlocks
 }
 @Volatile private var open=true
 private var offset=0L
 private fun checkOpen(){if(!open)throw ClosedChannelException()}
 override fun isOpen()=open
 override fun close(){open=false;synchronized(chunks){chunks.clear()}}
 override fun size():Long{checkOpen();return length}
 override fun position():Long{checkOpen();return offset}
 override fun position(newPosition:Long):SeekableByteChannel{checkOpen();require(newPosition>=0);offset=newPosition;return this}
 @Synchronized override fun read(dst:ByteBuffer):Int {
  checkOpen()
  if(!dst.hasRemaining())return 0
  if(offset>=length)return -1
  val requested=minOf(dst.remaining().toLong(),length-offset).toInt()
  var copied=0
  while(copied<requested) {
   checkOpen()
   if(Thread.currentThread().isInterrupted)throw IOException("Archive read cancelled")
   val index=offset/blockSize
   val bytes=synchronized(chunks){chunks[index]}?:run {
    val start=index*blockSize
    val count=minOf(blockSize.toLong(),length-start).toInt()
    val result=fetch(start,count)
    checkOpen()
    if(result.size!=count)throw IOException("Incomplete archive range")
    synchronized(chunks){if(open)chunks[index]=result}
    result
   }
   val inside=(offset%blockSize).toInt()
   val n=minOf(bytes.size-inside,requested-copied)
   dst.put(bytes,inside,n)
   offset+=n;copied+=n
  }
  return copied
 }
 override fun write(src:ByteBuffer):Int=throw NonWritableChannelException()
 override fun truncate(size:Long):SeekableByteChannel=throw NonWritableChannelException()
}
