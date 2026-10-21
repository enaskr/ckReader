package com.ckchoi.ckreader

/** A completed cache requires durable data, not a catalog row or remote URL. */
internal object CacheRebuildPolicy {
 fun shouldSkip(forceRebuild:Boolean,hasSavedCache:Boolean,needsDriveWriteback:Boolean=false):Boolean =
  !forceRebuild && hasSavedCache && !needsDriveWriteback
}
