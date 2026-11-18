package com.acremote.bluestar.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.acremote.bluestar.core.AcTimer
import com.acremote.bluestar.core.TimerCodec

/**
 * Stores timers and arms Android alarms for them. Alarms survive the app being
 * closed; [BootReceiver] re-arms them after a reboot or clock change.
 */
class TimerScheduler(context: Context) {
    private val ctx = context.applicationContext
    private val prefs = ctx.getSharedPreferences("acremote_timers", Context.MODE_PRIVATE)
    private val alarms = ctx.getSystemService(AlarmManager::class.java)

    fun all(): List<AcTimer> = TimerCodec.decode(prefs.getString(KEY, null)).sortedBy { it.triggerAtMillis }

    fun add(timer: AcTimer) {
        save(all().filterNot { it.id == timer.id } + timer)
        arm(timer)
    }

    fun cancel(id: Long) {
        all().firstOrNull { it.id == id }?.let { alarms.cancel(pending(it)) }
        save(all().filterNot { it.id == id })
    }

    fun cancelAll() {
        all().forEach { alarms.cancel(pending(it)) }
        save(emptyList())
    }

    /** Called when an alarm fires: removes one-shot timers, moves daily ones to tomorrow. */
    @Synchronized
    fun onFired(id: Long, now: Long = System.currentTimeMillis()): AcTimer? {
        val timer = all().firstOrNull { it.id == id } ?: return null
        val next = timer.rescheduledAfter(now)
        save(all().filterNot { it.id == id } + listOfNotNull(next))
        next?.let(::arm)
        return timer
    }

    /** After reboot/clock change: re-arm everything; drop one-shots more than 10 minutes overdue. */
    fun rearmAll(now: Long = System.currentTimeMillis()) {
        val kept = all().mapNotNull { t ->
            when {
                t.triggerAtMillis >= now - LATE_GRACE_MS -> t
                t.repeatsDaily -> t.rescheduledAfter(now)
                else -> null
            }
        }
        save(kept)
        kept.forEach(::arm)
    }

    private fun arm(t: AcTimer) {
        val pi = pending(t)
        val at = maxOf(t.triggerAtMillis, System.currentTimeMillis() + 1_000)
        val exactAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()
        if (exactAllowed) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    private fun pending(t: AcTimer): PendingIntent {
        val intent = Intent(ctx, TimerReceiver::class.java)
            .setAction(TimerReceiver.ACTION_FIRE)
            .putExtra(TimerReceiver.EXTRA_ID, t.id)
        return PendingIntent.getBroadcast(
            ctx,
            (t.id % Int.MAX_VALUE).toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun save(list: List<AcTimer>) {
        prefs.edit().putString(KEY, TimerCodec.encode(list)).commit()
    }

    private companion object {
        const val KEY = "timers"
        const val LATE_GRACE_MS = 10 * 60_000L
    }
}
