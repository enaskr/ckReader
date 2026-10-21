package com.ckchoi.ckreader

import com.ckchoi.elibrary.common.GlobalConfig
import com.ckchoi.elibrary.common.ReaderDesign
import org.junit.Assert.*
import org.junit.Test

/** Google-login drive list uses the same tokens as each explicit app theme. */
class GoogleDrivePickerThemeTest {
 @Test fun lightDarkSystemSelectionsAreDistinctAndFollowSystemMode() {
  assertFalse(GlobalConfig.resolveDark("light",true))
  assertTrue(GlobalConfig.resolveDark("dark",false))
  assertFalse(GlobalConfig.resolveDark("system",false))
  assertTrue(GlobalConfig.resolveDark("system",true))
  assertNotEquals(ReaderDesign.heyclicky.surface,ReaderDesign.cobalt.surface)
  assertNotEquals(ReaderDesign.heyclicky.raised,ReaderDesign.cobalt.raised)
  assertNotEquals(ReaderDesign.heyclicky.text,ReaderDesign.cobalt.text)
 }

 @Test fun drivePickerRowsAndButtonsHaveReadableContrastAcrossAllEditions() {
  for(t in listOf(ReaderDesign.heyclicky,ReaderDesign.cobalt,ReaderDesign.eink)) {
   assertTrue(t.name+" dialog title on surface",
    ReaderDesign.contrast(t.text,t.surface)>=4.5)
   assertTrue(t.name+" selected drive title on raised card",
    ReaderDesign.contrast(t.text,t.raised)>=4.5)
   assertTrue(t.name+" Google account/subtitle on surface",
    ReaderDesign.contrast(t.muted,t.surface)>=4.5)
   assertTrue(t.name+" drive row secondary text",
    ReaderDesign.contrast(t.muted,t.raised)>=4.5)
   assertTrue(t.name+" close button tint",
    ReaderDesign.contrast(t.onSubtle,t.subtle)>=4.5)
   assertTrue(t.name+" storage icon on subtle",
    ReaderDesign.contrast(t.primary,t.subtle)>=3.0)
  }
 }
}
