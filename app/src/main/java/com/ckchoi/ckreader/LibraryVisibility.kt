package com.ckchoi.ckreader

/** Storage-relative names only: a dot anywhere else in a name is ordinary text. */
internal object LibraryVisibility {
 fun visible(path:String):Boolean=path.split('/','\\').none{it.startsWith('.')}

 fun visibleAncestor(path:String):String=path.split('/').takeWhile{!it.startsWith('.')}.joinToString("/")

 fun listing(value:LibraryListing):LibraryListing {
  val folders=value.folders.filter(::visible)
  return value.copy(folders=folders,
   books=value.books.filter{visible(it.path)&&visible(it.filename)},
   folderCovers=value.folderCovers.filterKeys{it in folders},
   folderInfos=value.folderInfos.filterKeys{it in folders})
 }
}
