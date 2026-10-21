package com.ckchoi.ckreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CancellationException

class BookOasisPaginationTest {
 @Test fun retrievesAllPagesAndDeduplicatesSeries() {
  val pages=mapOf(
   1 to BookOasisPagination.Slice(listOf("A","B","C"),true),
   2 to BookOasisPagination.Slice(listOf("C","D","E"),true),
   3 to BookOasisPagination.Slice(listOf("F"),false)
  )
  val received=mutableListOf<Int>()
  val totals=mutableListOf<Int>()
  val result=BookOasisPagination.collect(
   key={it},fetch={ page->received.add(page);pages.getValue(page) },
   onPage={_,all,_->totals.add(all.size)}
  )
  assertEquals(listOf(1,2,3),received)
  assertEquals(listOf(3,5,6),totals)
  assertEquals(listOf("A","B","C","D","E","F"),result)
 }

 @Test fun resumesIncompleteListingWithoutRefetchingOlderPages() {
  val received=mutableListOf<Int>()
  val result=BookOasisPagination.collect(
   firstPage=3,existing=listOf("A","B"),key={it},
   fetch={n->received.add(n);BookOasisPagination.Slice(listOf("C","D"),false)},
   onPage={_,_,_->}
  )
  assertEquals(listOf(3),received)
  assertEquals(listOf("A","B","C","D"),result)
 }

 @Test fun reportsRepeatedPagesInsteadOfMarkingComplete() {
  assertThrows(IllegalStateException::class.java) {
   BookOasisPagination.collect(
    key={it},fetch={ BookOasisPagination.Slice(listOf("same"),true) },
    onPage={_,_,_->}
   )
  }
 }

 @Test fun supportsCancellationWithoutFetchingNextPage() {
  val seen=mutableListOf<Int>()
  var cancel=false
  assertThrows(CancellationException::class.java) {
   BookOasisPagination.collect(
    key={it},fetch={n->seen.add(n);BookOasisPagination.Slice(listOf("$n"),true)},
    onPage={_,_,_->cancel=true},
    cancelled={cancel}
   )
  }
  assertTrue(seen.size==1)
 }
}
