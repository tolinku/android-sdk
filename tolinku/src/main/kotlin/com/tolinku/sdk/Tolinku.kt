package com.tolinku.sdk

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Main entry point for the Tolinku Android SDK.
 *
 * Initialize the SDK early in your app lifecycle (e.g., in Application.onCreate):
 *
 * ```kotlin
 * Tolinku.configure(apiKey = "tolk_pub_...")
 * ```
 *
 * Then use the singleton to access SDK features:
 *
 * ```kotlin
 * Tolinku.track("custom.signup", mapOf("source" to "android"))
 * val ref = Tolinku.referrals.create(userId = "user_123")
 * ```
 */
object Tolinku {

    /** SDK version string, injected at build time from build.gradle.kts. */
    @JvmStatic
    val VERSION: String = BuildConfig.SDK_VERSION

    internal const val TAG = "TolinkuSDK"

    /**
     * When true, the SDK logs debug information to Logcat under the "TolinkuSDK" tag.
     */
    @JvmStatic
    var debug: Boolean = false

    @Volatile
    private var client: TolinkuClient? = null
    @Volatile
    internal var configuredApiKey: String? = null
        private set
    @Volatile
    internal var configuredBaseUrl: String? = null
        private set
    @Volatile
    private var _analytics: Analytics? = null
    @Volatile
    private var _referrals: Referrals? = null
    @Volatile
    private var _deferred: DeferredDeepLink? = null
    @Volatile
    private var _messages: Messages? = null
    @Volatile
    private var _context: Context? = null
    @Volatile
    private var _userId: String? = null
    @Volatile
    private var lifecycleCallbacks: Application.ActivityLifecycleCallbacks? = null

    /**
     * Coroutine scope owned by the SDK, used by async callback wrappers.
     * Cancelled and recreated on [shutdown] / [configure].
     */
    @Volatile
    internal var scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private set

    private val lock = Any()

    /**
     * Access the analytics module for tracking events.
     * @throws TolinkuException if the SDK has not been configured.
     */
    @JvmStatic
    val analytics: Analytics
        get() = _analytics ?: throw TolinkuException("Tolinku SDK not configured. Call Tolinku.configure() first.")

    /**
     * Access the referrals module for managing referral links.
     * @throws TolinkuException if the SDK has not been configured.
     */
    @JvmStatic
    val referrals: Referrals
        get() = _referrals ?: throw TolinkuException("Tolinku SDK not configured. Call Tolinku.configure() first.")

    /**
     * Access the deferred deep link module for claiming deferred links.
     * @throws TolinkuException if the SDK has not been configured.
     */
    @JvmStatic
    val deferred: DeferredDeepLink
        get() = _deferred ?: throw TolinkuException("Tolinku SDK not configured. Call Tolinku.configure() first.")

    /**
     * Access the messages module for fetching in-app messages.
     * @throws TolinkuException if the SDK has not been configured.
     */
    @JvmStatic
    val messages: Messages
        get() = _messages ?: throw TolinkuException("Tolinku SDK not configured. Call Tolinku.configure() first.")

    /**
     * Return the application context passed during configuration, or null if
     * configure() was called without a context.
     */
    @JvmStatic
    val applicationContext: Context?
        get() = _context

    /**
     * The current user ID, used for segment targeting and analytics attribution.
     */
    @JvmStatic
    val userId: String?
        get() = _userId

    /**
     * Set the user ID for segment targeting and analytics attribution.
     * Pass null to clear the user ID.
     *
     * @param userId The unique identifier for the current user, or null to clear.
     */
    @JvmStatic
    fun setUserId(userId: String?) {
        _userId = userId
    }

    /**
     * Configure the Tolinku SDK. Must be called before any other SDK method.
     *
     * @param apiKey Your Tolinku publishable API key (starts with "tolk_pub_").
     * @param baseUrl The base URL of your Tolinku instance. Defaults to "https://api.tolinku.com".
     * @param context Optional Android context (the application context will be retained for
     *   device info and SharedPreferences). Recommended for deferred deep link signal matching.
     * @param debug When true, enables debug logging to Logcat.
     */
    @JvmStatic
    @JvmOverloads
    fun configure(
        apiKey: String,
        baseUrl: String = "https://api.tolinku.com",
        context: Context? = null,
        debug: Boolean = false
    ) {
        require(apiKey.isNotBlank()) { "API key must not be blank" }
        require(baseUrl.isNotBlank()) { "Base URL must not be blank" }

        // Enforce HTTPS, except for local development
        val isLocalDev = baseUrl.startsWith("http://localhost") ||
                baseUrl.startsWith("http://127.0.0.1") ||
                baseUrl.startsWith("http://10.") ||
                Regex("^http://172\\.(1[6-9]|2\\d|3[01])\\.").containsMatchIn(baseUrl) ||
                baseUrl.startsWith("http://192.168.")
        require(baseUrl.startsWith("https://") || isLocalDev) {
            "Base URL must use HTTPS to protect your API key. Use https:// instead of http://. " +
                    "Local development URLs (localhost, 127.0.0.1, 10.x, 172.16-31.x, 192.168.x) are exempt from this requirement."
        }

        this.debug = debug

        synchronized(lock) {
            // Shut down the old Analytics instance first (flushes and cancels its scope)
            _analytics?.let { oldAnalytics ->
                runBlocking {
                    oldAnalytics.shutdown()
                }
            }

            // Cancel the previous scope and create a fresh one
            scope.cancel()
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

            // Shut down any existing client
            client?.shutdown()

            configuredApiKey = apiKey
            configuredBaseUrl = baseUrl

            val newClient = TolinkuClient(apiKey, baseUrl)
            client = newClient
            _analytics = Analytics(newClient)
            _referrals = Referrals(newClient)
            _deferred = DeferredDeepLink(newClient)
            _messages = Messages(newClient)
            _context = context?.applicationContext

            // Register lifecycle callbacks to flush analytics when app goes to background
            lifecycleCallbacks?.let { cb ->
                (_context as? Application)?.unregisterActivityLifecycleCallbacks(cb)
            }
            if (_context is Application) {
                val callbacks = object : Application.ActivityLifecycleCallbacks {
                    private var activityCount = 0

                    override fun onActivityStarted(activity: Activity) { activityCount++ }
                    override fun onActivityStopped(activity: Activity) {
                        activityCount--
                        if (activityCount <= 0) {
                            // App went to background; flush analytics
                            scope.launch {
                                try {
                                    _analytics?.flush()
                                } catch (e: Exception) {
                                    if (Tolinku.debug) {
                                        Log.w(TAG, "Lifecycle flush failed: ${e.message}")
                                    }
                                }
                                Unit
                            }
                        }
                    }

                    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
                    override fun onActivityResumed(activity: Activity) {}
                    override fun onActivityPaused(activity: Activity) {}
                    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
                    override fun onActivityDestroyed(activity: Activity) {}
                }
                (_context as Application).registerActivityLifecycleCallbacks(callbacks)
                lifecycleCallbacks = callbacks
            }
        }

        if (debug) {
            Log.d(TAG, "Tolinku SDK v$VERSION configured (baseUrl=$baseUrl)")
        }
    }

    /**
     * Convenience method to track a custom event.
     * Equivalent to calling `Tolinku.analytics.track(eventType, properties)`.
     *
     * @param eventType The event type identifier (e.g., "custom.signup").
     * @param properties Optional key-value properties to attach to the event.
     * @throws TolinkuException if the SDK has not been configured or the request fails.
     */
    @JvmStatic
    suspend fun track(eventType: String, properties: Map<String, Any>? = null) {
        val mergedProps = if (_userId != null) {
            val props = (properties ?: emptyMap()).toMutableMap()
            props["user_id"] = _userId!!
            props
        } else {
            properties
        }
        analytics.track(eventType, mergedProps)
    }

    /**
     * Check whether the SDK has been configured.
     */
    @JvmStatic
    val isConfigured: Boolean
        get() = client != null

    /**
     * Shut down the SDK and release resources.
     * After calling this, you must call [configure] again before using the SDK.
     */
    @JvmStatic
    fun shutdown() {
        synchronized(lock) {
            // Flush pending analytics events and cancel its scope BEFORE cancelling SDK scope
            _analytics?.let { analytics ->
                runBlocking {
                    analytics.shutdown()
                }
            }

            // Cancel the SDK coroutine scope
            scope.cancel()
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

            client?.shutdown()
            client = null
            configuredApiKey = null
            configuredBaseUrl = null
            _analytics = null
            _referrals = null
            _deferred = null
            _messages = null
            lifecycleCallbacks?.let { cb ->
                (_context as? Application)?.unregisterActivityLifecycleCallbacks(cb)
            }
            lifecycleCallbacks = null
            _context = null
            _userId = null
        }

        if (debug) {
            Log.d(TAG, "Tolinku SDK shut down")
        }
    }
}
