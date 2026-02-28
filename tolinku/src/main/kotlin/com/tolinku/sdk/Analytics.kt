package com.tolinku.sdk

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

/**
 * Handles analytics event tracking through the Tolinku API.
 *
 * Events are batched in memory and flushed automatically when:
 * - The queue reaches [BATCH_SIZE] events (default 10).
 * - [FLUSH_INTERVAL_MS] milliseconds pass since the first queued event (default 5000).
 * - [flush] is called manually.
 * - [shutdown] is called.
 *
 * The batch is sent as a single POST to `/v1/api/analytics/batch`.
 */
class Analytics internal constructor(private val client: TolinkuClient) {

    companion object {
        /** Number of events that triggers an automatic flush. */
        internal const val BATCH_SIZE = 10
        /** Maximum time (ms) to wait before flushing after the first queued event. */
        internal const val FLUSH_INTERVAL_MS = 5000L
        /** Maximum number of events to keep in the queue to prevent unbounded growth. */
        internal const val MAX_QUEUE_SIZE = 1000
    }

    private val mutex = Mutex()
    private val eventQueue = mutableListOf<JSONObject>()
    private var flushTimerJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    /**
     * Track a custom analytics event. The event is added to the internal queue
     * and will be flushed automatically or when [flush] is called.
     *
     * @param eventType The event type identifier (e.g., "custom.signup", "custom.purchase").
     * @param properties Optional key-value properties to attach to the event.
     * @throws IllegalArgumentException if eventType is blank.
     */
    suspend fun track(eventType: String, properties: Map<String, Any>? = null) {
        require(eventType.isNotBlank()) { "eventType must not be blank" }

        val event = JSONObject().apply {
            put("event_type", eventType)
            put("properties", if (properties != null) JSONObject(properties) else JSONObject())
        }

        var shouldFlush = false

        mutex.withLock {
            // Enforce max queue size
            if (eventQueue.size >= MAX_QUEUE_SIZE) {
                if (Tolinku.debug) {
                    Log.w(Tolinku.TAG, "Analytics queue full ($MAX_QUEUE_SIZE). Dropping oldest event.")
                }
                eventQueue.removeAt(0)
            }
            eventQueue.add(event)

            if (eventQueue.size >= BATCH_SIZE) {
                shouldFlush = true
            } else if (eventQueue.size == 1) {
                // First event in the queue; start the flush timer
                startFlushTimer()
            }
        }

        if (shouldFlush) {
            flush()
        }
    }

    /**
     * Immediately flush all queued events to the server.
     * If the queue is empty, this is a no-op.
     *
     * @throws TolinkuException if the batch request fails.
     */
    suspend fun flush() {
        val eventsToSend: List<JSONObject>

        mutex.withLock {
            if (eventQueue.isEmpty()) return
            eventsToSend = eventQueue.toList()
            eventQueue.clear()
            cancelFlushTimer()
        }

        sendBatch(eventsToSend)
    }

    /**
     * Java-friendly callback wrapper for [flush].
     *
     * @param callback Invoked with a [Result] when the flush operation completes.
     */
    fun flushAsync(callback: TolinkuCallback<Unit>) {
        Tolinku.scope.launch {
            val result = runCatching { flush() }
            callback.onResult(result)
        }
    }

    /**
     * Shut down the analytics module, flushing any remaining events and cancelling the internal scope.
     * Errors during the final flush are logged but not thrown.
     */
    internal suspend fun shutdown() {
        // Cancel the flush timer first
        mutex.withLock {
            cancelFlushTimer()
        }

        // Flush remaining events
        try {
            flush()
        } catch (e: Exception) {
            if (Tolinku.debug) {
                Log.w(Tolinku.TAG, "Error flushing analytics during shutdown: ${e.message}", e)
            }
        }

        // Cancel the internal coroutine scope
        scope.cancel()
    }

    /**
     * Java-friendly callback wrapper for [track].
     *
     * @param eventType The event type identifier.
     * @param properties Optional key-value properties to attach to the event.
     * @param callback Invoked with a [Result] when the operation completes.
     */
    @JvmOverloads
    fun trackAsync(
        eventType: String,
        properties: Map<String, Any>? = null,
        callback: TolinkuCallback<Unit>
    ) {
        Tolinku.scope.launch {
            val result = runCatching { track(eventType, properties) }
            callback.onResult(result)
        }
    }

    /**
     * Return the current number of queued (unflushed) events. Useful for testing.
     */
    internal suspend fun queueSize(): Int {
        mutex.withLock {
            return eventQueue.size
        }
    }

    private fun startFlushTimer() {
        flushTimerJob?.cancel()
        flushTimerJob = scope.launch {
            delay(FLUSH_INTERVAL_MS)
            try {
                flush()
            } catch (e: Exception) {
                if (Tolinku.debug) {
                    Log.w(Tolinku.TAG, "Timer flush failed: ${e.message}", e)
                }
            }
        }
    }

    private fun cancelFlushTimer() {
        flushTimerJob?.cancel()
        flushTimerJob = null
    }

    private suspend fun sendBatch(events: List<JSONObject>) {
        if (events.isEmpty()) return

        val body = JSONObject().apply {
            put("events", JSONArray(events))
        }

        if (Tolinku.debug) {
            Log.d(Tolinku.TAG, "Flushing ${events.size} analytics event(s)")
        }

        try {
            val result = client.post("/v1/api/analytics/batch", body)
            val errors = result.optJSONArray("errors")
            if (errors != null && errors.length() > 0 && Tolinku.debug) {
                Log.w(Tolinku.TAG, "Batch partial failure: $errors")
            }
        } catch (e: Exception) {
            // Re-queue failed events at the front so they can be retried on the next flush
            mutex.withLock {
                val spaceLeft = MAX_QUEUE_SIZE - eventQueue.size
                if (spaceLeft > 0) {
                    val toRequeue = events.take(spaceLeft)
                    eventQueue.addAll(0, toRequeue)
                }
            }
            if (Tolinku.debug) {
                Log.w(Tolinku.TAG, "Failed to flush analytics batch: ${e.message}")
            }
            throw e
        }
    }
}
