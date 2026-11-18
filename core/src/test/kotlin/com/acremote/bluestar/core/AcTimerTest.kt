package com.acremote.bluestar.core

import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AcTimerTest {
    private val ist = ZoneId.of("Asia/Kolkata")

    @Test
    fun nextAtLaterToday() {
        val now = ZonedDateTime.of(2026, 11, 18, 21, 0, 30, 0, ist)
        assertEquals(ZonedDateTime.of(2026, 11, 18, 22, 30, 0, 0, ist), TimerMath.nextAt(22 * 60 + 30, now))
    }

    @Test
    fun nextAtTomorrowWhenPassed() {
        val now = ZonedDateTime.of(2026, 11, 18, 23, 0, 0, 0, ist)
        assertEquals(ZonedDateTime.of(2026, 11, 19, 6, 0, 0, 0, ist), TimerMath.nextAt(6 * 60, now))
        // Exactly now counts as passed.
        assertEquals(ZonedDateTime.of(2026, 11, 19, 23, 0, 0, 0, ist), TimerMath.nextAt(23 * 60, now))
    }

    @Test
    fun dailyTimerReschedulesToNextDay() {
        val fire = ZonedDateTime.of(2026, 11, 18, 22, 30, 0, 0, ist).toInstant().toEpochMilli()
        val t = AcTimer(1, "D", TimerAction.ON, fire, dailyMinuteOfDay = 22 * 60 + 30)
        val next = t.rescheduledAfter(fire, ist)!!
        assertEquals(ZonedDateTime.of(2026, 11, 19, 22, 30, 0, 0, ist).toInstant().toEpochMilli(), next.triggerAtMillis)
        assertNull(t.copy(dailyMinuteOfDay = null).rescheduledAfter(fire, ist))
    }

    @Test
    fun codecRoundTrip() {
        val list = listOf(
            AcTimer(1, "THING1", TimerAction.OFF, 1700000000000),
            AcTimer(2, "THING2", TimerAction.ON, 1700000600000, 1350),
        )
        assertEquals(list, TimerCodec.decode(TimerCodec.encode(list)))
        assertEquals(emptyList(), TimerCodec.decode(null))
        assertEquals(emptyList(), TimerCodec.decode("garbage\n1\tX\tNOPE\t5\t"))
    }

    @Test
    fun remainingText() {
        assertEquals("under 1m", TimerMath.formatRemaining(30_000))
        assertEquals("45m", TimerMath.formatRemaining(45 * 60_000L))
        assertEquals("2h", TimerMath.formatRemaining(120 * 60_000L))
        assertEquals("1h 20m", TimerMath.formatRemaining(80 * 60_000L + 5_000))
    }
}
