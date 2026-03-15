package com.tolinku.sdk

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal

/**
 * Unit tests for [Ecommerce] batching and event tracking.
 */
class EcommerceTest {

    private lateinit var server: MockWebServer
    private lateinit var client: TolinkuClient
    private lateinit var ecommerce: Ecommerce
    private var userId: String? = null

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = TolinkuClient(
            apiKey = "tolk_pub_test_key",
            baseUrl = server.url("/").toString().trimEnd('/')
        )
        userId = null
        ecommerce = Ecommerce(client) { userId }
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
    fun `events are queued without immediately sending`() = runTest {
        ecommerce.viewItem(listOf(TolinkuItem(itemId = "sku_1")))
        ecommerce.viewItem(listOf(TolinkuItem(itemId = "sku_2")))

        assertEquals(0, server.requestCount)
        assertEquals(2, ecommerce.queueSize())
    }

    // -----------------------------------------------------------------------
    // Auto-flush at batch size
    // -----------------------------------------------------------------------

    @Test
    fun `auto-flushes when queue reaches batch size`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"ok":true}""")
        )

        for (i in 0 until Ecommerce.BATCH_SIZE) {
            ecommerce.viewItem(listOf(TolinkuItem(itemId = "sku_$i")))
        }

        assertEquals(1, server.requestCount)
        assertEquals(0, ecommerce.queueSize())

        val request = server.takeRequest()
        assertEquals("/v1/api/analytics/ecommerce/batch", request.path)
        val body = JSONObject(request.body.readUtf8())
        val events = body.getJSONArray("events")
        assertEquals(Ecommerce.BATCH_SIZE, events.length())
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

        ecommerce.viewItem(listOf(TolinkuItem(itemId = "sku_1")))
        ecommerce.addToCart(listOf(TolinkuItem(itemId = "sku_1")))
        ecommerce.flush()

        assertEquals(1, server.requestCount)
        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals(2, body.getJSONArray("events").length())
    }

    @Test
    fun `flush is no-op when queue is empty`() = runTest {
        ecommerce.flush()
        assertEquals(0, server.requestCount)
    }

    // -----------------------------------------------------------------------
    // Purchase event format
    // -----------------------------------------------------------------------

    @Test
    fun `purchase includes all fields`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"ok":true}""")
        )

        ecommerce.purchase(
            transactionId = "order_123",
            revenue = BigDecimal("49.99"),
            currency = "USD",
            couponCode = "SAVE10",
            discount = BigDecimal("5.00"),
            shipping = BigDecimal("4.99"),
            tax = BigDecimal("3.75"),
            items = listOf(TolinkuItem(itemId = "sku_1", itemName = "T-Shirt", price = BigDecimal("24.99"), quantity = 2))
        )
        ecommerce.flush()

        val body = JSONObject(server.takeRequest().body.readUtf8())
        val event = body.getJSONArray("events").getJSONObject(0)
        assertEquals("purchase", event.getString("event_type"))
        assertEquals("order_123", event.getString("transaction_id"))
        assertEquals(49.99, event.getDouble("revenue"), 0.001)
        assertEquals("USD", event.getString("currency"))
        assertEquals("SAVE10", event.getString("coupon_code"))
        assertEquals(5.0, event.getDouble("discount"), 0.001)
        assertEquals(4.99, event.getDouble("shipping"), 0.001)
        assertEquals(3.75, event.getDouble("tax"), 0.001)

        val items = event.getJSONArray("items")
        assertEquals(1, items.length())
        assertEquals("sku_1", items.getJSONObject(0).getString("item_id"))
    }

    // -----------------------------------------------------------------------
    // User ID injection
    // -----------------------------------------------------------------------

    @Test
    fun `injects user_id when set`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"ok":true}""")
        )

        userId = "user_456"
        ecommerce.viewItem(listOf(TolinkuItem(itemId = "sku_1")))
        ecommerce.flush()

        val body = JSONObject(server.takeRequest().body.readUtf8())
        val event = body.getJSONArray("events").getJSONObject(0)
        assertEquals("user_456", event.getString("user_id"))
    }

    @Test
    fun `does not inject user_id when null`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"ok":true}""")
        )

        userId = null
        ecommerce.viewItem(listOf(TolinkuItem(itemId = "sku_1")))
        ecommerce.flush()

        val body = JSONObject(server.takeRequest().body.readUtf8())
        val event = body.getJSONArray("events").getJSONObject(0)
        assertTrue(!event.has("user_id"))
    }

    // -----------------------------------------------------------------------
    // All 13 event types
    // -----------------------------------------------------------------------

    @Test
    fun `tracks all 13 event types`() = runTest {
        // Enqueue two responses (batch flushes at 10)
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))

        ecommerce.viewItem(listOf(TolinkuItem(itemId = "a")))
        ecommerce.addToCart(listOf(TolinkuItem(itemId = "a")))
        ecommerce.removeFromCart(listOf(TolinkuItem(itemId = "a")))
        ecommerce.addToWishlist(listOf(TolinkuItem(itemId = "a")))
        ecommerce.viewCart()
        ecommerce.addPaymentInfo()
        ecommerce.beginCheckout()
        ecommerce.purchase(transactionId = "t", revenue = BigDecimal.ONE, currency = "USD")
        ecommerce.refund(transactionId = "t", revenue = BigDecimal.ONE)
        ecommerce.search(term = "shoes")
        // Batch flushed at 10, now the remaining 3
        ecommerce.share(itemId = "a")
        ecommerce.rate(itemId = "a", rating = 5.0)
        ecommerce.spendCredits(revenue = BigDecimal.TEN, currency = "USD")
        ecommerce.flush()

        // Collect event types from all requests
        val types = mutableListOf<String>()
        for (i in 0 until server.requestCount) {
            val body = JSONObject(server.takeRequest().body.readUtf8())
            val events = body.getJSONArray("events")
            for (j in 0 until events.length()) {
                types.add(events.getJSONObject(j).getString("event_type"))
            }
        }

        assertTrue(types.contains("view_item"))
        assertTrue(types.contains("add_to_cart"))
        assertTrue(types.contains("remove_from_cart"))
        assertTrue(types.contains("add_to_wishlist"))
        assertTrue(types.contains("view_cart"))
        assertTrue(types.contains("add_payment_info"))
        assertTrue(types.contains("begin_checkout"))
        assertTrue(types.contains("purchase"))
        assertTrue(types.contains("refund"))
        assertTrue(types.contains("search"))
        assertTrue(types.contains("share"))
        assertTrue(types.contains("rate"))
        assertTrue(types.contains("spend_credits"))
    }

    // -----------------------------------------------------------------------
    // Error recovery
    // -----------------------------------------------------------------------

    @Test
    fun `re-queues events on flush failure`() = runTest {
        // Client retries 3 times on 5xx, so we need 4 failures to exhaust retries
        repeat(4) { server.enqueue(MockResponse().setResponseCode(500)) }
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))

        ecommerce.viewItem(listOf(TolinkuItem(itemId = "sku_1")))
        ecommerce.viewItem(listOf(TolinkuItem(itemId = "sku_2")))
        ecommerce.flush()

        // Events should be re-queued after all retries exhausted
        assertEquals(2, ecommerce.queueSize())

        // Retry should succeed
        ecommerce.flush()
        assertEquals(0, ecommerce.queueSize())
    }

    // -----------------------------------------------------------------------
    // Endpoint
    // -----------------------------------------------------------------------

    @Test
    fun `sends to correct endpoint`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))

        ecommerce.viewItem(listOf(TolinkuItem(itemId = "sku_1")))
        ecommerce.flush()

        val request = server.takeRequest()
        assertEquals("/v1/api/analytics/ecommerce/batch", request.path)
    }

    // -----------------------------------------------------------------------
    // Search properties
    // -----------------------------------------------------------------------

    @Test
    fun `search sends search_term in properties`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))

        ecommerce.search(term = "blue shoes")
        ecommerce.flush()

        val body = JSONObject(server.takeRequest().body.readUtf8())
        val event = body.getJSONArray("events").getJSONObject(0)
        val properties = event.getJSONObject("properties")
        assertEquals("blue shoes", properties.getString("search_term"))
    }

    // -----------------------------------------------------------------------
    // TolinkuItem JSON format
    // -----------------------------------------------------------------------

    @Test
    fun `TolinkuItem serializes correctly`() {
        val item = TolinkuItem(
            itemId = "sku_1",
            itemName = "T-Shirt",
            itemCategory = "Apparel",
            itemBrand = "Tolinku",
            itemVariant = "Red / L",
            price = BigDecimal("24.99"),
            quantity = 2,
            currency = "USD"
        )
        val json = item.toJson()
        assertEquals("sku_1", json.getString("item_id"))
        assertEquals("T-Shirt", json.getString("item_name"))
        assertEquals("Apparel", json.getString("item_category"))
        assertEquals("Tolinku", json.getString("item_brand"))
        assertEquals("Red / L", json.getString("item_variant"))
        assertEquals(24.99, json.getDouble("price"), 0.001)
        assertEquals(2, json.getInt("quantity"))
        assertEquals("USD", json.getString("currency"))
    }
}
