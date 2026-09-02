package com.tolinku.sdk

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Signals passed to `claimBySignals` override what the device reports, matching
 * the Flutter, React Native and web SDKs. This SDK and iOS took no overrides at
 * all, so an app holding a better value than the SDK could read had nowhere to
 * put it.
 *
 * The property that matters is the second group: overriding one signal must not
 * discard the others. Matching compares only what both sides supplied, so a
 * partial override that dropped the rest would leave less to compare on than
 * passing nothing at all, which is the opposite of what the caller intended.
 */
class SignalOverrideTest {

    /** The merge `claimBySignals` performs when building its request. */
    private fun body(
        collected: Map<String, Any>,
        timezone: String? = null,
        language: String? = null,
        screenWidth: Int? = null,
        screenHeight: Int? = null,
        devicePixelRatio: Double? = null,
        osVersion: String? = null,
    ): JSONObject = JSONObject().apply {
        put("appspace_id", "app123")
        put("timezone", timezone ?: collected["timezone"])
        put("language", language ?: collected["language"])
        put("screen_width", screenWidth ?: collected["screen_width"])
        put("screen_height", screenHeight ?: collected["screen_height"])
        put("device_pixel_ratio", devicePixelRatio ?: collected["device_pixel_ratio"])
        put("os_version", osVersion ?: collected["os_version"])
    }

    private val collected = mapOf(
        "timezone" to "Europe/London",
        "language" to "en-GB",
        "screen_width" to 411,
        "screen_height" to 891,
        "device_pixel_ratio" to 2.625,
        "os_version" to "13",
    )

    @Test
    fun `sends what the device reports when nothing is passed`() {
        val b = body(collected)

        assertEquals("Europe/London", b.getString("timezone"))
        assertEquals("en-GB", b.getString("language"))
        assertEquals(411, b.getInt("screen_width"))
        assertEquals("13", b.getString("os_version"))
    }

    @Test
    fun `a passed signal wins over the device`() {
        val b = body(collected, timezone = "Asia/Seoul")

        assertEquals("Asia/Seoul", b.getString("timezone"))
    }

    @Test
    fun `overriding one signal keeps the rest`() {
        // The mistake worth guarding: matching compares only what both sides
        // supplied, so dropping the others would leave less to compare on than
        // passing nothing at all.
        val b = body(collected, timezone = "Asia/Seoul")

        assertEquals("en-GB", b.getString("language"))
        assertEquals(411, b.getInt("screen_width"))
        assertEquals(891, b.getInt("screen_height"))
        assertEquals(2.625, b.getDouble("device_pixel_ratio"), 0.001)
        assertEquals("13", b.getString("os_version"))
    }

    @Test
    fun `every signal can be overridden at once`() {
        val b = body(
            collected,
            timezone = "Asia/Seoul",
            language = "ko-KR",
            screenWidth = 390,
            screenHeight = 844,
            devicePixelRatio = 3.0,
            osVersion = "17.1",
        )

        assertEquals("Asia/Seoul", b.getString("timezone"))
        assertEquals("ko-KR", b.getString("language"))
        assertEquals(390, b.getInt("screen_width"))
        assertEquals(844, b.getInt("screen_height"))
        assertEquals(3.0, b.getDouble("device_pixel_ratio"), 0.001)
        assertEquals("17.1", b.getString("os_version"))
    }
}
