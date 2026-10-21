package com.ckchoi.ckreader

/**
 * Scroll state is used only for Back and for updates to the currently visible
 * list. A new folder entry must never inherit an earlier visit's offset.
 */
internal object LibraryScrollPolicy {
 fun useHistory(onBack:Boolean):Boolean=onBack
 fun position(keys:List<String?>,rowKey:String,fallback:Int):Int {
  if(keys.isEmpty())return 0
  val matched=keys.indexOf(rowKey)
  return if(matched>=0)matched else fallback.coerceIn(0,keys.lastIndex)
 }
}
