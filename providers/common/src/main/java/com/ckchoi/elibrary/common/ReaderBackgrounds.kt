package com.ckchoi.elibrary.common

/**
 * Reader page-only colors. Index 0 follows app light/dark mode.
 * Indices 1 and 2 preserve existing user saved settings (cream and dark).
 */
object ReaderBackgrounds {
 data class Tone(val label:String,val bg:String,val fg:String)
 val tones=listOf(
  Tone("테마 기본","", ""),
  Tone("크림","#F7EFDA","#3F3020"),
  Tone("어둡게","#1D2A3C","#F2F7FF"),
  Tone("순백","#FFFFFF","#242B38"),
  Tone("종이","#FAF8F2","#302E2A"),
  Tone("연한 블루","#EAF4FF","#203951"),
  Tone("민트","#E6F4ED","#234336"),
  Tone("세이지","#E5EEDC","#33432A"),
  Tone("라벤더","#F0EDFA","#3A2D54"),
  Tone("피치","#FFF0E6","#563428"),
  Tone("쿨 그레이","#EBEFF4","#273548"),
  Tone("연한 옐로","#FFF6D9","#4D3D19"),
  Tone("딥 네이비","#11243F","#EEF5FF"),
  Tone("포레스트","#17332E","#F1FAF3"),
  Tone("코코아","#423230","#FFF4E9"),
  Tone("블랙","#10151C","#F5F8FC")
 )
 fun get(index:Int):Tone=tones.getOrElse(index){tones.first()}
 fun contrast(index:Int):Double {
  val tone=get(index)
  return if(index==0)Double.POSITIVE_INFINITY else
   ReaderDesign.contrast(tone.fg,tone.bg)
 }
}
