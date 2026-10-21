package com.ckchoi.yesss24

import java.net.URLDecoder

/** Resolve an EPUB URI reference to its decoded ZIP entry name. */
internal object EpubPaths {
 private val scheme=Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")

 fun resolve(baseFile:String,reference:String):String? {
  // Strip URI components before decoding so %23 and %3F remain filename characters.
  val raw=reference.substringBefore('#').substringBefore('?')
  if(raw.isBlank()||scheme.containsMatchIn(raw))return null
  // Unlike form data, a URI path's literal '+' is not a space. Decode only once.
  val decoded=runCatching{URLDecoder.decode(raw.replace("+","%2B"),"UTF-8")}.getOrNull()?:return null
  if(decoded.startsWith('/')||decoded.contains('\\')||decoded.contains('\u0000')||scheme.containsMatchIn(decoded))return null
  val parts=baseFile.substringBeforeLast('/',"").split('/').filter{it.isNotEmpty()}.toMutableList()
  for(part in decoded.split('/'))when(part){
   "","."->Unit
   ".."->{if(parts.isEmpty())return null;parts.removeAt(parts.lastIndex)}
   else->parts.add(part)
  }
  return parts.joinToString("/").takeIf{it.isNotEmpty()}
 }
}
