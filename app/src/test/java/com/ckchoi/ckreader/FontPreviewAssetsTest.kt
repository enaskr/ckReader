package com.ckchoi.ckreader

import org.junit.Assert.*
import org.junit.Test

class FontPreviewAssetsTest {
 @Test fun allKnownImageIdsResolveSafely() {
  val ids=listOf("bareun-batang","school-bareun","gowun-dodum","maru-buri",
    "seoul-hangang","seoul-namsan","gyeonggi-batang","ibm-plex-sans-kr",
    "kopub-world-batang","kopub-world-dotum","nanum-gibbeum",
    "seoul-alrim","sogang","yes24")
  assertEquals(14,ids.size)
  assertEquals(ids.size,ids.toSet().size)
  for(id in ids)assertEquals("font_previews/$id.webp",FontPreviewAssets.assetName(id))
 }
 @Test fun rejectInvalidPathIds() {
  for(id in listOf("../secret","abc/def","A B","-foo","foo..bar",""))
   assertNull(FontPreviewAssets.assetName(id))
 }
}
