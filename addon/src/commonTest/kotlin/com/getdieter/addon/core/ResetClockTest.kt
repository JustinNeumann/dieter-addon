package com.getdieter.addon.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class ResetClockTest {
    private val berlin = { _: Instant -> 3600 }
    private val today = Instant.parse("2026-11-02T05:29:00Z")

    @Test
    fun resetClockShowsTheLocalDayAndTimeToTheMinute() {
        assertEquals("Sun 8 Nov 21:00", AddonRules.resetClock("2026-11-08T19:59:59.664282+00:00", today, berlin))
        assertEquals("Sun 8 Nov 20:59", AddonRules.resetClock("2026-11-08T19:59:20Z", today, berlin))
    }

    @Test
    fun resetClockAddsTheYearWhenItIsNotThisOne() {
        assertEquals("Fri 1 Jan 2027 00:30", AddonRules.resetClock("2026-12-31T23:30:10Z", today, berlin))
    }

    @Test
    fun resetClockKeepsATextItCannotRead() {
        assertEquals("soon", AddonRules.resetClock("soon", today, berlin))
    }
}
