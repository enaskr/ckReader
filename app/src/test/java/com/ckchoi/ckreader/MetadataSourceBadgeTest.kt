package com.ckchoi.ckreader

import org.junit.Assert.*
import org.junit.Test

class MetadataSourceBadgeTest {
 @Test fun explicitProviderBadgesAreKyanl(){
  assertEquals("K",MetadataSourceBadge.code("YAML · kavita.yaml"))
  assertEquals("K",MetadataSourceBadge.code("Google Drive · KAVITA.YML"))
  assertEquals("Y",MetadataSourceBadge.code("YAML · book.epub.yaml"))
  assertEquals("Y",MetadataSourceBadge.code("Google Drive · book.yml"))
  assertEquals("A",MetadataSourceBadge.code("알라딘 OpenAPI"))
  assertEquals("N",MetadataSourceBadge.code("국립중앙도서관 OpenAPI"))
  assertEquals("L",MetadataSourceBadge.code("도서관 정보나루"))
  assertEquals("L",MetadataSourceBadge.code("도서관정보마루"))
  assertEquals("B",MetadataSourceBadge.code("Book Oasis API"))
  assertEquals("W",MetadataSourceBadge.code("네이버시리즈"))
  assertEquals("?",MetadataSourceBadge.code(""))
 }
 @Test fun verifyAuthoredAndOfficialSourcesForNonDestructiveYamlWrites(){
  assertTrue(MetadataSourceBadge.isKavita("YAML · kavita.yaml"))
  assertFalse(MetadataSourceBadge.isKavita("Google Drive · metadata.yaml"))
  assertTrue(MetadataSourceBadge.isYaml("Google Drive · name.epub.yaml"))
  assertFalse(MetadataSourceBadge.isYaml("알라딘 OpenAPI"))
  for(value in listOf("알라딘 OpenAPI","국립중앙도서관 OpenAPI","도서관 정보나루"))
   assertTrue(MetadataSourceBadge.isOfficial(value))
  assertFalse(MetadataSourceBadge.isOfficial("네이버시리즈"))
  assertFalse(MetadataSourceBadge.isOfficial("YAML · kavita.yaml"))
 }
 @Test fun detailBadgeNamesMatchTheFiveRefreshSources(){
  assertEquals("kavita.yaml",MetadataSourceBadge.label("K"))
  assertEquals("도서 파일 YAML",MetadataSourceBadge.label("Y"))
  assertEquals("알라딘 OpenAPI",MetadataSourceBadge.label("A"))
  assertEquals("국립중앙도서관 OpenAPI",MetadataSourceBadge.label("N"))
  assertEquals("도서관 정보나루 OpenAPI",MetadataSourceBadge.label("L"))
  assertEquals("Book Oasis API",MetadataSourceBadge.label("B"))
  assertEquals("기타 도서정보",MetadataSourceBadge.label("W"))
 }
}
