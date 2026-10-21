package com.ckchoi.ckreader

import org.junit.Assert.*
import org.junit.Test

class LibraryScrollPolicyTest {
 @Test fun openingAnyFolderStartsFromTopEvenIfPreviouslyVisited() {
  assertFalse(LibraryScrollPolicy.useHistory(onBack=false))
  assertTrue(LibraryScrollPolicy.useHistory(onBack=true))
 }
 @Test fun backNavigationRestoresTheSameFolderOrBookWhenRowsShift() {
  val rows=listOf("heading:FOLDERS","folder:alpha","folder:beta","folder:gamma")
  assertEquals(2,LibraryScrollPolicy.position(rows,"folder:beta",1))
  val changed=listOf("heading:FOLDERS","folder:new","folder:alpha","folder:beta")
  assertEquals(3,LibraryScrollPolicy.position(changed,"folder:beta",2))
 }
 @Test fun outdatedAnchorFallsBackToSafePosition() {
  assertEquals(1,LibraryScrollPolicy.position(listOf("a","b"),"missing",1))
  assertEquals(1,LibraryScrollPolicy.position(listOf("a","b"),"missing",999))
  assertEquals(0,LibraryScrollPolicy.position(emptyList(),"anything",999))
 }
}
