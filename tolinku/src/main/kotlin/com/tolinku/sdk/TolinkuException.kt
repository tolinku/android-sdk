package com.tolinku.sdk

/**
 * Exception thrown by the Tolinku SDK when an API call fails
 * or the SDK is used incorrectly.
 *
 * @property statusCode The HTTP status code, if the error originated from an HTTP response.
 * @property retryAfterMs For HTTP 429 responses, the server-suggested retry delay in
 *   milliseconds (parsed from the Retry-After header). Null otherwise.
 */
class TolinkuException(
    message: String,
    cause: Throwable? = null,
    val statusCode: Int? = null,
    val retryAfterMs: Long? = null,
    val code: String? = null
) : Exception(message, cause)
