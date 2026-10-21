package com.ckchoi.ckreader

import java.text.Collator
import java.util.Locale

/**
 * A single display-title sort used by all local/remote libraries.
 *
 * Prefer the actual title shown in the list (YAML, BookOasis or verified
 * metadata); when it is unavailable, use the filename without its extension.
 * Compare Korean text with the Korean collation rules, ignoring letter case,
 * and compare digit runs numerically ("2권" before "10권").
 */
internal object LibraryTitleOrder {
 private fun collator():Collator=Collator.getInstance(Locale.KOREA).apply {
  strength=Collator.SECONDARY
  decomposition=Collator.CANONICAL_DECOMPOSITION
 }

 fun bookTitle(book:LibraryBook):String=
  SeriesVolumeTitle.title(book.filename,book.title).trim().ifBlank {
   book.filename.substringBeforeLast('.',book.filename).trim()
  }

 internal fun compare(a:String,b:String,collator:Collator):Int {
  var i=0;var j=0
  val left=a.trim();val right=b.trim()
  while(i<left.length && j<right.length) {
   val startI=i;val startJ=j
   if(left[i] in '0'..'9' && right[j] in '0'..'9') {
    while(i<left.length && left[i] in '0'..'9')i++
    while(j<right.length && right[j] in '0'..'9')j++
    val digitsA=left.substring(startI,i).trimStart('0').ifBlank{"0"}
    val digitsB=right.substring(startJ,j).trimStart('0').ifBlank{"0"}
    val numeric=digitsA.length.compareTo(digitsB.length).takeIf{it!=0}
     ?:digitsA.compareTo(digitsB)
    if(numeric!=0)return numeric
    val padding=(i-startI).compareTo(j-startJ)
    if(padding!=0)return padding
   } else {
    if(left[i] in '0'..'9') {
     i++
    } else {
     while(i<left.length && left[i] !in '0'..'9')i++
    }
    if(right[j] in '0'..'9') {
     j++
    } else {
     while(j<right.length && right[j] !in '0'..'9')j++
    }
    val compared=collator.compare(left.substring(startI,i),right.substring(startJ,j))
    if(compared!=0)return compared
   }
  }
  return (left.length-i).compareTo(right.length-j).sign()
 }

 private fun Int.sign():Int=when {
  this<0->-1
  this>0->1
  else->0
 }

 fun books(items:List<LibraryBook>):List<LibraryBook> {
  if(items.size<2)return items
  val language=collator()
  return items.sortedWith(Comparator { a,b ->
   compare(bookTitle(a),bookTitle(b),language).takeIf{it!=0}
    ?:compare(a.filename,b.filename,language).takeIf{it!=0}
    ?:a.path.compareTo(b.path)
  })
 }

 fun folders(items:List<String>,bookOasis:Boolean):List<String> {
  if(items.size<2)return items
  val language=collator()
  val home=if(bookOasis)BookOasisPaths.library("home","전체 보기") else ""
  return items.sortedWith(Comparator { a,b ->
   when {
    a==home&&b!=home->-1
    b==home&&a!=home->1
    else -> {
     val nameA=if(bookOasis)BookOasisPaths.display(a) else a
     val nameB=if(bookOasis)BookOasisPaths.display(b) else b
     compare(nameA,nameB,language).takeIf{it!=0} ?: a.compareTo(b)
    }
   }
  })
 }

 fun listing(result:LibraryListing,sourceType:String):LibraryListing {
  val visible=LibraryVisibility.listing(result)
  return visible.copy(folders=folders(visible.folders,sourceType=="bookoasis"),
   books=books(visible.books))
 }
}
