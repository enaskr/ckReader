package com.ckchoi.ckreader

import org.junit.Assert.assertEquals
import org.junit.Test

class BookSizeFormatterTest {
 @Test fun formatsBytesAndBinaryUnits() {
  assertEquals("확인되지 않음",BookSizeFormatter.format(-1L))
  assertEquals("0 B",BookSizeFormatter.format(0L))
  assertEquals("512 B",BookSizeFormatter.format(512L))
  assertEquals("1.0 KiB (1,024 B)",BookSizeFormatter.format(1024L))
  assertEquals("1.0 MiB (1,048,576 B)",BookSizeFormatter.format(1_048_576L))
  assertEquals("1.0 GiB (1,073,741,824 B)",BookSizeFormatter.format(1_073_741_824L))
 }
}
