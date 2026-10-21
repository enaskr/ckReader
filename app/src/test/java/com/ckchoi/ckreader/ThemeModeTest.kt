package com.ckchoi.ckreader

import com.ckchoi.elibrary.common.GlobalConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class ThemeModeTest {
 @Test fun explicitModesOverrideSystemConfiguration() {
  assertTrue(GlobalConfig.resolveDark("dark",false))
  assertTrue(GlobalConfig.resolveDark("dark",true))
  assertFalse(GlobalConfig.resolveDark("light",false))
  assertFalse(GlobalConfig.resolveDark("light",true))
 }
 @Test fun systemModeTracksDeviceNightSetting() {
  assertTrue(GlobalConfig.resolveDark("system",true))
  assertFalse(GlobalConfig.resolveDark("system",false))
 }
}
