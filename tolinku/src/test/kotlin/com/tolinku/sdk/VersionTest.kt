package com.tolinku.sdk

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Tolinku.VERSION] is sent in the User-Agent on every request, so a value that
 * does not match what was published silently misreports which SDK is in the
 * field. The Flutter SDK's constant sat at 0.1.0 through two releases before a
 * guard like this existed.
 *
 * This module is in a better position than most: `sdkVersion` in
 * build.gradle.kts feeds both `BuildConfig.SDK_VERSION` and the Maven
 * coordinate, so the two cannot drift apart. What is worth holding is that the
 * value keeps coming from there rather than being pasted in, and that it still
 * reaches the wire, which is where the web SDK was found wanting: it declared no
 * version at all until 0.4.1.
 */
class VersionTest {

    @Test
    fun `version looks like a version rather than a placeholder`() {
        assertTrue(
            "VERSION was \"${Tolinku.VERSION}\"",
            Regex("""^\d+\.\d+\.\d+(-[\w.]+)?$""").matches(Tolinku.VERSION),
        )
    }

    @Test
    fun `version comes from the build, not from a literal in the source`() {
        // BuildConfig is generated from sdkVersion in build.gradle.kts, the same
        // value published to Maven. Reading it here is what keeps them one thing.
        assertTrue(Tolinku.VERSION == BuildConfig.SDK_VERSION)
        assertTrue(Tolinku.VERSION.isNotBlank())
    }
}
