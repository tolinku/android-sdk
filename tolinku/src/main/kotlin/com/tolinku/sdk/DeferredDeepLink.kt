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
            put("language", Locale.getDefault().toLanguageTag())
            put("screen_width", screenWidth)
            put("screen_height", screenHeight)
            // Pixel ratio separates devices that report the same dp dimensions.
            put("device_pixel_ratio", context.resources.displayMetrics.density.toDouble())
            put("os_version", Build.VERSION.RELEASE ?: "")
        }

        return try {
            val response = client.postPublic("/v1/api/deferred/claim-by-signals", body)
            DeferredLink.fromJson(response)
        } catch (e: TolinkuException) {
            if (e.statusCode == 404) return null
            throw e
        }
    }

    /**
     * Recover the link that led to this install, trying both mechanisms.
     *
     * The Play Install Referrer is asked first: it names the exact click, lasts
     * for days, and does not care which network the device was on. Device
     * signals are the fallback for installs the referrer cannot reach, such as
     * a sideload, a store other than Play, or a device where Play Services is
     * unavailable.
     *
     * Call once on first launch. It is safe to call again, but a claim is
     * consumed the first time it succeeds, so a second call returns null.
     *
     * @param appspaceId The Appspace ID, not the slug or subdomain.
     * @param context Used for the referrer connection and screen metrics.
     * @return The claimed [DeferredLink], or null when nothing was waiting.
     */
    suspend fun claimDeferredLink(appspaceId: String, context: Context): DeferredLink? {
        require(appspaceId.isNotBlank()) { "appspaceId must not be blank" }

        InstallReferrer.fetchToken(context)?.let { token ->
            val byToken = try {
                claimByToken(token)
            } catch (e: TolinkuException) {
                // A referrer that cannot be claimed is worth one fallback rather
                // than a thrown error: the install still happened.
                null
            }
            if (byToken != null) return byToken
        }

        return claimBySignals(appspaceId, context)
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
     * Java-friendly callback wrapper for [claimDeferredLink].
     *
     * @param appspaceId The Appspace ID, not the slug or subdomain.
     * @param context Used for the referrer connection and screen metrics.
     * @param callback Invoked with a [Result] when the operation completes.
     */
    fun claimDeferredLinkAsync(
        appspaceId: String,
        context: Context,
        callback: TolinkuCallback<DeferredLink?>,
    ) {
        Tolinku.scope.launch {
            try {
                callback.onResult(Result.success(claimDeferredLink(appspaceId, context)))
            } catch (e: Exception) {
                callback.onResult(Result.failure(e))
            }
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
    /**
     * Screen size in density-independent pixels.
     *
     * These are matched against the landing page's `screen.width` / `screen.height`,
     * which a browser reports in CSS pixels. On Android a CSS pixel is a dp, not a
     * physical pixel, so the raw pixel counts reported by WindowMetrics (1080x2340
     * on a typical device) never came close to the browser's values (412x915) and
     * both screen signals always failed to score.
     */
    private fun getScreenDimensions(context: Context): Pair<Int, Int> {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val density = context.resources.displayMetrics.density.takeIf { it > 0f } ?: 1f

        val (widthPx, heightPx) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics: WindowMetrics = windowManager.currentWindowMetrics
            val bounds = metrics.bounds
            Pair(bounds.width(), bounds.height())
        } else {
            val displayMetrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getMetrics(displayMetrics)
            Pair(displayMetrics.widthPixels, displayMetrics.heightPixels)
        }

        return Pair(
            Math.round(widthPx / density),
            Math.round(heightPx / density),
        )
    }
}
