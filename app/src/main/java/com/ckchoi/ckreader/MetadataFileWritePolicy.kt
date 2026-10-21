package com.ckchoi.ckreader

/**
 * Cache fuzzy matches for display, but do not reorganize physical files
 * unless an official result has a verified identity (ISBN or exact title).
 */
internal object MetadataFileWritePolicy {
 fun eligible(query:String,author:String,isbn:String,match:WebBookMetadata):Boolean {
  if(!MetadataSourceBadge.isOfficial(match.source))return false
  val requestedIsbn=isbn.filter(Char::isDigit).takeIf{it.length in listOf(10,13)}
  val resolvedIsbn=match.isbn.filter(Char::isDigit).takeIf{it.length in listOf(10,13)}
  if(requestedIsbn!=null&&resolvedIsbn!=null&&requestedIsbn!=resolvedIsbn)return false
  if(requestedIsbn!=null&&requestedIsbn==resolvedIsbn)return true
  val requestedTitle=MetadataTitleMatcher.normalized(query)
  val resolvedTitle=MetadataTitleMatcher.normalized(match.title)
  if(requestedTitle.length<2||requestedTitle!=resolvedTitle)return false
  val requestedAuthor=MetadataTitleMatcher.normalized(author)
  val resolvedAuthor=MetadataTitleMatcher.normalized(match.author)
  return requestedAuthor.isBlank()||resolvedAuthor.isBlank()||
   requestedAuthor.contains(resolvedAuthor)||resolvedAuthor.contains(requestedAuthor)
 }
}
