package com.ckchoi.ckreader

import com.ckchoi.elibrary.common.ReaderDesign
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Check both requested themes without Android graphics/runtime dependencies. */
class ReaderDesignTest {
 @Test fun themesAreDistinctAndMatchUserSelection() {
  val light=ReaderDesign.heyclicky
  val night=ReaderDesign.cobalt
  assertEquals("Heyclicky",light.name)
  assertEquals("Cobalt",night.name)
  assertTrue(!light.dark)
  assertTrue(night.dark)
  assertTrue(light.background!=night.background)
  assertTrue(light.primary!=night.primary)
 }

 @Test fun normalAndSecondaryTextHaveWcagAaContrast() {
  for(t in listOf(ReaderDesign.heyclicky,ReaderDesign.cobalt)) {
   assertTrue(t.name+" body on background",ReaderDesign.contrast(t.text,t.background)>=4.5)
   assertTrue(t.name+" body on surface",ReaderDesign.contrast(t.text,t.surface)>=4.5)
   assertTrue(t.name+" muted on background",ReaderDesign.contrast(t.muted,t.background)>=4.5)
   assertTrue(t.name+" muted on surface",ReaderDesign.contrast(t.muted,t.surface)>=4.5)
  }
 }

 @Test fun primaryButtonsAndSubtleButtonLabelsHaveWcagAaContrast() {
  for(t in listOf(ReaderDesign.heyclicky,ReaderDesign.cobalt)) {
   assertTrue(t.name+" primary",ReaderDesign.contrast(t.onPrimary,t.primary)>=4.5)
   assertTrue(t.name+" secondary",ReaderDesign.contrast(t.onSubtle,t.subtle)>=4.5)
  }
 }

 @Test fun borderHasVisibleDifferentiationFromSurface() {
  for(t in listOf(ReaderDesign.heyclicky,ReaderDesign.cobalt)) {
   assertTrue(t.name,ReaderDesign.contrast(t.border,t.surface)>=1.35)
  }
 }
}
