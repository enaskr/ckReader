package com.ckchoi.ckreader

/**
 * Automatic web metadata completion is strictly for books without cover
 * information. Existing source cover or user-selected web cover metadata is
 * authoritative; a persisted BLOB also prevents automatic overwrite.
 *
 * Explicit user actions (manual lookup and cache rebuild) use separate entry
 * points and are not suppressed by this automatic-only policy.
 */
internal object BookMetadataCoverPolicy {
 fun hasCoverInformation(book:LibraryBook,cachedThumbnail:Boolean):Boolean =
  book.cover.isNotBlank()||book.webCover.isNotBlank()||cachedThumbnail
 fun shouldAutoSearch(book:LibraryBook,cachedThumbnail:Boolean):Boolean =
  !hasCoverInformation(book,cachedThumbnail)
}
