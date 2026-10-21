package com.ckchoi.ckreader

/** Google login connects directly; all other Drive connections use the RClone addon. */
internal object RcloneModuleRemoval {
 fun affectedSources(sources:List<LibrarySource>):List<LibrarySource> =
  sources.filter{it.type=="drive"&&!DriveAccess.google(it)}
}
