package com.tolinku.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class MessageTimingTest {
    private val hour = 3_600_000L
    private val day = 24 * hour
    private val now = 1_800_000_000_000L

    @Test
    fun aDismissalHidesForItsDaysThenNot() {
        assertTrue(MessageTiming.stillDismissed(now - 2 * day, 3, now))
        assertFalse(MessageTiming.stillDismissed(now - 3 * day, 3, now))
    }

    @Test
    fun withoutDismissDaysADismissalLastsAsBefore() {
        assertTrue(MessageTiming.stillDismissed(now - 400 * day, null, now))
    }

    @Test
    fun nothingStoredHidesNothing() {
        assertFalse(MessageTiming.stillDismissed(null, 3, now))
        assertFalse(MessageTiming.tooSoon(null, 6, now))
    }

    @Test
    fun theIntervalCountsHoursNotDays() {
        // Shown five hours ago with a six hour gap: too soon. Seven hours ago: fine. The old
        // day-based check got both of these wrong around midnight.
        assertTrue(MessageTiming.tooSoon(now - 5 * hour, 6, now))
        assertFalse(MessageTiming.tooSoon(now - 7 * hour, 6, now))
        assertFalse(MessageTiming.tooSoon(now - hour, 0, now))
        assertFalse(MessageTiming.tooSoon(now - hour, null, now))
    }

    @Test
    fun daysStoredByEarlierVersionsAreStillRead() {
        val utc = TimeZone.getTimeZone("UTC")
        assertEquals(1_763_683_200_000L, MessageTiming.legacyDayToMs("2025-11-21", utc))
        assertNull(MessageTiming.legacyDayToMs("not a date", utc))
        assertNull(MessageTiming.legacyDayToMs("2025-13-45", utc))
        assertNull(MessageTiming.legacyDayToMs(null, utc))
    }
}
