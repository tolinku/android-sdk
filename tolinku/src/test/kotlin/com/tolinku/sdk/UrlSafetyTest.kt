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

    @Test
    fun `stays an allowlist now that a second rule exists beside it`() {
        // The two rules are for different jobs, and widening a call to action
        // must not widen anything the SDK fetches or renders.
        assertTrue(isSafeUrl("https://example.com/promo"))
        assertTrue(isSafeUrl("http://example.com/promo"))
        assertFalse(isSafeUrl("myapp://order/4821"))
        assertFalse(isSafeUrl("market://details?id=com.example.app"))
        assertFalse(isSafeUrl("intent://scan/#Intent;scheme=zxing;end"))
        assertFalse(isSafeUrl("tel:+18005550199"))
        assertFalse(isSafeUrl("mailto:hi@example.com"))
    }
}

/**
 * The rule for a message's call to action, which is a denylist on purpose.
 *
 * A deep linking SDK whose in-app message cannot link into its own app is the
 * defect this covers: `myapp://order/4821` was dropped by the http/https
 * allowlist, silently, before the host app's own onAction handler was called.
 * There is no list of schemes to allow, since every customer has their own, so
 * only the schemes that can run code or forge an origin are named.
 */
class NavigableUrlTest {

    @Test
    fun `allows a custom scheme into the host app`() {
        // The defect: the most natural call to action on a deep linking product.
        assertTrue(isNavigableUrl("myapp://order/4821"))
        assertTrue(isNavigableUrl("com.example.app://profile"))
        assertTrue(isNavigableUrl("myapp://order/4821?ref=promo#top"))
    }

    @Test
    fun `allows the schemes that reach another app`() {
        assertTrue(isNavigableUrl("market://details?id=com.example.app"))
        assertTrue(isNavigableUrl("intent://scan/#Intent;scheme=zxing;end"))
        assertTrue(isNavigableUrl("tel:+18005550199"))
        assertTrue(isNavigableUrl("mailto:hi@example.com"))
    }

    @Test
    fun `still allows the web`() {
        assertTrue(isNavigableUrl("https://example.com/promo"))
        assertTrue(isNavigableUrl("http://example.com/promo"))
        assertTrue(isNavigableUrl("HTTPS://example.com/promo"))
    }

    @Test
    fun `refuses every scheme that can run code or forge an origin`() {
        assertFalse(isNavigableUrl("javascript:alert(1)"))
        assertFalse(isNavigableUrl("vbscript:msgbox(1)"))
        assertFalse(isNavigableUrl("data:text/html,<script>alert(1)</script>"))
        assertFalse(isNavigableUrl("blob:https://example.com/abc-123"))
        assertFalse(isNavigableUrl("file:///etc/passwd"))
    }

    @Test
    fun `is not fooled by the case of a denied scheme`() {
        assertFalse(isNavigableUrl("JavaScript:alert(1)"))
        assertFalse(isNavigableUrl("JAVASCRIPT:alert(1)"))
        assertFalse(isNavigableUrl("DaTa:text/html,x"))
    }

    @Test
    fun `is not fooled by whitespace around or inside a denied scheme`() {
        // Tabs and newlines inside a scheme are ignored by whatever follows the
        // URL, so these navigate as javascript: unless they are stripped first.
        assertFalse(isNavigableUrl("  javascript:alert(1)"))
        assertFalse(isNavigableUrl("javascript:alert(1)  "))
        assertFalse(isNavigableUrl("java\tscript:alert(1)"))
        assertFalse(isNavigableUrl("java\nscript:alert(1)"))
        assertFalse(isNavigableUrl("java\r\nscript:alert(1)"))
        assertFalse(isNavigableUrl("\tjavascript:alert(1)"))
    }

    @Test
    fun `refuses a URL with no scheme`() {
        // Relative or a fragment. There is no base to resolve it against here.
        assertFalse(isNavigableUrl("example.com"))
        assertFalse(isNavigableUrl("//example.com"))
        assertFalse(isNavigableUrl("/promo"))
        assertFalse(isNavigableUrl("#section"))
        assertFalse(isNavigableUrl("not a url"))
    }

    @Test
    fun `treats absent or empty as not navigable`() {
        assertFalse(isNavigableUrl(null))
        assertFalse(isNavigableUrl(""))
        assertFalse(isNavigableUrl("   "))
    }

    @Test
    fun `reads the scheme of a URL that java net URI will not parse`() {
        // URI throws on an unencoded space, so relying on it alone would refuse
        // a working deep link and, worse, wave a dangerous one through as
        // unrecognised.
        assertFalse(isNavigableUrl("javascript:alert(1, 2)"))
        assertTrue(isNavigableUrl("myapp://order/4821 promo"))
    }
}
