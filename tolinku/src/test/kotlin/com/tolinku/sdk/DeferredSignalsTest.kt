package com.tolinku.sdk

import android.content.Context
import android.content.res.Resources
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito
import java.util.Locale

/**
 * Tests for the signal payload [DeferredDeepLink.claimBySignals] sends.
 *
 * These matter because the values are matched against what the Tolinku landing page
 * records in the browser, and a mismatch in units or format does not fail loudly: the
 * signal is simply skipped and the claim quietly returns null. Two such mismatches
 * shipped previously, a bare language subtag and physical rather than density
 * independent pixels, and between them they made signal claiming unusable on Android.
 */
class DeferredSignalsTest {

    private lateinit var server: MockWebServer
    private lateinit var client: TolinkuClient
    private lateinit var deferred: DeferredDeepLink
    private var originalLocale: Locale = Locale.getDefault()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = TolinkuClient(
            apiKey = "tolk_pub_test_key",
            baseUrl = server.url("/").toString().trimEnd('/')
        )
        deferred = DeferredDeepLink(client)
        originalLocale = Locale.getDefault()
    }

    @After
    fun tearDown() {
        Locale.setDefault(originalLocale)
        client.shutdown()
        server.shutdown()
    }

    /**
     * A Context reporting a screen of [widthPx] by [heightPx] physical pixels at
     * [density]. Unit tests report SDK_INT as 0, so the pre-R branch using
     * Display.getMetrics is the one exercised here.
     */
    private fun mockContext(widthPx: Int, heightPx: Int, density: Float): Context {
        // Plain Mockito rather than the mockito-kotlin DSL: that DSL is inline and
        // compiled for JVM target 11, which this library does not build against.
        val display = Mockito.mock(Display::class.java)
        Mockito.doAnswer { invocation ->
            val dm = invocation.getArgument<DisplayMetrics>(0)
            dm.widthPixels = widthPx
            dm.heightPixels = heightPx
            dm.density = density
            null
        }.`when`(display).getMetrics(any(DisplayMetrics::class.java))

        val windowManager = Mockito.mock(WindowManager::class.java)
        Mockito.`when`(windowManager.defaultDisplay).thenReturn(display)

        val metrics = Mockito.mock(DisplayMetrics::class.java)
        metrics.widthPixels = widthPx
        metrics.heightPixels = heightPx
        metrics.density = density

        val resources = Mockito.mock(Resources::class.java)
        Mockito.`when`(resources.displayMetrics).thenReturn(metrics)

        val context = Mockito.mock(Context::class.java)
        Mockito.`when`(context.getSystemService(Context.WINDOW_SERVICE)).thenReturn(windowManager)
        Mockito.`when`(context.resources).thenReturn(resources)
        return context
    }

    private fun enqueueMatch() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"deep_link_path":"/product/42","appspace_id":"app123"}""")
        )
    }

    private suspend fun claimAndCaptureBody(context: Context): JSONObject {
        deferred.claimBySignals("64f0a1b2c3d4e5f60718", context)
        return JSONObject(server.takeRequest().body.readUtf8())
    }

    // -----------------------------------------------------------------------
    // Language
    // -----------------------------------------------------------------------

    @Test
    fun `language is a full BCP 47 tag, not a bare subtag`() = runTest {
        Locale.setDefault(Locale("ko", "KR"))
        enqueueMatch()

        val body = claimAndCaptureBody(mockContext(1080, 2340, 2.625f))

        // The landing page records navigator.language, which always carries a region.
        // Sending "ko" here meant the language signal could never score.
        assertEquals("ko-KR", body.getString("language"))
    }

    @Test
    fun `language uses a hyphen separator, never an underscore`() = runTest {
        Locale.setDefault(Locale("pt", "BR"))
        enqueueMatch()

        val body = claimAndCaptureBody(mockContext(1080, 2340, 2.625f))

        assertEquals("pt-BR", body.getString("language"))
        assertTrue(
            "BCP-47 uses a hyphen; an underscore will not match the browser value",
            !body.getString("language").contains("_")
        )
    }

    // -----------------------------------------------------------------------
    // Screen dimensions
    // -----------------------------------------------------------------------

    @Test
    fun `screen dimensions are density independent pixels, not physical pixels`() = runTest {
        Locale.setDefault(Locale("ko", "KR"))
        enqueueMatch()

        // A Pixel 7: 1080x2340 physical at density 2.625, which a browser reports
        // as roughly 412x891 CSS pixels.
        val body = claimAndCaptureBody(mockContext(1080, 2340, 2.625f))

        assertEquals(411, body.getInt("screen_width"))
        assertEquals(891, body.getInt("screen_height"))
    }

    @Test
    fun `screen dimensions survive a density of one`() = runTest {
        enqueueMatch()
        val body = claimAndCaptureBody(mockContext(412, 891, 1.0f))

        assertEquals(412, body.getInt("screen_width"))
        assertEquals(891, body.getInt("screen_height"))
    }

    @Test
    fun `a zero density does not divide by zero`() = runTest {
        enqueueMatch()
        // Defensive: density should never be zero, but dividing by it would produce
        // Infinity and a payload the server cannot parse.
        val body = claimAndCaptureBody(mockContext(1080, 2340, 0f))

        assertEquals(1080, body.getInt("screen_width"))
        assertEquals(2340, body.getInt("screen_height"))
    }

    // -----------------------------------------------------------------------
    // Newer signals
    // -----------------------------------------------------------------------

    @Test
    fun `device pixel ratio is sent and matches display density`() = runTest {
        enqueueMatch()
        val body = claimAndCaptureBody(mockContext(1080, 2340, 2.625f))

        assertTrue("device_pixel_ratio should be present", body.has("device_pixel_ratio"))
        assertEquals(2.625, body.getDouble("device_pixel_ratio"), 0.0001)
    }

    @Test
    fun `os version key is always present`() = runTest {
        enqueueMatch()
        val body = claimAndCaptureBody(mockContext(1080, 2340, 2.625f))

        // The value comes from Build.VERSION.RELEASE, which is not populated under
        // plain JVM unit tests; only its presence can be asserted here.
        assertTrue("os_version should be present", body.has("os_version"))
    }

    @Test
    fun `payload carries every signal the matcher compares`() = runTest {
        Locale.setDefault(Locale("ko", "KR"))
        enqueueMatch()
        val body = claimAndCaptureBody(mockContext(1080, 2340, 2.625f))

        for (key in listOf(
            "appspace_id", "timezone", "language",
            "screen_width", "screen_height", "device_pixel_ratio", "os_version"
        )) {
            assertTrue("payload is missing $key", body.has(key))
        }
        assertEquals("64f0a1b2c3d4e5f60718", body.getString("appspace_id"))
    }

    // -----------------------------------------------------------------------
    // Request shape and error handling
    // -----------------------------------------------------------------------

    @Test
    fun `claimBySignals posts unauthenticated to the expected path`() = runTest {
        enqueueMatch()
        deferred.claimBySignals("64f0a1b2c3d4e5f60718", mockContext(1080, 2340, 2.625f))

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/api/deferred/claim-by-signals", request.path)
        assertNull("should not send an API key", request.getHeader("X-API-Key"))
    }

    @Test
    fun `claimBySignals returns the link on success`() = runTest {
        enqueueMatch()
        val link = deferred.claimBySignals("64f0a1b2c3d4e5f60718", mockContext(1080, 2340, 2.625f))

        assertNotNull(link)
        assertEquals("/product/42", link!!.deepLinkPath)
    }

    @Test
    fun `claimBySignals returns null on 404`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"No matching deferred link found"}"""))

        val link = deferred.claimBySignals("64f0a1b2c3d4e5f60718", mockContext(1080, 2340, 2.625f))
        assertNull("404 means nothing was waiting for this device, not a fault", link)
    }

    @Test
    fun `claimBySignals throws on 403 rather than reporting no match`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(403)
                .setBody("""{"error":"Unknown appspace_id. Use your Appspace ID, not its slug or subdomain."}""")
        )

        try {
            deferred.claimBySignals("wrong-id", mockContext(1080, 2340, 2.625f))
            fail("A wrong appspaceId must surface, not return null")
        } catch (e: TolinkuException) {
            assertEquals(403, e.statusCode)
        }
    }

    @Test
    fun `blank appspaceId is rejected before any request`() = runTest {
        try {
            deferred.claimBySignals("   ", mockContext(1080, 2340, 2.625f))
            fail("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("appspaceId"))
        }
    }
}
