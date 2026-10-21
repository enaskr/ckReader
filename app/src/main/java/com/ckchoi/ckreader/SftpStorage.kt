package com.ckchoi.ckreader

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.Base64

/** Password-authenticated, SHA256 host-key-pinned and read-only SFTP backend. */
internal class SftpStorage {
 data class FileInfo(val name:String,val directory:Boolean,val size:Long)
 companion object {
  fun validFingerprint(text:String):Boolean {
   val raw=text.trim().removePrefix("SHA256:").trimEnd('=')
   return raw.length==43 && raw.matches(Regex("[A-Za-z0-9+/]+"))
  }
  private fun root(source:LibrarySource):String {
   val u=URI(source.location)
   require(u.scheme.equals("sftp",true)&&u.host!=null&&u.host.isNotBlank() &&u.userInfo==null&&u.query==null&&u.fragment==null){
    "SFTP 서버 주소는 sftp://host:port/경로 형식으로 입력하세요."
   }
   val path=u.path.orEmpty().ifBlank{"/"}
   require(path.startsWith("/")&&path.split('/').none{it=="."||it==".."||'\\' in it||'\u0000' in it}){"안전하지 않은 SFTP 기본 경로"}
   return path.trimEnd('/').ifEmpty{"/"}
  }
  fun remotePath(source:LibrarySource,path:String):String {
   val normalized=FolderYamlStorage.relative(path)
   val base=root(source)
   return if(normalized.isBlank())base else if(base=="/")"/$normalized" else "$base/$normalized"
  }
 }
 private fun <T> useChannel(source:LibrarySource,task:(ChannelSftp)->T):T {
  val uri=URI(source.location)
  val base=root(source)
  require(base.isNotEmpty())
  require(source.user.isNotBlank()&&source.secret.isNotBlank()){"SFTP 사용자명과 비밀번호를 입력하세요."}
  val expected=JSONObject(source.extra).optString("host_key_sha256")
   .trim().removePrefix("SHA256:").trimEnd('=')
  require(validFingerprint(expected)) {"서버 SSH 호스트키 SHA256 지문이 필요합니다."}
  val jsch=JSch()
  val pin=object:HostKeyRepository {
   override fun check(host:String?,key:ByteArray?):Int {
    if(key==null)return HostKeyRepository.CHANGED
    val actual=Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(key))
    return if(MessageDigest.isEqual(actual.toByteArray(),expected.toByteArray())) HostKeyRepository.OK
     else HostKeyRepository.CHANGED
   }
   override fun add(hostkey:HostKey?,ui:UserInfo?) {}
   override fun remove(host:String?,type:String?) {}
   override fun remove(host:String?,type:String?,key:ByteArray?) {}
   override fun getKnownHostsRepositoryID():String="Pinned SHA256"
   override fun getHostKey():Array<HostKey> =emptyArray()
   override fun getHostKey(host:String?,type:String?):Array<HostKey> =emptyArray()
  }
  jsch.hostKeyRepository=pin
  val session:Session=jsch.getSession(source.user,uri.host,if(uri.port in 1..65535)uri.port else 22)
  session.setPassword(source.secret)
  session.setConfig("StrictHostKeyChecking","yes")
  session.setConfig("PreferredAuthentications","password,keyboard-interactive")
  session.timeout=30000
  var channel:ChannelSftp?=null
  try {
   session.connect(20000)
   val connected=(session.openChannel("sftp") as ChannelSftp).apply{connect(20000)}
   channel=connected
   return task(connected)
  } finally {
   runCatching{channel?.disconnect()}
   runCatching{session.disconnect()}
  }
 }
 fun list(source:LibrarySource,folder:String):List<FileInfo> = useChannel(source){sftp->
  @Suppress("UNCHECKED_CAST")
  val entries=sftp.ls(remotePath(source,folder)) as java.util.Vector<ChannelSftp.LsEntry>
  entries.mapNotNull{entry->
   val name=entry.filename
   if(name=="."||name==".."||name.isBlank()||'/' in name||'\\' in name)null
   else FileInfo(name,entry.attrs.isDir,entry.attrs.size)
  }
 }
 fun size(source:LibrarySource,path:String):Long=useChannel(source){it.stat(remotePath(source,path)).size}
 fun read(source:LibrarySource,path:String,max:Int):ByteArray=useChannel(source){sftp->
  sftp.get(remotePath(source,path)).use {FolderYamlStorage.limited(it,max)}
 }
 fun copy(source:LibrarySource,path:String,target:File,max:Long=10_000_000_000L,
  onProgress:(Long,Long)->Unit={_,_->}){
  useChannel(source){sftp->
   val remote=remotePath(source,path)
   val length=sftp.stat(remote).size
   require(length in 1..max){"SFTP 파일 크기가 허용 범위를 벗어났습니다."}
   target.outputStream().use{out->
    sftp.get(remote).use{input->
     val buffer=ByteArray(64*1024);var total=0L;var last=0L
     while(true){
      if(Thread.currentThread().isInterrupted)throw InterruptedException("SFTP 전송 중단")
      val read=input.read(buffer);if(read<0)break
      total+=read
      require(total<=max){"SFTP 파일 크기 초과"}
      out.write(buffer,0,read)
      val now=System.currentTimeMillis()
      if(now-last>=300L){onProgress(total,length);last=now}
     }
     onProgress(total,length)
     check(total==length){"SFTP 전송 중 파일 길이가 달라졌습니다."}
    }
   }
  }
 }
 fun range(source:LibrarySource,path:String,offset:Long,length:Int):ByteArray=useChannel(source){sftp->
  require(offset>=0 && length in 1..1_048_576)
  sftp.get(remotePath(source,path),null,offset).use{stream->
   val result=java.io.ByteArrayOutputStream(length)
   val buffer=ByteArray(32*1024)
   while(result.size()<length){
    val count=stream.read(buffer,0,minOf(length-result.size(),buffer.size))
    if(count<0)break
    if(count>0)result.write(buffer,0,count)
   }
   result.toByteArray()
  }
 }
}
