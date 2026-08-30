package com.tolinku.sdk

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `destroy` is the name every Tolinku SDK uses to tear down. This SDK shipped
 * it as `shutdown`, so both work and code moved between platforms compiles.
 * `shutdown` is meant for deprecation later, once moving off it is a one-line
 * change rather than a surprise.
 *
 * An alias is worth having only while it stays identical, so these check the
 * resulting state rather than trusting that one still calls the other.
 */
class DestroyAliasTest {

    @After
    fun tearDown() {
        Tolinku.destroy()
    }

    @Test
    fun `destroy tears the SDK down the same way shutdown does`() {
        Tolinku.configure(apiKey = "tolk_pub_test", baseUrl = "https://links.example.com")
        assertTrue(Tolinku.isConfigured)

        Tolinku.destroy()
        assertFalse("destroy() should leave the SDK unconfigured", Tolinku.isConfigured)
    }

    @Test
    fun `shutdown still works alongside destroy`() {
        Tolinku.configure(apiKey = "tolk_pub_test", baseUrl = "https://links.example.com")

        Tolinku.shutdown()
        assertFalse("shutdown() must keep working", Tolinku.isConfigured)
    }

    @Test
    fun `the SDK can be configured again after either name`() {
        Tolinku.configure(apiKey = "tolk_pub_test", baseUrl = "https://links.example.com")
        Tolinku.destroy()
        Tolinku.configure(apiKey = "tolk_pub_second", baseUrl = "https://links.example.com")
        assertTrue(Tolinku.isConfigured)

        Tolinku.shutdown()
        Tolinku.configure(apiKey = "tolk_pub_third", baseUrl = "https://links.example.com")
        assertTrue(Tolinku.isConfigured)
    }

    @Test
    fun `destroy on an unconfigured SDK is harmless`() {
        Tolinku.destroy()
        Tolinku.destroy()
        assertFalse(Tolinku.isConfigured)
    }
}
