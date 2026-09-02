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
    @JvmOverloads
    suspend fun claimByToken(token: String, appspaceId: String? = null): DeferredLink? {
        require(token.isNotBlank()) { "token must not be blank" }

        return try {
            // appspaceId narrows what the token may claim, never widens it, and
            // it is what lets a failed claim be attributed: the default host
            // resolves to no Appspace, so without it a miss belongs to nobody
            // and goes uncounted.
            val response = client.getPublic(
                "/v1/api/deferred/claim",
                queryParams = buildMap {
                    put("token", token)
                    if (!appspaceId.isNullOrBlank()) put("appspace_id", appspaceId)
                }
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
     * The signals are read from the device, so nothing beyond the Appspace ID and
     * a context needs passing. Pass one only where you hold a better value than
     * the SDK can read, and pass it in the form matching compares against: an
     * IANA timezone ("Asia/Seoul"), a BCP-47 tag with its region ("ko-KR"),
     * screen size in density independent pixels, and a version starting with a
     * digit ("13").
     *
     * A signal that is absent is skipped rather than counted as a failed
     * comparison, so leaving one out is safe. One in the wrong shape is worse
     * than none, which is why these are overrides rather than the only source.
     *
     * @param appspaceId The Appspace ID to claim the link for.
     * @param context Android context used to read display metrics.
     * @param timezone IANA identifier, overriding the device's.
     * @param language BCP-47 tag with region, overriding the device's.
     * @param screenWidth Screen width in density independent pixels.
     * @param screenHeight Screen height in density independent pixels.
     * @param devicePixelRatio Ratio of physical pixels to density independent ones.
     * @param osVersion Version compared on its leading digits.
     * @return The [DeferredLink] if a match is found, or null otherwise.
     * @throws IllegalArgumentException if appspaceId is blank.
     * @throws TolinkuException if the request fails for reasons other than "not found".
     */
    suspend fun claimBySignals(
        appspaceId: String,
        context: Context,
        timezone: String? = null,
        language: String? = null,
        screenWidth: Int? = null,
        screenHeight: Int? = null,
        devicePixelRatio: Double? = null,
        osVersion: String? = null,
    ): DeferredLink? {
        require(appspaceId.isNotBlank()) { "appspaceId must not be blank" }

        val (collectedWidth, collectedHeight) = getScreenDimensions(context)

        // Anything the caller passed wins. A value from their own lookup is
        // better than one inferred here, and passing one must not discard the
        // rest, which is the mistake that makes a partial override worse than
        // none at all.
        val body = JSONObject().apply {
            put("appspace_id", appspaceId)
            put("timezone", timezone ?: TimeZone.getDefault().id)
            put("language", language ?: Locale.getDefault().toLanguageTag())
            put("screen_width", screenWidth ?: collectedWidth)
            put("screen_height", screenHeight ?: collectedHeight)
            // Pixel ratio separates devices that report the same dp dimensions.
            put("device_pixel_ratio", devicePixelRatio ?: context.resources.displayMetrics.density.toDouble())
            put("os_version", osVersion ?: Build.VERSION.RELEASE ?: "")
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
    suspend fun claimDeferredLink(
        appspaceId: String,
        context: Context,
        force: Boolean = false,
    ): DeferredLink? {
        require(appspaceId.isNotBlank()) { "appspaceId must not be blank" }

        // Claiming is a first-launch action, but nothing stops an app calling
        // this on every launch. Each repeat costs a request and records a miss,
        // so a healthy integration would report a match rate near zero.
        if (!force && alreadyAttempted(context)) return null

        InstallReferrer.fetchToken(context)?.let { token ->
            val byToken = try {
                claimByToken(token, appspaceId)
            } catch (e: TolinkuException) {
                // A referrer that cannot be claimed is worth one fallback rather
                // than a thrown error: the install still happened.
                null
            }
            if (byToken != null) {
                rememberAttempt(context)
                return byToken
            }
        }

        // claimBySignals returns null only for a 404, a real "nothing waiting",
        // and throws for anything else. So reaching this line means the server
        // answered, and only an answer is worth remembering: recording a
        // dropped request would spend the install's one chance at attribution
        // on a bad connection.
        val bySignals = claimBySignals(appspaceId, context)
        rememberAttempt(context)
        return bySignals
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(CLAIM_PREFS, Context.MODE_PRIVATE)

    private fun alreadyAttempted(context: Context): Boolean =
        try {
            prefs(context).contains(CLAIMED_KEY)
        } catch (e: Throwable) {
            // Storage unavailable: attempt the claim rather than skip it.
            false
        }

    private fun rememberAttempt(context: Context) {
        try {
            prefs(context).edit().putLong(CLAIMED_KEY, System.currentTimeMillis()).apply()
        } catch (e: Throwable) {
            // Not worth failing a claim that already succeeded.
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
     * Java-friendly callback wrapper for [claimDeferredLink].
     *
     * @param appspaceId The Appspace ID, not the slug or subdomain.
     * @param context Used for the referrer connection and screen metrics.
     * @param callback Invoked with a [Result] when the operation completes.
     */
    @JvmOverloads
    fun claimDeferredLinkAsync(
        appspaceId: String,
        context: Context,
        callback: TolinkuCallback<DeferredLink?>,
        force: Boolean = false,
    ) {
        Tolinku.scope.launch {
            try {
                callback.onResult(Result.success(claimDeferredLink(appspaceId, context, force)))
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
    private companion object {
        const val CLAIM_PREFS = "tolinku_deferred"
        const val CLAIMED_KEY = "claim_attempted_at"
    }

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
