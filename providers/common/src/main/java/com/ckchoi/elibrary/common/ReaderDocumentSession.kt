package com.ckchoi.elibrary.common

/** Active readers retain their original path and continue saving their current position. */
object ReaderDocumentSession {
 private val owners=java.util.WeakHashMap<Any,String>()
 @Synchronized fun open(owner:Any,document:String){owners[owner]=document}
 @Synchronized fun close(owner:Any){owners.remove(owner)}
 @Synchronized fun contains(document:String)=owners.values.any{it==document}
}
