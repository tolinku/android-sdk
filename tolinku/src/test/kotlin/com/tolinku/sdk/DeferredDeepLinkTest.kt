package com.tolinku.sdk

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [DeferredDeepLink], covering token-based claiming
 * and input validation.
 *
 * Android-specific APIs (WindowMetrics) require instrumented tests
 * and are not covered here.
 */
class DeferredDeepLinkTest {

    private lateinit var server: MockWebServer
    private lateinit var client: TolinkuClient
    private lateinit var deferred: DeferredDeepLink

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = TolinkuClient(
            apiKey = "tolk_pub_test_key",
            baseUrl = server.url("/").toString().trimEnd('/')
        )
        deferred = DeferredDeepLink(client)
    }

    @After
    fun tearDown() {
        client.shutdown()
        server.shutdown()
    }

    // -----------------------------------------------------------------------
    // claimByToken
    // -----------------------------------------------------------------------

    @Test
    fun `claimByToken returns DeferredLink on success`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"deep_link_path":"/product/42","appspace_id":"app123"}""")
        )

        val link = deferred.claimByToken("tok_abc123")

        assertNotNull(link)
        assertEquals("/product/42", link!!.deepLinkPath)
        assertEquals("app123", link.appspaceId)

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertTrue(
            "Path should contain token query param, got: ${request.path}",
            request.path!!.contains("token=tok_abc123")
        )
    }

    @Test
    fun `claimByToken sends unauthenticated request`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"deep_link_path":"/home","appspace_id":"app123"}""")
        )

        deferred.claimByToken("tok_xyz")

        val request = server.takeRequest()
        assertNull(
            "claimByToken should not include API key header",
            request.getHeader("X-API-Key")
        )
    }

    @Test
    fun `claimByToken returns null on 404`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(404)
                .setBody("""{"error":"Not Found"}""")
        )

        val link = deferred.claimByToken("tok_nonexistent")
        assertNull(link)
    }

    @Test
    fun `claimByToken throws on non-404 error`() = runTest {
        // Enqueue enough 500s to exhaust retries (initial + MAX_RETRIES)
        repeat(TolinkuClient.MAX_RETRIES + 1) {
            server.enqueue(
                MockResponse()
                    .setResponseCode(500)
                    .setBody("""{"error":"Internal Server Error"}""")
            )
        }

        try {
            deferred.claimByToken("tok_err")
            fail("Expected TolinkuException")
        } catch (e: TolinkuException) {
            assertEquals(500, e.statusCode)
        }
    }

    @Test
    fun `claimByToken with blank token throws IllegalArgumentException`() = runTest {
        try {
            deferred.claimByToken("")
            fail("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("token"))
        }
    }

    @Test
    fun `claimByToken with whitespace token throws IllegalArgumentException`() = runTest {
        try {
            deferred.claimByToken("   ")
            fail("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("token"))
        }
    }

    @Test
    fun `claimByToken parses referral fields`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                        "deep_link_path": "/offer/summer",
                        "appspace_id": "app456",
                        "referrer_id": "user_abc",
                        "referral_code": "REF123"
                    }
                    """.trimIndent()
                )
        )

        val link = deferred.claimByToken("tok_full")

        assertNotNull(link)
        assertEquals("/offer/summer", link!!.deepLinkPath)
        assertEquals("app456", link.appspaceId)
        assertEquals("user_abc", link.referrerId)
        assertEquals("REF123", link.referralCode)
    }
}
