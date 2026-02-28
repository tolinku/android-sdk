package com.tolinku.sdk

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.URLEncoder

/**
 * Unit tests for [Referrals], focusing on URL encoding of referral codes.
 */
class ReferralsTest {

    private lateinit var server: MockWebServer
    private lateinit var client: TolinkuClient
    private lateinit var referrals: Referrals

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = TolinkuClient(
            apiKey = "tolk_pub_test_key",
            baseUrl = server.url("/").toString().trimEnd('/')
        )
        referrals = Referrals(client)
    }

    @After
    fun tearDown() {
        client.shutdown()
        server.shutdown()
    }

    @Test
    fun `get encodes referral code in URL path`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"referrer_id":"user_1","status":"pending","milestone_history":[]}""")
        )

        referrals.get("REF+123")

        val request = server.takeRequest()
        val expectedEncoded = URLEncoder.encode("REF+123", "UTF-8")
        assertTrue(
            "Path should contain URL-encoded referral code, got: ${request.path}",
            request.path!!.contains(expectedEncoded)
        )
    }

    @Test
    fun `get encodes special characters in referral code`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"referrer_id":"u1","status":"pending","milestone_history":[]}""")
        )

        referrals.get("code/with spaces&special=chars")

        val request = server.takeRequest()
        // The path should not contain raw spaces or unencoded ampersands
        val path = request.path!!
        assertTrue("Path should not contain raw spaces", !path.contains(" "))
    }

    @Test
    fun `get with simple alphanumeric code works`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"referrer_id":"user_1","status":"pending","milestone_history":[]}""")
        )

        val referral = referrals.get("ABC123")
        assertEquals("user_1", referral.referrerId)

        val request = server.takeRequest()
        assertTrue(request.path!!.endsWith("/v1/api/referral/ABC123"))
    }

    @Test
    fun `create sends correct JSON body`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"referral_code":"NEW_CODE","referral_url":"https://example.com/ref/NEW_CODE","referral_id":"doc_42"}""")
        )

        val referral = referrals.create(
            userId = "user_42",
            metadata = mapOf("source" to "test"),
            userName = "Alice"
        )

        assertEquals("NEW_CODE", referral.referralCode)
        assertEquals("doc_42", referral.referralId)

        val request = server.takeRequest()
        val body = JSONObject(request.body.readUtf8())
        assertEquals("user_42", body.getString("user_id"))
        assertEquals("Alice", body.getString("user_name"))
        assertEquals("test", body.getJSONObject("metadata").getString("source"))
    }
}
