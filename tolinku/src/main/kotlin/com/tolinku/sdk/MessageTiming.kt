package com.tolinku.sdk

import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * When an in-app message may show again: after a dismissal, and between two showings.
 *
 * Plain Kotlin on purpose. The presenter used to work this out with java.time, which Android only
 * has from API 26, and the SDK supports API 24: on Android 7 the first message shown threw
 * NoClassDefFoundError, an Error rather than an Exception, so nothing caught it and the app
 * crashed. This uses epoch milliseconds and SimpleDateFormat, both there since API 1.
 *
 * Times are kept to the millisecond now, as the iOS SDK keeps them. Earlier versions stored only
 * the day ("2026-11-21"), so a "6 hours between showings" rule worked in whole days; those stored
 * days are still read, as midnight local time on that day.
 */
internal object MessageTiming {
    private const val DAY_MS = 86_400_000L
    private const val HOUR_MS = 3_600_000L

    /**
     * Whether a dismissal still hides the message. A message without dismissDays stays hidden
     * once dismissed (unchanged behaviour); a dismissal with no readable time hides nothing.
     */
    fun stillDismissed(dismissedAtMs: Long?, dismissDays: Int?, nowMs: Long): Boolean {
        if (dismissedAtMs == null) return false
        if (dismissDays == null) return true
        return nowMs - dismissedAtMs < dismissDays * DAY_MS
    }

    /** Whether too little time has passed since the message was last shown. */
    fun tooSoon(lastShownAtMs: Long?, minIntervalHours: Int?, nowMs: Long): Boolean {
        if (lastShownAtMs == null || minIntervalHours == null || minIntervalHours <= 0) return false
        return nowMs - lastShownAtMs < minIntervalHours * HOUR_MS
    }

    /**
     * A day stored by an earlier SDK version ("yyyy-MM-dd"), as midnight in [zone] that day, or
     * null if it can't be read.
     */
    fun legacyDayToMs(day: String?, zone: TimeZone = TimeZone.getDefault()): Long? {
        if (day.isNullOrBlank()) return null
        return try {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                isLenient = false
                timeZone = zone
            }.parse(day.trim())?.time
        } catch (e: ParseException) {
            null
        }
    }
}
