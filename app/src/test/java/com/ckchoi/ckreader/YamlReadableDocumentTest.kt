package com.ckchoi.ckreader

import org.junit.Assert.*
import org.junit.Test

class YamlReadableDocumentTest {
 @Test fun findsKavitaYamlAndYmlIgnoringCase() {
  assertTrue(YamlReadableDocument.isKavita("KAVITA.yaml"))
  assertTrue(YamlReadableDocument.isKavita("kavita.yml"))
  assertFalse(YamlReadableDocument.isKavita("metadata.yaml"))
 }
 @Test fun permitsOnlyYamlBasenames() {
  for(name in listOf("kavita.yaml","metadata.YML","book1.yaml"))assertTrue(YamlReadableDocument.isYamlFile(name))
  for(name in listOf(".hidden.yml","../secret.yaml","a/secret.yaml","a\\secret.yaml","x.txt",""))assertFalse(YamlReadableDocument.isYamlFile(name))
 }
 @Test fun prioritizesKavitaFilesInDisplay() {
  val names=YamlReadableDocument.candidateNames(listOf("other.yml","KAVITA.YAML","kavita.yml","a.yaml","kavita.yaml","x.txt"))
  assertEquals("KAVITA.YAML",names.first())
  assertEquals(4,names.size)
 }
 @Test fun parsesMetadataNestedSectionsWithoutExecutingTags() {
  val rows=YamlReadableDocument.parse("""title: 한글 도서\nauthor: 홍길동\nbooks:\n  - title: 제1권\n    isbn: 123456789\n""".replace("\\n","\n").toByteArray())
  assertTrue(rows.filterIsInstance<YamlReadableDocument.Row.Value>().any{it.label.contains("도서명")&&it.text=="한글 도서"})
  assertTrue(rows.filterIsInstance<YamlReadableDocument.Row.Heading>().any{it.label.contains("도서 목록")})
 }
 @Test(expected=Exception::class)
 fun refusesUntrustedObjectTags(){
  YamlReadableDocument.parse("!!javax.script.ScriptEngineManager {}".toByteArray())
 }
}
