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
        /** How long the same link is treated as one tap rather than two. */
        internal const val OPEN_DEDUPE_MS = 5000L
    }

    /** Set once the server says this Appspace does not attribute app opens. */
    @Volatile
    private var appOpensDisabled = false

    private val openDedupe = OpenDedupe()

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
    /**
     * Report that a link opened the app, when it opened without the browser.
     *
     * An App Link hands the app the URL directly, so Tolinku is never contacted
     * and the tap goes unrecorded. Those taps come from people who already have
     * the app, so leaving them out makes a re-engagement campaign look like a
     * failure exactly when it worked.
     *
     * Only http and https links are reported. A custom scheme means Tolinku's
     * own hand-off page opened the app, and that tap was counted when the page
     * was served, so passing one does nothing rather than counting it twice.
     *
     * Never throws. This runs on the path that routes the user somewhere, and a
     * tap that goes unrecorded is not worth interrupting that.
     */
    suspend fun trackLinkOpen(url: String, userId: String? = null, deferred: Boolean = false) {
        if (appOpensDisabled) return

        val trimmed = url.trim()
        if (trimmed.isEmpty()) return

        // The scheme decides, and the server checks it again.
        val scheme = try {
            java.net.URI(trimmed).scheme?.lowercase()
        } catch (t: Throwable) {
            null
        }
        if (scheme != "http" && scheme != "https") return

        if (openDedupe.shouldSkip(trimmed, deferred)) return

        try {
            // The User-Agent names the SDK, not the platform, and the Flutter
            // and React Native SDKs are the same on both. Without this an app
            // open lands in a blank bucket on every breakdown.
            val body = JSONObject().put("url", trimmed).put("platform", "android")
            // Whether this open followed a deferred install. Only the app
            // knows. An ordinary open is somebody who already had the app and
            // can never convert to an install; an open after a deferred claim
            // is the last stage of the install funnel. The server records them
            // as different events and must not pool them.
            if (deferred) body.put("deferred", true)
            if (!userId.isNullOrBlank()) body.put("user_id", userId)
            val reply = client.postPublic("/v1/api/opens", body)
            // Remembering a no means the setting costs one request a launch
            // rather than one per link.
            if (reply.has("attribute") && !reply.optBoolean("attribute", true)) {
                appOpensDisabled = true
            }
        } catch (t: Throwable) {
            // Deliberately silent.
        }
    }

    suspend fun track(eventType: String, properties: Map<String, Any>? = null) {
        require(eventType.isNotBlank()) { "eventType must not be blank" }

        var normalizedType = eventType
        if (!normalizedType.startsWith("custom.")) {
            normalizedType = "custom.$normalizedType"
        }

        val eventNamePattern = Regex("^custom\\.[a-z0-9_]+$")
        require(eventNamePattern.matches(normalizedType)) {
            "eventType \"$normalizedType\" is invalid. Must match pattern \"custom.[a-z0-9_]+\" (lowercase letters, numbers, and underscores only after \"custom.\")"
        }

        val event = JSONObject().apply {
            put("event_type", normalizedType)
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

/**
 * Whether a reported open is a repeat of the one just before it.
 *
 * Its own type so the rule can be tested without an Android framework or a
 * network. It was three fields and a comparison inside `trackLinkOpen`, and
 * this was the only one of the four SDKs with no test for it at all, which is
 * how the deferred flag came to be missing from the key.
 *
 * A cold start and onNewIntent can both deliver the same tap depending on how
 * the activity is wired, so an app instrumenting both would otherwise report it
 * twice and be billed twice. A genuine second tap of the same link inside the
 * window is implausible; a duplicate delivery of one tap is not.
 *
 * The deferred flag is part of the key. An ordinary open and a deferred one are
 * different events on the server, and only the app can tell them apart, so a
 * deferred report just after an ordinary one for the same link is new
 * information rather than a repeat. Keyed on the URL alone, somebody who tapped
 * a link, installed, then tapped it again would have the deferred report
 * swallowed, losing the last stage of the install funnel in the one case it
 * exists to show.
 */
internal class OpenDedupe(private val windowMs: Long = Analytics.OPEN_DEDUPE_MS) {
    private var lastUrl: String? = null
    private var lastAt: Long = 0L
    private var lastDeferred = false

    @Synchronized
    fun shouldSkip(
        url: String,
        deferred: Boolean,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        if (url == lastUrl && deferred == lastDeferred && now - lastAt < windowMs) return true
        lastUrl = url
        lastAt = now
        lastDeferred = deferred
        return false
    }
}
