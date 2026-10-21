package com.ckchoi.ckreader

/**
 * Native rclone Range reads may stall after the header probe succeeded.
 * Use a complete validated local ZIP/CBZ for all Drive-backed sources:
 * imported rclone configuration, GDS, and Google OAuth login.
 *
 * This is intentionally independent of metadata file size (which can be
 * unavailable before remote listing finishes).
 */
internal object RemoteArchiveReadPolicy {
 fun useDisk(
  sourceType:String,
  format:String,
  sizeBytes:Long,
  isGds:Boolean=false,
 ):Boolean=sourceType=="drive" && format.lowercase() in setOf("zip","cbz")
}
