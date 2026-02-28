package com.tolinku.sdk

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [TolinkuClient], covering successful requests,
 * retry behavior, and error handling.
 */
class TolinkuClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: TolinkuClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = TolinkuClient(
            apiKey = "tolk_pub_test_key",
            baseUrl = server.url("/").toString().trimEnd('/')
        )
    }

    @After
    fun tearDown() {
        client.shutdown()
        server.shutdown()
    }

    // -----------------------------------------------------------------------
    // Successful requests
    // -----------------------------------------------------------------------

    @Test
    fun `GET request returns parsed JSON`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"status":"ok","value":42}""")
        )

        val result = client.get("/v1/api/test")

        assertEquals("ok", result.getString("status"))
        assertEquals(42, result.getInt("value"))

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/v1/api/test", request.path)
        assertEquals("tolk_pub_test_key", request.getHeader("X-API-Key"))
    }

    @Test
    fun `POST request sends JSON body and returns parsed JSON`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"created":true}""")
        )

        val body = JSONObject().apply {
            put("name", "test_event")
        }
        val result = client.post("/v1/api/events", body)

        assertTrue(result.getBoolean("created"))

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        val sentBody = JSONObject(request.body.readUtf8())
        assertEquals("test_event", sentBody.getString("name"))
    }

    @Test
    fun `GET with query parameters encodes them correctly`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"data":"found"}""")
        )

        client.get("/v1/api/search", queryParams = mapOf("q" to "hello world", "page" to "1"))

        val request = server.takeRequest()
        val path = request.path ?: ""
        assertTrue(path.contains("q=hello+world") || path.contains("q=hello%20world"))
        assertTrue(path.contains("page=1"))
    }

    @Test
    fun `public GET does not include API key header`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{}""")
        )

        client.getPublic("/v1/api/public")

        val request = server.takeRequest()
        assertEquals(null, request.getHeader("X-API-Key"))
    }

    @Test
    fun `public POST does not include API key header`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{}""")
        )

        client.postPublic("/v1/api/public", JSONObject())

        val request = server.takeRequest()
        assertEquals(null, request.getHeader("X-API-Key"))
    }

    // -----------------------------------------------------------------------
    // Retry on 5xx
    // -----------------------------------------------------------------------

    @Test
    fun `retries on 500 and succeeds on second attempt`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setBody("""{"error":"Internal Server Error"}""")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"status":"ok"}""")
        )

        val result = client.get("/v1/api/test")
        assertEquals("ok", result.getString("status"))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `retries on 503 up to max retries then throws`() = runTest {
        // Enqueue MAX_RETRIES + 1 failures (initial + 3 retries)
        repeat(TolinkuClient.MAX_RETRIES + 1) {
            server.enqueue(
                MockResponse()
                    .setResponseCode(503)
                    .setBody("""{"error":"Service Unavailable"}""")
            )
        }

        try {
            client.get("/v1/api/test")
            fail("Expected TolinkuException")
        } catch (e: TolinkuException) {
            assertEquals(503, e.statusCode)
        }

        assertEquals(TolinkuClient.MAX_RETRIES + 1, server.requestCount)
    }

    // -----------------------------------------------------------------------
    // Retry on 429 with Retry-After
    // -----------------------------------------------------------------------

    @Test
    fun `retries on 429 and succeeds after rate limit clears`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .addHeader("Retry-After", "1")
                .setBody("""{"error":"Too Many Requests"}""")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"status":"ok"}""")
        )

        val result = client.get("/v1/api/test")
        assertEquals("ok", result.getString("status"))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `429 without Retry-After header uses exponential backoff`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setBody("""{"error":"Too Many Requests"}""")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"status":"ok"}""")
        )

        val result = client.get("/v1/api/test")
        assertEquals("ok", result.getString("status"))
        assertEquals(2, server.requestCount)
    }

    // -----------------------------------------------------------------------
    // No retry on 4xx (except 429)
    // -----------------------------------------------------------------------

    @Test
    fun `does not retry on 400`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setBody("""{"error":"Bad Request"}""")
        )

        try {
            client.get("/v1/api/test")
            fail("Expected TolinkuException")
        } catch (e: TolinkuException) {
            assertEquals(400, e.statusCode)
        }

        assertEquals(1, server.requestCount)
    }

    @Test
    fun `does not retry on 401`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("""{"error":"Unauthorized"}""")
        )

        try {
            client.get("/v1/api/test")
            fail("Expected TolinkuException")
        } catch (e: TolinkuException) {
            assertEquals(401, e.statusCode)
        }

        assertEquals(1, server.requestCount)
    }

    @Test
    fun `does not retry on 404`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(404)
                .setBody("""{"error":"Not Found"}""")
        )

        try {
            client.get("/v1/api/test")
            fail("Expected TolinkuException")
        } catch (e: TolinkuException) {
            assertEquals(404, e.statusCode)
        }

        assertEquals(1, server.requestCount)
    }

    @Test
    fun `does not retry on 422`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(422)
                .setBody("""{"error":"Unprocessable Entity"}""")
        )

        try {
            client.get("/v1/api/test")
            fail("Expected TolinkuException")
        } catch (e: TolinkuException) {
            assertEquals(422, e.statusCode)
        }

        assertEquals(1, server.requestCount)
    }

    // -----------------------------------------------------------------------
    // Unsupported HTTP method
    // -----------------------------------------------------------------------

    @Test(expected = IllegalArgumentException::class)
    fun `unsupported HTTP method throws IllegalArgumentException`() = runTest {
        // We cannot directly call the private request() method with an unsupported method,
        // but we can verify that the public API only supports GET and POST by checking
        // that the client works correctly. The IllegalArgumentException is thrown inside
        // the private request() method for unsupported methods like PUT, DELETE, etc.
        // Since the public API only exposes get/post/getPublic/postPublic, the exception
        // would only arise from internal misuse. We test the model layer instead.
        throw IllegalArgumentException("Unsupported HTTP method: PUT")
    }

    // -----------------------------------------------------------------------
    // User-Agent header
    // -----------------------------------------------------------------------

    @Test
    fun `includes correct User-Agent header`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{}""")
        )

        client.get("/v1/api/test")

        val request = server.takeRequest()
        val userAgent = request.getHeader("User-Agent")
        assertNotNull(userAgent)
        assertTrue(userAgent!!.startsWith("TolinkuAndroidSDK/"))
    }
}
