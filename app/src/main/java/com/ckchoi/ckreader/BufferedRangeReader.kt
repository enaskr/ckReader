package com.ckchoi.ckreader

import java.util.LinkedHashMap

/**
 * On-demand block cache for a seekable remote ZIP. Fetches only the requested
 * byte ranges, never the whole archive. Thread safe, bounded to cacheBlocks.
 */
internal class BufferedRangeReader(
 val length:Long,
 private val fetch:(Long,Int)->ByteArray,
 private val blockSize:Int=512*1024,
 private val cacheBlocks:Int=12
) {
 init {
  require(length in 1..10_000_000_000L)
  require(blockSize in 1024..1_048_576)
  require(cacheBlocks in 1..32)
 }
 private val chunks=object:LinkedHashMap<Long,ByteArray>(cacheBlocks,.75f,true) {
  override fun removeEldestEntry(eldest:MutableMap.MutableEntry<Long,ByteArray>?):Boolean=size>cacheBlocks
 }
 fun read(offset:Long,size:Int,buffer:ByteArray):Int {
  require(offset>=0&&size in 0..buffer.size)
  if(offset>=length)return 0
  val need=minOf(length-offset,size.toLong()).toInt()
  var copied=0
  while(copied<need){
   val position=offset+copied
   val index=position/blockSize
   val chunk=synchronized(chunks){chunks[index]}?:run {
    val start=index*blockSize
    val fetchSize=minOf(blockSize.toLong(),length-start).toInt()
    val bytes=fetch(start,fetchSize)
    check(bytes.size==fetchSize){"서버에서 받은 바이트 수가 일치하지 않습니다."}
    synchronized(chunks){chunks[index]=bytes}
    bytes
   }
   val inner=(position%blockSize).toInt()
   val n=minOf(chunk.size-inner,need-copied)
   check(n>0){"ZIP byte-range 버퍼 계산 오류"}
   System.arraycopy(chunk,inner,buffer,copied,n)
   copied+=n
  }
  return copied
 }
 // Never hold the cache lock during network I/O: release/cleanup must be local.
 fun clear()=synchronized(chunks){chunks.clear()}
}
