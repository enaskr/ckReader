package com.ckchoi.ckreader

import org.junit.Assert.*
import org.junit.Test

class BookOrganizationRulesTest {
 @Test fun singleBookStaysExactlyInCurrentFolder(){
  for(folder in listOf("","책/일반/0Z","책/분류/제목 [저자]")){
   val book=BookOrganizationRules.Book(
    FolderYamlStorage.child(folder,"raw.epub"),"3540 임신출산의 모든 것","김영아, 박현주")
   val plan=BookOrganizationRules.plan(folder,listOf(book),1).single()
   assertEquals(folder,plan.folder)
   assertEquals(listOf(book),plan.books)
  }
 }
 @Test fun severalBooksReceiveDifferentFoldersEvenForSameSeries(){
  val books=(1..2).map{
   BookOrganizationRules.Book("incoming/book$it.epub","안시성 "+it+"권","배상열","안시성")
  }
  val plan=BookOrganizationRules.plan("incoming",books,2)
  assertEquals(2,plan.size)
  assertEquals(2,plan.map{it.folder}.distinct().size)
  assertTrue(plan.all{it.folder.startsWith("incoming/")})
  assertTrue(plan.all{it.books.size==1})
 }
 @Test fun differentAuthorsNeverMerge(){
  val books=listOf(BookOrganizationRules.Book("in/a.epub","같은 제목","저자 A"),
   BookOrganizationRules.Book("in/b.epub","같은 제목","저자 B"))
  val names=BookOrganizationRules.plan("in",books,2).map{it.folder}
  assertEquals(setOf("in/같은 제목_저자 A","in/같은 제목_저자 B"),names.toSet())
 }
 @Test fun fileNameFallbackWhenTitleIsMissing(){
  val unknown=BookOrganizationRules.Book("in/my-file.cbz","","")
  assertEquals("in/my-file",BookOrganizationRules.plan("in",listOf(unknown),2).single().folder)
 }
 @Test fun duplicateNamesGetCollisionFreeSuffix(){
  val books=listOf(
   BookOrganizationRules.Book("in/a.epub","제목","저자"),
   BookOrganizationRules.Book("in/b.epub","제목","저자"),
   BookOrganizationRules.Book("in/c.epub","제목","저자")
  )
  val results=BookOrganizationRules.plan("in",books,3)
  assertEquals(3,results.size)
  assertEquals(3,results.map{it.folder}.distinct().size)
  assertEquals("in/제목_저자",results[0].folder)
 }
 @Test fun titlesAndAuthorsRetainScriptsAndDoNotMerge(){
  val books=listOf(
   BookOrganizationRules.Book("in/a.epub","日本語の本","山田太郎"),
   BookOrganizationRules.Book("in/b.epub","中文书籍","王明"),
   BookOrganizationRules.Book("in/c.epub","C++","Author"),
   BookOrganizationRules.Book("in/d.epub","C","Author"),
   BookOrganizationRules.Book("in/e.epub","★","Author"),
   BookOrganizationRules.Book("in/f.epub","☆","Author"))
  assertEquals(books.size,BookOrganizationRules.plan("in",books,books.size).size)
  assertEquals("John Smith",BookOrganizationRules.primaryAuthor("John Smith, Jane"))
 }
 @Test fun safeNamesCannotEscapeOrBecomeHidden(){
  assertEquals("",BookOrganizationRules.safeName(".."))
  for(text in listOf("../a/b:*?",".hidden","CON","한글".repeat(100))) {
   val safe=BookOrganizationRules.safeName(text)
   assertFalse(safe.startsWith('.'))
   assertFalse(safe.contains('/'))
   assertTrue(safe.toByteArray().size<=220)
  }
 }
}
