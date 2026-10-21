package com.ckchoi.ckreader

import org.junit.Assert.*
import org.junit.Test

class IconContentBoundsTest {
 @Test fun keepsWhiteInteriorAndFindsTheSurroundingSymbol() {
  val pixels=IntArray(100){-1}
  pixels[33]=0xff0088ff.toInt();pixels[66]=0xff0088ff.toInt()
  assertEquals(IconRegion(2,2,8,8),IconContentBounds.find(pixels,10,10))
 }
 @Test fun transparentBlackPaddingDoesNotMakeAnIconFillTheWholeTile() {
  val pixels=IntArray(100)
  pixels[44]=0xff000000.toInt();pixels[55]=0xff0088ff.toInt()
  assertEquals(IconRegion(3,3,7,7),IconContentBounds.find(pixels,10,10))
 }
 @Test fun blankImagesRemainValidFullImageBounds() {
  assertEquals(IconRegion(0,0,10,10),IconContentBounds.find(IntArray(100){-1},10,10))
 }
}
