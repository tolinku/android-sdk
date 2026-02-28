package com.tolinku.sdk

import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.pow
import kotlin.random.Random

/**
 * Low-level HTTP client for communicating with the Tolinku API.
 * Uses OkHttp for networking and org.json for JSON serialization.
 *
 * Includes automatic retry with exponential backoff for transient failures
 * (network errors, HTTP 429, and HTTP 5xx responses).
 */
internal class TolinkuClient(
    private val apiKey: String,
    private val baseUrl: String
) {
    companion object {
        /** Maximum number of retry attempts after the initial request. */
        const val MAX_RETRIES = 3
        /** Base delay in milliseconds before the first retry. */
        const val BASE_DELAY_MS = 500L
        /** Maximum random jitter added to each retry delay, in milliseconds. */
        const val MAX_JITTER_MS = 250L
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * Perform an authenticated GET request.
     */
    suspend fun get(path: String, queryParams: Map<String, String>? = null): JSONObject {
        return executeWithRetry {
            request(path, method = "GET", queryParams = queryParams, authenticated = true)
        }
    }

    /**
     * Perform an authenticated POST request with a JSON body.
     */
    suspend fun post(path: String, body: JSONObject): JSONObject {
        return executeWithRetry {
            request(path, method = "POST", body = body, authenticated = true)
        }
    }

    /**
     * Perform an unauthenticated GET request.
     */
    suspend fun getPublic(path: String, queryParams: Map<String, String>? = null): JSONObject {
        return executeWithRetry {
            request(path, method = "GET", queryParams = queryParams, authenticated = false)
        }
    }

    /**
     * Perform an unauthenticated POST request with a JSON body.
     */
    suspend fun postPublic(path: String, body: JSONObject): JSONObject {
        return executeWithRetry {
            request(path, method = "POST", body = body, authenticated = false)
        }
    }

    /**
     * Execute a request block with retry logic. Retries on:
     * - IOException (network errors)
     * - HTTP 429 (Too Many Requests), respecting the Retry-After header
     * - HTTP 5xx (server errors)
     *
     * Does NOT retry on 4xx errors (except 429) or successful responses.
     * Uses exponential backoff: BASE_DELAY_MS * 2^attempt + random jitter (0..MAX_JITTER_MS).
     */
    private suspend fun executeWithRetry(block: suspend () -> JSONObject): JSONObject {
        var lastException: Exception? = null

        for (attempt in 0..MAX_RETRIES) {
            try {
                return block()
            } catch (e: TolinkuException) {
                val code = e.statusCode
                val isRetryable = code == 429 || (code != null && code in 500..599)

                if (!isRetryable || attempt == MAX_RETRIES) {
                    throw e
                }

                lastException = e

                // For 429, check if a Retry-After header delay was embedded in the exception.
                // Otherwise, use exponential backoff.
                val retryAfterMs = e.retryAfterMs
                val backoffMs = if (retryAfterMs != null && retryAfterMs > 0) {
                    retryAfterMs
                } else {
                    (BASE_DELAY_MS * 2.0.pow(attempt.toDouble())).toLong()
                }
                val jitter = Random.nextLong(0, MAX_JITTER_MS + 1)
                val totalDelay = backoffMs + jitter

                if (Tolinku.debug) {
                    Log.d(
                        Tolinku.TAG,
                        "Retry ${attempt + 1}/$MAX_RETRIES after ${totalDelay}ms (status=$code)"
                    )
                }

                delay(totalDelay)
            } catch (e: IOException) {
                if (attempt == MAX_RETRIES) {
                    throw TolinkuException(
                        message = "Network error after ${MAX_RETRIES + 1} attempts: ${e.message}",
                        cause = e
                    )
                }

                lastException = e

                val backoffMs = (BASE_DELAY_MS * 2.0.pow(attempt.toDouble())).toLong()
                val jitter = Random.nextLong(0, MAX_JITTER_MS + 1)
                val totalDelay = backoffMs + jitter

                if (Tolinku.debug) {
                    Log.d(
                        Tolinku.TAG,
                        "Retry ${attempt + 1}/$MAX_RETRIES after ${totalDelay}ms (IOException: ${e.message})"
                    )
                }

                delay(totalDelay)
            }
        }

        // Should not reach here, but just in case
        throw lastException ?: TolinkuException("Request failed after retries")
    }

    private suspend fun request(
        path: String,
        method: String,
        body: JSONObject? = null,
        queryParams: Map<String, String>? = null,
        authenticated: Boolean = true
    ): JSONObject {
        val urlBuilder = StringBuilder(baseUrl.trimEnd('/'))
            .append(path)

        if (!queryParams.isNullOrEmpty()) {
            urlBuilder.append("?")
            urlBuilder.append(
                queryParams.entries.joinToString("&") { (key, value) ->
                    "${java.net.URLEncoder.encode(key, "UTF-8")}=${java.net.URLEncoder.encode(value, "UTF-8")}"
                }
            )
        }

        val requestBuilder = Request.Builder()
            .url(urlBuilder.toString())

        if (authenticated) {
            requestBuilder.addHeader("X-API-Key", apiKey)
        }

        requestBuilder.addHeader("Accept", "application/json")
        requestBuilder.addHeader("User-Agent", "TolinkuAndroidSDK/${Tolinku.VERSION}")

        when (method) {
            "GET" -> requestBuilder.get()
            "POST" -> {
                val jsonBody = (body ?: JSONObject()).toString()
                requestBuilder.post(jsonBody.toRequestBody(jsonMediaType))
            }
            else -> throw IllegalArgumentException("Unsupported HTTP method: $method")
        }

        val request = requestBuilder.build()

        if (Tolinku.debug) {
            Log.d(Tolinku.TAG, "$method $urlBuilder")
        }

        return suspendCancellableCoroutine { continuation ->
            val call = httpClient.newCall(request)

            continuation.invokeOnCancellation {
                call.cancel()
            }

            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (Tolinku.debug) {
                        Log.e(Tolinku.TAG, "Network error: ${e.message}", e)
                    }
                    // Propagate the IOException directly so executeWithRetry can catch it
                    continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use { resp ->
                            val responseBody = resp.body?.string() ?: "{}"

                            if (Tolinku.debug) {
                                Log.d(Tolinku.TAG, "Response ${resp.code}: $responseBody")
                            }

                            if (!resp.isSuccessful) {
                                val errorJson = try {
                                    JSONObject(responseBody)
                                } catch (e: Exception) {
                                    null
                                }
                                val errorMessage = errorJson?.optString("error", "Request failed")
                                    ?: "Request failed with status ${resp.code}"
                                val errorCode = errorJson?.optString("code")?.ifEmpty { null }

                                // Parse Retry-After header for 429 responses
                                val retryAfterMs = if (resp.code == 429) {
                                    resp.header("Retry-After")?.toLongOrNull()?.let { it * 1000L }
                                } else {
                                    null
                                }

                                continuation.resumeWithException(
                                    TolinkuException(
                                        message = errorMessage,
                                        statusCode = resp.code,
                                        retryAfterMs = retryAfterMs,
                                        code = errorCode
                                    )
                                )
                                return
                            }

                            val jsonResult = try {
                                JSONObject(responseBody)
                            } catch (e: Exception) {
                                // Wrap array responses or empty responses
                                JSONObject().put("data", responseBody)
                            }

                            continuation.resume(jsonResult)
                        }
                    } catch (e: TolinkuException) {
                        continuation.resumeWithException(e)
                    } catch (e: Exception) {
                        if (Tolinku.debug) {
                            Log.e(Tolinku.TAG, "Unexpected error: ${e.message}", e)
                        }
                        continuation.resumeWithException(
                            TolinkuException(
                                message = "Unexpected error: ${e.message}",
                                cause = e
                            )
                        )
                    }
                }
            })
        }
    }

    /**
     * Shut down the HTTP client and release resources.
     */
    fun shutdown() {
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
    }
}
