package com.ckchoi.ckreader

import org.junit.Assert.*
import org.junit.Test

class DoubleBackExitGuardTest {
 @Test fun twoConsecutivePressesWithinTwoSecondsExit() {
  val guard=DoubleBackExitGuard()
  assertFalse(guard.press(0))
  assertTrue(guard.press(1_999))
  assertFalse(guard.press(2_000))
 }
 @Test fun expiredPressStartsAnotherPair() {
  val guard=DoubleBackExitGuard()
  assertFalse(guard.press(100))
  assertFalse(guard.press(2_101))
  assertTrue(guard.press(2_500))
 }
 @Test fun navigationTouchAndBackgroundingDisarmThePreviousPress() {
  val guard=DoubleBackExitGuard()
  assertFalse(guard.press(1_000))
  guard.reset()
  assertFalse(guard.press(1_100))
  assertTrue(guard.press(1_200))
 }
}
