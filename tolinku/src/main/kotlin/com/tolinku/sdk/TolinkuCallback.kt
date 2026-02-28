package com.tolinku.sdk

/**
 * Callback interface for Java-friendly async wrappers.
 *
 * This is a functional (SAM) interface, so Java callers can use a lambda:
 * ```java
 * tolinku.referrals.createAsync("user_1", null, null, result -> {
 *     if (result.isSuccess()) {
 *         Referral ref = result.getOrNull();
 *     }
 * });
 * ```
 *
 * @param T The result type.
 */
fun interface TolinkuCallback<T> {
    fun onResult(result: Result<T>)
}
