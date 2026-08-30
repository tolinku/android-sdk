package com.tolinku.sdk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claiming is a first-launch action. Nothing stops an app calling it on every
 * launch, and each repeat costs a request and records a miss, so a healthy
 * integration would report a match rate near zero.
 *
 * The rule: a completed call is remembered, a dropped one is not.
 * claimBySignals returns null only for a 404 and throws for everything else, so
 * "returned" and "answered" mean the same thing, which is what makes the guard
 * safe to apply after it.
 */
class ClaimOnceTest {

    @Test
    fun `a 404 is an answer and settles the attempt`() {
        assertTrue(settles(TolinkuException("not found", statusCode = 404)))
    }

    @Test
    fun `a transport failure is not an answer`() {
        assertFalse(settles(TolinkuException("offline", statusCode = null)))
    }

    @Test
    fun `a misconfiguration is not an answer either`() {
        // 403 means the appspaceId is wrong. The install is not spent on it:
        // the integrator fixes the ID and the next launch tries again.
        assertFalse(settles(TolinkuException("forbidden", statusCode = 403)))
    }

    /**
     * Mirrors claimBySignals: a 404 is swallowed into null, so control returns
     * and the attempt is recorded. Anything else propagates, so it is not.
     */
    private fun settles(e: TolinkuException): Boolean =
        try {
            if (e.statusCode == 404) true else throw e
        } catch (thrown: TolinkuException) {
            false
        }
}
