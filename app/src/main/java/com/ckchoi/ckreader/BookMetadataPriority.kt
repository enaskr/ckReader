package com.ckchoi.ckreader

/** Authored folder metadata has the same priority on every filesystem source. */
internal object BookMetadataPriority {
 fun mayReplaceExisting(status:String,sourceType:String,yamlPreferred:Boolean):Boolean =
  status=="chosen" && !(FolderYamlMetadata.supports(sourceType)&&yamlPreferred)
}
