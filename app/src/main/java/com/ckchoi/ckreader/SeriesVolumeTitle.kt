package com.ckchoi.ckreader

import java.text.Normalizer
import java.util.Locale

/**
 * A filename's explicit volume is authoritative when a common folder/YAML or
 * stale web-cache title incorrectly describes another volume of that series.
 * Never rename the actual file or discard a correctly matched subtitle.
 */
internal object SeriesVolumeTitle {
 data class Volume(val stem:String,val base:String,val ordinal:Int)

 // Supports title 2, title10, title 제2권, title 2 - subtitle.
 private val marker=Regex(
  """^(.+?)(?:[\s_·.-]*)(?:제\s*)?(\d{1,3})\s*(?:권|부|편|화|회|vol(?:ume)?\.?)?(?:\s*[-–—:·]\s*.+)?$""",
  RegexOption.IGNORE_CASE)
 private val authorPrefix=Regex("""^\[[^]]{1,80}]\s*""")
 private val authorSuffix=Regex("""\s*\[[^]]{1,80}]\s*$""")
 private val serviceSuffix=Regex("""#\d{1,9}$""")
 private val completionSuffix=Regex("""\s*(?:\((?:완결|완)\)|완결)\s*$""")
 private fun normalize(value:String):String =
  Normalizer.normalize(value,Normalizer.Form.NFKC).lowercase(Locale.ROOT)
   .replace(Regex("""[^가-힣a-z0-9]"""),"")

 fun volume(filename:String):Volume? {
  val stem=filename.replace('\\','/').substringAfterLast('/')
   .substringBeforeLast('.').trim()
  val withoutService=serviceSuffix.replace(stem,"").trim()
  val display=authorSuffix.replace(authorPrefix.replace(withoutService,"").trim(),"").trim()
  val clean=completionSuffix.replace(display,"").trim()
  val match=marker.matchEntire(clean)?:return null
  val base=match.groupValues[1].trim(' ','_', '-', '.', '·').takeIf{it.length>=2}?:return null
  val ordinal=match.groupValues[2].toIntOrNull()?.takeIf{it in 1..999}?:return null
  if(normalize(base).length<2)return null
  return Volume(display,base,ordinal)
 }

 /** A correctly indexed authored subtitle is retained, never truncated. */
 fun title(filename:String,sourceTitle:String):String {
  if(sourceTitle.isBlank())return sourceTitle
  val volume=volume(filename)?:return sourceTitle
  val current=sourceTitle.trim()
  val oldVolume=volume(current)
  val base=normalize(volume.base)
  if(oldVolume!=null&&normalize(oldVolume.base)==base)
   return if(oldVolume.ordinal==volume.ordinal)sourceTitle else volume.stem
  // A folder-wide title without an ordinal cannot identify an individual file.
  if(normalize(current)==base)return volume.stem
  return sourceTitle
 }

 fun correctedOrdinal(filename:String,sourceTitle:String,previous:Int):Int {
  val v=volume(filename)?:return previous
  return if(title(filename,sourceTitle)!=sourceTitle)v.ordinal else previous
 }

 fun apply(book:LibraryBook) {
  val original=book.title
  if(original.isBlank())return
  val fixed=title(book.filename,original)
  if(fixed!=original) {
   book.seriesIndex=correctedOrdinal(book.filename,original,book.seriesIndex)
   book.title=fixed
  }
 }
}
