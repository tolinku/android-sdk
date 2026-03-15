package com.tolinku.sdk

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.util.UUID

/**
 * A product item for ecommerce event tracking.
 */
data class TolinkuItem(
    val itemId: String,
    val itemName: String? = null,
    val itemCategory: String? = null,
    val itemBrand: String? = null,
    val itemVariant: String? = null,
    val itemListName: String? = null,
    val itemListId: String? = null,
    val itemImageUrl: String? = null,
    val price: BigDecimal? = null,
    val quantity: Int = 1,
    val currency: String? = null,
    val couponCode: String? = null,
    val discount: BigDecimal? = null
) {
    internal fun toJson(): JSONObject = JSONObject().apply {
        put("item_id", itemId)
        itemName?.let { put("item_name", it) }
        itemCategory?.let { put("item_category", it) }
        itemBrand?.let { put("item_brand", it) }
        itemVariant?.let { put("item_variant", it) }
        itemListName?.let { put("item_list_name", it) }
        itemListId?.let { put("item_list_id", it) }
        itemImageUrl?.let { put("item_image_url", it) }
        price?.let { put("price", it.toDouble()) }
        put("quantity", quantity)
        currency?.let { put("currency", it) }
        couponCode?.let { put("coupon_code", it) }
        discount?.let { put("discount", it.toDouble()) }
    }
}

/**
 * Handles ecommerce event tracking through the Tolinku API.
 *
 * Events are batched in memory and flushed automatically when:
 * - The queue reaches [BATCH_SIZE] events (default 10).
 * - [FLUSH_INTERVAL_MS] milliseconds pass since the first queued event (default 5000).
 * - [flush] is called manually.
 * - [shutdown] is called.
 */
class Ecommerce internal constructor(
    private val client: TolinkuClient,
    private val getUserId: () -> String?
) {

    companion object {
        internal const val BATCH_SIZE = 10
        internal const val FLUSH_INTERVAL_MS = 5000L
        internal const val MAX_QUEUE_SIZE = 500
        private const val CART_ID_PREF_KEY = "tolinku_ecom_cart_id"
    }

    private val mutex = Mutex()
    private val eventQueue = mutableListOf<JSONObject>()
    private var flushTimerJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    @Volatile
    private var memoryCartId: String? = null

    // ─── Public methods (13 event types) ────────────────────

    suspend fun viewItem(items: List<TolinkuItem>) {
        enqueue(buildEvent("view_item", items = items))
    }

    suspend fun addToCart(items: List<TolinkuItem>, cartId: String? = null) {
        val resolvedCartId = cartId ?: getOrCreateCartId()
        enqueue(buildEvent("add_to_cart", cartId = resolvedCartId, items = items))
    }

    suspend fun removeFromCart(items: List<TolinkuItem>, cartId: String? = null) {
        val resolvedCartId = cartId ?: getCartId()
        enqueue(buildEvent("remove_from_cart", cartId = resolvedCartId, items = items))
    }

    suspend fun addToWishlist(items: List<TolinkuItem>) {
        enqueue(buildEvent("add_to_wishlist", items = items))
    }

    suspend fun viewCart() {
        enqueue(buildEvent("view_cart", cartId = getCartId()))
    }

    suspend fun addPaymentInfo(cartId: String? = null) {
        val resolvedCartId = cartId ?: getCartId()
        enqueue(buildEvent("add_payment_info", cartId = resolvedCartId))
    }

    suspend fun beginCheckout(
        revenue: BigDecimal? = null,
        currency: String? = null,
        cartId: String? = null,
        items: List<TolinkuItem>? = null
    ) {
        val resolvedCartId = cartId ?: getCartId()
        enqueue(buildEvent("begin_checkout", revenue = revenue, currency = currency, cartId = resolvedCartId, items = items))
    }

    suspend fun purchase(
        transactionId: String,
        revenue: BigDecimal,
        currency: String,
        items: List<TolinkuItem>? = null,
        cartId: String? = null,
        couponCode: String? = null,
        discount: BigDecimal? = null,
        shipping: BigDecimal? = null,
        tax: BigDecimal? = null
    ) {
        val resolvedCartId = cartId ?: getCartId()
        enqueue(buildEvent(
            "purchase",
            transactionId = transactionId,
            revenue = revenue,
            currency = currency,
            cartId = resolvedCartId,
            couponCode = couponCode,
            discount = discount,
            shipping = shipping,
            tax = tax,
            items = items
        ))
        clearCartId()
    }

    suspend fun refund(
        transactionId: String,
        revenue: BigDecimal,
        currency: String? = null,
        items: List<TolinkuItem>? = null
    ) {
        enqueue(buildEvent("refund", transactionId = transactionId, revenue = revenue, currency = currency, items = items))
    }

    suspend fun search(term: String) {
        enqueue(buildEvent("search", properties = mapOf("search_term" to term)))
    }

    suspend fun share(itemId: String? = null, url: String? = null, method: String? = null) {
        val props = mutableMapOf<String, String>()
        itemId?.let { props["item_id"] = it }
        url?.let { props["url"] = it }
        method?.let { props["method"] = it }
        enqueue(buildEvent("share", properties = props))
    }

    suspend fun rate(itemId: String, rating: Double, maxRating: Double? = null) {
        val props = mutableMapOf("item_id" to itemId, "rating" to rating.toString())
        maxRating?.let { props["max_rating"] = it.toString() }
        enqueue(buildEvent("rate", properties = props))
    }

    suspend fun spendCredits(revenue: BigDecimal, currency: String) {
        enqueue(buildEvent("spend_credits", revenue = revenue, currency = currency))
    }

    // ─── Flush & Shutdown ────────────────────────────────────

    suspend fun flush() {
        val eventsToSend: List<JSONObject>

        mutex.withLock {
            if (eventQueue.isEmpty()) return
            eventsToSend = eventQueue.toList()
            eventQueue.clear()
            flushTimerJob?.cancel()
            flushTimerJob = null
        }

        try {
            if (Tolinku.debug) Log.d(Tolinku.TAG, "Flushing ${eventsToSend.size} ecommerce event(s)")

            val body = JSONObject().apply {
                put("events", JSONArray(eventsToSend))
            }
            client.post("/v1/api/analytics/ecommerce/batch", body)
        } catch (e: Exception) {
            if (Tolinku.debug) Log.w(Tolinku.TAG, "Ecommerce flush failed: ${e.message}")
            // Re-queue on failure
            mutex.withLock {
                val spaceLeft = MAX_QUEUE_SIZE - eventQueue.size
                if (spaceLeft > 0) {
                    eventQueue.addAll(0, eventsToSend.take(spaceLeft))
                }
            }
        }
    }

    internal suspend fun shutdown() {
        flushTimerJob?.cancel()
        flushTimerJob = null
        flush()
        scope.cancel()
    }

    /**
     * Return the current number of queued (unflushed) events. Useful for testing.
     */
    internal suspend fun queueSize(): Int {
        mutex.withLock {
            return eventQueue.size
        }
    }

    // ─── Private ─────────────────────────────────────────────

    private suspend fun enqueue(event: JSONObject) {
        mutex.withLock {
            // Inject user_id
            getUserId()?.let { event.put("user_id", it) }

            if (eventQueue.size >= MAX_QUEUE_SIZE) {
                eventQueue.removeAt(0)
            }
            eventQueue.add(event)

            if (eventQueue.size >= BATCH_SIZE) {
                // Release lock before flushing
            } else if (eventQueue.size == 1) {
                startFlushTimer()
                return
            } else {
                return
            }
        }
        // Flush outside the lock
        flush()
    }

    private fun startFlushTimer() {
        flushTimerJob?.cancel()
        flushTimerJob = scope.launch {
            delay(FLUSH_INTERVAL_MS)
            try { flush() } catch (e: Exception) {
                if (Tolinku.debug) Log.w(Tolinku.TAG, "Ecommerce timer flush failed: ${e.message}")
            }
        }
    }

    private fun buildEvent(
        eventType: String,
        transactionId: String? = null,
        revenue: BigDecimal? = null,
        currency: String? = null,
        cartId: String? = null,
        couponCode: String? = null,
        discount: BigDecimal? = null,
        shipping: BigDecimal? = null,
        tax: BigDecimal? = null,
        items: List<TolinkuItem>? = null,
        properties: Map<String, String>? = null
    ): JSONObject = JSONObject().apply {
        put("event_type", eventType)
        transactionId?.let { put("transaction_id", it) }
        revenue?.let { put("revenue", it.toDouble()) }
        currency?.let { put("currency", it) }
        cartId?.let { put("cart_id", it) }
        couponCode?.let { put("coupon_code", it) }
        discount?.let { put("discount", it.toDouble()) }
        shipping?.let { put("shipping", it.toDouble()) }
        tax?.let { put("tax", it.toDouble()) }
        items?.let { itemList ->
            put("items", JSONArray().apply {
                itemList.forEach { put(it.toJson()) }
            })
        }
        properties?.let { props ->
            put("properties", JSONObject(props as Map<*, *>))
        }
    }

    // ─── Cart ID lifecycle (SharedPreferences + memory fallback) ─

    private fun getOrCreateCartId(): String {
        getCartId()?.let { return it }
        val cartId = UUID.randomUUID().toString()
        setCartId(cartId)
        return cartId
    }

    private fun getCartId(): String? {
        try {
            Tolinku.applicationContext?.let { ctx ->
                val prefs = ctx.getSharedPreferences("tolinku_ecom", Context.MODE_PRIVATE)
                prefs.getString(CART_ID_PREF_KEY, null)?.let { return it }
            }
        } catch (_: Exception) { /* ignore */ }
        return memoryCartId
    }

    private fun setCartId(cartId: String) {
        memoryCartId = cartId
        try {
            Tolinku.applicationContext?.let { ctx ->
                ctx.getSharedPreferences("tolinku_ecom", Context.MODE_PRIVATE)
                    .edit()
                    .putString(CART_ID_PREF_KEY, cartId)
                    .apply()
            }
        } catch (_: Exception) { /* memory fallback already set */ }
    }

    private fun clearCartId() {
        memoryCartId = null
        try {
            Tolinku.applicationContext?.let { ctx ->
                ctx.getSharedPreferences("tolinku_ecom", Context.MODE_PRIVATE)
                    .edit()
                    .remove(CART_ID_PREF_KEY)
                    .apply()
            }
        } catch (_: Exception) { /* ignore */ }
    }
}
