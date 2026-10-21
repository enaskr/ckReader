package com.ckchoi.ckreader

import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import java.util.Base64

/** A portable, filename-keyed document; storage URLs and credentials are excluded. */
internal object FolderYamlExport {
 data class Record(val filename:String,val values:Map<String,String>,val cover:ByteArray?=null)
 private val fields=setOf("title","author","publisher","summary","genre","isbn","translator",
  "series_name","series_index","series_total","publishedDate","price","metadataUrl","metadata_source","cover")
 fun encode(records:List<Record>):ByteArray? {
  val files=linkedMapOf<String,Any>()
  for(record in records) {
   if(record.filename.isBlank()||'/' in record.filename||'\\' in record.filename||!LibraryVisibility.visible(record.filename))continue
   val values=linkedMapOf<String,Any>()
   record.values.filterKeys{it in fields}.filterValues{it.isNotBlank()}.forEach{(key,value)->
    values[key]=value.take(if(key=="summary")800 else if(key in setOf("cover","metadataUrl"))550 else 200)
   }
   if((values["title"] as? String).isNullOrBlank())continue
   record.cover?.takeIf{it.size in 24..100_000}?.let{
    values["cover"]="data:image/jpeg;base64,"+Base64.getEncoder().encodeToString(it)
   }
   files[record.filename]=values
  }
  if(files.isEmpty())return null
  val options=DumperOptions().apply{defaultFlowStyle=DumperOptions.FlowStyle.BLOCK;isAllowUnicode=true;width=120;indent=2}
  val bytes=Yaml(options).dump(linkedMapOf("files" to files)).toByteArray(Charsets.UTF_8)
  return bytes.takeIf{it.size<=FolderYamlMetadata.MAX_BYTES}
 }
}
