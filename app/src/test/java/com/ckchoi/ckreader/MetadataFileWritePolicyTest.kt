package com.ckchoi.ckreader

import org.junit.Assert.*
import org.junit.Test

class MetadataFileWritePolicyTest {
 private fun book(source:String="알라딘 OpenAPI",title:String="어린 왕자",
  isbn:String="",author:String="생텍쥐페리") =
  WebBookMetadata(title=title,author=author,isbn=isbn,source=source)
 @Test fun neverMovesForYamlOrWebOrFuzzyTitle(){
  assertFalse(MetadataFileWritePolicy.eligible("어린 왕자","","",
   book(source="네이버")))
  assertFalse(MetadataFileWritePolicy.eligible("어린 왕자","","",
   book(source="YAML · kavita.yaml")))
  assertFalse(MetadataFileWritePolicy.eligible("어린 왕자","","",
   book(title="어린 왕자 해설판")))
  assertFalse(MetadataFileWritePolicy.eligible("어린 왕자","다른 저자","",book()))
 }
 @Test fun exactIdentityAllowsOfficialSourceButNotConflictingIsbn(){
  assertTrue(MetadataFileWritePolicy.eligible("어린 왕자","생텍쥐페리","",book()))
  assertTrue(MetadataFileWritePolicy.eligible("다른 표제","","9788932917245",
   book(isbn="9788932917245")))
  assertFalse(MetadataFileWritePolicy.eligible("어린 왕자","","9788932917245",
   book(isbn="9780000000000")))
  assertTrue(MetadataFileWritePolicy.eligible("어린 왕자","","",
   book(source="도서관 정보나루")))
 }
}
