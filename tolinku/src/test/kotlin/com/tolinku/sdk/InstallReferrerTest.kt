package com.tolinku.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The Play referrer is a shared string. A developer's own campaign parameters
 * sit beside ours, so the token has to be found among the pairs rather than
 * assumed to be the whole value.
 */
class InstallReferrerTest {

    @Test
    fun `reads the token when it is the only pair`() {
        assertEquals("ABC123", InstallReferrer.parseToken("tolk_token=ABC123"))
    }

    @Test
    fun `reads the token beside a developer's own campaign parameters`() {
        assertEquals(
            "ABC123",
            InstallReferrer.parseToken("utm_source=newsletter&tolk_token=ABC123&utm_medium=email"),
        )
    }

    @Test
    fun `reads the token when it is last`() {
        assertEquals("ABC123", InstallReferrer.parseToken("utm_source=x&tolk_token=ABC123"))
    }

    @Test
    fun `tolerates a percent encoded referrer`() {
        assertEquals("ABC123", InstallReferrer.parseToken("tolk_token%3DABC123"))
    }

    @Test
    fun `returns null for an organic install`() {
        assertNull(InstallReferrer.parseToken("utm_source=google-play&utm_medium=organic"))
    }

    @Test
    fun `returns null for nothing at all`() {
        assertNull(InstallReferrer.parseToken(null))
        assertNull(InstallReferrer.parseToken(""))
        assertNull(InstallReferrer.parseToken("   "))
    }

    @Test
    fun `returns null rather than an empty token`() {
        assertNull(InstallReferrer.parseToken("tolk_token="))
        assertNull(InstallReferrer.parseToken("utm_source=x&tolk_token=&utm_medium=y"))
    }

    @Test
    fun `does not mistake a similarly named parameter for ours`() {
        assertNull(InstallReferrer.parseToken("my_tolk_token=NOPE"))
        assertNull(InstallReferrer.parseToken("tolk_token_other=NOPE"))
    }

    @Test
    fun `keeps a literal plus, matching the other SDKs`() {
        // Java's URLDecoder reads "+" as a space; decodeURIComponent and
        // Uri.decodeComponent do not. A token is base64url today so this cannot
        // bite yet, but the three parsers have to agree or Android breaks alone.
        assertEquals("a+b", InstallReferrer.parseToken("tolk_token=a+b"))
    }

    @Test
    fun `tolerates whitespace between pairs`() {
        assertEquals("ABC123", InstallReferrer.parseToken("utm_source=x& tolk_token=ABC123"))
    }
}
