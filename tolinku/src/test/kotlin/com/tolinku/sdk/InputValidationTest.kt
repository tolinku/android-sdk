package com.tolinku.sdk

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Unit tests verifying that all validated methods throw [IllegalArgumentException]
 * when given blank strings.
 */
class InputValidationTest {

    private lateinit var server: MockWebServer
    private lateinit var client: TolinkuClient
    private lateinit var analytics: Analytics
    private lateinit var referrals: Referrals
    private lateinit var deferred: DeferredDeepLink
    private lateinit var messages: Messages

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = TolinkuClient(
            apiKey = "tolk_pub_test_key",
            baseUrl = server.url("/").toString().trimEnd('/')
        )
        analytics = Analytics(client)
        referrals = Referrals(client)
        deferred = DeferredDeepLink(client)
        messages = Messages(client)
    }

    @After
    fun tearDown() {
        client.shutdown()
        server.shutdown()
    }

    // -----------------------------------------------------------------------
    // Analytics validation
    // -----------------------------------------------------------------------

    @Test
    fun `analytics track with empty string throws`() = runTest {
        assertThrowsIllegalArgument { analytics.track("") }
    }

    @Test
    fun `analytics track with blank string throws`() = runTest {
        assertThrowsIllegalArgument { analytics.track("   ") }
    }

    // -----------------------------------------------------------------------
    // Referrals validation
    // -----------------------------------------------------------------------

    @Test
    fun `referrals create with blank userId throws`() = runTest {
        assertThrowsIllegalArgument { referrals.create("") }
    }

    @Test
    fun `referrals create with whitespace userId throws`() = runTest {
        assertThrowsIllegalArgument { referrals.create("  ") }
    }

    @Test
    fun `referrals get with blank code throws`() = runTest {
        assertThrowsIllegalArgument { referrals.get("") }
    }

    @Test
    fun `referrals complete with blank code throws`() = runTest {
        assertThrowsIllegalArgument { referrals.complete("", "user_1") }
    }

    @Test
    fun `referrals complete with blank referredUserId throws`() = runTest {
        assertThrowsIllegalArgument { referrals.complete("CODE", "") }
    }

    @Test
    fun `referrals milestone with blank code throws`() = runTest {
        assertThrowsIllegalArgument { referrals.milestone("", "m1") }
    }

    @Test
    fun `referrals milestone with blank milestone throws`() = runTest {
        assertThrowsIllegalArgument { referrals.milestone("CODE", "") }
    }

    @Test
    fun `referrals claimReward with blank code throws`() = runTest {
        assertThrowsIllegalArgument { referrals.claimReward("") }
    }

    // -----------------------------------------------------------------------
    // DeferredDeepLink validation
    // -----------------------------------------------------------------------

    @Test
    fun `deferred claimByToken with blank token throws`() = runTest {
        assertThrowsIllegalArgument { deferred.claimByToken("") }
    }

    @Test
    fun `deferred claimByToken with whitespace token throws`() = runTest {
        assertThrowsIllegalArgument { deferred.claimByToken("   ") }
    }

    // -----------------------------------------------------------------------
    // Messages validation
    // -----------------------------------------------------------------------

    @Test
    fun `messages fetch with blank trigger throws`() = runTest {
        assertThrowsIllegalArgument { messages.fetch("") }
    }

    @Test
    fun `messages fetch with whitespace trigger throws`() = runTest {
        assertThrowsIllegalArgument { messages.fetch("   ") }
    }

    // -----------------------------------------------------------------------
    // Tolinku.configure validation
    // -----------------------------------------------------------------------

    @Test
    fun `configure with http URL throws`() {
        assertThrowsIllegalArgumentSync {
            Tolinku.configure(
                apiKey = "tolk_pub_test_key",
                baseUrl = "http://example.com"
            )
        }
    }

    @Test
    fun `configure with https URL succeeds`() {
        // Should not throw
        Tolinku.configure(
            apiKey = "tolk_pub_test_key",
            baseUrl = "https://example.com"
        )
        assertTrue(Tolinku.isConfigured)
        Tolinku.shutdown()
    }

    @Test
    fun `configure with localhost http URL succeeds`() {
        // Should not throw (localhost exception)
        Tolinku.configure(
            apiKey = "tolk_pub_test_key",
            baseUrl = "http://localhost:3000"
        )
        assertTrue(Tolinku.isConfigured)
        Tolinku.shutdown()
    }

    @Test
    fun `configure with 10 dot http URL succeeds`() {
        // Should not throw (local network exception)
        Tolinku.configure(
            apiKey = "tolk_pub_test_key",
            baseUrl = "http://10.0.0.1:3000"
        )
        assertTrue(Tolinku.isConfigured)
        Tolinku.shutdown()
    }

    @Test
    fun `configure with 192 dot 168 http URL succeeds`() {
        // Should not throw (local network exception)
        Tolinku.configure(
            apiKey = "tolk_pub_test_key",
            baseUrl = "http://192.168.1.1:3000"
        )
        assertTrue(Tolinku.isConfigured)
        Tolinku.shutdown()
    }

    // -----------------------------------------------------------------------
    // Helper
    // -----------------------------------------------------------------------

    private suspend fun assertThrowsIllegalArgument(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // Expected
            assertTrue("Exception message should not be empty", e.message?.isNotEmpty() == true)
        }
    }

    private fun assertThrowsIllegalArgumentSync(block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // Expected
            assertTrue("Exception message should not be empty", e.message?.isNotEmpty() == true)
        }
    }
}
