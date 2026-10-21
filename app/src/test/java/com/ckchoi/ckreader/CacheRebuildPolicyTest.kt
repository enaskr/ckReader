package com.ckchoi.ckreader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CacheRebuildPolicyTest {
 @Test fun normalGenerationSkipsOnlyPersistentCompletedCache() {
  assertTrue(CacheRebuildPolicy.shouldSkip(forceRebuild=false,hasSavedCache=true))
  assertFalse(CacheRebuildPolicy.shouldSkip(forceRebuild=false,hasSavedCache=false))
 }

 @Test fun forceRebuildProcessesCachedAndUncachedBooks() {
  assertFalse(CacheRebuildPolicy.shouldSkip(forceRebuild=true,hasSavedCache=true))
  assertFalse(CacheRebuildPolicy.shouldSkip(forceRebuild=true,hasSavedCache=false))
 }
 @Test fun driveBookWithoutYamlIsProcessedEvenWhenCoverIsCached() {
  assertFalse(CacheRebuildPolicy.shouldSkip(forceRebuild=false,hasSavedCache=true,
   needsDriveWriteback=true))
 }
}
