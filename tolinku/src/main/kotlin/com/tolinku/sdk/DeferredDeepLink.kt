package com.tolinku.sdk

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.WindowMetrics
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Locale
import java.util.TimeZone

/**
 * Handles deferred deep link claiming through the Tolinku API.
 *
 * Deferred deep links allow users who install the app via a link
 * to be routed to the correct content after installation, even though
 * the link could not be handled at click time.
 */
class DeferredDeepLink internal constructor(private val client: TolinkuClient) {

    /**
     * Claim a deferred deep link using a known token.
     *
     * @param token The deferred link token (typically passed via a query parameter).
     * @return The [DeferredLink] if found, or null if no matching link exists.
     * @throws IllegalArgumentException if token is blank.
     * @throws TolinkuException if the request fails for reasons other than "not found".
     */
    suspend fun claimByToken(token: String): DeferredLink? {
        require(token.isNotBlank()) { "token must not be blank" }

        return try {
            val response = client.getPublic(
                "/v1/api/deferred/claim",
                queryParams = mapOf("token" to token)
            )
            DeferredLink.fromJson(response)
        } catch (e: TolinkuException) {
            if (e.statusCode == 404) return null
            throw e
        }
    }

    /**
     * Claim a deferred deep link by matching device signals.
     *
     * This method automatically collects the device timezone, language,
     * and screen dimensions to match against pending deferred links.
     * If an application context is available, it also attempts to retrieve
     * the Google Play Install Referrer data.
     *
     * @param appspaceId The Appspace ID to claim the link for.
     * @param context Android context used to read display metrics.
     * @return The [DeferredLink] if a match is found, or null otherwise.
     * @throws IllegalArgumentException if appspaceId is blank.
     * @throws TolinkuException if the request fails for reasons other than "not found".
     */
    suspend fun claimBySignals(appspaceId: String, context: Context): DeferredLink? {
        require(appspaceId.isNotBlank()) { "appspaceId must not be blank" }

        val (screenWidth, screenHeight) = getScreenDimensions(context)

        val body = JSONObject().apply {
            put("appspace_id", appspaceId)
            put("timezone", TimeZone.getDefault().id)
            put("language", Locale.getDefault().language)
            put("screen_width", screenWidth)
            put("screen_height", screenHeight)
        }

        return try {
            val response = client.postPublic("/v1/api/deferred/claim-by-signals", body)
            DeferredLink.fromJson(response)
        } catch (e: TolinkuException) {
            if (e.statusCode == 404) return null
            throw e
        }
    }

    // -----------------------------------------------------------------------
    // Java-friendly callback wrappers
    // -----------------------------------------------------------------------

    /**
     * Java-friendly callback wrapper for [claimByToken].
     *
     * @param token The deferred link token.
     * @param callback Invoked with a [Result] when the operation completes.
     */
    fun claimByTokenAsync(token: String, callback: TolinkuCallback<DeferredLink?>) {
        Tolinku.scope.launch {
            val result = runCatching { claimByToken(token) }
            callback.onResult(result)
        }
    }

    /**
     * Java-friendly callback wrapper for [claimBySignals].
     *
     * @param appspaceId The Appspace ID to claim the link for.
     * @param context Android context used to read display metrics.
     * @param callback Invoked with a [Result] when the operation completes.
     */
    fun claimBySignalsAsync(
        appspaceId: String,
        context: Context,
        callback: TolinkuCallback<DeferredLink?>
    ) {
        Tolinku.scope.launch {
            val result = runCatching { claimBySignals(appspaceId, context) }
            callback.onResult(result)
        }
    }

    /**
     * Return the screen width and height in pixels, using WindowMetrics on
     * API 30+ and falling back to the deprecated DisplayMetrics approach on
     * older devices.
     */
    private fun getScreenDimensions(context: Context): Pair<Int, Int> {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics: WindowMetrics = windowManager.currentWindowMetrics
            val bounds = metrics.bounds
            Pair(bounds.width(), bounds.height())
        } else {
            val displayMetrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getMetrics(displayMetrics)
            Pair(displayMetrics.widthPixels, displayMetrics.heightPixels)
        }
    }
}
