package com.tolinku.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two decisions the in-app message JavaScript bridge makes, tested apart
 * from the bridge.
 *
 * The bridge itself needs a Dialog, a Context and a WebView, so it cannot run
 * on a plain JVM. Everything about it was therefore asserted nowhere: the rule
 * it applies to a `navigate:` URL could be swapped back to the http-only
 * allowlist, silently breaking every message that links into the host app, and
 * the whole suite stayed green. These two functions are that rule and that
 * hand-off, lifted out of the bridge so a revert has somewhere to fail.
 */
class MessageActionTest {

    @Test
    fun `follows a call to action that links into the host app`() {
        // The defect. On a deep linking product this is the ordinary case, and
        // the http-only allowlist dropped it before onAction was ever called.
        assertTrue(shouldFollowMessageAction("myapp://order/4821"))
        assertTrue(shouldFollowMessageAction("my_app://order/4821"))
        assertTrue(shouldFollowMessageAction("com.example.app://profile"))
        assertTrue(shouldFollowMessageAction("market://details?id=com.example.app"))
        assertTrue(shouldFollowMessageAction("tel:+18005550199"))
    }

    @Test
    fun `still follows a plain web link`() {
        assertTrue(shouldFollowMessageAction("https://example.com/promo"))
        assertTrue(shouldFollowMessageAction("http://example.com/promo"))
    }

    @Test
    fun `refuses a call to action that runs code or reads local storage`() {
        assertFalse(shouldFollowMessageAction("javascript:alert(1)"))
        assertFalse(shouldFollowMessageAction("java\tscript:alert(1)"))
        assertFalse(shouldFollowMessageAction("data:text/html,<script>alert(1)</script>"))
        assertFalse(shouldFollowMessageAction("file:///etc/passwd"))
        // Reaches startActivity, so Android's rules apply and this one is ours
        // to refuse rather than the web's.
        assertFalse(shouldFollowMessageAction("content://com.host/secret"))
        assertFalse(shouldFollowMessageAction("jar:file:///x.apk!/classes.dex"))
        assertFalse(shouldFollowMessageAction("filesystem:file:///persistent/x"))
    }

    @Test
    fun `refuses an action with no scheme to resolve`() {
        assertFalse(shouldFollowMessageAction("/promo"))
        assertFalse(shouldFollowMessageAction("example.com"))
        assertFalse(shouldFollowMessageAction(""))
    }

    @Test
    fun `passes the URL through to the host handler`() {
        var seen: String? = null
        val ok = invokeActionHandler("myapp://order/4821") { seen = it }

        assertTrue(ok)
        assertEquals("myapp://order/4821", seen)
    }

    @Test
    fun `a host handler that throws does not take the app down`() {
        // The host could only receive http and https before the denylist, so a
        // handler written as Uri.parse(url).host!! was correct then and throws
        // now. It throws on the main thread inside our dismiss handler, where
        // nothing of ours is left above it to catch anything.
        val ok = invokeActionHandler("tel:+18005550199") {
            throw NullPointerException("Uri.parse(url).host must not be null")
        }

        assertFalse(ok)
    }

    @Test
    fun `absorbs an Error from a host handler too, not only an Exception`() {
        // A Kotlin host that left a branch as TODO() throws NotImplementedError,
        // which is an Error. An in-app message is never worth a crash.
        assertFalse(invokeActionHandler("myapp://x") { TODO("not wired up yet") })
        assertFalse(invokeActionHandler("myapp://x") { throw AssertionError("nope") })
    }
}
