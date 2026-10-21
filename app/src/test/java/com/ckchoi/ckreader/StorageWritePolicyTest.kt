package com.ckchoi.ckreader

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class StorageWritePolicyTest {
 @Test fun everyGdsMarkerForcesReadOnlyEvenWithWriteCapableAccountFields() {
  for(extra in listOf("{\"ckreader_gds\":true}","{\"type\":\"gds\"}","{\"gds_endpoint\":\"https://example.invalid\",\"token\":\"ignored\"}"))
   assertTrue(LibrarySource("id","drive","GDS","",extra=extra).readOnly)
  assertTrue(LibrarySource("id","gds","GDS","").readOnly)
 }
 @Test fun ordinaryFileSourcesStillUseActualFolderPermissions() {
  for(type in listOf("local","drive","webdav","ftp"))
   assertFalse(LibrarySource("id",type,"Test","",extra="{\"type\":\"drive\"}").readOnly)
 }
 @Test fun directGoogleNeedsExplicitFullDriveScopeAndGdsNeverWrites(){
  val auth=JSONObject().put(GoogleDriveSignIn.AUTH_KEY,true)
  assertTrue(LibrarySource("id","drive","Google","",extra=auth.toString()).readOnly)
  assertFalse(LibrarySource("id","drive","Google","",extra=auth.put("scope","drive").toString()).readOnly)
  assertTrue(LibrarySource("id","drive","GDS","",extra=auth.put("ckreader_gds",true).toString()).readOnly)
 }
}
