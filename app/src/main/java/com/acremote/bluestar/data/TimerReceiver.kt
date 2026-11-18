package com.acremote.bluestar.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.acremote.bluestar.R
import com.acremote.bluestar.core.AcCommands
import com.acremote.bluestar.core.TimerAction
import kotlin.concurrent.thread

/** Runs a timer: signs in, sends power on/off, and posts a notification with the result. */
class TimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val id = intent.getLongExtra(EXTRA_ID, -1)
        val app = context.applicationContext
        val pending = goAsync()
        thread(name = "ac-timer") {
            try {
                run(app, id)
            } finally {
                pending.finish()
            }
        }
    }

    private fun run(ctx: Context, id: Long) {
        val timer = TimerScheduler(ctx).onFired(id) ?: return
        val verb = if (timer.action == TimerAction.OFF) "off" else "on"
        val login = CredentialStore(ctx).load()
        if (login == null) {
            notify(ctx, "Timer skipped", "Sign in to AC Remote again so timers can run.")
            return
        }
        val client = BlueStarClient()
        var lastError: Exception? = null
        repeat(2) { attempt ->
            try {
                val session = client.login(login.authId, login.password, login.isEmail)
                val device = client.fetchDevices(session).firstOrNull { it.id == timer.deviceId }
                    ?: throw IllegalStateException("AC not found on your account")
                val cmd = if (timer.action == TimerAction.OFF) {
                    AcCommands.powerOff(device.state)
                } else {
                    AcCommands.powerOn(device.state)
                }
                client.sendCommand(session, device.id, cmd.desired)
                notify(ctx, "${device.name} turned $verb", "Timer ran at ${java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date())}.")
                return
            } catch (e: Exception) {
                lastError = e
                if (attempt == 0) Thread.sleep(4_000)
            }
        }
        notify(ctx, "Timer couldn't turn the AC $verb", lastError?.message ?: "Check your internet connection.")
    }

    private fun notify(ctx: Context, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Timers", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_ac)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx).notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), n)
    }

    companion object {
        const val ACTION_FIRE = "com.acremote.bluestar.TIMER_FIRE"
        const val EXTRA_ID = "timer_id"
        private const val CHANNEL = "timers"
    }
}

/** Re-arms timers after reboot, app update, or a clock/time-zone change. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            -> TimerScheduler(context).rearmAll()
        }
    }
}
