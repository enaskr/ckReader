package com.ckchoi.ckreader

import org.junit.Assert.*
import org.junit.Test

class AladinApiRoutingTest {
 @Test fun primaryUrlAlwaysUsesHttps() {
  assertEquals("https://aladinapi.ibmer.app",AladinApiRouting.PRIMARY)
  assertEquals("http://help.dscloud.me:9786",AladinApiRouting.LEGACY)
 }
 @Test fun legacyOnlyForTransportOrGatewayOutages() {
  assertTrue(AladinApiRouting.requiresLegacy(null,true))
  for(code in listOf(502,503,504))
   assertTrue(AladinApiRouting.requiresLegacy(code,false))
  for(code in listOf(200,201,400,401,403,404,429,500))
   assertFalse("not a connection problem: $code",
    AladinApiRouting.requiresLegacy(code,false))
 }
 @Test fun savedCoverUrlsMapToAuthenticatedPrimary() {
  val path="/covers/402732161"
  assertEquals(path,AladinApiRouting.cachedCoverPath(
   "http://help.dscloud.me:9786"+path))
  assertEquals(path,AladinApiRouting.cachedCoverPath(
   "https://aladinapi.ibmer.app"+path))
  assertNull(AladinApiRouting.cachedCoverPath(
   "http://help.dscloud.me:9786"+path+"?X-API-Key=secret"))
  assertNull(AladinApiRouting.cachedCoverPath(
   "https://aladinapi.ibmer.app@somewhere.invalid"+path))
  assertNull(AladinApiRouting.cachedCoverPath(
   "http://localhost:9786"+path))
  assertNull(AladinApiRouting.cachedCoverPath(
   "https://aladinapi.ibmer.app/api/books/402732161"))
 }
 @Test fun onlyListedReadEndpointsAreAccepted() {
  for(path in listOf("/api/books","/api/books/123","/api/isbn/9791173255694",
   "/covers/402732161","/api/health"))
   assertTrue("should allow $path",AladinApiRouting.validPath(path))
  for(path in listOf("/api/delete","/api/books/123/../../../etc/passwd",
   "/covers/404?token=secret","/api/login","/api/books/-1","/api/stats"))
   assertFalse("should reject $path",AladinApiRouting.validPath(path))
 }
 @Test fun userProvidedApiKeyRejectsNewlineInjection() {
  assertTrue(AladinApiCredentials.validKey("valid-key-sample-long"))
  assertFalse(AladinApiCredentials.validKey("short"))
  assertFalse(AladinApiCredentials.validKey("some-valid-value\r\nInjected: true"))
 }
}
