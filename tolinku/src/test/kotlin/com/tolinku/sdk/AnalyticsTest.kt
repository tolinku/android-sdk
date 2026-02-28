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
 * Unit tests for [Analytics] batching behavior.
 */
class AnalyticsTest {

    private lateinit var server: MockWebServer
    private lateinit var client: TolinkuClient
    private lateinit var analytics: Analytics

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = TolinkuClient(
            apiKey = "tolk_pub_test_key",
            baseUrl = server.url("/").toString().trimEnd('/')
        )
        analytics = Analytics(client)
    }

    @After
    fun tearDown() {
        client.shutdown()
        server.shutdown()
    }

    // -----------------------------------------------------------------------
    // Queueing behavior
    // -----------------------------------------------------------------------

    @Test
    fun `track queues events without immediately sending`() = runTest {
        // Track fewer than BATCH_SIZE events; no request should be made
        analytics.track("custom.view")
        analytics.track("custom.click")

        assertEquals(0, server.requestCount)
        assertEquals(2, analytics.queueSize())
    }

    @Test
    fun `track queues event with properties`() = runTest {
        analytics.track("custom.purchase", mapOf("amount" to 42, "currency" to "USD"))

        assertEquals(0, server.requestCount)
        assertEquals(1, analytics.queueSize())
    }

    // -----------------------------------------------------------------------
    // Auto-flush at batch size
    // -----------------------------------------------------------------------

    @Test
    fun `auto-flushes when queue reaches batch size`() = runTest {
        // Enqueue a success response for the batch POST
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"ok":true}""")
        )

        for (i in 1..Analytics.BATCH_SIZE) {
            analytics.track("custom.event_$i")
        }

        // The batch should have been flushed automatically
        assertEquals(1, server.requestCount)
        assertEquals(0, analytics.queueSize())

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertTrue(request.path!!.contains("/v1/api/analytics/batch"))

        val body = JSONObject(request.body.readUtf8())
        val events = body.getJSONArray("events")
        assertEquals(Analytics.BATCH_SIZE, events.length())
    }

    // -----------------------------------------------------------------------
    // Manual flush
    // -----------------------------------------------------------------------

    @Test
    fun `manual flush sends all queued events`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"ok":true}""")
        )

        analytics.track("custom.signup")
        analytics.track("custom.login")
        analytics.track("custom.purchase")

        assertEquals(0, server.requestCount)
        assertEquals(3, analytics.queueSize())

        analytics.flush()

        assertEquals(1, server.requestCount)
        assertEquals(0, analytics.queueSize())

        val request = server.takeRequest()
        val body = JSONObject(request.body.readUtf8())
        val events = body.getJSONArray("events")
        assertEquals(3, events.length())
    }

    @Test
    fun `flush with empty queue is a no-op`() = runTest {
        analytics.flush()
        assertEquals(0, server.requestCount)
    }

    // -----------------------------------------------------------------------
    // Event format
    // -----------------------------------------------------------------------

    @Test
    fun `event contains event_type and properties`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"ok":true}""")
        )

        analytics.track("custom.test", mapOf("key" to "value"))
        analytics.flush()

        val request = server.takeRequest()
        val body = JSONObject(request.body.readUtf8())
        val events = body.getJSONArray("events")
        assertEquals(1, events.length())

        val event = events.getJSONObject(0)
        assertEquals("custom.test", event.getString("event_type"))

        val properties = event.getJSONObject("properties")
        assertEquals("value", properties.getString("key"))
    }

    @Test
    fun `event without properties has empty properties object`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"ok":true}""")
        )

        analytics.track("custom.simple")
        analytics.flush()

        val request = server.takeRequest()
        val body = JSONObject(request.body.readUtf8())
        val event = body.getJSONArray("events").getJSONObject(0)

        val properties = event.getJSONObject("properties")
        assertEquals(0, properties.length())
    }

    // -----------------------------------------------------------------------
    // Validation
    // -----------------------------------------------------------------------

    @Test
    fun `empty event name throws IllegalArgumentException`() = runTest {
        try {
            analytics.track("")
            fail("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("eventType"))
        }
    }

    @Test
    fun `blank event name throws IllegalArgumentException`() = runTest {
        try {
            analytics.track("   ")
            fail("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("eventType"))
        }
    }
}
