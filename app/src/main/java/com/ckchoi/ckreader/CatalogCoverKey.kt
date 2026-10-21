package com.ckchoi.ckreader

/**
 * Thumbnail memory keys depend only on immutable source/book/folder identity.
 * Changing a web cover URL, title or metadata source cannot hide a cached image.
 */
internal object CatalogCoverKey {
 fun book(sourceId:String,bookId:String,path:String):String =
  "b|"+sourceId+"|"+bookId+"|"+path
 fun folder(sourceId:String,path:String):String =
  "f|"+sourceId+"|"+path
}
