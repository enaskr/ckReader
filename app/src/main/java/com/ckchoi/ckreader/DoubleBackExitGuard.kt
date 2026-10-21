package com.ckchoi.ckreader

internal class DoubleBackExitGuard(private val windowMillis:Long=2_000L) {
 private var firstPressAt:Long?=null
 fun reset(){firstPressAt=null}
 fun press(nowMillis:Long):Boolean {
  val first=firstPressAt
  if(first!=null&&nowMillis>=first&&nowMillis-first<=windowMillis) {
   reset()
   return true
  }
  firstPressAt=nowMillis
  return false
 }
}
