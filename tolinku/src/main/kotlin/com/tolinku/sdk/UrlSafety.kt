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
