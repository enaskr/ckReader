package com.ckchoi.ckreader

import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class LiteEditionTest {
 @Test fun disabledIntegrationsCannotReachTheirNetworkClients() {
  assumeTrue(BuildConfig.LITE)
  assertEquals(AladinApiStatus.KEY_MISSING,
   AladinApiGateway(null).request("/api/health",overrideKey="test-valid-key").status)
  val http=OkHttpClient.Builder().addInterceptor{
   throw AssertionError("A removed OpenAPI was contacted: ${it.request().url}")
  }.build()
  val catalog=PublicLibraryCatalogClient(null,http,{"test-valid-key"})
  assertTrue(catalog.search(MetadataApiProvider.NATIONAL_LIBRARY,"어린 왕자").isEmpty())
  assertTrue(catalog.search(MetadataApiProvider.DATA4LIBRARY,"어린 왕자").isEmpty())
  assertFalse(catalog.contacted)
  assertTrue(BookMetadataSearch().searchAladin("어린 왕자").isEmpty())
  assertNull(BookMetadataSearch().searchOfficial("어린 왕자"))
  val denied=runCatching{GoogleDriveSignIn.request("reader@example.invalid")}.exceptionOrNull()
  assertTrue(denied is IllegalStateException)
 }
}
