package com.ckchoi.ckreader

import org.junit.Assert.*
import org.junit.Test

class LibraryVisibilityTest {
 @Test fun hidesEveryDescendantOfDotDirectory() {
  for(path in listOf(".관리자",".관리자/a.epub","books/.private/a.pdf","books/.a/.b/c.txt",".book.epub","a/.book.cbz"))
   assertFalse(path,LibraryVisibility.visible(path))
 }
 @Test fun keepsOrdinaryDotsAndLiteralEscapes() {
  for(path in listOf("","books","book.v2.epub","books v1.2/a.txt","%2Ebook.epub","숨김.아님/책.pdf"))
   assertTrue(path,LibraryVisibility.visible(path))
 }
 @Test fun removesOldSnapshotEntriesAndAssociatedFolderDetails() {
  val source=LibrarySource("visibility","webdav","Test","")
  fun book(path:String)=LibraryBook(path,source,path,path.substringAfterLast('/'))
  val old=LibraryListing(listOf(".private","visible"),listOf(
   book(".private/legacy.epub"),book("visible/.legacy.epub"),book("visible/real.epub")),
   folderCovers=mapOf(".private" to "hidden.jpg","visible" to "cover.jpg"),
   folderInfos=mapOf(".private" to FolderCatalogInfo(),"visible" to FolderCatalogInfo()))
  val filtered=LibraryTitleOrder.listing(old,"webdav")
  assertEquals(listOf("visible"),filtered.folders)
  assertEquals(listOf("visible/real.epub"),filtered.books.map{it.path})
  assertEquals(setOf("visible"),filtered.folderCovers.keys)
  assertEquals(setOf("visible"),filtered.folderInfos.keys)
 }
 @Test fun restoredNavigationStopsAtLastVisibleParent() {
  assertEquals("books",LibraryVisibility.visibleAncestor("books/.private/subfolder"))
  assertEquals("",LibraryVisibility.visibleAncestor(".private/subfolder"))
  assertEquals("books/v1.2",LibraryVisibility.visibleAncestor("books/v1.2"))
 }
}
