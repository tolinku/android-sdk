package com.tolinku.sdk

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [Messages], covering fetch behavior, response parsing,
 * and input validation.
 */
class MessagesTest {

    private lateinit var server: MockWebServer
    private lateinit var client: TolinkuClient
    private lateinit var messages: Messages

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = TolinkuClient(
            apiKey = "tolk_pub_test_key",
            baseUrl = server.url("/").toString().trimEnd('/')
        )
        messages = Messages(client)
    }

    @After
    fun tearDown() {
        client.shutdown()
        server.shutdown()
    }

    // -----------------------------------------------------------------------
    // Fetch without trigger
    // -----------------------------------------------------------------------

    @Test
    fun `fetch without trigger calls correct endpoint`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"messages":[]}""")
        )

        messages.fetch()

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/v1/api/messages", request.path)
    }

    @Test
    fun `fetch returns parsed messages`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                        "messages": [
                            {
                                "id": "msg_001",
                                "name": "welcome_msg",
                                "title": "Welcome!",
                                "body": "Thanks for joining.",
                                "trigger": "welcome",
                                "priority": 10
                            },
                            {
                                "id": "msg_002",
                                "name": "feature_msg",
                                "title": "New Feature",
                                "body": "Check out our latest feature.",
                                "priority": 5
                            }
                        ]
                    }
                    """.trimIndent()
                )
        )

        val result = messages.fetch()

        assertEquals(2, result.size)

        val first = result[0]
        assertEquals("msg_001", first.id)
        assertEquals("Welcome!", first.title)
        assertEquals("Thanks for joining.", first.body)
        assertEquals("welcome", first.trigger)
        assertEquals(10, first.priority)

        val second = result[1]
        assertEquals("msg_002", second.id)
        assertEquals("New Feature", second.title)
    }

    @Test
    fun `fetch returns empty list when no messages`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"messages":[]}""")
        )

        val result = messages.fetch()
        assertNotNull(result)
        assertTrue(result.isEmpty())
    }

    // -----------------------------------------------------------------------
    // Fetch with trigger
    // -----------------------------------------------------------------------

    @Test
    fun `fetch with trigger includes query parameter`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"messages":[]}""")
        )

        messages.fetch(trigger = "milestone")

        val request = server.takeRequest()
        assertTrue(
            "Path should contain trigger param, got: ${request.path}",
            request.path!!.contains("trigger=milestone")
        )
    }

    @Test
    fun `fetch sends authenticated request`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"messages":[]}""")
        )

        messages.fetch()

        val request = server.takeRequest()
        assertEquals("tolk_pub_test_key", request.getHeader("X-API-Key"))
    }

    // -----------------------------------------------------------------------
    // Validation
    // -----------------------------------------------------------------------

    @Test
    fun `fetch with blank trigger throws IllegalArgumentException`() = runTest {
        try {
            messages.fetch(trigger = "")
            fail("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("trigger"))
        }
    }

    @Test
    fun `fetch with whitespace trigger throws IllegalArgumentException`() = runTest {
        try {
            messages.fetch(trigger = "   ")
            fail("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("trigger"))
        }
    }

    @Test
    fun `fetch with null trigger does not throw`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"messages":[]}""")
        )

        // Should not throw
        val result = messages.fetch(trigger = null)
        assertNotNull(result)
    }

    // -----------------------------------------------------------------------
    // Error handling
    // -----------------------------------------------------------------------

    @Test
    fun `fetch throws TolinkuException on server error`() = runTest {
        // Enqueue enough 500s to exhaust retries
        repeat(TolinkuClient.MAX_RETRIES + 1) {
            server.enqueue(
                MockResponse()
                    .setResponseCode(500)
                    .setBody("""{"error":"Internal Server Error"}""")
            )
        }

        try {
            messages.fetch()
            fail("Expected TolinkuException")
        } catch (e: TolinkuException) {
            assertEquals(500, e.statusCode)
        }
    }
}
