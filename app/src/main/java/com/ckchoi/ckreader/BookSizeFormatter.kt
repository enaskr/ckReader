package com.ckchoi.ckreader

import java.util.Locale

internal object BookSizeFormatter {
 fun format(bytes:Long):String {
  if(bytes<0)return "확인되지 않음"
  if(bytes<1024)return bytes.toString()+" B"
  val units=arrayOf("KiB","MiB","GiB","TiB")
  var value=bytes.toDouble()
  var index=-1
  while(value>=1024.0&&index<units.lastIndex) {value/=1024.0;index++}
  return String.format(Locale.ROOT,"%.1f %s (%,d B)",value,units[index],bytes)
 }
}
