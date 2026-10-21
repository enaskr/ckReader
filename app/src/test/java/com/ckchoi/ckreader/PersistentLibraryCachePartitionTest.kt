package com.ckchoi.ckreader

import org.junit.Assert.assertEquals
import org.junit.Test

class PersistentLibraryCachePartitionTest {
 @Test fun rootLevelBooksAreGroupedIntoSingleDb() {
  assertEquals("__root__",PersistentLibraryCache.firstDepth("a.epub"))
  assertEquals("__root__",PersistentLibraryCache.firstDepth("b.pdf"))
  assertEquals("__root__",PersistentLibraryCache.topFolder(""))
 }
 @Test fun firstDirectorySplitsEachStorageIndependently() {
  assertEquals("Novels",PersistentLibraryCache.firstDepth("Novels/01.epub"))
  assertEquals("Novels",PersistentLibraryCache.firstDepth("Novels/Series/02.cbz"))
  assertEquals("Magazine",PersistentLibraryCache.firstDepth("Magazine/March.pdf"))
  assertEquals("Novels",PersistentLibraryCache.topFolder("Novels/Series"))
  assertEquals("Magazine",PersistentLibraryCache.topFolder("Magazine"))
 }
 @Test fun queryStringsDoNotChangeFolderDbPartition() {
  assertEquals("Series",PersistentLibraryCache.topFolder("Series?search=test"))
  assertEquals("__root__",PersistentLibraryCache.topFolder("?search=abc"))
 }
}
