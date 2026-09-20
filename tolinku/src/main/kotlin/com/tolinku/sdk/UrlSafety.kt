package com.tolinku.sdk

import java.net.URI

/**
 * Whether a URL is safe to open or hand to app code.
 *
 * Only `http` and `https`. An in-app message is rendered in a WebView and can
 * ask the app to navigate, so the URL it names crosses from page content into
 * native code. Every other scheme a URL can carry is a way of doing something
 * besides opening a web page: `javascript:` executes, `file:` and `content:`
 * read local storage, `intent:` reaches other apps.
 *
 * The scheme is parsed rather than matched as a prefix, which is what the iOS,
 * Flutter, React Native and web SDKs do. A prefix check rejected `HTTPS://` in
 * capitals, a perfectly ordinary URL, so this SDK refused links the others
 * opened.
 */
internal fun isSafeUrl(url: String?): Boolean {
    if (url == null) return false

    // Leading whitespace would otherwise let " javascript:..." past a prefix
    // check, and is never meaningful in a URL.
    val trimmed = url.trim()
    if (trimmed.isEmpty()) return false

    val scheme = try {
        URI(trimmed).scheme?.lowercase()
    } catch (t: Throwable) {
        // A string that will not parse is not safe to open.
        null
    }
    return scheme == "http" || scheme == "https"
}

/**
 * The schemes that can run code, read local storage, or forge an origin when
 * something follows them.
 *
 * Longer than the web platform's list in `safe-url.ts` on purpose. That list
 * guards a browser, where `content:`, `jar:` and `filesystem:` mean nothing.
 * Here the URL ends up at `startActivity`, so Android's rules are the ones that
 * apply: `content:` reads a ContentProvider, including another app's private
 * files wherever a permission has been granted, and `jar:` and `filesystem:`
 * both carry a second URL inside them, so allowing either hands back whatever
 * the rest of this set refuses. A denylist only holds if it names the wrappers
 * too.
 */
private val EXECUTABLE_SCHEMES = setOf(
    "javascript",
    "vbscript",
    "data",
    "blob",
    "file",
    "content",
    "jar",
    "filesystem"
)

/**
 * What a scheme may be made of.
 *
 * RFC 3986 says a letter, then letters, digits, `+`, `-` and `.`. Underscore is
 * allowed here on top of that because Android allows it: a manifest can declare
 * `<data android:scheme="my_app">` and `Uri.parse("my_app://x").scheme` reads
 * back `my_app`, so schemes spelled that way are registered by shipped apps.
 * Holding to the RFC would drop a customer's call to action for a spelling
 * their own manifest accepts, which is the defect this whole denylist exists to
 * stop happening.
 */
private val SCHEME_PATTERN = Regex("^([a-zA-Z][a-zA-Z0-9+.\\-_]*):")

/**
 * A URL's scheme, lowercased, or null when it carries none.
 *
 * `URI` is the authority on what a scheme is, but it throws on anything it
 * considers malformed, including URLs Android opens perfectly well: a space in
 * a path is enough, and so is an underscore in the scheme, so `my_app://x`
 * never reaches `URI.getScheme` at all. The pattern answers when `URI` cannot,
 * so a deep link with an unencoded character or an underscore still has its
 * scheme read rather than being refused, and `data:text/html,<script>` still
 * reports `data` rather than reaching the denylist as an unrecognised string.
 */
private fun schemeOf(url: String): String? {
    val parsed = try {
        URI(url).scheme
    } catch (t: Throwable) {
        null
    }
    return (parsed ?: SCHEME_PATTERN.find(url)?.groupValues?.get(1))?.lowercase()
}

/**
 * Whether a URL is safe to follow when someone taps a message's call to action.
 *
 * Deliberately not [isSafeUrl]. That one allows http and https only, which is
 * right for something the SDK fetches or renders and wrong for a call to
 * action: this is a deep linking product, so the most natural button in an
 * in-app message is one that opens a screen in the host app,
 * `myapp://order/4821`. An allowlist dropped exactly that, silently, without
 * even calling the host app's own handler, while the platform that authored
 * the message has always permitted it.
 *
 * Every customer's scheme is different, so there is no list to allow. The
 * small, known set of dangerous schemes is named instead, matching the
 * platform's rule in `safe-url.ts` and the React Native SDK's
 * `isSafeActionUrl`, and everything else is left to open.
 *
 * Parsing first is what makes this hold: tabs and newlines inside a scheme are
 * ignored by the things that follow URLs, so `java\tscript:alert(1)` would run,
 * and they are stripped here the same way a URL parser strips them.
 */
internal fun isNavigableUrl(url: String?): Boolean {
    if (url == null) return false

    val cleaned = url.replace("\t", "").replace("\n", "").replace("\r", "").trim()
    if (cleaned.isEmpty()) return false

    // No scheme means relative or a fragment. There is no base to resolve it
    // against on a device, so it is not something to open.
    val scheme = schemeOf(cleaned) ?: return false
    return scheme !in EXECUTABLE_SCHEMES
}
