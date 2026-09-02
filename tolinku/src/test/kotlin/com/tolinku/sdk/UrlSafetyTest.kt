package com.tolinku.sdk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The same rule the iOS, Flutter, React Native and web SDKs apply, so a link
 * that opens on one platform opens on all of them.
 *
 * It used to be a prefix match here, which refused `HTTPS://` in capitals: a
 * perfectly ordinary URL that the other four opened without complaint. A
 * customer would have seen a message button work everywhere except Android.
 */
class UrlSafetyTest {

    @Test
    fun `allows the two web schemes`() {
        assertTrue(isSafeUrl("https://example.com/promo"))
        assertTrue(isSafeUrl("http://example.com/promo"))
    }

    @Test
    fun `is not fooled by the case of the scheme`() {
        // The divergence this replaced.
        assertTrue(isSafeUrl("HTTPS://example.com/promo"))
        assertTrue(isSafeUrl("HtTpS://example.com/promo"))
        assertFalse(isSafeUrl("JavaScript:alert(1)"))
    }

    @Test
    fun `blocks schemes that do something other than open a page`() {
        assertFalse(isSafeUrl("javascript:alert(1)"))
        assertFalse(isSafeUrl("file:///etc/passwd"))
        assertFalse(isSafeUrl("content://com.other.app/data"))
        assertFalse(isSafeUrl("intent://scan/#Intent;scheme=zxing;end"))
        assertFalse(isSafeUrl("data:text/html,<script>alert(1)</script>"))
    }

    @Test
    fun `blocks a scheme hidden behind whitespace`() {
        assertFalse(isSafeUrl("  javascript:alert(1)"))
        assertFalse(isSafeUrl("\tfile:///etc/passwd"))
    }

    @Test
    fun `allows a real URL behind whitespace`() {
        assertTrue(isSafeUrl("  https://example.com/promo  "))
    }

    @Test
    fun `treats absent or empty as unsafe`() {
        assertFalse(isSafeUrl(null))
        assertFalse(isSafeUrl(""))
        assertFalse(isSafeUrl("   "))
    }

    @Test
    fun `requires a scheme rather than assuming one`() {
        assertFalse(isSafeUrl("example.com"))
        assertFalse(isSafeUrl("//example.com"))
        assertFalse(isSafeUrl("/promo"))
    }

    @Test
    fun `refuses rather than throwing on something that will not parse`() {
        assertFalse(isSafeUrl("ht!tp://[bad"))
        assertFalse(isSafeUrl("not a url"))
    }
}
