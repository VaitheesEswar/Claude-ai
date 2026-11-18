package com.acremote.bluestar.core

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

enum class TimerAction { ON, OFF }

/**
 * A timer that the phone runs (the AC's own timer protocol isn't known).
 * [dailyMinuteOfDay] is set for "every day" timers and null for one-shot ones.
 */
data class AcTimer(
    val id: Long,
    val deviceId: String,
    val action: TimerAction,
    val triggerAtMillis: Long,
    val dailyMinuteOfDay: Int? = null,
) {
    val repeatsDaily: Boolean get() = dailyMinuteOfDay != null

    /** The next run of a daily timer after this one fires; null for one-shot timers. */
    fun rescheduledAfter(firedAtMillis: Long, zone: ZoneId = ZoneId.systemDefault()): AcTimer? {
        val minute = dailyMinuteOfDay ?: return null
        val next = TimerMath.nextAt(minute, ZonedDateTime.ofInstant(Instant.ofEpochMilli(firedAtMillis + 60_000), zone))
        return copy(triggerAtMillis = next.toInstant().toEpochMilli())
    }
}

object TimerMath {
    /** Next time the clock shows [minuteOfDay] (today if still ahead, else tomorrow). */
    fun nextAt(minuteOfDay: Int, now: ZonedDateTime): ZonedDateTime {
        val t = LocalTime.of(minuteOfDay / 60, minuteOfDay % 60)
        var at = now.with(t).withSecond(0).withNano(0)
        if (!at.isAfter(now)) at = at.plusDays(1)
        return at
    }

    /** "1h 20m", "45m", "under 1m". */
    fun formatRemaining(millis: Long): String {
        val totalMin = millis / 60_000
        if (totalMin < 1) return "under 1m"
        val h = totalMin / 60
        val m = totalMin % 60
        return when {
            h == 0L -> "${m}m"
            m == 0L -> "${h}h"
            else -> "${h}h ${m}m"
        }
    }
}

/** Line-per-timer text format for SharedPreferences. */
object TimerCodec {
    fun encode(timers: List<AcTimer>): String = timers.joinToString("\n") {
        listOf(it.id, it.deviceId, it.action.name, it.triggerAtMillis, it.dailyMinuteOfDay ?: "").joinToString("\t")
    }

    fun decode(text: String?): List<AcTimer> = text.orEmpty().lines().mapNotNull { line ->
        val p = line.split("\t")
        if (p.size < 5) return@mapNotNull null
        AcTimer(
            id = p[0].toLongOrNull() ?: return@mapNotNull null,
            deviceId = p[1].ifBlank { return@mapNotNull null },
            action = runCatching { TimerAction.valueOf(p[2]) }.getOrNull() ?: return@mapNotNull null,
            triggerAtMillis = p[3].toLongOrNull() ?: return@mapNotNull null,
            dailyMinuteOfDay = p[4].toIntOrNull(),
        )
    }
}
