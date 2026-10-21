package com.ckchoi.elibrary.common

import android.content.ContentResolver
import android.net.Uri
import android.os.Bundle

/** Private content-provider range protocol; carries no account credentials. */
object ArchiveRangeSource {
 const val AUTHORITY="com.ckchoi.ckreader.stream"
 const val SIZE="archive.size"
 const val READ="archive.read"
 const val MAX_READ=262144
 fun matches(uri:Uri)=uri.scheme=="content"&&uri.authority==AUTHORITY&&
  uri.pathSegments.size==3&&uri.pathSegments[0]=="archive"
 fun open(resolver:ContentResolver,uri:Uri,onRead:(Int)->Unit={}):RangeReadChannel {
  require(matches(uri))
  val length=resolver.call(uri,SIZE,uri.toString(),null)?.getLong("length",-1)
   ?:error("압축파일 크기를 확인할 수 없습니다.")
  return RangeReadChannel(length,{offset,count->
   val args=Bundle().apply{putLong("offset",offset);putInt("count",count)}
   val bytes=resolver.call(uri,READ,uri.toString(),args)?.getByteArray("bytes")
    ?:error("압축파일 범위를 읽을 수 없습니다.")
   onRead(bytes.size)
   bytes
  })
 }
}
