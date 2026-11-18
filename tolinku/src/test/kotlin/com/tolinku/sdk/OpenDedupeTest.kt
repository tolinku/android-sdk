package com.tolinku.sdk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that decides whether a reported open is a repeat.
 *
 * This was three fields and a comparison inside `Analytics.trackLinkOpen`,
 * where it needed an Android framework and a network to reach, so it was the
 * only one of the four SDKs with no test for it. That is how the deferred flag
 * came to be missing from the key: nothing could have noticed.
 */
class OpenDedupeTest {

    @Test
    fun `reports one tap once, however it was delivered`() {
        // A cold start and onNewIntent can both hand over the same tap, so an
        // app instrumenting both paths would otherwise be billed twice for it.
        val dedupe = OpenDedupe()
        assertFalse(dedupe.shouldSkip("https://links.example.com/promo", false, 1_000))
        assertTrue(dedupe.shouldSkip("https://links.example.com/promo", false, 1_200))
    }

    @Test
    fun `reports a different link straight after`() {
        val dedupe = OpenDedupe()
        assertFalse(dedupe.shouldSkip("https://links.example.com/a", false, 1_000))
        assertFalse(dedupe.shouldSkip("https://links.example.com/b", false, 1_050))
    }

    @Test
    fun `reports the same link again once the window has passed`() {
        val dedupe = OpenDedupe()
        assertFalse(dedupe.shouldSkip("https://links.example.com/promo", false, 1_000))
        assertFalse(
            dedupe.shouldSkip(
                "https://links.example.com/promo",
                false,
                1_000 + Analytics.OPEN_DEDUPE_MS,
            ),
        )
    }

    @Test
    fun `reports a deferred open after an ordinary one for the same link`() {
        // The case this exists for. Somebody taps a link, has no app, installs,
        // then taps the link again: the ordinary open is delivered by the
        // intent first and the deferred one follows from the claim. Keyed on
        // the URL alone the second was swallowed, losing the last stage of the
        // install funnel in the one case it is there to show.
        val dedupe = OpenDedupe()
        assertFalse(dedupe.shouldSkip("https://links.example.com/promo", false, 1_000))
        assertFalse(dedupe.shouldSkip("https://links.example.com/promo", true, 1_200))
    }

    @Test
    fun `reports an ordinary open after a deferred one for the same link`() {
        // The same rule in the other direction, since nothing orders these two.
        val dedupe = OpenDedupe()
        assertFalse(dedupe.shouldSkip("https://links.example.com/promo", true, 1_000))
        assertFalse(dedupe.shouldSkip("https://links.example.com/promo", false, 1_200))
    }

    @Test
    fun `still reports one deferred open once`() {
        // The flag joins the key, it does not bypass it.
        val dedupe = OpenDedupe()
        assertFalse(dedupe.shouldSkip("https://links.example.com/promo", true, 1_000))
        assertTrue(dedupe.shouldSkip("https://links.example.com/promo", true, 1_200))
    }
}
